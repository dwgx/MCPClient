package net.marcloud.mcp.core.compat.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

/**
 * The keygen half of the compat signing ceremony: mints a kernel Ed25519 keypair.
 *
 * <p>{@link PatchSignerCli} signs with a private key but never creates one, so until now the
 * keypair had to be produced by some means outside the project and the exact encodings each side
 * expects were written down in prose rather than enforced. That is the gap this closes.
 *
 * <p><b>The two encodings are not interchangeable and both are load-bearing:</b>
 * <ul>
 *   <li>the PRIVATE key is written as <b>PKCS#8, standard base64</b>, which is what
 *       {@code PatchSignerCli --privkey} reads;</li>
 *   <li>the PUBLIC key is written as <b>X.509/SPKI, standard base64</b>, which is the format of
 *       the shipped {@code kernel-ed25519.pub} resource that {@code KernelTrustAnchor} loads.</li>
 * </ul>
 * Getting these the wrong way round produces files that look plausible and fail with nothing more
 * than "signature not trusted", which is the failure mode this whole ceremony is built to avoid.
 *
 * <p><b>It verifies before it writes.</b> The generated pair is round-tripped through a
 * sign-then-verify of a known message, so a keypair that cannot actually be used never reaches the
 * disk. A ceremony that emits an unusable key is worse than one that fails, because the damage
 * only surfaces after the public key has been baked into a build.
 *
 * <p><b>The private key never goes near the repository.</b> The caller names an output directory
 * and is expected to keep it outside any working tree; the file is created with owner-only
 * permissions where the filesystem supports them. Nothing here logs, copies, or transmits the key.
 *
 * <p>Usage:
 * <pre>
 *   java -cp core.jar net.marcloud.mcp.core.compat.tools.KernelKeygenCli \
 *       --out ~/.mcp-keys [--force]
 * </pre>
 * Writes {@code kernel-ed25519.key.b64} (private, PKCS#8) and {@code kernel-ed25519.pub}
 * (public, SPKI) into that directory. Refuses to overwrite either without {@code --force}, since
 * silently replacing a key that patches are already signed against would invalidate them all.
 */
public final class KernelKeygenCli {

    /** Filename for the private key, matching what {@code sign-patch.sh} documents. */
    static final String PRIVATE_FILE = "kernel-ed25519.key.b64";
    /** Filename for the public key, matching the shipped resource's name. */
    static final String PUBLIC_FILE = "kernel-ed25519.pub";

    private KernelKeygenCli() {
    }

    public static void main(String[] args) throws Exception {
        String out = null;
        boolean force = false;
        for (int i = 0; i < args.length; i++) {
            if ("--out".equals(args[i]) && i + 1 < args.length) {
                out = args[++i];
            } else if ("--force".equals(args[i])) {
                force = true;
            } else {
                throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (out == null) {
            throw new IllegalArgumentException("--out <directory> is required");
        }

        Path dir = Path.of(expandHome(out));
        Files.createDirectories(dir);
        Path privPath = dir.resolve(PRIVATE_FILE);
        Path pubPath = dir.resolve(PUBLIC_FILE);
        if (!force && (Files.exists(privPath) || Files.exists(pubPath))) {
            throw new IllegalStateException("refusing to overwrite existing key material in " + dir
                    + " — pass --force only if you accept that every patch signed against the "
                    + "old key stops verifying");
        }

        KeyPair pair = generate();
        // Prove the pair works BEFORE anything is written: a key that cannot sign-and-verify is
        // only discovered later, after its public half has shipped in a build.
        assertUsable(pair);

        String privB64 = Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded());
        String pubB64 = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());

        writeOwnerOnly(privPath, privB64);
        Files.write(pubPath, (pubB64 + "\n").getBytes(StandardCharsets.UTF_8));

        // The public key goes to stdout so it can be piped into the shipped resource; everything
        // else is diagnostics. The PRIVATE key is deliberately never printed.
        System.err.println("[KernelKeygenCli] wrote private key: " + privPath);
        System.err.println("[KernelKeygenCli] wrote public key:  " + pubPath);
        System.err.println("[KernelKeygenCli] next: copy the public key over "
                + "core/src/main/resources/net/marcloud/mcp/core/compat/kernel-ed25519.pub, "
                + "then re-sign EVERY patch with scripts/sign-patch.sh --privkey " + privPath);
        System.out.println(pubB64);
    }

    /** A fresh Ed25519 keypair from the platform's own generator. */
    static KeyPair generate() throws Exception {
        // Named curve via the standard algorithm name, so the JDK picks its vetted parameters
        // rather than this class restating them.
        KeyPairGenerator gen = KeyPairGenerator.getInstance("Ed25519");
        return gen.generateKeyPair();
    }

    /**
     * Round-trip the pair through a real signature, and reject it if verification fails.
     *
     * <p>Also checks the signature is 64 bytes, the length {@code Ed25519PatchSigner} enforces —
     * a pair whose signatures the verifier would reject on length alone is unusable no matter
     * that the maths worked.
     */
    static void assertUsable(KeyPair pair) throws Exception {
        byte[] message = "mcp-kernel-keygen-selftest".getBytes(StandardCharsets.UTF_8);
        PrivateKey priv = pair.getPrivate();
        PublicKey pub = pair.getPublic();

        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(priv);
        signer.update(message);
        byte[] sig = signer.sign();
        if (sig.length != 64) {
            throw new IllegalStateException("generated key produced a " + sig.length
                    + "-byte signature; Ed25519 signatures must be 64 bytes");
        }

        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pub);
        verifier.update(message);
        if (!verifier.verify(sig)) {
            throw new IllegalStateException("generated keypair failed its own sign/verify "
                    + "round trip; refusing to write unusable key material");
        }
    }

    /**
     * Write a file readable only by its owner.
     *
     * <p>Permissions are applied on POSIX filesystems and skipped elsewhere rather than failing:
     * the ceremony is documented to run on the maintainer's own machine, and refusing to write on
     * a filesystem without POSIX bits would block the ceremony without making anything safer.
     */
    private static void writeOwnerOnly(Path path, String contents) throws IOException {
        Files.write(path, (contents + "\n").getBytes(StandardCharsets.UTF_8));
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            System.err.println("[KernelKeygenCli] could not restrict permissions on " + path
                    + " (" + e + ") — secure it yourself");
        }
    }

    /** Expand a leading {@code ~} , since this is invoked by hand and a tilde is natural to type. */
    private static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
