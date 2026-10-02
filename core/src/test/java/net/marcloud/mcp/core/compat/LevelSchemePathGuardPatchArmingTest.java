package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;

import org.junit.Test;

import net.marcloud.mcp.core.compat.patches.LevelSchemePathGuardPatch;

/**
 * SEC-1 arming: the patch is REGISTERED, its signature VERIFIES, and it CHANGES BYTES against the
 * real compiled vanilla class.
 *
 * <p><b>Why "registered" is the wrong word to stop at.</b> In this codebase a patch that is
 * registered but whose signature does not verify is SKIPPED by {@link CompatEngine}, and the client
 * runs vanilla's vulnerable {@code level://} branch anyway. The reverse also happened, and is worse:
 * {@code GlClampToEdgePatch} matched an {@code LDC} where javac emits {@code SIPUSH}, so it armed,
 * verified, and returned {@code null} on every real load &mdash; reported as armed while doing
 * nothing. Those are two DIFFERENT failures with the SAME symptom in the log ("N patch(es) armed"),
 * and only the engine's own {@code targetsChanged} counter separates them. So this file does not
 * stop at registration, or even at arming: it asserts armed-set membership AND
 * {@code targetsChanged > 0} against the real {@code NetHandlerPlayClient.class}.
 *
 * <p><b>Everything here is the production composition.</b> {@link Compat#defaultDatabase()} and
 * {@link Compat#defaultTrustAnchors()} are the real ones &mdash; the shipped registration list, and
 * the real baked-root &rarr; root-metadata &rarr; targets-key &rarr; patch-signature derivation.
 * There is no trust-everything stand-in signer anywhere in this file, because a stand-in would make
 * the whole file unable to fail for the reason it exists. (See
 * {@code LevelSchemePathGuardPatchMeetsTheRealVanillaClassTest}, which uses one deliberately, because
 * it measures the transform rather than the trust chain.)
 *
 * <p><b>Mutation coverage, built in rather than bolted on.</b> Corrupting one character of
 * {@link LevelSchemePathGuardPatch#KERNEL_SIGNATURE} turns
 * {@link #thePatchArmsInTheRealDefaultDatabaseAgainstTheRealTrustChain()} red; deleting the
 * {@code db.register(...)} line from {@link Compat#defaultDatabase()} turns it red too, via
 * {@link #thePatchIsRegisteredInTheShippedDefaultDatabase()}. Both are silent at runtime, and only a
 * test that reads the real shipped constants can tell them apart.
 *
 * <p>This file lives in {@code compat}, not {@code compat.patches}, so it calls the package-private
 * {@link CompatEngine#apply} directly instead of reaching it reflectively. That is deliberate:
 * reflecting over the engine's own dispatch would let a signature change to the method silently
 * reduce this file to a compile error in a test nobody reads, whereas a direct call fails here.
 */
public final class LevelSchemePathGuardPatchArmingTest {

    /** The vanilla class this patch transforms, JVM internal name (what the engine keys on). */
    private static final String INTERNAL = "net/minecraft/client/network/NetHandlerPlayClient";

    /** A class with no armed patch, used to pin the "no patch means unchanged" contract. */
    private static final String UNRELATED_INTERNAL =
            "net/minecraft/client/multiplayer/ServerData";

    /** The patchId the shipped manifest derives &mdash; the key every counter is reported under. */
    private static final String PATCH_ID = new LevelSchemePathGuardPatch().manifest().patchId();

