package net.marcloud.mcp.core.drivers.store;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

/**
 * Durable, thread-safe experience store, persisted as JSON (gson). Loaded at
 * startup, saved on every write, so the AI's accumulated knowledge survives
 * restarts — the "growth / 神话 memory" pillar (JARVIS-1-style experiences,
 * Voyager-style retrievable notes).
 *
 * <p>Search is simple case-insensitive substring matching over title/content/
 * tags — adequate for a dev tool; a vector index can replace it later without
 * changing the tool surface.
 */
public final class MemoryStore {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final List<MemoryEntry> entries = new ArrayList<>();
    private final AtomicInteger seq = new AtomicInteger();
    private final Object lock = new Object();

    public MemoryStore(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        synchronized (lock) {
            entries.clear();
            if (!Files.exists(file)) {
                return;
            }
            try (Reader r = Files.newBufferedReader(file)) {
                List<MemoryEntry> loaded = GSON.fromJson(r,
                        new TypeToken<List<MemoryEntry>>() { }.getType());
                if (loaded != null) {
                    entries.addAll(loaded);
                }
            } catch (Exception e) {
                System.err.println("[MCP Core] failed to load memory (" + file + "): " + e);
            }
            // Advance the id sequence past any loaded numeric ids.
            int max = 0;
            for (MemoryEntry e : entries) {
                try {
                    max = Math.max(max, Integer.parseInt(e.id().replaceAll("\\D", "")));
                } catch (RuntimeException ignored) {
                }
            }
            seq.set(max);
        }
    }

    /**
     * Persist the current entries transactionally: write to a sibling temp file,
     * fsync-free flush via try-with-resources, then atomically move it over the
     * target. Either the old file survives intact or the new one fully replaces
     * it — a partial/torn file is never observed. On any failure the IOException
     * is PROPAGATED so callers can roll back the in-memory change and report the
     * failure to the AI, instead of silently claiming success while data is lost
     * on restart.
     */
    private void save() throws IOException {
        synchronized (lock) {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            Path dir = file.getParent();
            Path tmp = (dir != null)
                    ? Files.createTempFile(dir, ".mcp_memory", ".tmp")
                    : Files.createTempFile(".mcp_memory", ".tmp");
            try {
                try (Writer w = Files.newBufferedWriter(tmp)) {
                    GSON.toJson(new ArrayList<>(entries), w);
                }
                try {
                    Files.move(tmp, file,
                            StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException atomicUnsupported) {
                    // Some filesystems can't do an atomic cross-node move; fall back
                    // to a plain replace (still no torn write of the target).
                    Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | RuntimeException e) {
                // Clean up the temp file; never leak it, and never leave the target
                // half-written (we never wrote the target directly).
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best-effort cleanup
                }
                throw e;
            }
        }
    }

    /**
     * Add an experience; returns the assigned id.
     *
     * @throws UncheckedIOException if the entry could not be persisted. The
     *         in-memory add is rolled back first, so a failed write leaves the
     *         store exactly as it was (no phantom entry that vanishes on restart).
     */
    public String write(String title, String content, List<String> tags) {
        synchronized (lock) {
            int assigned = seq.incrementAndGet();
            String id = "m" + assigned;
            MemoryEntry e = new MemoryEntry(id, title, content,
                    tags == null ? List.of() : List.copyOf(tags), System.currentTimeMillis());
            entries.add(e);
            try {
                save();
            } catch (IOException io) {
                // Roll back the speculative add AND the id sequence so the store is
                // untouched and the id can be reused on the next attempt.
                entries.remove(e);
                seq.compareAndSet(assigned, assigned - 1);
                throw new UncheckedIOException("failed to persist memory (" + file + ")", io);
            }
            return e.id();
        }
    }

    /** All entries whose title/content/tags contain {@code query} (all if blank). */
    public List<MemoryEntry> search(String query, int limit) {
        synchronized (lock) {
            List<MemoryEntry> out = new ArrayList<>();
            // newest first
            for (int i = entries.size() - 1; i >= 0 && out.size() < limit; i--) {
                MemoryEntry e = entries.get(i);
                if (e.matches(query)) {
                    out.add(e);
                }
            }
            return out;
        }
    }

    /**
     * Delete by id; true if removed and persisted, false if no such id.
     *
     * @throws UncheckedIOException if the removal could not be persisted. The
     *         in-memory removal is rolled back first (entry re-inserted at its
     *         original position), so a failed write leaves the store unchanged
     *         rather than dropping the entry only in memory until the next restart
     *         resurrects it.
     */
    public boolean delete(String id) {
        synchronized (lock) {
            int idx = -1;
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i).id().equals(id)) {
                    idx = i;
                    break;
                }
            }
            if (idx < 0) {
                return false;
            }
            MemoryEntry removed = entries.remove(idx);
            try {
                save();
            } catch (IOException io) {
                // Restore the entry at its original index so ordering is preserved.
                entries.add(idx, removed);
                throw new UncheckedIOException("failed to persist memory (" + file + ")", io);
            }
            return true;
        }
    }

    public int size() {
        synchronized (lock) {
            return entries.size();
        }
    }
}
