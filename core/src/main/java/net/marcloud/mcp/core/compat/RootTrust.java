package net.marcloud.mcp.core.compat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.LinkedHashMap;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;
import net.marcloud.mcp.core.io.http.Json;

/**
 * TUF L2 — the baked-in ROOT trust. This is the single ultimate anchor the shipped client
 * holds: the root PUBLIC key(s). From it, {@link #effectiveAnchors()} loads the shipped
 * {@link RootMetadata} + its root signatures, verifies the document up to the baked root
 * ({@link TufTrust}), and returns the targets keys the root authorized — the anchors
 * {@link Ed25519PatchSigner} verifies patches against. A patch therefore arms only if its
 * signing (targets) key was blessed by a document signed by the baked root.
 *
 * <p><b>Why a layer above {@link KernelTrustAnchor}.</b> Previously the kernel (targets) key
 * was baked in directly, so rotating it meant re-shipping the client. Now only the ROOT key is
 * baked; the targets key is authorized by a root-signed document that can be re-issued offline.
 * A compromised targets key is revoked by publishing a new root document that drops it — no
 * client rebuild. This is the standard TUF separation of the trust anchor (root) from the
 * working signer (targets).
 *
 * <p><b>Resources (all under {@code /net/marcloud/mcp/core/compat/}):</b>
 * {@code root-ed25519.pub} (base64 X.509/SPKI root public key), {@code root-metadata.json}
 * (the document: version, threshold, root + targets keys as base64 SPKI), and
 * {@code root-metadata.sig} (JSON keyId→base64 root signature over the document's canonical
 * bytes). Any missing/malformed resource → {@link TrustAnchors#empty()} (fail-closed).
 *
 * <p><b>Fail-closed.</b> Every failure path — missing resource, parse error, root threshold
 * unmet — degrades to empty anchors (arm nothing), never a throw, never a silent trust.
 *
 * <p><b>Root replacement rules (TUF §5.3 / §5.3.10).</b> {@link #verifyRootUpdate} is the path
 * for a DELIVERED root document (rotation/revocation): it refuses one whose version is not
 * exactly one greater than the baked document's, refuses to update from or to a document that is
 * already expired at verification time, and refuses a document whose declared threshold exceeds
 * the number of root keys this client ships baked (that document could never meet its own
 * threshold, so it would silently disarm every patch). {@link #updateRejectionReason} states
 * those rules; the baked path ({@link #effectiveAnchors}) applies the freeze and satisfiability
 * checks to the document it holds. The shipped document declares no expiry, so the freeze check
 * passes for it and bites the moment a document declares one.
 */
public final class RootTrust {

    static final String ROOT_PUB = "/net/marcloud/mcp/core/compat/root-ed25519.pub";
    static final String ROOT_META = "/net/marcloud/mcp/core/compat/root-metadata.json";
    static final String ROOT_SIG = "/net/marcloud/mcp/core/compat/root-metadata.sig";

    private RootTrust() {
    }

    /**
     * The effective patch-verification anchors derived by verifying the shipped root metadata
     * up to the baked root key, or {@link TrustAnchors#empty()} if anything is missing/invalid.
     */
    public static TrustAnchors effectiveAnchors() {
        try {
            Map<String, PublicKey> bakedRoot = loadBakedRootKeys();
            if (bakedRoot.isEmpty()) {
                return failClosed("no baked root key");
            }
            RootMetadata meta = loadMetadata();
            if (meta == null) {
                return failClosed("root metadata missing/invalid");
            }
            // TUF §5.3.10 freeze rule, read for a client with no update cycle: there is no
            // "update start time" here, only the moment of verification, so a baked document
            // that is already expired at that moment is refused rather than trusted. A document
            // that declares no expiry makes no freshness claim and passes (the shipped one
            // declares none); a declared one is enforced.
            long now = System.currentTimeMillis();
            if (meta.expiredAt(now)) {
                return failClosed("baked root document expired at " + meta.expiresEpochMillis()
                        + " (verification time " + now + ") — this client's baked root metadata "
                        + "is stale; update the client rather than trusting an expired root");
            }
            // A threshold the baked side cannot satisfy can never be met by ANY signature set,
            // so the document silently disarms every patch. Say so explicitly instead: the fix
            // is to ship the missing baked key(s), which is a client change (see RootMetadata).
            String thresholdReason = thresholdRejectionReason(meta, bakedRoot.size());
            if (thresholdReason != null) {
                return failClosed(thresholdReason);
            }
            Map<String, byte[]> sigs = loadSignatures();
            if (sigs.isEmpty()) {
                return failClosed("root signatures missing");
            }
            TrustAnchors anchors = TufTrust.effectiveAnchors(meta, sigs, bakedRoot);
            if (anchors.isEmpty()) {
                return failClosed("root signature threshold not met");
            }
            return anchors;
        } catch (Throwable t) {
            return failClosed("root trust load error: " + t);
        }
    }