    /**
     * THE ACCEPTANCE CRITERION. The real engine, the real database, the real trust chain, the real
     * compiled vanilla class &mdash; and the engine's own counters must show it changed bytes.
     *
     * <p>Both halves are load-bearing and neither substitutes for the other. Armed-set membership
     * alone is satisfied by a patch that transforms nothing; {@code targetsChanged > 0} alone would
     * be satisfied by a patch nobody dispatches. Only together do they mean "the guard is live in
     * the shipped client".
     */
    @Test
    public void thePatchArmsInTheRealDefaultDatabaseAgainstTheRealTrustChain() throws Exception {
        byte[] vanilla = realVanillaClass();

        CompatEngine engine = realEngine();

        assertTrue("THE POINT: SEC-1 must be in the ARMED set of the real default database built "
                + "against the real baked trust chain. If this fails, the patch is registered but "
                + "the engine skips it, and the client is still running vanilla's vulnerable "
                + "level:// branch. The usual cause is a KERNEL_SIGNATURE computed over different "
                + "manifest fields than the ones shipped, which verifies against nothing.",
                engine.armedPatchIds().contains(PATCH_ID));

        assertTrue("and the vanilla class must therefore be in the dispatch index",
                engine.armedInternalNames().contains(INTERNAL));

        byte[] out = engine.apply(INTERNAL, vanilla);

        assertNotNull("an armed patch must hand back changed bytes for the real vanilla class; null "
                + "here is the 'armed and did nothing' failure that hides behind the "
                + "'N patch(es) armed' log line", out);
        CompatEngine.ApplyRecord r = engine.applyRecord(PATCH_ID);
        assertEquals("the transform must have run once, for this class", 1, r.transformRuns());
        assertEquals("THE POINT: the engine handed the JVM DIFFERENT bytes, and must say so. This "
                + "is the counter that separates 'armed' from 'armed and inert'.", 1,
                r.targetsChanged());
        assertEquals("and it must not have thrown to get there", 0, r.applyFailures());
        assertEquals("", r.lastApplyError());

        assertFalse("the patched bytes must actually differ from vanilla's", Arrays.equals(vanilla, out));
    }

    /**
     * The signature verifies as a STANDALONE fact, so a failure above is attributable: it was either
     * the signature or something else, and this says which.
     *
     * <p>Note this is deliberately NOT "assert my computation equals itself". The bytes checked here
     * are recomputed by {@link PatchCanonicalizer} from the SHIPPED manifest's nine covered fields
     * and verified under the SHIPPED kernel public key &mdash; the assertion and the production path
     * are the same code.
     */
    @Test
    public void theShippedSignatureVerifiesAgainstTheShippedManifest() {
        PatchManifest m = new LevelSchemePathGuardPatch().manifest();

        assertNotNull("SEC-1 ships signed; a null signature means the patch cannot arm at all",
                m.signature());
        assertTrue("THE POINT: the shipped SEC-1 signature must verify under the shipped kernel "
                + "anchor. One character changed in any of the nine signed fields (targetClass, "
                + "contentHash, keyId, status, kiRef, publisher, version, supersedes, "
                + "platformCondition) and this goes false, which is why a stale signature is silent "
                + "in production.",
                new Ed25519PatchSigner(Compat.defaultTrustAnchors()).verify(m));
    }

    /**
     * The negative direction, so the positive test is not vacuous.
     *
     * <p>Uses EMPTY anchors rather than a broken signature, so it does not depend on the shipped
     * signature being good: the durable statement of the rule is "no trusted key, no arming,
     * whatever the manifest carries". A patch dead for an unrelated reason (wrong status, protected
     * target, L0 mismatch) would fail the positive test while passing this one; a patch that armed
     * under untrusted anchors would fail this one while passing the positive test. Both directions
     * are needed for either to mean anything.
     */
    @Test
    public void thePatchDoesNotArmWhenNoAnchorIsTrusted() {
        CompatEngine engine = CompatEngine.build(Compat.defaultDatabase(),
                new Ed25519PatchSigner(TrustAnchors.empty()), null);

        assertFalse("with no trusted anchor SEC-1 must not arm, or the signature is decorative",
                engine.armedPatchIds().contains(PATCH_ID));
        assertFalse("and its target must not dispatch",
                engine.armedInternalNames().contains(INTERNAL));
    }

    /**
     * Registering the patch in {@link Compat#defaultDatabase()} is what makes it VISIBLE and
     * dispatchable; without the line, a valid signature alone would still leave the client on
     * vanilla's branch. Pinned here because a deleted registration line produces no error anywhere
     * &mdash; {@code list_compat_patches} simply stops listing a security fix.
     */
    @Test
    public void thePatchIsRegisteredInTheShippedDefaultDatabase() {
        boolean registered = false;
        for (CompatPatch p : Compat.defaultDatabase().all()) {
            if (p instanceof LevelSchemePathGuardPatch) {
                registered = true;
                break;
            }
        }
        assertTrue("SEC-1 must be registered in Compat.defaultDatabase(); a signed but unregistered "
                + "patch is dead code with good tests, which is exactly what this arming change "
                + "exists to prevent", registered);
    }

