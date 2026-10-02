package net.marcloud.mcp.core.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.compat.patches.GlClampToEdgePatch;
import net.marcloud.mcp.core.compat.patches.IdentityProbePatch;

import org.junit.Test;

/**
 * The observable the compat layer promised and did not have: {@code armed} is authorisation, and this
 * test pins the distinction between "authorised" and "changed bytes".
 *
 * <p>The regression it exists for: {@code GlClampToEdgePatch} matched {@code LDC} where javac emits
 * {@code SIPUSH}, so it armed, verified, and did nothing — recorded in
 * {@code compat/patches/GlClampToEdgePatch.java:152-159}. Nothing in the tool could tell, because
 * {@code apply} computed the answer and discarded it at the return.
 *
 * <p><b>Why this file exists next to {@code GlClampToEdgePatchMeetsTheRealVanillaClassTest}.</b>
 * That test calls {@code patch.transform} directly, so it is blind by construction to the gap this
 * one closes: a patch can pass its own unit test while the engine around it never sees a byte
 * change. This test goes through {@link CompatEngine#apply} and through
 * {@link CompatTools#reportFor}, which is where the armed-versus-acted distinction actually lives.
 */
public final class CompatEngineAppliedObservableTest {

    private static final String TEXTUREUTIL_INTERNAL =
            "net/minecraft/client/renderer/texture/TextureUtil";

    /** A signer that trusts every bound manifest (same stand-in the other engine tests use). */
    private static final PatchSigner TRUSTING = new PatchSigner() {
        @Override public boolean verify(PatchManifest m) { return m != null && m.isBound(); }
        @Override public PatchManifest sign(PatchManifest m, String h) { return m.withTransform(h, "sig"); }
    };

    private static CompatEngine engineOf(CompatPatch... patches) {
        CompatDatabase db = new CompatDatabase();
        for (CompatPatch p : patches) {
            db.register(p);
        }
        return CompatEngine.build(db, TRUSTING, null);
    }

    private static byte[] realVanillaTextureUtil() {
        for (Path root : new Path[] {Path.of("client/target/classes"), Path.of("../client/target/classes")}) {
            Path p = root.resolve(TEXTUREUTIL_INTERNAL + ".class");
            if (Files.isRegularFile(p)) {
                try {
                    return Files.readAllBytes(p);
                } catch (IOException e) {
                    throw new AssertionError("read " + p, e);
                }
            }
        }
        return null;
    }

    /**
     * THE TEST. The shipped GL clamp patch, run through the engine against the class it actually
     * meets. Under the old code there was nothing to assert here at all: the boolean died inside
     * apply(). With the observable in place, a patch that arms but rewrites nothing is
     * distinguishable from one that works — and this is the case that regresses if someone narrows
     * the transform's accepted encodings again.
     */
    @Test
    public void anArmedPatchThatChangesTheRealVanillaClassIsCountedAsChanged() {
        byte[] vanilla = realVanillaTextureUtil();
        assumeTrue("client/target/classes not built; run ./mvnw.cmd -pl client compile", vanilla != null);

        GlClampToEdgePatch patch = new GlClampToEdgePatch();
        CompatEngine engine = engineOf(patch);
        String id = patch.manifest().patchId();

        assertTrue("premise: the patch's real shipped signature must arm it under a trusting signer",
                engine.armedPatchIds().contains(id));

        byte[] out = engine.apply(TEXTUREUTIL_INTERNAL, vanilla);

        assertNotNull("premise: on the REAL vanilla class the transform must return patched bytes; "
                + "null here means the patch armed and did nothing", out);
        CompatEngine.ApplyRecord r = engine.applyRecord(id);
        assertEquals("the transform ran exactly once, for this class", 1, r.transformRuns());
        assertEquals("THE POINT OF THIS TEST: a patch that handed the JVM different bytes must be "
                + "counted, otherwise an armed no-op reads identically to a working patch",
                1, r.targetsChanged());
    }

