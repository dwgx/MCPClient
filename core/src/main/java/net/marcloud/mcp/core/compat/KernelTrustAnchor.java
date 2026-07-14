package net.marcloud.mcp.core.compat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.util.Map;

import net.marcloud.mcp.core.alpc.CompatCrypto;

/**
 * The baked-in kernel trust anchor — the built-in {@link TrustAnchors} entry the shipped
 * client verifies signed compat patches against. This is the compat trust root: a genuine
 * Ed25519 keypair was generated once; the PUBLIC key ships as the jar resource
 * {@code kernel-ed25519.pub} (base64 X.509/SPKI), and the PRIVATE key is held OUTSIDE the
 * repo by the offline signing tool ({@link net.marcloud.mcp.core.compat.tools.PatchSignerCli}).
 *
 * <p>Embedding only a PUBLIC key grants no forging power, so it does not violate the
 * no-long-term-secret-in-client threat model (the same rationale {@link TrustAnchors}
 * documents). A signed patch is trusted only if its signature verifies under this key;
 * the matching private key never enters the game JVM.
 *
 * <p><b>Fail-closed.</b> If the resource is missing, unreadable, or not a valid Ed25519
 * SPKI, {@link #anchors()} returns {@link TrustAnchors#empty()} (trust nothing) rather
 * than throwing — a broken anchor must degrade to "arm nothing", never crash boot or
 * silently weaken trust. Since there is no signature-free arming path, an empty anchor
 * means the engine arms zero patches.
 *
 * <p><b>keyId is dual-use safe.</b> {@link #KEY_ID} is a strict token matching
 * {@code Ed25519PatchSigner.KEY_ID_CHARSET} (letters/digits/dot/underscore/dash, never
 * {@code ':'}), so it round-trips the {@code ed25519:v1:<keyId>:<sig>} wire form the
 * signer parses (red-team F7). The signing CLI stamps exactly this keyId, and this anchor
 * pins the same id to the kernel public key.
 */
public final class KernelTrustAnchor {

    /** The keyId that names the kernel signing key in the wire form and the anchor map. */
    public static final String KEY_ID = "mcp-kernel-ed25519-v1";

    /** Classpath resource holding the base64 X.509/SPKI of the kernel PUBLIC key. */
    static final String RESOURCE = "/net/marcloud/mcp/core/compat/kernel-ed25519.pub";

    private KernelTrustAnchor() {
    }

    /**
     * The trust anchors pinning the kernel public key under {@link #KEY_ID}, or
     * {@link TrustAnchors#empty()} if the baked key cannot be loaded (fail-closed).
     */
    public static TrustAnchors anchors() {
        PublicKey pub = loadKernelPublicKey();
        if (pub == null) {
            System.err.println("[MCP Compat] kernel trust anchor unavailable — "
                    + "no patch will arm (fail-closed).");
            return TrustAnchors.empty();
        }
        return TrustAnchors.of(Map.of(KEY_ID, pub));
    }

    /** The kernel public key, or null on any load/parse error (fail-closed). */
    static PublicKey loadKernelPublicKey() {
        try (InputStream in = KernelTrustAnchor.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                return null;
            }
            String b64 = new String(in.readAllBytes(), StandardCharsets.UTF_8).trim();
            if (b64.isEmpty()) {
                return null;
            }
            byte[] spki = CompatCrypto.unb64(b64);
            return CompatCrypto.decodeSpki("Ed25519", spki);
        } catch (Throwable t) {
            // Any failure -> no anchor. Never throw from the trust-root loader.
            return null;
        }
    }
}
