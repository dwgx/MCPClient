package net.marcloud.mcp.core.compat.tools;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import net.marcloud.mcp.core.compat.RootMetadata;

/**
 * The ROOT half of the ceremony: mints a root keypair and signs the root authorization document
 * that delegates patch-signing authority to a kernel (targets) key.
 *
 * <p><b>Why this is separate from {@link KernelKeygenCli}.</b> Patch verification does not read the
 * shipped kernel public key directly. {@code Compat.defaultTrustAnchors} derives its anchors from
 * {@code root-metadata.json} via {@code RootTrust} → {@code TufTrust}: the document lists the
 * authorized targets keys, and it is itself signed to the baked-in ROOT key. That is a two-level
 * TUF chain, so replacing the kernel key alone accomplishes nothing — the document still authorizes
 * the old one, and a patch signed with the new key fails to verify with no diagnostic beyond
 * "signature not trusted". Measured: that is exactly what happened when only the kernel key was
 * rotated.
 *
 * <p><b>The canonical signing input is not restated here.</b> It is a domain-tagged,
 * length-prefixed, keyId-sorted encoding, and hand-writing a second copy of it is the kind of
 * duplication that silently drifts. This calls {@link RootMetadata#signingBytes()} — the very method
 * the verifier calls — so the two cannot disagree by construction.
 *
 * <p><b>Verifies before writing.</b> The freshly signed document is run back through
 * {@code TufTrust.isRootSignedToBakedTrust} against the new root key, and the resulting anchors are
 * checked to actually contain the kernel keyId. A ceremony that emits an unverifiable document is
 * worse than one that fails, because the breakage only appears after the build ships.
 *
 * <p>Usage — after {@link KernelKeygenCli} has minted the kernel pair:
 * <pre>
 *   java -cp core.jar net.marcloud.mcp.core.compat.tools.RootCeremonyCli \
 *       --keys ~/.mcp-keys --resources core/src/main/resources/net/marcloud/mcp/core/compat
 * </pre>
 * Mints {@code root-ed25519.key.b64} into {@code --keys}, and writes {@code root-ed25519.pub},
 * {@code root-metadata.json} and {@code root-metadata.sig} into {@code --resources}.
 */
public final class RootCeremonyCli {

    /** Private root key filename, kept beside the kernel private key outside the repository. */
    static final String ROOT_PRIVATE_FILE = "root-ed25519.key.b64";
    /** Shipped resource names, matching what {@code RootTrust} loads. */
    static final String ROOT_PUBLIC_FILE = "root-ed25519.pub";
    static final String METADATA_FILE = "root-metadata.json";
    static final String SIGNATURE_FILE = "root-metadata.sig";

    /** The keyIds the client pins. Both are baked into the code, so they are not configurable. */
    static final String ROOT_KEY_ID = "mcp-root-ed25519-v1";
    static final String KERNEL_KEY_ID = "mcp-kernel-ed25519-v1";

    private RootCeremonyCli() {
    }

    public static void main(String[] args) throws Exception {
        String keysDir = null;
        String resourcesDir = null;
        for (int i = 0; i < args.length; i++) {
            if ("--keys".equals(args[i]) && i + 1 < args.length) {
                keysDir = args[++i];
            } else if ("--resources".equals(args[i]) && i + 1 < args.length) {
                resourcesDir = args[++i];
            } else {
                throw new IllegalArgumentException("unknown argument: " + args[i]);
            }
        }
        if (keysDir == null || resourcesDir == null) {
            throw new IllegalArgumentException("--keys <dir> and --resources <dir> are required");
        }

        Path keys = Path.of(expandHome(keysDir));
        Path resources = Path.of(expandHome(resourcesDir));
        Files.createDirectories(keys);
        if (!Files.isDirectory(resources)) {
            throw new IllegalArgumentException("not a directory: " + resources);
        }

        // The kernel public key the document will authorize. Read from the ceremony directory
        // rather than from the shipped resource, so the document is bound to the key the operator
        // just minted rather than to whatever a previous build happened to carry.
        Path kernelPub = keys.resolve(KernelKeygenCli.PUBLIC_FILE);
        if (!Files.exists(kernelPub)) {
            throw new IllegalStateException("kernel public key not found at " + kernelPub
                    + " — run KernelKeygenCli first; the root document has to authorize a key "
                    + "that already exists");
        }
        PublicKey kernel = readPublic(kernelPub);

        KeyPair root = KernelKeygenCli.generate();
        KernelKeygenCli.assertUsable(root);

        // version 2, not 1: the document supersedes the shipped one, and a rotation that reuses
        // the old version number is indistinguishable from the document it replaces.
        Map<String, PublicKey> rootKeys = new LinkedHashMap<>();
        rootKeys.put(ROOT_KEY_ID, root.getPublic());
        Map<String, PublicKey> targetsKeys = new LinkedHashMap<>();
        targetsKeys.put(KERNEL_KEY_ID, kernel);
        RootMetadata meta = new RootMetadata(2, 1, rootKeys, targetsKeys);

        byte[] signingInput = meta.signingBytes();
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(root.getPrivate());
        signer.update(signingInput);
        byte[] signature = signer.sign();

        // Prove the chain BEFORE writing: the document must verify under the new root key, and the
        // anchors it yields must actually contain the kernel keyId patches will be signed under.
        assertChainVerifies(meta, signature, root.getPublic());

        writeOwnerOnly(keys.resolve(ROOT_PRIVATE_FILE),
                Base64.getEncoder().encodeToString(root.getPrivate().getEncoded()));
        Files.write(resources.resolve(ROOT_PUBLIC_FILE),
                (Base64.getEncoder().encodeToString(root.getPublic().getEncoded()) + "\n")
                        .getBytes(StandardCharsets.UTF_8));
        Files.write(resources.resolve(METADATA_FILE),
                metadataJson(meta, root.getPublic(), kernel).getBytes(StandardCharsets.UTF_8));
        Files.write(resources.resolve(SIGNATURE_FILE),
                signatureJson(signature).getBytes(StandardCharsets.UTF_8));

        System.err.println("[RootCeremonyCli] root private key: " + keys.resolve(ROOT_PRIVATE_FILE));
        System.err.println("[RootCeremonyCli] wrote " + ROOT_PUBLIC_FILE + ", " + METADATA_FILE
                + " and " + SIGNATURE_FILE + " into " + resources);
        System.err.println("[RootCeremonyCli] the document authorizes " + KERNEL_KEY_ID
                + "; next: rebuild core, then re-sign EVERY patch with that kernel key");
    }

