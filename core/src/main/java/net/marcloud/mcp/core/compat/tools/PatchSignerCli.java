package net.marcloud.mcp.core.compat.tools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;

import net.marcloud.mcp.core.alpc.CompatCrypto;
import net.marcloud.mcp.core.compat.Ed25519PatchSigner;
import net.marcloud.mcp.core.compat.KernelTrustAnchor;
import net.marcloud.mcp.core.compat.PatchManifest;
import net.marcloud.mcp.core.compat.TrustAnchors;

/**
 * Offline patch-signing CLI — the "build-time signing" mechanism for compat patches. Every
 * patch must be signed to arm (in-code registration confers no trust); this tool produced
 * KI-4's shipped signature. It takes a patch's canonical signing inputs (targetClass, kiRef,
 * publisher, version, status, platformCondition, supersedes, and an author-supplied
 * transformHash) plus the kernel PRIVATE key from a file, and emits the
 * {@code ed25519:v1:<keyId>:<b64url>} signature string an {@link Ed25519PatchSigner}
 * verifies against the baked-in kernel public key.
 *
 * <p><b>The private key lives OUTSIDE the repo.</b> This tool reads a PKCS#8 Ed25519
 * private key from a file path you pass on the command line; it is NEVER hardcoded and
 * NEVER shipped in the client jar (only the matching PUBLIC key ships, as the
 * {@link KernelTrustAnchor} resource). For now a local key file suffices; the TUF endgame
 * moves this to an air-gapped HSM / KMS ceremony (see the tuf-role-alignment brief).
 *
 * <p><b>HONESTY (KI-10, do not oversell).</b> The signature binds the manifest LABEL
 * (targetClass, contentHash, keyId, status, kiRef, publisher, version, platformCondition,
 * supersedes), NOT the executed transform bytes. {@code transformHash} here is AUTHOR-SUPPLIED — this tool does not (and
 * cannot, without the transform bytecode) recompute it from what the patch's
 * {@code transform()} actually emits. So a valid signature authenticates the label, not
 * the payload. This is safe only because patches are still registered in-code; before any
 * data-channel delivery ships, {@code contentHash} MUST be recomputed from the real
 * transform bytes (a separate KI-10 fix needing owner sign-off). This CLI deliberately
 * does not close that gap and does not claim to.
 *
 * <p><b>Usage:</b>
 * <pre>
 *   java -cp core.jar net.marcloud.mcp.core.compat.tools.PatchSignerCli \
 *       --privkey  &lt;path-to-pkcs8-ed25519.key.b64&gt; \
 *       --target   net.minecraft.network.NetworkSystem \
 *       --kiref    KI-4 \
 *       --publisher kernel \
 *       --transform-hash &lt;sha256-hex-the-author-computed&gt; \
 *       [--keyid    mcp-kernel-ed25519-v1]   (default: the kernel keyId) \
 *       [--version  1.0.0.0] \
 *       [--status   VERIFIED] \
 *       [--platform &lt;platformCondition&gt;]    (default: ""; MUST match the manifest — signed) \
 *       [--supersedes &lt;patchId&gt;]            (default: none; MUST match the manifest — signed)
 * </pre>
 * The private-key file holds the base64 of a PKCS#8-encoded Ed25519 private key (the
 * {@code PRIVATE_PKCS8_B64} the keygen ceremony produced). On success the signature string
 * is printed to stdout (nothing else), so it can be captured into a manifest.
 */
public final class PatchSignerCli {

    private PatchSignerCli() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Map<String, String> a = parse(args);
        String privPath = require(a, "privkey");
        String target = require(a, "target");
        String kiRef = require(a, "kiref");
        String publisher = require(a, "publisher");
        String transformHash = require(a, "transform-hash");
        String keyId = a.getOrDefault("keyid", KernelTrustAnchor.KEY_ID);
        String version = a.getOrDefault("version", "1.0.0.0");
        String statusStr = a.getOrDefault("status", "VERIFIED");
        // name/builtAt are required by the manifest builder but are NOT covered by the
        // signing input (see PatchCanonicalizer), so placeholders here do not affect the
        // produced signature. code is also uncovered. platformCondition and supersedes ARE
        // covered (F1 follow-up), so --platform / --supersedes must match the shipped
        // manifest exactly or the signature will not verify against it.
        String name = a.getOrDefault("name", target);
        String builtAt = a.getOrDefault("built-at", "1970-01-01T00:00:00Z");
        String platform = a.getOrDefault("platform", "");
        String supersedes = a.get("supersedes"); // nullable: absent -> null (canonicalized "")
        String code = a.getOrDefault("code", "MCP-SIGN");

        PatchManifest.Status status;
        try {
            status = PatchManifest.Status.valueOf(statusStr);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("--status must be one of VERIFIED/SUPERSEDED/DISABLED: " + statusStr);
        }

        PrivateKey priv = loadPrivateKey(privPath);

        // Build the unbound manifest, then let the signer bind (transformHash -> contentHash
        // + patchId) and sign the canonical input under our keyId, exactly as the client
        // will recompute + verify it.
        PatchManifest unbound = new PatchManifest.Builder()
                .code(code).name(name).version(version).kiRef(kiRef)
                .targetClass(target).platformCondition(platform)
                .publisher(publisher).builtAt(builtAt).status(status)
                .supersedes(supersedes)
                .build();

        Ed25519PatchSigner signer = new Ed25519PatchSigner(TrustAnchors.empty(), priv, keyId);
        PatchManifest signed = signer.sign(unbound, transformHash);

        // Emit ONLY the signature string on stdout (diagnostics go to stderr).
        System.err.println("[PatchSignerCli] signed patchId=" + signed.patchId()
                + " keyId=" + keyId + " (label-only signature; transform bytes NOT bound — KI-10)");
        System.out.println(signed.signature());
    }

    private static PrivateKey loadPrivateKey(String path) throws Exception {
        byte[] raw = Files.readAllBytes(Path.of(path));
        String b64 = new String(raw, java.nio.charset.StandardCharsets.UTF_8).trim();
        if (b64.isEmpty()) {
            throw new IllegalArgumentException("private key file is empty: " + path);
        }
        byte[] pkcs8 = CompatCrypto.unb64(b64);
        return CompatCrypto.decodePkcs8("Ed25519", pkcs8);
    }

    private static java.util.Map<String, String> parse(String[] args) {
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("--")) {
                throw new IllegalArgumentException("expected --flag, got: " + k);
            }
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException("missing value for " + k);
            }
            m.put(k.substring(2), args[++i]);
        }
        return m;
    }

    private static String require(java.util.Map<String, String> m, String key) {
        String v = m.get(key);
        if (v == null || v.isBlank()) {
            throw new IllegalArgumentException("missing required --" + key);
        }
        return v;
    }
}
