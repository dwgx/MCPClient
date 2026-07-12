package net.marcloud.mcp.board.persist;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/** Regression tests for the crash-safe {@link Store} persistence engine. */
public class StoreTest {

    @Rule
    public final TemporaryFolder tmp = new TemporaryFolder();

    /**
     * A value that serializes ITSELF — the engine never switches on its type. Its
     * on-disk keys ({@code enabled}/{@code speed}/{@code label}) are the stable
     * schema; {@link #displayName} is deliberately NOT persisted, to prove that a
     * renamed label does not affect the saved data.
     */
    static final class Settings implements Persistable {
        static final boolean DEF_ENABLED = false;
        static final double DEF_SPEED = 1.0;
        static final String DEF_LABEL = "";

        boolean enabled = DEF_ENABLED;
        double speed = DEF_SPEED;
        String label = DEF_LABEL;
        String displayName = "original"; // transient — never written

        @Override
        public void save(DataView out) {
            out.putBoolean("enabled", enabled);
            out.putDouble("speed", speed);
            out.putString("label", label);
        }

        @Override
        public void load(DataView in) {
            enabled = in.getBoolean("enabled", DEF_ENABLED);
            speed = in.getDouble("speed", DEF_SPEED);
            label = in.getString("label", DEF_LABEL);
        }

        @Override
        public void reset() {
            enabled = DEF_ENABLED;
            speed = DEF_SPEED;
            label = DEF_LABEL;
        }
    }

    private Path file() {
        return tmp.getRoot().toPath().resolve("board.json");
    }

    @Test
    public void roundTripPreservesValues() throws IOException {
        Path f = file();
        Settings a = new Settings();
        a.enabled = true;
        a.speed = 3.5;
        a.label = "north";
        new Store(f).register("nav", a).save();

        assertTrue("file must exist after save", Files.exists(f));

        Settings b = new Settings();
        Store in = new Store(f).register("nav", b);
        in.load();

        assertTrue(b.enabled);
        assertEquals(3.5, b.speed, 0.0);
        assertEquals("north", b.label);
        assertFalse("no recovery on a clean file", in.lastLoadRecovered());
        assertEquals(Store.VERSION, in.lastLoadedVersion());
    }

    @Test
    public void corruptFileIsBackedUpAndDefaultsRestoredWithoutThrow() throws IOException {
        Path f = file();
        Files.write(f, "{ this is not ] valid json".getBytes(Charset.forName("UTF-8")));

        Settings s = new Settings();
        s.enabled = true;      // dirty pre-load state
        s.speed = 99.0;
        s.label = "stale";

        Store store = new Store(f).register("nav", s);
        store.load(); // must NOT throw

        // reset-before-load defaults applied despite the corrupt file
        assertEquals(Settings.DEF_ENABLED, s.enabled);
        assertEquals(Settings.DEF_SPEED, s.speed, 0.0);
        assertEquals(Settings.DEF_LABEL, s.label);
        assertTrue("corruption must be flagged", store.lastLoadRecovered());

        // a <name>-<epochMillis>.backup sibling must have been created
        Path backup = findBackup(f);
        assertNotNull("a .backup of the corrupt file must exist", backup);
        assertFalse("corrupt primary must have been moved aside", Files.exists(f));
    }

    @Test
    public void pathologicallyDeepFileIsQuarantinedNotFatal() throws IOException {
        Path f = file();
        // A legal-looking envelope whose 'data' nests arrays tens of thousands
        // deep. It is well-formed by grammar, but the recursive-descent parser
        // recurses once per level and blows the stack (StackOverflowError, an
        // Error, NOT a RuntimeException). load() must treat that as corruption:
        // quarantine and run on defaults, never propagate.
        int depth = 100000;
        StringBuilder sb = new StringBuilder(depth * 2 + 64);
        sb.append("{\"version\":1,\"data\":");
        for (int i = 0; i < depth; i++) {
            sb.append('[');
        }
        for (int i = 0; i < depth; i++) {
            sb.append(']');
        }
        sb.append('}');
        Files.write(f, sb.toString().getBytes(Charset.forName("UTF-8")));

        Settings s = new Settings();
        s.enabled = true;   // dirty pre-load state
        s.speed = 42.0;
        s.label = "stale";

        Store store = new Store(f).register("nav", s);
        store.load(); // must NOT throw (StackOverflowError must be caught)

        // reset-before-load defaults survive the quarantined file
        assertEquals(Settings.DEF_ENABLED, s.enabled);
        assertEquals(Settings.DEF_SPEED, s.speed, 0.0);
        assertEquals(Settings.DEF_LABEL, s.label);
        assertTrue("deep-nesting corruption must be flagged", store.lastLoadRecovered());

        Path backup = findBackup(f);
        assertNotNull("a .backup of the pathological file must exist", backup);
        assertFalse("pathological primary must have been moved aside", Files.exists(f));
    }

