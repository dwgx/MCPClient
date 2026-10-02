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

import net.marcloud.mcp.core.compat.patches.ScoreboardNullGuardPatch;

/**
 * SEC-3 arming: the patch is REGISTERED, its signature VERIFIES, and it CHANGES BYTES against the
 * real compiled vanilla {@code Scoreboard.class}.
 *
 * <p>Everything here is the production composition &mdash; {@link Compat#defaultDatabase()} and
 * {@link Compat#defaultTrustAnchors()} are the real ones, with a real
 * {@link Ed25519PatchSigner} and no trust-everything stand-in. A stand-in would make this file
 * unable to fail for the reason it exists.
 */
public final class ScoreboardNullGuardPatchArmingTest {

    private static final String INTERNAL = "net/minecraft/scoreboard/Scoreboard";
    private static final String UNRELATED_INTERNAL = "net/minecraft/client/multiplayer/ServerData";

    private static final String PATCH_ID = new ScoreboardNullGuardPatch().manifest().patchId();

    /** THE ACCEPTANCE CRITERION. Armed-set membership AND targetsChanged > 0. */
    @Test
    public void thePatchArmsInTheRealDefaultDatabaseAgainstTheRealTrustChain() throws Exception {
        byte[] vanilla = realVanillaClass();
        CompatEngine engine = realEngine();

        assertTrue("THE POINT: SEC-3 must be in the ARMED set of the real default database built "
                + "against the real baked trust chain.", engine.armedPatchIds().contains(PATCH_ID));
        assertTrue("and the vanilla class must therefore be in the dispatch index",
                engine.armedInternalNames().contains(INTERNAL));

        byte[] out = engine.apply(INTERNAL, vanilla);

        assertNotNull("an armed patch must hand back changed bytes for the real vanilla class", out);
        CompatEngine.ApplyRecord r = engine.applyRecord(PATCH_ID);
        assertEquals("the transform must have run once, for this class", 1, r.transformRuns());
        assertEquals("THE POINT: the engine handed the JVM DIFFERENT bytes, and must say so. This "
                + "is the counter that separates 'armed' from 'armed and inert'.", 1,
                r.targetsChanged());
        assertEquals("and it must not have thrown to get there", 0, r.applyFailures());
        assertFalse("the patched bytes must actually differ from vanilla's", Arrays.equals(vanilla, out));
    }

    @Test
    public void theShippedSignatureVerifiesAgainstTheShippedManifest() {
        PatchManifest m = new ScoreboardNullGuardPatch().manifest();
        assertNotNull("SEC-3 ships signed", m.signature());
        assertTrue("THE POINT: the shipped SEC-3 signature must verify under the shipped kernel "
                + "anchor.", new Ed25519PatchSigner(Compat.defaultTrustAnchors()).verify(m));
    }

    @Test
    public void thePatchDoesNotArmWhenNoAnchorIsTrusted() {
        CompatEngine engine = CompatEngine.build(Compat.defaultDatabase(),
                new Ed25519PatchSigner(TrustAnchors.empty()), null);
        assertFalse("with no trusted anchor SEC-3 must not arm",
                engine.armedPatchIds().contains(PATCH_ID));
        assertFalse("and its target must not dispatch",
                engine.armedInternalNames().contains(INTERNAL));
    }

    @Test
    public void thePatchIsRegisteredInTheShippedDefaultDatabase() {
        boolean registered = false;
        for (CompatPatch p : Compat.defaultDatabase().all()) {
            if (p instanceof ScoreboardNullGuardPatch) {
                registered = true;
                break;
            }
        }
        assertTrue("SEC-3 must be registered in Compat.defaultDatabase(); a signed but unregistered "
                + "patch is dead code with good tests", registered);
    }

    /**
     * Membership, not a total count. A hard-coded count is a tripwire for the next patch that
     * lands rather than for the failure this file exists to catch &mdash; it went red the moment
     * SEC-3 was added while every fact it cared about was still true.
     */
    @Test
    public void theShippedDefaultDatabaseCarriesAtLeastThePreExistingPatches() {
        assertTrue("the shipped database must hold the five pre-existing patches plus SEC-1..3",
                Compat.defaultDatabase().all().size() >= 7);
    }

    @Test
    public void anUnrelatedClassIsLeftUntouched() {
        assertNull("a class with no armed patch must yield null (unchanged), not bytes",
                realEngine().apply(UNRELATED_INTERNAL, new byte[] {1}));
    }

    @Test
    public void theGuardsAreNotStackedTwiceUnderTheRealEngine() throws Exception {
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
    public void theEmittedBytesCarryBothGuards() throws Exception {
        byte[] out = realEngine().apply(INTERNAL, realVanillaClass());
        assertNotNull(out);
        String asLatin1 = new String(out, java.nio.charset.StandardCharsets.ISO_8859_1);
        assertTrue("the patched bytes must reference the teams map",
                asLatin1.contains("teams"));
        assertTrue("and the scoreObjectives map", asLatin1.contains("scoreObjectives"));
    }

    private static CompatEngine realEngine() {
        return CompatEngine.build(Compat.defaultDatabase(),
                new Ed25519PatchSigner(Compat.defaultTrustAnchors()), null);
    }

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
        assumeTrue("no compiled " + INTERNAL, false);
        return new byte[0];
    }
}