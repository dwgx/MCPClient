package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.JarFile;

import org.junit.Test;

import net.marcloud.mcp.core.compat.patches.ServerResourcePackDirGuardPatch;

/**
 * SEC-2 arming: the patch is REGISTERED, its signature VERIFIES, and it CHANGES BYTES against the
 * real compiled vanilla class.
 *
 * <p><b>Why "registered" is the wrong word to stop at.</b> A patch that is registered but whose
 * signature does not verify is SKIPPED by {@link CompatEngine}, and the client runs vanilla's
 * throwing {@code deleteOldServerResourcesPacks} anyway &mdash; so the first resource pack any
 * server sends still ends the night. The reverse also happened and is worse:
 * {@code GlClampToEdgePatch} matched an {@code LDC} where javac emits {@code SIPUSH}, so it armed,
 * verified, and returned {@code null} on every real load, reported as armed while doing nothing.
 * Those are two DIFFERENT failures with the SAME symptom in the log ("N patch(es) armed"), and only
 * the engine's own {@code targetsChanged} counter separates them. So this file asserts armed-set
 * membership AND {@code targetsChanged > 0} against the real {@code ResourcePackRepository.class}.
 *
 * <p><b>Everything here is the production composition.</b> {@link Compat#defaultDatabase()} and
 * {@link Compat#defaultTrustAnchors()} are the real ones. There is no trust-everything stand-in
 * signer anywhere in this file, because a stand-in would make the whole file unable to fail for
 * the reason it exists.
 *
 * <p>This file lives in {@code compat}, not {@code compat.patches}, so it calls the
 * package-private {@link CompatEngine#apply} directly instead of reaching it reflectively.
 */
public final class ServerResourcePackDirGuardPatchArmingTest {

    private static final String INTERNAL = "net/minecraft/client/resources/ResourcePackRepository";

    /** A class with no armed patch, used to pin the "no patch means unchanged" contract. */
    private static final String UNRELATED_INTERNAL = "net/minecraft/client/multiplayer/ServerData";

    /** The patchId the shipped manifest derives &mdash; the key every counter is reported under. */
    private static final String PATCH_ID = new ServerResourcePackDirGuardPatch().manifest().patchId();