    @Test
    public void renamedDisplayNameButStableIdStillLoads() throws IOException {
        Path f = file();
        Settings a = new Settings();
        a.label = "keepme";
        a.displayName = "Old Fancy Name";
        new Store(f).register("stable-id", a).save();

        // Same stable id, totally different display name: data still keys back.
        Settings b = new Settings();
        b.displayName = "Renamed Completely Different";
        new Store(f).register("stable-id", b).load();

        assertEquals("keepme", b.label);
    }

    @Test
    public void wrongIdOrphansDataAndYieldsDefaults() throws IOException {
        Path f = file();
        Settings a = new Settings();
        a.label = "wontbefound";
        new Store(f).register("id-one", a).save();

        Settings b = new Settings();
        new Store(f).register("id-two", b).load(); // different id
        assertEquals("mismatched id must default", Settings.DEF_LABEL, b.label);
    }

    @Test
    public void olderEnvelopeMissingFieldsLoadsTolerantly() throws IOException {
        Path f = file();
        // A hand-rolled OLD envelope: lower version, savedAt absent, and the value
        // slice omits 'speed' entirely and carries an unknown extra field.
        String old = "{\n"
                + "  \"version\": 0,\n"
                + "  \"data\": {\n"
                + "    \"nav\": { \"enabled\": true, \"future_field\": \"ignored\" }\n"
                + "  }\n"
                + "}\n";
        Files.write(f, old.getBytes(Charset.forName("UTF-8")));

        Settings s = new Settings();
        Store store = new Store(f).register("nav", s);
        store.load(); // must not throw on missing/extra fields

        assertTrue("present field read", s.enabled);
        assertEquals("missing field defaults", Settings.DEF_SPEED, s.speed, 0.0);
        assertEquals("missing string defaults", Settings.DEF_LABEL, s.label);
        assertEquals(0, store.lastLoadedVersion());
        assertFalse(store.lastLoadRecovered());
    }

    @Test
    public void missingFileIsFirstRunNotCorruption() {
        Path f = file();
        Settings s = new Settings();
        s.label = "dirty";
        Store store = new Store(f).register("nav", s);
        store.load();
        assertEquals(Settings.DEF_LABEL, s.label);
        assertFalse("absent file is not corruption", store.lastLoadRecovered());
    }

    @Test
    public void saveLeavesNoTempFileBehind() throws IOException {
        Path f = file();
        new Store(f).register("nav", new Settings()).save();
        long tmpCount;
        try (java.util.stream.Stream<Path> paths = Files.list(tmp.getRoot().toPath())) {
            tmpCount = paths.filter(p -> p.getFileName().toString().endsWith(".tmp")).count();
        }
        assertEquals("no .tmp file should survive an atomic write", 0L, tmpCount);
    }

    @Test
    public void multipleValuesEachSerializeThemselves() throws IOException {
        Path f = file();
        Settings a = new Settings();
        a.label = "alpha";
        Settings b = new Settings();
        b.speed = 7.0;
        new Store(f).register("a", a).register("b", b).save();

        Settings a2 = new Settings();
        Settings b2 = new Settings();
        new Store(f).register("a", a2).register("b", b2).load();
        assertEquals("alpha", a2.label);
        assertEquals(7.0, b2.speed, 0.0);
    }

    private static Path findBackup(Path f) throws IOException {
        String prefix = f.getFileName().toString() + "-";
        try (java.util.stream.Stream<Path> paths = Files.list(f.getParent())) {
            return paths.filter(p -> {
                String n = p.getFileName().toString();
                return n.startsWith(prefix) && n.endsWith(".backup");
            }).findFirst().orElse(null);
        }
    }
}