    /**
     * Run the freshly signed document back through the verifier's own gate.
     *
     * <p>Uses {@code TufTrust} rather than a local re-implementation, for the same reason the
     * signing input is not restated: the only check worth making is the one the client makes.
     */
    static void assertChainVerifies(RootMetadata meta, byte[] signature, PublicKey rootPublic) {
        Map<String, byte[]> sigs = new LinkedHashMap<>();
        sigs.put(ROOT_KEY_ID, signature);
        Map<String, PublicKey> baked = new LinkedHashMap<>();
        baked.put(ROOT_KEY_ID, rootPublic);

        if (!net.marcloud.mcp.core.compat.TufTrust.isRootSignedToBakedTrust(meta, sigs, baked)) {
            throw new IllegalStateException("the signed root document does not verify against the "
                    + "root key that just signed it; refusing to write it");
        }
        net.marcloud.mcp.core.compat.TrustAnchors anchors =
                net.marcloud.mcp.core.compat.TufTrust.effectiveAnchors(meta, sigs, baked);
        if (anchors.lookup(KERNEL_KEY_ID) == null) {
            throw new IllegalStateException("the verified document does not authorize "
                    + KERNEL_KEY_ID + "; patches signed with that key would not arm");
        }
    }

    /**
     * The document, in the flat JSON shape {@code RootTrust} parses.
     *
     * <p>Written by hand rather than through a serializer because the shipped file is exactly this
     * shape and core carries no JSON writer — only a reader. The key ORDER here is irrelevant to
     * verification: {@code signingBytes()} sorts by keyId, which is what makes the encoding
     * independent of how this text happens to be laid out.
     */
    private static String metadataJson(RootMetadata meta, PublicKey rootPub, PublicKey kernelPub) {
        return "{\"version\":" + meta.version()
                + ",\"rootThreshold\":" + meta.rootThreshold()
                + ",\"rootKeys\":{\"" + ROOT_KEY_ID + "\":\""
                + Base64.getEncoder().encodeToString(rootPub.getEncoded()) + "\"}"
                + ",\"targetsKeys\":{\"" + KERNEL_KEY_ID + "\":\""
                + Base64.getEncoder().encodeToString(kernelPub.getEncoded()) + "\"}}";
    }

    /**
     * The detached signature file: keyId → base64 signature.
     *
     * <p>STANDARD base64, not URL-safe. {@code RootTrust} decodes it through
     * {@code CompatCrypto.unb64} and the shipped file uses the standard alphabet; patch signatures
     * are URL-safe, and mixing the two is a silent verification failure.
     */
    private static String signatureJson(byte[] signature) {
        return "{\"" + ROOT_KEY_ID + "\":\""
                + Base64.getEncoder().encodeToString(signature) + "\"}";
    }

    private static PublicKey readPublic(Path path) throws Exception {
        String b64 = Files.readString(path).trim();
        return KeyFactory.getInstance("Ed25519")
                .generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    /** Read a PKCS#8 private key, the encoding {@link KernelKeygenCli} writes. */
    static PrivateKey readPrivate(Path path) throws Exception {
        String b64 = Files.readString(path).trim();
        return KeyFactory.getInstance("Ed25519")
                .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
    }

    private static void writeOwnerOnly(Path path, String contents) throws IOException {
        Files.write(path, (contents + "\n").getBytes(StandardCharsets.UTF_8));
        try {
            Set<PosixFilePermission> ownerOnly = EnumSet.of(
                    PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(path, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            System.err.println("[RootCeremonyCli] could not restrict permissions on " + path
                    + " (" + e + ") — secure it yourself");
        }
    }

    private static String expandHome(String path) {
        if (path.equals("~") || path.startsWith("~/")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
