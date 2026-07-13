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
    /** Live authorization lease when online (BLUE-1); null offline-only. */
    private static volatile PatchLease lease;

    /** Lease TTL: the online authorization must be re-confirmed within this window
     *  by the heartbeat, or it fails closed on its own (no permanent authorization). */
    private static final long ONLINE_LEASE_TTL_MILLIS =
            Long.getLong("mcp.core.compatLeaseTtlMs", 60_000L);

    /** Heartbeat re-authorization period (must be < TTL so the lease never lapses
     *  during healthy operation). */
    private static final long HEARTBEAT_MILLIS =
            Long.getLong("mcp.core.compatHeartbeatMs", 20_000L);

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
            // BLUE-1: when online authorization is in force, do NOT let the premain
            // snapshot be the final word (a ~seconds ticket would otherwise arm a
            // patch for the whole JVM lifetime, and de-list could never reach us).
            // Attach a LIVE lease seeded from that first authorization; apply() then
            // gates on it at the moment of use, and a heartbeat can renew/disarm it.
            if (online != null) {
                PatchLease live = new PatchLease();
                // Seed the lease with the first authorization; a short TTL so it must
                // be renewed by the heartbeat or it fails closed on its own.
                live.renew(online, 1L, ONLINE_LEASE_TTL_MILLIS);
                e.setLease(live);
                lease = live;
                startHeartbeat(db, live);
            }
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

    /**
     * BLUE-1 heartbeat: a daemon that periodically re-authorizes with the authority
     * and {@link PatchLease#renew renews} the live lease with a strictly increasing
     * epoch. This is what makes de-list / revoke actually reach a RUNNING client and
     * keeps the short lease from lapsing during healthy operation. If a heartbeat
     * fails (authority unreachable), the lease is NOT renewed and simply expires on
     * its own TTL — fail-closed, disarming every patch until the authority is back.
     */
    private static void startHeartbeat(CompatDatabase db, PatchLease live) {
        Thread t = new Thread(() -> {
            long epoch = 2L; // premain seed used epoch 1
            while (true) {
                try {
                    Thread.sleep(HEARTBEAT_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                try {
                    Set<String> ids = resolveOnlineAuthorizedIds(db);
                    if (ids != null) {
                        // Renew with the fresh authorized set; monotonic epoch rejects
                        // any stale/rolled-back view. A failed handshake returns an
                        // empty set here -> renew to empty -> everything disarms.
                        live.renew(ids, epoch++, ONLINE_LEASE_TTL_MILLIS);
                    }
                } catch (Throwable ex) {
                    // Never let the heartbeat die on a transient error; the lease
                    // will expire on its own if we stop renewing.
                    System.err.println("[MCP Compat] heartbeat re-auth failed (lease will expire): " + ex);
                }
            }
        }, "mcp-compat-heartbeat");
        t.setDaemon(true);
        t.start();
    }

    /** The engine built at premain, or null if the agent never loaded. */
    public static CompatEngine engine() {
        return engine;
    }

    /** The live authorization lease when online (BLUE-1), or null offline-only. */
    public static PatchLease lease() {
        return lease;
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
