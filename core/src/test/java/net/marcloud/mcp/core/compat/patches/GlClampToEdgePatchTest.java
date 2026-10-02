package net.marcloud.mcp.core.compat.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import net.marcloud.mcp.core.compat.Compat;
import net.marcloud.mcp.core.compat.Ed25519PatchSigner;
import net.marcloud.mcp.core.compat.PatchManifest;

/**
 * The vendored client must be vanilla, and the behaviour the port wanted must come from a patch.
 *
 * <p>This pins both halves, because either alone is easy to satisfy dishonestly. Asserting only
 * "the patch transforms" would pass while the vendor tree carried the same edit, which is the
 * arrangement this whole change exists to undo: a change nobody can locate, nobody can revert,
 * and nobody can reason about apart from its author.
 *
 * <p>The owner's standing policy is that a behavioural change to vendored code goes through a
 * compat patch, because a patch is one signed manifest entry with a status and an evidence
 * string, while an edit to {@code client/src} is only findable by whoever made it.
 */
public class GlClampToEdgePatchTest {

    private static final String TEXTURE_UTIL =
            "client/src/main/java/net/minecraft/client/renderer/texture/TextureUtil.java";

    /**
     * Surefire runs with the MODULE directory as the working directory, not the repo root, so
     * the vendored tree is one level up. Both spellings are tried because which one is right
     * depends on how the suite is invoked, and a test that only passes under one of them is
     * testing the invocation as much as the file.
     */
    private static String vendorSource() throws IOException {
        for (String candidate : new String[] {TEXTURE_UTIL, "../" + TEXTURE_UTIL}) {
            Path p = Paths.get(candidate);
            if (Files.exists(p)) {
                return new String(Files.readAllBytes(p), StandardCharsets.UTF_8)
                        .replace("\r\n", "\n");
            }
        }
        throw new AssertionError("cannot find " + TEXTURE_UTIL + " from "
                + Paths.get(".").toAbsolutePath());
    }

    /**
     * The vendored file must carry vanilla's constant, not the port's.
     *
     * <p>Fails on the pre-change tree with "vanilla GL_CLAMP must be back in the vendored
     * source" — which is exactly the state this patch was written to end.
     */
    @Test
    public void theVendoredSourceIsVanillaAgain() throws Exception {
        String src = vendorSource();
        assertTrue("vanilla GL_CLAMP must be back in the vendored source; the modern-constant "
                        + "fix belongs in " + GlClampToEdgePatch.class.getSimpleName(),
                src.contains("GL11.GL_TEXTURE_WRAP_S, GL11.GL_CLAMP)"));
        assertTrue("and GL_CLAMP_TO_EDGE must be gone from client/src entirely",
                !src.contains("GL_CLAMP_TO_EDGE"));
    }

    /**
     * The other reverted edit must stay reverted.
     *
     * <p>Vanilla bounds the transparent-texel pre-scan by the MIP LEVEL count. The port widened
     * it to the base-level pixel count, which moves every cutout sprite onto the alpha-weighted
     * averaging branch. Nothing replaces it: the commit that made the change concedes it does
     * not fix KI-1, so there is no defect for a patch to fix.
     */
    @Test
    public void theMipmapScanBoundIsVanillaAndUnreplaced() throws Exception {
        String src = vendorSource();
        assertTrue("the mipmap pre-scan must be bounded by the level count again",
                src.contains("i < p_147949_2_.length"));
        assertTrue("and must not be widened to the base-level pixel count",
                !src.contains("i < p_147949_2_[0].length"));
    }

    // ===== the patch itself =====

