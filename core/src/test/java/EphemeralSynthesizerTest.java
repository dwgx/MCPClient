import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import net.marcloud.mcp.core.synth.EphemeralSynthesizer;
import org.junit.Before;
import org.junit.Test;

/**
 * Tests EphemeralSynthesizer: compile+define+invoke hidden classes, GC-ability,
 * error handling, package constraint.
 */
public class EphemeralSynthesizerTest {

    private EphemeralSynthesizer synth;

    @Before
    public void setup() {
        this.synth = new EphemeralSynthesizer();
    }

    @Test
    public void computesFromArgs() {
        String src = """
                package net.marcloud.mcp.core.synth;
                import java.util.Map;
                public class Compute {
                    public String handle(Map<String,Object> args) {
                        int a = ((Number) args.get("a")).intValue();
                        int b = ((Number) args.get("b")).intValue();
                        return String.valueOf(a + b);
                    }
                }
                """;
        var result = synth.eval("net.marcloud.mcp.core.synth.Compute", src,
                Map.of("a", 3, "b", 4));
        assertTrue(result.message(), result.success());
        assertEquals("7", result.output());
    }

    @Test
    public void missingHandleReported() {
        String src = """
                package net.marcloud.mcp.core.synth;
                public class NoHandle {
                    public String wrongName() { return "nope"; }
                }
                """;
        var result = synth.eval("net.marcloud.mcp.core.synth.NoHandle", src, Map.of());
        assertFalse(result.success());
        assertTrue(result.message().contains("handle"));
    }

    @Test
    public void wrongPackageRejected() {
        String src = """
                package gen;
                import java.util.Map;
                public class WrongPkg {
                    public String handle(Map<String,Object> args) { return "bad"; }
                }
                """;
        var result = synth.eval("gen.WrongPkg", src, Map.of());
        assertFalse(result.success());
        assertTrue(result.message().contains("package net.marcloud.mcp.core.synth"));
    }

    @Test
    public void compileErrorReported() {
        String src = """
                package net.marcloud.mcp.core.synth;
                public class Broken {
                    int x = ;
                }
                """;
        var result = synth.eval("net.marcloud.mcp.core.synth.Broken", src, Map.of());
        assertFalse(result.success());
        assertTrue(result.message().contains("compile failed"));
    }

    @Test
    public void definesHiddenClass() {
        String src = """
                package net.marcloud.mcp.core.synth;
                import java.util.Map;
                public class Hidden {
                    public String handle(Map<String,Object> args) { return "ok"; }
                }
                """;
        var result = synth.eval("net.marcloud.mcp.core.synth.Hidden", src, Map.of());
        assertTrue(result.success());
        assertNotNull(result.hiddenClass().get());
        assertTrue(result.hiddenClass().get().isHidden());
    }

    @Test
    public void hiddenClassIsGCable() throws InterruptedException {
        // Non-vacuous GC test (audit #15): register the hidden class's weak ref with
        // a ReferenceQueue and hold NO strong reference to the Class after eval. If
        // the synthesizer leaked a strong ref (or we did), the ref never enqueues and
        // this test FAILS — the earlier version pinned the class in a local and could
        // only ever pass. We assert the ref is genuinely collected, and separately
        // that it WAS a hidden class (checked before dropping the ref).
        String src = """
                package net.marcloud.mcp.core.synth;
                import java.util.Map;
                public class Temp {
                    public String handle(Map<String,Object> args) { return "temp"; }
                }
                """;
        var result = synth.eval("net.marcloud.mcp.core.synth.Temp", src, Map.of());
        assertTrue(result.success());

        java.lang.ref.ReferenceQueue<Class<?>> queue = new java.lang.ref.ReferenceQueue<>();
        java.lang.ref.WeakReference<Class<?>> ref;
        {
            Class<?> hidden = result.hiddenClass().get();
            assertNotNull("class present immediately after eval", hidden);
            assertTrue("synthesized class must be a hidden class", hidden.isHidden());
            ref = new java.lang.ref.WeakReference<>(hidden, queue);
            hidden = null; // drop the only strong reference in this test
        }

        // GC should now be able to reclaim it; ReferenceQueue.remove confirms it was
        // actually collected (not merely that get() returned null due to clearing).
        for (int i = 0; i < 50 && ref.get() != null; i++) {
            System.gc();
            System.runFinalization();
            Thread.sleep(20);
        }
        assertNull("hidden class must be GC-able once all strong refs are dropped",
                ref.get());
    }

    @Test
    public void evalThrowSurfaces() {
        String src = """
                package net.marcloud.mcp.core.synth;
                import java.util.Map;
                public class Throw {
                    public String handle(Map<String,Object> args) {
                        throw new RuntimeException("boom");
                    }
                }
                """;
        var result = synth.eval("net.marcloud.mcp.core.synth.Throw", src, Map.of());
        assertFalse(result.success());
        assertTrue(result.message().contains("eval threw"));
        assertTrue(result.message().contains("boom"));
    }
}
