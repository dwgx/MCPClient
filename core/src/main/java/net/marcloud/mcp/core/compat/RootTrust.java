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
            Map<String, PublicKey> rootKeys = decodeKeyMap((Map<String, Object>) m.get("rootKeys"));
            Map<String, PublicKey> targetsKeys = decodeKeyMap((Map<String, Object>) m.get("targetsKeys"));
            return new RootMetadata(version, threshold, rootKeys, targetsKeys);
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