    /** Every literal handed to glTexParameteri as the wrap mode, in emission order. */
    private static java.util.List<Integer> wrapConstants(byte[] classBytes) {
        ClassReader reader = new ClassReader(classBytes);
        ClassNode cn = new ClassNode();
        reader.accept(cn, 0);
        java.util.List<Integer> out = new java.util.ArrayList<>();
        for (MethodNode mn : cn.methods) {
            if (!GlClampToEdgePatch.METHOD_NAME.equals(mn.name)
                    || !GlClampToEdgePatch.METHOD_DESC.equals(mn.desc)) {
                continue;
            }
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null;
                    insn = insn.getNext()) {
                if (insn.getOpcode() == Opcodes.INVOKESTATIC
                        && insn instanceof MethodInsnNode min
                        && "glTexParameteri".equals(min.name)) {
                    // The value argument is the push immediately before the call.
                    AbstractInsnNode back = insn.getPrevious();
                    if (back instanceof LdcInsnNode ldc && ldc.cst instanceof Integer v) {
                        out.add(v);
                    }
                }
            }
        }
        return out;
    }

    @Test
    public void thePatchRewritesVanillaClampToEdge() {
        GlClampToEdgePatch patch = new GlClampToEdgePatch();
        byte[] out = patch.transform(patch.canaryClassBytes());
        assertNotNull("the patch must fire on its own canary", out);
        java.util.List<Integer> got = wrapConstants(out);
        assertEquals("both wrap-mode constants must be rewritten", 2, got.size());
        assertEquals("GL_CLAMP_TO_EDGE", 0x812F, got.get(0).intValue());
        assertEquals("GL_CLAMP_TO_EDGE", 0x812F, got.get(1).intValue());
    }

    /**
     * An already-fixed class must be left alone rather than rewritten again.
     *
     * <p>The transform is idempotent by returning {@code null} when nothing matched, so a second
     * application is a no-op instead of a corruption.
     */
    @Test
    public void thePatchIsIdempotent() {
        GlClampToEdgePatch patch = new GlClampToEdgePatch();
        byte[] once = patch.transform(patch.canaryClassBytes());
        assertNotNull(once);
        assertNull("a second application must be a no-op, not a second rewrite",
                patch.transform(once));
    }

    /** An unrecognised shape must degrade to vanilla behaviour, never to a wrong rewrite. */
    @Test
    public void anUnrecognisedShapeIsLeftAlone() {
        assertNull(new GlClampToEdgePatch().transform(null));
        assertNull(new GlClampToEdgePatch().transform(new byte[0]));
        assertNull("a class with no such method must not be touched",
                new GlClampToEdgePatch().transform(readUnrelatedClass()));
    }

    /** Any real class that is not TextureUtil, to prove the key does not fire broadly. */
    private static byte[] readUnrelatedClass() {
        try (java.io.InputStream in = Object.class.getResourceAsStream("/java/util/Date.class")) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new AssertionError("could not read the canary class", e);
        }
    }

    /**
     * The shipped signature must verify against the shipped manifest, through the real anchor.
     *
     * <p>This is the assertion that would have caught the actual mistake. The signature was
     * produced correctly and then installed through a shell pipe; it arrived the right LENGTH
     * with the wrong bytes, so nothing that only inspected the string could tell. The live client
     * caught it, a hundred lines later, as
     * {@code SKIP unverified patch MCP-GL0001 — signature not trusted} and a texture path that
     * silently stopped clamping.
     *
     * <p>Ed25519 is deterministic, so a mismatch is not "the key rotated": re-signing with the
     * current key and seeing the bytes change would mean something else is wrong. This test
     * instead checks the property that actually matters — the shipped pair verifies — and the
     * two controls beside it prove the failure mode is specific to this patch rather than a
     * broken trust chain.
     */
    @Test
    public void theShippedSignatureVerifiesAgainstTheShippedManifest() {
        Ed25519PatchSigner verifier = new Ed25519PatchSigner(Compat.defaultTrustAnchors());
        assertTrue("the shipped GL0001 manifest must verify against the real kernel anchor",
                verifier.verify(new GlClampToEdgePatch().manifest()));
    }

    /** Controls, so a red here means THIS patch is wrong rather than the trust chain being down. */
    @Test
    public void theOtherShippedPatchesStillVerify() {
        Ed25519PatchSigner verifier = new Ed25519PatchSigner(Compat.defaultTrustAnchors());
        assertTrue("KI-1 must verify", verifier.verify(new Ki1MipmapZeroFillPatch().manifest()));
        assertTrue("KI-11 must verify", verifier.verify(new Ki11DwmHotkeyPatch().manifest()));
    }

    /** A single flipped character must not survive: that is precisely how this one broke. */
    @Test
    public void aCorruptedSignatureIsRejected() {
        String good = GlClampToEdgePatch.KERNEL_SIGNATURE;
        char[] chars = good.toCharArray();
        int i = chars.length - 1;
        chars[i] = chars[i] == 'A' ? 'B' : 'A';
        String corrupted = new String(chars);

        PatchManifest m = new GlClampToEdgePatch().manifest();
        PatchManifest tampered = new PatchManifest.Builder()
                .code(m.code()).name("t").version(m.version()).kiRef(m.kiRef())
                .targetClass(m.targetClass()).platformCondition(m.platformCondition())
                .publisher(m.publisher()).builtAt("t").status(m.status())
                .build()
                .withTransform(PatchManifest.sha256Hex(GlClampToEdgePatch.TRANSFORM_SEED),
                        corrupted);
        assertTrue("a one-character corruption must not verify -- otherwise a corrupted constant "
                        + "is indistinguishable from a good one until a texture misbehaves",
                !new Ed25519PatchSigner(Compat.defaultTrustAnchors()).verify(tampered));
    }
}