    /**
     * The same engine, a patch that arms and then changes nothing: the shipped IdentityProbePatch,
     * whose transform is a no-op by design. This is the GlClampToEdge failure SHAPE as a standing
     * property, and it needs no build fixture, so it never skips.
     */
    @Test
    public void anArmedPatchThatRunsAndChangesNothingIsReportedAsInert() {
        IdentityProbePatch probe = new IdentityProbePatch();
        CompatEngine engine = engineOf(probe);
        String id = probe.manifest().patchId();
        String internal = IdentityProbePatch.TARGET.replace('.', '/');

        assertTrue("premise: it arms", engine.armedPatchIds().contains(id));
        assertNull("and it changes nothing (JDK transformer convention)", engine.apply(internal, new byte[]{1, 2, 3}));

        CompatEngine.ApplyRecord r = engine.applyRecord(id);
        assertEquals("it must be recorded as HAVING RUN", 1, r.transformRuns());
        assertEquals("and as changing nothing -- this is the shape that shipped in GL clamp and was "
                + "indistinguishable from success", 0, r.targetsChanged());
        assertEquals("a no-op is not a failure", 0, r.applyFailures());
        assertEquals("", r.lastApplyError());
    }

    /**
     * Armed, ran, and threw. Today the same three facts as armed+success. Fail-open keeps the
     * original bytes (already asserted in CompatEngineTest.throwingPatchPreservesOriginalBytes --
     * do not weaken that test, this one adds the triage).
     */
    @Test
    public void aThrowingPatchIsRecordedAsAFailureAndNotAsSuccess() {
        PatchManifest m = new PatchManifest.Builder()
                .code("MCP-KI9999").name("t").version("1.0.0.0").kiRef("KI-test")
                .targetClass("net.minecraft.client.Foo").platformCondition("").publisher("kernel")
                .builtAt("2026-07-13T00:00:00Z").status(PatchManifest.Status.VERIFIED).build()
                .withTransform(PatchManifest.sha256Hex("throwing"), null);
        CompatEngine engine = engineOf(new CompatPatch() {
            @Override public PatchManifest manifest() { return m; }
            @Override public byte[] transform(byte[] original) {
                throw new IllegalStateException("buggy patch");
            }
        });

        assertNull("fail-open: the original bytes are kept", engine.apply("net/minecraft/client/Foo", new byte[]{1, 2, 3}));

        CompatEngine.ApplyRecord r = engine.applyRecord(m.patchId());
        assertEquals(1, r.transformRuns());
        assertEquals(0, r.targetsChanged());
        assertEquals("a throw is not a no-op: it must be counted separately", 1, r.applyFailures());
        assertTrue("and it must name the class it happened on: " + r.lastApplyError(),
                r.lastApplyError().contains("net/minecraft/client/Foo"));
        assertTrue(r.lastApplyError().contains("IllegalStateException"));
    }

    /** Every armed patch has a record, even one that never ran: absence must never be a state. */
    @Test
    public void everyArmedPatchHasARecordAndAnUnknownIdYieldsZerosNotNull() {
        IdentityProbePatch probe = new IdentityProbePatch();
        CompatEngine engine = engineOf(probe);   // built, never applied
        assertEquals("the record set is exactly the armed set",
                engine.armedPatchIds(), engine.applyRecords().keySet());
        CompatEngine.ApplyRecord untouched = engine.applyRecord(probe.manifest().patchId());
        assertEquals("armed but not executed is its own state, not zero-change", 0, untouched.transformRuns());
        assertEquals(0, untouched.targetsChanged());
        CompatEngine.ApplyRecord unknown = engine.applyRecord("cp-does-not-exist");
        assertTrue("never null", unknown != null);
        assertEquals(0, unknown.transformRuns());
        assertEquals(0, unknown.targetsChanged());
    }

