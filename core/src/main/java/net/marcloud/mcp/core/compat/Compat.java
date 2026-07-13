package net.marcloud.mcp.core.compat;

import java.lang.instrument.Instrumentation;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
// PatchManifest is same package

import net.marcloud.mcp.core.alpc.AlpcProtocol;
import net.marcloud.mcp.core.alpc.CompatCandidate;
import net.marcloud.mcp.core.alpc.CompatCrypto;

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
 *
 * <p>When {@code -Dmcp.core.psecure=true}, premain also opens the online ticket
 * channel ({@link CompatAuthorityClient}) against the P-SECURE process. Fail-closed:
 * unreachable / reject ⇒ empty authorized set ⇒ zero patches armed.
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
            Set<String> online = resolveOnlineAuthorizedIds(db);
            CompatEngine e = CompatEngine.installFrom(inst, db, new UnsignedPatchSigner(), online);
            database = db;
            engine = e;
            return e;
        } catch (Throwable t) {
            System.err.println("[MCP Compat] premain ignite failed (compat disabled): " + t);
            return null;
        }
    }

    /**
     * When psecure is off, returns {@code null} (skip online filter). When on,
     * handshake+ticket or fail-closed empty set. Package-visible for tests.
     */
    static Set<String> resolveOnlineAuthorizedIds(CompatDatabase db) {
        if (!Boolean.parseBoolean(System.getProperty(AlpcProtocol.ENABLE_PROPERTY, "false"))) {
            return null; // offline-only path
        }
        String host = System.getProperty("mcp.core.psecureHost", "127.0.0.1");
        int port = Integer.getInteger("mcp.core.psecurePort", AlpcProtocol.DEFAULT_PORT);
        String token = System.getProperty(AlpcProtocol.TOKEN_PROPERTY, "");
        String pubB64 = System.getProperty(AlpcProtocol.COMPAT_PUBKEY_PROPERTY, "");
        if (pubB64.isBlank()) {
            System.err.println("[MCP Compat] psecure on but " + AlpcProtocol.COMPAT_PUBKEY_PROPERTY
                    + " missing — arming nothing (fail-closed).");
            return Set.of();
        }
        PublicKey pub;
        try {
            pub = CompatCrypto.decodeSpki("Ed25519", CompatCrypto.unb64(pubB64));
        } catch (Exception e) {
            System.err.println("[MCP Compat] bad authority public key — arming nothing: " + e);
            return Set.of();
        }
        List<CompatCandidate> candidates = new ArrayList<>();
        for (CompatPatch p : db.all()) {
            PatchManifest m = p.manifest();
            if (m.status() == PatchManifest.Status.VERIFIED
                    && m.contentHash() != null && !m.contentHash().isBlank()) {
                candidates.add(new CompatCandidate(m.patchId(), m.contentHash()));
            }
        }
        if (candidates.isEmpty()) {
            return Set.of();
        }
        CompatAuthorityClient client = new CompatAuthorityClient(host, port, token, 1500, pub);
        try {
            if (!client.handshake()) {
                System.err.println("[MCP Compat] authority unreachable at premain — "
                        + "patches unavailable (fail-closed).");
                return Set.of();
            }
            Set<String> ids = client.authorize(candidates);
            System.err.println("[MCP Compat] online tickets authorized " + ids.size()
                    + " of " + candidates.size() + " candidate(s).");
            return ids;
        } finally {
            client.close();
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
