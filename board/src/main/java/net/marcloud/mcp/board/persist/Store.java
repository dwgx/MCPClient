package net.marcloud.mcp.board.persist;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A crash-safe, corruption-tolerant persistence engine for the board — the layer
 * the framework has been missing entirely. It owns ONE file and a set of
 * {@link Persistable} values registered under STABLE ids, and gives them four
 * guarantees the naive "write the file directly" approach never had:
 *
 * <ol>
 *   <li><b>Atomic write.</b> {@link #save()} writes a sibling temp file, fsync-free
 *       but flushed, then {@link Files#move} with {@code ATOMIC_MOVE}; if the
 *       filesystem refuses (cross-device, some Windows cases) it falls back to a
 *       {@code REPLACE_EXISTING} move. A crash mid-write can never leave a
 *       half-written primary file.</li>
 *   <li><b>Corruption recovery.</b> {@link #load()} that hits an unreadable /
 *       malformed file renames it aside to {@code <name>-<epochMillis>.backup} and
 *       proceeds with defaults instead of throwing — the user loses nothing they
 *       can inspect, and the app still starts.</li>
 *   <li><b>Stable-id keying.</b> Values are keyed by a caller-supplied stable id,
 *       NEVER a display name — renaming a feature's label must not orphan its
 *       saved data (the Emperor/Lavender trap).</li>
 *   <li><b>Self-describing, version-tolerant envelope.</b> The file is
 *       {@code {version, savedAt, data:{id: {...}}}}; {@link #load()} tolerates a
 *       lower/absent version and missing or extra fields without throwing, so old
 *       and future files both load.</li>
 * </ol>
 *
 * <p>The engine NEVER switches on value type: each {@link Persistable} serializes
 * itself into its own {@link DataView}. Registration order is preserved so the
 * file is deterministic. Not thread-safe: drive from one thread, like the rest of
 * the board.
 */
public final class Store {

    /** Current envelope schema version. Bump when the envelope shape changes. */
    public static final int VERSION = 1;

    private static final String ENVELOPE_VERSION = "version";
    private static final String ENVELOPE_SAVED_AT = "savedAt";
    private static final String ENVELOPE_DATA = "data";

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private final Path file;
    /** Stable-id → value. Insertion-ordered for a deterministic on-disk layout. */
    private final Map<String, Persistable> registry = new LinkedHashMap<String, Persistable>();

    /** Version read from the last {@link #load()} (0 if none/absent). For tests/migration. */
    private int lastLoadedVersion;
    /** {@code true} if the last {@link #load()} quarantined a corrupt file. */
    private boolean lastLoadRecovered;

    /** A store bound to {@code file}. The file need not exist yet. */
    public Store(Path file) {
        if (file == null) {
            throw new IllegalArgumentException("file must not be null");
        }
        this.file = file;
    }

    /**
     * Register {@code value} under the STABLE {@code id}. The id is the on-disk
     * key — it must be stable across renames of any display name. Throws on a
     * null/blank id, a null value, or a duplicate id.
     */
    public Store register(String id, Persistable value) {
        if (id == null || id.trim().isEmpty()) {
            throw new IllegalArgumentException("id must not be null or blank");
        }
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        if (registry.containsKey(id)) {
            throw new IllegalStateException("duplicate persist id: " + id);
        }
        registry.put(id, value);
        return this;
    }

    /** The file this store reads/writes. */
    public Path file() {
        return file;
    }

    /** Envelope version seen by the last {@link #load()} ({@code 0} if none). */
    public int lastLoadedVersion() {
        return lastLoadedVersion;
    }

    /** {@code true} if the last {@link #load()} recovered from a corrupt file. */
    public boolean lastLoadRecovered() {
        return lastLoadRecovered;
    }

    // ---- save ---------------------------------------------------------------

    /**
     * Ask every registered value to serialize itself, wrap the result in a
     * self-describing envelope, and write it ATOMICALLY. A value whose
     * {@link Persistable#save} throws is skipped (its previous on-disk data is
     * simply omitted this round) rather than aborting the whole save.
     *
     * @throws IOException if the bytes cannot be written or moved into place
     */
    public void save() throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Persistable> e : registry.entrySet()) {
            DataView view = new DataView();
            try {
                e.getValue().save(view);
            } catch (Throwable t) {
                System.err.println("[Store] save of '" + e.getKey() + "' threw: " + t);
                continue;
            }
            data.put(e.getKey(), view.raw());
        }

        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put(ENVELOPE_VERSION, Long.valueOf(VERSION));
        envelope.put(ENVELOPE_SAVED_AT, Long.valueOf(System.currentTimeMillis()));
        envelope.put(ENVELOPE_DATA, data);

        writeAtomic(Json.write(envelope));
    }

    /**
     * Write {@code text} to a sibling temp file, then move it onto {@link #file}
     * atomically — falling back to a plain replacing move when the filesystem
     * cannot do {@code ATOMIC_MOVE}. Ensures the parent directory exists first.
     */
    private void writeAtomic(String text) throws IOException {
        Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        // Temp file kept inside the same directory so the move stays on one
        // device (a cross-device rename can never be atomic).
        Path tmp = (parent == null ? file.resolveSibling(file.getFileName() + ".tmp")
                : parent.resolve(file.getFileName() + "." + System.nanoTime() + ".tmp"));
        Files.write(tmp, text.getBytes(UTF8));
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException notAtomic) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            // Best effort: don't leave the temp file lingering on a failed move.
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // ignore cleanup failure; surface the original
            }
            throw e;
        }
    }

    // ---- load ---------------------------------------------------------------

    /**
     * Reset every registered value to its defaults, then, if the file exists and
     * is readable+valid, hand each value its own slice of the envelope to load.
     *
     * <p>RESET-BEFORE-LOAD: defaults are applied first so a field absent from the
     * file is left at its default, never a stale prior value. A MISSING file just
     * leaves everything at defaults (a first run). A CORRUPT file (unreadable or
     * malformed JSON, or a non-object root) is renamed aside to
     * {@code <name>-<epochMillis>.backup} and treated as a first run — no throw.
     * A value's own {@link Persistable#load} that throws is isolated so one bad
     * value cannot abort loading the rest.
     */
    public void load() {
        lastLoadedVersion = 0;
        lastLoadRecovered = false;

        // (1) reset-before-load: every value starts from defaults.
        for (Map.Entry<String, Persistable> e : registry.entrySet()) {
            try {
                e.getValue().reset();
            } catch (Throwable t) {
                System.err.println("[Store] reset of '" + e.getKey() + "' threw: " + t);
            }
        }

        if (!Files.exists(file)) {
            return; // first run — defaults stand.
        }

        String text;
        try {
            text = new String(Files.readAllBytes(file), UTF8);
        } catch (IOException io) {
            // Unreadable file: quarantine and run on defaults.
            quarantine();
            return;
        }

        Map<String, Object> envelope;
        try {
            envelope = Json.parse(text);
        } catch (RuntimeException | StackOverflowError malformed) {
            // A malformed document throws JsonException (a RuntimeException); a
            // pathologically deep one blows the recursive-descent parser's stack
            // (StackOverflowError, an Error). Both are corruption — quarantine
            // and run on defaults rather than letting the parser abort startup.
            quarantine();
            return;
        }

        DataView root = new DataView(envelope);
        lastLoadedVersion = root.getInt(ENVELOPE_VERSION, 0);

        // (4) version tolerance: a missing/lower version is fine; we still read
        // 'data' if present, and a file that predates the envelope (data at the
        // top level) still loads because getView falls back to an empty view and
        // each value defaults the fields it cannot find.
        DataView data = root.hasView(ENVELOPE_DATA) ? root.getView(ENVELOPE_DATA) : root;

        // (5) each value loads itself from its own id-keyed slice; the engine
        // never switches on type. A missing slice reads as all-defaults.
        for (Map.Entry<String, Persistable> e : registry.entrySet()) {
            DataView slice = data.getView(e.getKey());
            try {
                e.getValue().load(slice);
            } catch (Throwable t) {
                System.err.println("[Store] load of '" + e.getKey() + "' threw: " + t);
            }
        }
    }

    /**
     * Rename the current (corrupt) file aside to {@code <name>-<epochMillis>.backup}
     * so the user can inspect it and the next {@link #save()} starts clean. Never
     * throws: a failure to back up must not stop the app from starting on
     * defaults.
     */
    private void quarantine() {
        lastLoadRecovered = true;
        try {
            Path backup = file.resolveSibling(file.getFileName() + "-"
                    + System.currentTimeMillis() + ".backup");
            Files.move(file, backup, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.err.println("[Store] could not quarantine corrupt file " + file + ": " + e);
            // Last resort: try to delete it so a repeated corrupt read does not
            // loop. If even that fails, we still proceed on defaults.
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // proceed on defaults regardless
            }
        }
    }
}
