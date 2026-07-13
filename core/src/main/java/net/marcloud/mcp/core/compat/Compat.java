package net.marcloud.mcp.core.compat;

import java.lang.instrument.Instrumentation;

/**
 * Boot seam + holder for the compat engine. The engine must install its
 * transformer at <b>premain</b>, before any {@code net.minecraft.*} class loads —
 * so {@link #igniteAtPremain} is called from {@code CoreAgent.premain}, and the
 * resulting engine + database are stashed here for the later-starting {@code
 * McpCore} to expose through {@code list_compat_patches}.
 *
 * <p>The shipped default is deliberately <b>inert</b>: an empty database and the
 * fail-safe {@link UnsignedPatchSigner}, so premain installs a no-op transformer
 * and applies nothing. Real patches (KI-1 mipmap, KI-4 LocalServerChannel, …) are
 * added to {@link #defaultDatabase()} as signed patch classes once the crypto core
 * is designed; until then the engine is wired but empty (no advertised-but-dead
 * behavior — {@code list_compat_patches} honestly reports count 0).
 */
public final class Compat {

    private static volatile CompatEngine engine;
    private static volatile CompatDatabase database;

    private Compat() {
    }

    /**
     * The shipped default patch database. Empty for now — the confirmed KI patches
     * land here as bound, signed {@link CompatPatch} classes when the crypto core is
     * ready. Kept as a factory so tests can build their own populated database.
     */
    public static CompatDatabase defaultDatabase() {
        return new CompatDatabase();
    }

    /**
     * Build the default database + fail-safe signer, install the engine's transformer
     * on {@code inst}, and stash both. Called once from {@code CoreAgent.premain}.
     * Never throws — a compat failure must never take down agent startup.
     */
    public static CompatEngine igniteAtPremain(Instrumentation inst) {
        try {
            CompatDatabase db = defaultDatabase();
            CompatEngine e = CompatEngine.installFrom(inst, db, new UnsignedPatchSigner());
            database = db;
            engine = e;
            return e;
        } catch (Throwable t) {
            System.err.println("[MCP Compat] premain ignite failed (compat disabled): " + t);
            return null;
        }
    }

    /** The engine built at premain, or null if the agent never loaded. */
    public static CompatEngine engine() {
        return engine;
    }

    /**
     * The database built at premain, or a fresh empty one if the agent never loaded
     * (e.g. headless run without {@code -javaagent}) so callers always get a
     * non-null catalog.
     */
    public static CompatDatabase database() {
        CompatDatabase db = database;
        return db != null ? db : defaultDatabase();
    }
}
