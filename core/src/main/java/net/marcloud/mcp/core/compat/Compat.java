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
 * <p>The shipped default has ONE arming rule: a patch arms iff its Ed25519 signature
 * verifies against {@link #defaultTrustAnchors()} — the baked-in kernel public key. In-code
 * registration confers no trust. {@link #defaultDatabase()} registers the confirmed KI
 * patches (KI-4 LocalServerChannel, …), each shipping SIGNED by the kernel key, so they arm
 * through that verify path. With empty anchors the signer trusts nothing and the engine arms
 * nothing (fail-safe).
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
     * The shipped default patch database. Carries the confirmed KI patches as bound,
     * in-code {@link CompatPatch} classes, each shipping SIGNED by the kernel key. Kept as
     * a factory so tests can build their own populated database.
     *
     * <p><b>Arming.</b> Registration makes a patch VISIBLE ({@code list_compat_patches}) and
     * confers NO trust. Whether it ARMS is decided solely by the engine: a valid Ed25519
     * signature verified against {@link #defaultTrustAnchors()} plus the signer-independent
     * gauntlet (VERIFIED status, not a protected target, {@link CompatPatch#appliesToRuntime()}).
     * KI-4 arms because it carries a real kernel signature — not because it is registered
     * in-code. With empty anchors nothing arms.
     */
    public static CompatDatabase defaultDatabase() {
        CompatDatabase db = new CompatDatabase();
        // KI-4: LocalServerChannel bound on the wrong Netty event-loop group. Ships SIGNED
        // by the kernel key; arms through the normal signature-verify path against the
        // baked-in kernel anchor (in-code registration grants no trust).
        db.register(new net.marcloud.mcp.core.compat.patches.Ki4LocalServerChannelPatch());
        // KI-1: uninitialized mipmap levels sample as garbage (blue/white specks) under LWJGL3.
        // Ships SIGNED by the kernel key; arms through the normal signature-verify path (in-code
        // registration grants no trust).
        db.register(new net.marcloud.mcp.core.compat.patches.Ki1MipmapZeroFillPatch());
        // KI-11: nothing could open the DWM screen (DwmEntry had no callers, and the frozen client
        // cannot gain a KeyBinding). Hooks Minecraft.dispatchKeypresses for one edge-detected
        // hotkey. Ships SIGNED by the kernel key and DOES arm, so RSHIFT opens the screen; arming
        // is still the normal signature-verify path (in-code registration grants no trust).
        // Ki11SigningContractTest proves both directions: no trusted anchor means no arming, and
        // the shipped patch arms against the real derived chain.
        db.register(new net.marcloud.mcp.core.compat.patches.Ki11DwmHotkeyPatch());
        return db;
    }

    /**
     * The shipped default trust anchors — the baked-in kernel Ed25519 public key
     * ({@link KernelTrustAnchor}). This is the ONE trust root: a patch is trusted only if
     * its signature verifies under this kernel key; the matching PRIVATE key never enters
     * the client (it lives with the offline signing tool, {@code PatchSignerCli}).
     * Fail-closed: if the baked key cannot be loaded, this returns {@link TrustAnchors#empty()}
     * (arm nothing). There is no signature-free path — every patch, including the in-code
     * KI patches, arms only through {@link Ed25519PatchSigner} verification against these
     * anchors.
     *
     * <p><b>KNOWN GAP — the L2 root layer cannot currently deny what the baked kernel key
     * permits.</b> The fallback below restores {@link KernelTrustAnchor#anchors()} whenever the
     * root derivation yields nothing, and both paths pin the SAME keyId
     * ({@code mcp-kernel-ed25519-v1}). So a root document that revokes the kernel key by dropping
     * it from {@code targetsKeys} — or one whose signature is merely invalidated — falls through
     * and every patch still verifies. Measured: with a revoked root document
     * {@code RootTrust.effectiveAnchors()} is empty and logs "no patch will arm (fail-closed)",
     * while this method returns non-empty anchors and all three shipped patches verify.
     *
     * <p>That defeats the stated purpose of {@link RootTrust} ("a compromised targets key is
     * revoked by publishing a new root document that drops it — no client rebuild"). The fallback
     * exists for build compatibility, not for security, and no test covers the broken-chain path.
     * Closing it is a deliberate posture change — removing the fallback makes any damaged root
     * resource disarm every patch — so it is left recorded rather than changed in passing.
     */
    public static TrustAnchors defaultTrustAnchors() {
        // TUF L2 — verify to root. The patch-verification anchors are no longer the kernel
        // (targets) key baked in directly; they are DERIVED by verifying the shipped root
        // metadata up to the baked-in ROOT key (RootTrust -> TufTrust). A patch arms only if
        // its targets key was authorized by a document signed by the root — so trust chains
        // all the way to the single baked root. Fail-closed: if the root chain is missing or
        // the signature threshold is unmet, RootTrust returns empty anchors (arm nothing).
        // If the L2 root resources are absent (e.g. an older build), fall back to the direct
        // kernel anchor so the L0/L1 posture still holds rather than disarming everything.
        TrustAnchors viaRoot = RootTrust.effectiveAnchors();
        if (!viaRoot.isEmpty()) {
            return viaRoot;
        }
        return KernelTrustAnchor.anchors();
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
            // The shipped default signer is a real Ed25519 integrity verifier keyed on the
            // baked-in kernel public key ({@code defaultTrustAnchors}). A patch arms iff its
            // signature verifies under that key — the shipped KI patches (e.g. KI-4) ship
            // signed and arm here; anything unsigned or wrongly-signed does not.
            CompatEngine e = CompatEngine.build(db, new Ed25519PatchSigner(defaultTrustAnchors()), online);
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
                // F3: attach the lease BEFORE the transformer is registered, so no
                // net.minecraft.* class loading in the premain window can hit apply()
                // with a null lease and apply un-leased. A fresh PatchLease is already
                // EMPTY+EXPIRED (fail-closed), so even a class loaded the instant after
                // install() but before the seed authorizes nothing rather than applying.
                e.setLease(live);
                lease = live;
            }
            // Register the transformer LAST (F3): the lease, if any, is already attached.
            e.install(inst);
            if (lease != null) {
                startHeartbeat(db, lease);
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
