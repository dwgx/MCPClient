package net.marcloud.mcp.core.compat.tools;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PublicKey;

import net.marcloud.mcp.core.alpc.CompatCrypto;
import net.marcloud.mcp.core.compat.CompatDatabase;
import net.marcloud.mcp.core.compat.CompatEngine;
import net.marcloud.mcp.core.compat.CompatPatch;
import net.marcloud.mcp.core.compat.Ed25519PatchSigner;
import net.marcloud.mcp.core.compat.KernelTrustAnchor;
import net.marcloud.mcp.core.compat.PatchManifest;
import net.marcloud.mcp.core.compat.TrustAnchors;

import org.junit.Test;

/**
 * Teeth for the offline signing CLI ({@link PatchSignerCli}) and the baked kernel anchor.
 * The CLI is the "build-time signing" mechanism (it produced KI-4's shipped signature);
 * these prove:
 * <ul>
 *   <li>a signature it emits (from a private key on disk, NEVER hardcoded) verifies
 *       against the anchor pinned to the MATCHING public key, and drives a patch to
 *       ARM through the real engine;</li>
 *   <li>the same signature is REJECTED under the shipped baked kernel anchor, because the
 *       baked public key does NOT match this test's throwaway private key — i.e. only the
 *       real kernel private key (held by the owner, outside the repo) can mint a signature
 *       the shipped client trusts. This is the end-to-end supply-chain property.</li>
 * </ul>
 */
public final class PatchSignerCliTest {

    /** Run the CLI main, capturing stdout (the signature) and restoring streams. */
    private static String runCli(String... args) throws Exception {
        PrintStream origOut = System.out;
        PrintStream origErr = System.err;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            // Keep stderr quiet-ish but captured; diagnostics are non-load-bearing.
            System.setErr(new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8));
            PatchSignerCli.main(args);
        } finally {
            System.setOut(origOut);
            System.setErr(origErr);
        }
        return out.toString(StandardCharsets.UTF_8).trim();
    }

    @Test
    public void cliSignatureVerifiesUnderMatchingAnchorAndArms() throws Exception {
        // A throwaway keypair standing in for the kernel signing key. The PRIVATE half is
        // written to a temp FILE (as the real ceremony keeps it on disk, outside the repo);
        // the CLI reads it from there — never hardcoded.
        var kp = CompatCrypto.generateEd25519();
        Path keyFile = Files.createTempFile("mcp-test-priv", ".key.b64");
        try {
            Files.writeString(keyFile,
                    java.util.Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()));

            String target = "net.minecraft.client.renderer.texture.TextureUtil";
            String transformHash = PatchManifest.sha256Hex("cli-signed-transform");
            String sig = runCli(
                    "--privkey", keyFile.toString(),
                    "--target", target,
                    "--kiref", "KI-1",
                    "--publisher", "kernel",
                    "--transform-hash", transformHash,
                    "--keyid", KernelTrustAnchor.KEY_ID);

            assertTrue("CLI must emit an ed25519:v1: signature, got: " + sig,
                    sig.startsWith("ed25519:v1:"));

            // Rebuild the exact manifest the CLI signed (same covered fields), attach the
            // signature, register as a DATA patch, and verify it arms under an anchor
            // pinned to the MATCHING public key.
            PatchManifest signed = new PatchManifest.Builder()
                    .code("MCP-SIGN").name(target).version("1.0.0.0").kiRef("KI-1")
                    .targetClass(target).platformCondition("").publisher("kernel")
                    .builtAt("1970-01-01T00:00:00Z").status(PatchManifest.Status.VERIFIED)
                    .build()
                    .withTransform(transformHash, sig);

            PublicKey pub = kp.getPublic();
            Ed25519PatchSigner verifier =
                    new Ed25519PatchSigner(TrustAnchors.of(java.util.Map.of(KernelTrustAnchor.KEY_ID, pub)));
            assertTrue("CLI signature must verify under the matching anchor", verifier.verify(signed));

            CompatDatabase db = new CompatDatabase();
            db.register(signedPatch(signed));
            CompatEngine engine = CompatEngine.build(db, verifier);
            assertTrue("a CLI-signed patch must arm under the matching anchor",
                    engine.armedPatchIds().contains(signed.patchId()));
        } finally {
            Files.deleteIfExists(keyFile);
        }
    }

    @Test
    public void cliSignatureFromWrongKeyIsRejectedByBakedKernelAnchor() throws Exception {
        // The supply-chain wall: a signature minted by a NON-kernel private key must be
        // rejected under the SHIPPED baked kernel anchor. Only the real kernel private key
        // (outside this repo) can mint a signature the shipped client trusts.
        var wrongKp = CompatCrypto.generateEd25519();
        Path keyFile = Files.createTempFile("mcp-test-wrong", ".key.b64");
        try {
            Files.writeString(keyFile,
                    java.util.Base64.getEncoder().encodeToString(wrongKp.getPrivate().getEncoded()));
            String target = "net.minecraft.client.Foo";
            String transformHash = PatchManifest.sha256Hex("wrong-key-transform");
            String sig = runCli(
                    "--privkey", keyFile.toString(),
                    "--target", target,
                    "--kiref", "KI-x",
                    "--publisher", "kernel",
                    "--transform-hash", transformHash,
                    "--keyid", KernelTrustAnchor.KEY_ID);

            PatchManifest signed = new PatchManifest.Builder()
                    .code("MCP-SIGN").name(target).version("1.0.0.0").kiRef("KI-x")
                    .targetClass(target).platformCondition("").publisher("kernel")
                    .builtAt("1970-01-01T00:00:00Z").status(PatchManifest.Status.VERIFIED)
                    .build()
                    .withTransform(transformHash, sig);

            // Verify under the SHIPPED baked kernel anchor (real public key). The wrong
            // private key cannot satisfy it.
            Ed25519PatchSigner shippedVerifier =
                    new Ed25519PatchSigner(net.marcloud.mcp.core.compat.Compat.defaultTrustAnchors());
            assertFalse("a signature from a non-kernel key must be rejected by the baked anchor",
                    shippedVerifier.verify(signed));

            CompatDatabase db = new CompatDatabase();
            db.register(signedPatch(signed));
            CompatEngine engine = CompatEngine.build(db, shippedVerifier);
            assertTrue("a wrong-key-signed patch must not arm under the baked anchor",
                    engine.armedPatchIds().isEmpty());
        } finally {
            Files.deleteIfExists(keyFile);
        }
    }

    private static CompatPatch signedPatch(PatchManifest m) {
        return new CompatPatch() {
            @Override public PatchManifest manifest() { return m; }
            @Override public byte[] transform(byte[] original) { return new byte[]{7}; }
        };
    }
}
