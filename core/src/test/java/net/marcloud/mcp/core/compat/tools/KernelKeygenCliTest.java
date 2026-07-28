package net.marcloud.mcp.core.compat.tools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Pins the key encodings the rest of the ceremony depends on.
 *
 * <p>The failure this guards against is specific and quiet: a private key written in the wrong
 * encoding, or a public key that does not match it, produces a build where every patch reports
 * "signature not trusted" and nothing says why. So these tests do not check that files appeared —
 * they decode each file with the SAME reader the real consumers use, and prove the pair actually
 * verifies against each other.
 */
public final class KernelKeygenCliTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    /**
     * The private key must decode as PKCS#8 and the public key as X.509/SPKI.
     *
     * <p>Those are what {@code PatchSignerCli.loadPrivateKey} and {@code KernelTrustAnchor} read
     * respectively. Swapping them is the mistake that costs a whole debugging session, because
     * both files are base64 and both look fine.
     */
    @Test
    public void writesAPkcs8PrivateKeyAndAnSpkiPublicKey() throws Exception {
        Path dir = tmp.newFolder("keys").toPath();
        KernelKeygenCli.main(new String[] {"--out", dir.toString()});

        Path priv = dir.resolve(KernelKeygenCli.PRIVATE_FILE);
        Path pub = dir.resolve(KernelKeygenCli.PUBLIC_FILE);
        assertTrue("the private key file must be written", Files.exists(priv));
        assertTrue("the public key file must be written", Files.exists(pub));

        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = factory.generatePrivate(
                new PKCS8EncodedKeySpec(decode(priv)));
        PublicKey publicKey = factory.generatePublic(
                new X509EncodedKeySpec(decode(pub)));

        assertNotNull("the private key must decode as PKCS#8", privateKey);
        assertNotNull("the public key must decode as X.509/SPKI", publicKey);
        // The JDK reports the FAMILY name "EdDSA" here, not the curve name "Ed25519" that was
        // passed to KeyPairGenerator.getInstance. Asserting the curve name fails on a correct key,
        // so the family is what can be checked; the curve is pinned by the 64-byte signature
        // length the self-check enforces and by the pairing test below.
        assertEquals("EdDSA", privateKey.getAlgorithm());
        assertEquals("EdDSA", publicKey.getAlgorithm());
    }

    /** The two files must be halves of ONE pair, proven by a real signature round trip. */
    @Test
    public void theWrittenKeysAreAMatchingPair() throws Exception {
        Path dir = tmp.newFolder("keys").toPath();
        KernelKeygenCli.main(new String[] {"--out", dir.toString()});

        KeyFactory factory = KeyFactory.getInstance("Ed25519");
        PrivateKey privateKey = factory.generatePrivate(new PKCS8EncodedKeySpec(
                decode(dir.resolve(KernelKeygenCli.PRIVATE_FILE))));
        PublicKey publicKey = factory.generatePublic(new X509EncodedKeySpec(
                decode(dir.resolve(KernelKeygenCli.PUBLIC_FILE))));

        byte[] message = "pairing-check".getBytes(StandardCharsets.UTF_8);
        java.security.Signature signer = java.security.Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(message);
        byte[] sig = signer.sign();

        java.security.Signature verifier = java.security.Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(message);
        assertTrue("the public key must verify what the private key signed; mismatched halves are "
            + "indistinguishable from a bad signature at runtime", verifier.verify(sig));
    }

    /**
     * Overwriting existing key material must require an explicit opt-in.
     *
     * <p>Replacing a key silently would invalidate every patch already signed against it, and the
     * symptom appears only after the public half has shipped.
     */
    @Test
    public void refusesToOverwriteWithoutForce() throws Exception {
        Path dir = tmp.newFolder("keys").toPath();
        KernelKeygenCli.main(new String[] {"--out", dir.toString()});
        byte[] before = Files.readAllBytes(dir.resolve(KernelKeygenCli.PUBLIC_FILE));

        try {
            KernelKeygenCli.main(new String[] {"--out", dir.toString()});
            fail("a second run without --force must refuse rather than replace the key");
        } catch (IllegalStateException expected) {
            assertTrue("the refusal must explain the consequence",
                expected.getMessage().contains("old key"));
        }
        assertTrue("the existing key must be untouched after a refusal",
            java.util.Arrays.equals(before,
                Files.readAllBytes(dir.resolve(KernelKeygenCli.PUBLIC_FILE))));

        // With --force it proceeds, and produces a DIFFERENT key.
        KernelKeygenCli.main(new String[] {"--out", dir.toString(), "--force"});
        assertTrue("--force must actually mint a new key",
            !java.util.Arrays.equals(before,
                Files.readAllBytes(dir.resolve(KernelKeygenCli.PUBLIC_FILE))));
    }

    /** The self-check must reject a pair that cannot sign and verify its own message. */
    @Test
    public void theSelfCheckAcceptsAGenuinePairAndRejectsAMismatchedOne() throws Exception {
        KeyPair good = KernelKeygenCli.generate();
        // Must not throw.
        KernelKeygenCli.assertUsable(good);

        // A pair assembled from two different keypairs is exactly the "looks fine, never verifies"
        // case, and the check has to catch it.
        KeyPair other = KernelKeygenCli.generate();
        KeyPair mismatched = new KeyPair(other.getPublic(), good.getPrivate());
        try {
            KernelKeygenCli.assertUsable(mismatched);
            fail("assertUsable must reject a mismatched pair");
        } catch (IllegalStateException expected) {
            assertTrue("the failure must name the round trip",
                expected.getMessage().contains("round trip"));
        }
    }

    /** The private key must not be world-readable where the filesystem can express that. */
    @Test
    public void thePrivateKeyIsOwnerOnlyWherePosixPermissionsExist() throws Exception {
        Path dir = tmp.newFolder("keys").toPath();
        KernelKeygenCli.main(new String[] {"--out", dir.toString()});
        Path priv = dir.resolve(KernelKeygenCli.PRIVATE_FILE);
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                    Files.getPosixFilePermissions(priv);
            assertTrue("group must not be able to read the private key",
                !perms.contains(java.nio.file.attribute.PosixFilePermission.GROUP_READ));
            assertTrue("others must not be able to read the private key",
                !perms.contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
        } catch (UnsupportedOperationException e) {
            // A filesystem without POSIX bits cannot express this; the tool says so and moves on.
            System.out.println("[test] no POSIX permissions here — skipping");
        }
    }

    private static byte[] decode(Path path) throws Exception {
        // Same read the real consumers do: whole file, trimmed, standard base64.
        String b64 = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        return Base64.getDecoder().decode(b64);
    }
}