    /**
     * Verify a DELIVERED root document — a rotation or revocation that arrived from outside
     * this client — up to the baked root, returning the targets keys it authorizes. This is
     * the path where TUF's replacement rules apply; {@link #effectiveAnchors()} verifies the
     * document this client already holds, which nothing supersedes.
     *
     * <p>Enforces, in order: {@link #updateRejectionReason} (rollback §5.3 + freeze §5.3.10),
     * the threshold being satisfiable by the baked key set, then the same root-signature
     * threshold check the baked path uses. Fail-closed: any failure returns
     * {@link TrustAnchors#empty()} after saying why.
     *
     * <p><b>No caller in the shipped client yet.</b> The document is baked into the jar, so
     * today there is no delivery channel to feed this; it exists so the rules are enforced and
     * tested at the moment a channel arrives, instead of being retrofitted onto a chain that
     * already accepted a rolled-back document.
     */
    public static TrustAnchors verifyRootUpdate(RootMetadata candidate,
                                                Map<String, byte[]> rootSignatures) {
        try {
            Map<String, PublicKey> bakedRoot = loadBakedRootKeys();
            if (bakedRoot.isEmpty()) {
                return failClosed("no baked root key");
            }
            RootMetadata trusted = loadMetadata();
            if (trusted == null) {
                return failClosed("trusted (baked) root metadata missing/invalid");
            }
            String reason = updateRejectionReason(trusted, candidate, System.currentTimeMillis());
            if (reason != null) {
                return failClosed("root update refused — " + reason);
            }
            String thresholdReason = thresholdRejectionReason(candidate, bakedRoot.size());
            if (thresholdReason != null) {
                return failClosed("root update refused — " + thresholdReason);
            }
            if (rootSignatures == null || rootSignatures.isEmpty()) {
                return failClosed("root update carries no signatures");
            }
            TrustAnchors anchors = TufTrust.effectiveAnchors(candidate, rootSignatures, bakedRoot);
            if (anchors.isEmpty()) {
                return failClosed("root update signature threshold not met");
            }
            return anchors;
        } catch (Throwable t) {
            return failClosed("root update verification error: " + t);
        }
    }

    /**
     * The TUF replacement rules for a delivered root document, stated in one place so a refusal
     * carries its reason instead of only an empty anchor set:
     * <ol>
     *   <li><b>§5.3 rollback</b> — {@code candidate.version()} must be exactly
     *       {@code trusted.version() + 1}. Not merely newer (a skipped version hides a rotation)
     *       and never equal or lower (that IS a rollback attack).</li>
     *   <li><b>§5.3.10 freeze</b> — the TRUSTED document's declared expiry must be later than the
     *       update start time, or the anchor we are updating from is itself stale.</li>
     *   <li><b>freshness of the delivered document</b> — it must declare an expiry, and that
     *       expiry must also be later than the update start time. Installing an already-expired
     *       document (or one with no expiry to check) would leave the chain with no bound.</li>
     * </ol>
     *
     * <p><b>What "update start time" means here.</b> TUF assumes a client with a wall clock, a
     * persisted trusted-root file and a periodic update cycle. This client has none of those: it
     * bakes the document into the jar and holds no independent clock. The honest reading of the
     * rule for it is "the moment verification happens" — the instant a delivered document is
     * presented for acceptance — so callers pass {@code System.currentTimeMillis()} at
     * verification, and a document already expired when we look at it is refused loudly rather
     * than installed and relied on.
     *
     * @return null when {@code candidate} may replace {@code trusted}, else the reason it may not
     */
    public static String updateRejectionReason(RootMetadata trusted, RootMetadata candidate,
                                               long updateStartEpochMillis) {
        if (trusted == null) {
            return "no trusted root document to update from";
        }
        if (candidate == null) {
            return "no candidate root document";
        }
        if (!candidate.isSuccessorOf(trusted)) {
            return "rollback: root version " + candidate.version() + " is not exactly one greater "
                    + "than the trusted version " + trusted.version() + " (TUF §5.3)";
        }
        if (trusted.expiredAt(updateStartEpochMillis)) {
            return "freeze: the trusted root document expired at " + trusted.expiresEpochMillis()
                    + ", before the update started at " + updateStartEpochMillis + " (TUF §5.3.10)";
        }
        if (candidate.expiresEpochMillis() == 0) {
            return "freeze: the delivered root document declares no expiry, so its freshness "
                    + "cannot be established";
        }
        if (candidate.expiredAt(updateStartEpochMillis)) {
            return "freeze: the delivered root document expired at " + candidate.expiresEpochMillis()
                    + ", before the update started at " + updateStartEpochMillis;
        }
        return null;
    }