    /**
     * The three states a caller must be able to tell apart, read off the tool's own report:
     * changed, inert, and threw. A report that could only say "armed" would pass the old code;
     * this one cannot.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void theToolReportSeparatesChangedInertAndFailedPatches() {
        CompatDatabase db = new CompatDatabase();
        IdentityProbePatch probe = new IdentityProbePatch();          // will run and change nothing
        db.register(probe);
        PatchManifest boom = throwingManifest("net.minecraft.client.Boom", "boom");
        db.register(new CompatPatch() {
            @Override public PatchManifest manifest() { return boom; }
            @Override public byte[] transform(byte[] original) {
                throw new IllegalStateException("buggy patch");
            }
        });
        CompatEngine engine = CompatEngine.build(db, TRUSTING, null);
        engine.apply(IdentityProbePatch.TARGET.replace('.', '/'), new byte[]{1});
        engine.apply("net/minecraft/client/Boom", new byte[]{1});

        Map<String, Object> out = CompatTools.reportFor(db, engine);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("patches");
        assertEquals(2, rows.size());

        Map<String, Object> inertRow = rowFor(rows, probe.manifest().patchId());
        Map<String, Object> failedRow = rowFor(rows, boom.patchId());

        assertEquals("armed keeps its meaning: authorised", Boolean.TRUE, inertRow.get("armed"));
        assertEquals("1 armed and inert: ran, changed nothing, did not throw",
                1, ((Integer) inertRow.get("transformRuns")).intValue());
        assertEquals(0, ((Integer) inertRow.get("targetsChanged")).intValue());
        assertEquals(0, ((Integer) inertRow.get("applyFailures")).intValue());
        assertEquals("", inertRow.get("lastApplyError"));

        assertEquals("2 armed and failed: ran, changed nothing, THREW -- the third state",
                1, ((Integer) failedRow.get("transformRuns")).intValue());
        assertEquals(0, ((Integer) failedRow.get("targetsChanged")).intValue());
        assertEquals(1, ((Integer) failedRow.get("applyFailures")).intValue());
        assertTrue("and it is attributable: " + failedRow.get("lastApplyError"),
                String.valueOf(failedRow.get("lastApplyError")).contains("net/minecraft/client/Boom"));

        assertEquals(2, ((Integer) out.get("armedCount")).intValue());
        assertEquals("nothing changed any bytes", 0, ((Integer) out.get("changedCount")).intValue());
        assertEquals("but BOTH ran, so both are inert -- the headline that did not exist",
                2, ((Integer) out.get("inertCount")).intValue());
        assertEquals("and one of them threw, which inertCount alone would hide",
                1, ((Integer) out.get("failedCount")).intValue());
    }

    /**
     * An armed patch that has not been reached yet is NOT inert and NOT failed: it simply has not
     * run. Without this the headline would blame a patch whose target class never loaded.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void anArmedPatchThatHasNotRunIsNeitherInertNorFailed() {
        CompatDatabase db = new CompatDatabase();
        IdentityProbePatch probe = new IdentityProbePatch();
        db.register(probe);
        CompatEngine engine = CompatEngine.build(db, TRUSTING, null);

        Map<String, Object> out = CompatTools.reportFor(db, engine);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("patches");
        Map<String, Object> row = rowFor(rows, probe.manifest().patchId());
        assertEquals(0, ((Integer) row.get("transformRuns")).intValue());
        assertEquals(0, ((Integer) out.get("inertCount")).intValue());
        assertEquals(0, ((Integer) out.get("failedCount")).intValue());
    }

    /** An engine that never ignited (headless run, no javaagent) must not fabricate rows. */
    @Test
    @SuppressWarnings("unchecked")
    public void aNullEngineReportsNothingArmedAndNothingRun() {
        CompatDatabase db = new CompatDatabase();
        IdentityProbePatch probe = new IdentityProbePatch();
        db.register(probe);

        Map<String, Object> out = CompatTools.reportFor(db, null);
        List<Map<String, Object>> rows = (List<Map<String, Object>>) out.get("patches");
        Map<String, Object> row = rowFor(rows, probe.manifest().patchId());
        assertEquals(Boolean.FALSE, row.get("armed"));
        assertEquals(0, ((Integer) row.get("transformRuns")).intValue());
        assertEquals(0, ((Integer) out.get("armedCount")).intValue());
        assertEquals(0, ((Integer) out.get("inertCount")).intValue());
        assertEquals(0, ((Integer) out.get("changedCount")).intValue());
        assertEquals(0, ((Integer) out.get("failedCount")).intValue());
    }

    private static PatchManifest throwingManifest(String targetClass, String seed) {
        return new PatchManifest.Builder()
                .code("MCP-KI9999").name("t").version("1.0.0.0").kiRef("KI-test")
                .targetClass(targetClass).platformCondition("").publisher("kernel")
                .builtAt("2026-07-13T00:00:00Z").status(PatchManifest.Status.VERIFIED).build()
                .withTransform(PatchManifest.sha256Hex(seed), null);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> rowFor(List<Map<String, Object>> rows, String patchId) {
        for (Map<String, Object> row : rows) {
            if (patchId.equals(row.get("patchId"))) {
                return row;
            }
        }
        throw new AssertionError("no report row for patchId " + patchId);
    }
}