    /**
     * THE ACCEPTANCE CRITERION. The real engine, the real database, the real trust chain, the real
     * compiled vanilla class &mdash; and the engine's own counters must show it changed bytes.
     */
    @Test
    public void thePatchArmsInTheRealDefaultDatabaseAgainstTheRealTrustChain() throws Exception {
        byte[] vanilla = realVanillaClass();

        CompatEngine engine = realEngine();

        assertTrue("THE POINT: SEC-2 must be in the ARMED set of the real default database built "
                + "against the real baked trust chain. If this fails, the patch is registered but "
                + "the engine skips it, and the client is still running vanilla's throwing "
                + "deleteOldServerResourcesPacks. The usual cause is a KERNEL_SIGNATURE computed "
                + "over different manifest fields than the ones shipped.", engine.armedPatchIds()
                .contains(PATCH_ID));

        assertTrue("and the vanilla class must therefore be in the dispatch index",
                engine.armedInternalNames().contains(INTERNAL));

        byte[] out = engine.apply(INTERNAL, vanilla);

        assertNotNull("an armed patch must hand back changed bytes for the real vanilla class; null "
                + "here is the 'armed and did nothing' failure", out);
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
     * The signature verifies as a STANDALONE fact, so a failure above is attributable.
     */
    @Test
    public void theShippedSignatureVerifiesAgainstTheShippedManifest() {
        PatchManifest m = new ServerResourcePackDirGuardPatch().manifest();

        assertNotNull("SEC-2 ships signed; a null signature means the patch cannot arm at all",
                m.signature());
        assertTrue("THE POINT: the shipped SEC-2 signature must verify under the shipped kernel "
                + "anchor. One character changed in any of the nine signed fields and this goes "
                + "false, which is why a stale signature is silent in production.",
                new Ed25519PatchSigner(Compat.defaultTrustAnchors()).verify(m));
    }

    /**
     * The negative direction, so the positive test is not vacuous. Uses EMPTY anchors rather than
     * a broken signature, so it does not depend on the shipped signature being good.
     */
    @Test
    public void thePatchDoesNotArmWhenNoAnchorIsTrusted() {
        CompatEngine engine = CompatEngine.build(Compat.defaultDatabase(),
                new Ed25519PatchSigner(TrustAnchors.empty()), null);

        assertFalse("with no trusted anchor SEC-2 must not arm, or the signature is decorative",
                engine.armedPatchIds().contains(PATCH_ID));
        assertFalse("and its target must not dispatch",
                engine.armedInternalNames().contains(INTERNAL));
    }

    /**
     * Registering the patch in {@link Compat#defaultDatabase()} is what makes it VISIBLE and
     * dispatchable. Deleting that line produces no error anywhere &mdash; {@code list_compat_patches}
     * simply stops listing the fix &mdash; so it is pinned here directly.
     */
    @Test
    public void thePatchIsRegisteredInTheShippedDefaultDatabase() {
        boolean registered = false;
        for (CompatPatch p : Compat.defaultDatabase().all()) {
            if (p instanceof ServerResourcePackDirGuardPatch) {
                registered = true;
                break;
            }
        }
        assertTrue("SEC-2 must be registered in Compat.defaultDatabase(); a signed but unregistered "
                + "patch is dead code with good tests, which is exactly what this arming change "
                + "exists to prevent", registered);
    }

    /**
     * The shipped default database must carry both corpus-derived security patches.
     *
     * <p>Deliberately asserts MEMBERSHIP, not a total count. A hard-coded count is a tripwire for
     * the next patch that lands, not for the failure this file exists to catch: it went red the
     * moment SEC-3 was added while every fact it cared about was still true. Membership is the
     * claim; the number is incidental.
     */
    @Test
    public void theShippedDefaultDatabaseCarriesBothCorpusDerivedSecurityPatches() {
        boolean sec1 = false;
        boolean sec2 = false;
        for (CompatPatch p : Compat.defaultDatabase().all()) {
            if (p instanceof net.marcloud.mcp.core.compat.patches.LevelSchemePathGuardPatch) {
                sec1 = true;
            }
            if (p instanceof ServerResourcePackDirGuardPatch) {
                sec2 = true;
            }
        }
        assertTrue("SEC-1 must still be registered", sec1);
        assertTrue("SEC-2 must be registered", sec2);
        assertTrue("and the database must not be empty", Compat.defaultDatabase().all().size() >= 6);
    }

    /**
     * Pins the {@link CompatEngine#apply} contract the positive test relies on: {@code null} means
     * unchanged, per the {@code ClassFileTransformer} contract.
     */
    @Test
    public void anUnrelatedClassIsLeftUntouched() {
        assertNull("a class with no armed patch must yield null (unchanged), not bytes",
                realEngine().apply(UNRELATED_INTERNAL, new byte[] {1}));
    }

    /**
     * Re-transforming an already-patched class must be a decline, not a second injection. Asserted
     * through the real engine so it also pins the counter behaviour.
     */
    @Test
    public void theGuardIsNotStackedTwiceUnderTheRealEngine() throws Exception {
        byte[] vanilla = realVanillaClass();
        CompatEngine engine = realEngine();

        byte[] once = engine.apply(INTERNAL, vanilla);
        assertNotNull("premise: the first application must change bytes", once);
        int changedAfterFirst = engine.applyRecord(PATCH_ID).targetsChanged();

        assertNull("applying the already-patched bytes again must yield null (unchanged)",
                engine.apply(INTERNAL, once));
        assertEquals("and the declining second run must not be counted as a change",
                changedAfterFirst, engine.applyRecord(PATCH_ID).targetsChanged());
    }

    /** "The bytes changed" is not the same claim as "the right bytes changed". */
    @Test
    public void theEmittedBytesCarryTheGuard() throws Exception {
        byte[] out = realEngine().apply(INTERNAL, realVanillaClass());
        assertNotNull(out);
        String asLatin1 = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("the patched bytes must reference the dirServerResourcepacks field",
                asLatin1.contains("dirServerResourcepacks"));
        assertTrue("and must call File.isDirectory", asLatin1.contains("isDirectory"));
    }

    // ---- helpers ------------------------------------------------------------

    /** The production composition: shipped database, shipped trust chain, real signer. */
    private static CompatEngine realEngine() {
        return CompatEngine.build(Compat.defaultDatabase(),
                new Ed25519PatchSigner(Compat.defaultTrustAnchors()), null);
    }

    /**
     * Locate the compiled vanilla class from the client module's output, falling back to the
     * shipped jar. SKIPS rather than fails when absent.
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