    /**
     * The reason a document's declared {@code rootThreshold} can never be met by the
     * {@code bakedRootKeyCount} root keys this client ships, or null when it can.
     *
     * <p>{@link TufTrust} counts a signature only when its key is in BOTH the document's own
     * root-key set AND the baked set, so the effective threshold is capped by the baked key
     * count. A document declaring more than that is unsatisfiable by construction: it would
     * silently disarm every patch, which is exactly what the "raise the threshold to 2-of-3"
     * path documented in {@link RootMetadata} used to do (the baked side holds one key).
     * Refusing it loudly says which side has to change.
     */
    public static String thresholdRejectionReason(RootMetadata document, int bakedRootKeyCount) {
        if (document == null) {
            return "no root document to check a threshold on";
        }
        if (document.rootThreshold() > bakedRootKeyCount) {
            return "root threshold " + document.rootThreshold() + " exceeds the "
                    + bakedRootKeyCount + " root key(s) this client ships baked — the document "
                    + "can never meet its own threshold, so no patch could arm; ship the missing "
                    + "baked key(s) first (a client change, not a data change)";
        }
        return null;
    }

    private static TrustAnchors failClosed(String why) {
        System.err.println("[MCP Compat] root trust unavailable (" + why
                + ") — no patch will arm (fail-closed).");
        return TrustAnchors.empty();
    }

    /** Baked root keyId(s) → public key. Convention: the single root key under its keyId. */
    static Map<String, PublicKey> loadBakedRootKeys() {
        Map<String, PublicKey> out = new LinkedHashMap<>();
        String b64 = readResource(ROOT_PUB);
        if (b64 == null || b64.isBlank()) {
            return out;
        }
        try {
            PublicKey pub = CompatCrypto.decodeSpki("Ed25519", CompatCrypto.unb64(b64.trim()));
            // The baked root keyId is fixed and matches the metadata's declared root key id.
            out.put(ROOT_KEY_ID, pub);
        } catch (Throwable t) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    /** The fixed keyId naming the baked root key (dual-use-safe token, like the kernel keyId). */
    public static final String ROOT_KEY_ID = "mcp-root-ed25519-v1";

    /** Parse {@code root-metadata.json} into a {@link RootMetadata}, or null on any error. */
    @SuppressWarnings("unchecked")
    static RootMetadata loadMetadata() {
        String json = readResource(ROOT_META);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            Map<String, Object> m = Json.readObject(json);
            if (m == null) {
                return null;
            }
            int version = ((Number) m.get("version")).intValue();
            int threshold = ((Number) m.get("rootThreshold")).intValue();
            long expiresEpochMs = 0L;
            Object expires = m.get("expires");
            if (expires != null) {
                if (!(expires instanceof Number n)) {
                    // A declared-but-unreadable expiry must not be silently ignored: it is part
                    // of the signed bytes when present, so dropping it would verify the document
                    // against the wrong input — and would display an expiry nothing enforces.
                    return null;
                }
                expiresEpochMs = n.longValue();
            }
            Map<String, PublicKey> rootKeys = decodeKeyMap((Map<String, Object>) m.get("rootKeys"));
            Map<String, PublicKey> targetsKeys = decodeKeyMap((Map<String, Object>) m.get("targetsKeys"));
            return new RootMetadata(version, threshold, rootKeys, targetsKeys, expiresEpochMs);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Parse {@code root-metadata.sig}: keyId → base64 signature. Empty map on any error. */
    @SuppressWarnings("unchecked")
    static Map<String, byte[]> loadSignatures() {
        Map<String, byte[]> out = new LinkedHashMap<>();
        String json = readResource(ROOT_SIG);
        if (json == null || json.isBlank()) {
            return out;
        }
        try {
            Map<String, Object> parsed = Json.readObject(json);
            if (parsed == null) {
                return out;
            }
            for (Map.Entry<String, Object> e : parsed.entrySet()) {
                if (e.getValue() != null) {
                    out.put(e.getKey(), CompatCrypto.unb64(e.getValue().toString()));
                }
            }
        } catch (Throwable t) {
            return new LinkedHashMap<>();
        }
        return out;
    }

    private static Map<String, PublicKey> decodeKeyMap(Map<String, Object> raw) {
        Map<String, PublicKey> out = new LinkedHashMap<>();
        if (raw == null) {
            return out;
        }
        for (Map.Entry<String, Object> e : raw.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            PublicKey k = CompatCrypto.decodeSpki("Ed25519",
                    CompatCrypto.unb64(e.getValue().toString().trim()));
            out.put(e.getKey(), k);
        }
        return out;
    }

    private static String readResource(String path) {
        try (InputStream in = RootTrust.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            return null;
        }
    }
}