    /**
     * Pins the {@link CompatEngine#apply} contract the positive test relies on: {@code null} means
     * unchanged, per the {@code ClassFileTransformer} contract &mdash; so a class with no armed patch
     * is never handed back as "changed" bytes.
     */
    @Test
    public void anUnrelatedClassIsLeftUntouched() {
        assertNull("a class with no armed patch must yield null (unchanged), not bytes",
                realEngine().apply(UNRELATED_INTERNAL, new byte[] {1}));
    }

    /**
     * Re-transforming an already-patched class must be a decline, not a second injection. Asserted
     * through the real engine so it also pins the counter behaviour: a declining run increments
     * {@code transformRuns} but NOT {@code targetsChanged}, which is how an inert re-run stays
     * distinguishable from a real one in {@code list_compat_patches}.
     */
    @Test
    public void theGuardIsNotStackedTwiceUnderTheRealEngine() throws Exception {
        byte[] vanilla = realVanillaClass();
        CompatEngine engine = realEngine();

        byte[] once = engine.apply(INTERNAL, vanilla);
        assertNotNull("premise: the first application must change bytes", once);
        int changedAfterFirst = engine.applyRecord(PATCH_ID).targetsChanged();

        assertNull("applying the already-patched bytes again must yield null (unchanged): the "
                + "guard cannot be injected twice. Returning bytes here would mean every "
                + "re-transform of this class grew the method and stacked another check.",
                engine.apply(INTERNAL, once));
        assertEquals("and the declining second run must not be counted as a change",
                changedAfterFirst, engine.applyRecord(PATCH_ID).targetsChanged());
    }

    /**
     * "The bytes changed" is not the same claim as "the right bytes changed": any wrong rewrite
     * would also satisfy the counter. So assert the guard call is actually in the emitted class.
     */
    @Test
    public void theEmittedBytesCarryTheGuardCall() throws Exception {
        byte[] out = realEngine().apply(INTERNAL, realVanillaClass());
        assertNotNull(out);
        String asLatin1 = new String(out, StandardCharsets.ISO_8859_1);
        assertTrue("the patched bytes must reference LevelSchemePathGuardPatch.isInsideSaves",
                asLatin1.contains("isInsideSaves"));
        assertTrue("and must name the guard's owner class",
                asLatin1.contains("LevelSchemePathGuardPatch"));
    }

    // ---- helpers ------------------------------------------------------------

    /** The production composition: shipped database, shipped trust chain, real signer. */
    private static CompatEngine realEngine() {
        return CompatEngine.build(Compat.defaultDatabase(),
                new Ed25519PatchSigner(Compat.defaultTrustAnchors()), null);
    }

    /**
     * Locate the compiled vanilla class from the client module's output, falling back to the shipped
     * jar. SKIPS rather than fails when absent: a test that fails because someone skipped a build step
     * teaches people to skip builds.
     */
    private static byte[] realVanillaClass() throws IOException {
        String entry = INTERNAL + ".class";
        for (Path root : new Path[] {
                Path.of("client/target/classes"), Path.of("../client/target/classes")}) {
            Path p = root.resolve(entry);
            if (Files.isRegularFile(p)) {
                return Files.readAllBytes(p);
            }
        }
        for (Path jar : new Path[] {
                Path.of("client/target/MCP-1.8.9.jar"), Path.of("../client/target/MCP-1.8.9.jar")}) {
            if (Files.isRegularFile(jar)) {
                try (JarFile jf = new JarFile(jar.toFile())) {
                    var e = jf.getJarEntry(entry);
                    if (e != null) {
                        try (InputStream in = jf.getInputStream(e)) {
                            return in.readAllBytes();
                        }
                    }
                }
            }
        }
        assumeTrue("no compiled " + INTERNAL + " (build the client module, or produce "
                + "MCP-1.8.9.jar)", false);
        return new byte[0];
    }

}