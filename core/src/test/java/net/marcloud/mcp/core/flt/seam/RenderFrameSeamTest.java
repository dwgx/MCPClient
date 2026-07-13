package net.marcloud.mcp.core.flt.seam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Teeth for the render-frame seam ({@link RenderBridge} + {@link RenderFrameInjector}),
 * the render-frame twin of the tick seam. Verifies the behaviors that make the seam
 * safe to inline on the game render thread:
 * <ul>
 *   <li>{@link RenderBridge} dispatches to a wired sink, is a no-op with no sink, and
 *       SWALLOWS a faulting sink (a fault inlined on the render thread must never break
 *       the frame) while still advancing the frame counter;</li>
 *   <li>{@link RenderFrameInjector}'s reset-failure lifecycle retains state for retry
 *       (the same MEDIUM#10 discipline {@link TickInjector} carries), exercised via the
 *       package-private test seam without a live {@code -javaagent} JVM.</li>
 * </ul>
 */
public class RenderFrameSeamTest {

    @Before
    public void resetBridge() {
        RenderBridge.setSink(null);
        RenderBridge.resetCounter();
    }

    @After
    public void clearBridge() {
        RenderBridge.setSink(null);
        RenderBridge.resetCounter();
    }

    // ----- RenderBridge dispatch -----

    @Test
    public void dispatchesToWiredSink() {
        AtomicLong seen = new AtomicLong(-1);
        RenderBridge.setSink(seen::set);
        RenderBridge.onRenderFrame();
        assertEquals("sink invoked with frame counter", 1L, seen.get());
        RenderBridge.onRenderFrame();
        assertEquals("counter advances each frame", 2L, seen.get());
    }

    @Test
    public void noSinkIsNoOpButStillCounts() {
        // No sink wired (the degrade-to-absent case): must not throw, still counts.
        RenderBridge.onRenderFrame();
        RenderBridge.onRenderFrame();
        assertEquals(2L, RenderBridge.frameCounter());
    }

    @Test
    public void faultingSinkIsSwallowed() {
        AtomicInteger calls = new AtomicInteger();
        RenderBridge.setSink(frame -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom in overlay");
        });
        // Must NOT propagate — inlined on the render thread, a fault cannot break the frame.
        RenderBridge.onRenderFrame();
        RenderBridge.onRenderFrame();
        assertEquals("sink was actually called (test not vacuous)", 2, calls.get());
        assertEquals("counter still advances despite faults", 2L, RenderBridge.frameCounter());
    }

    @Test
    public void errorFromSinkIsAlsoSwallowed() {
        RenderBridge.setSink(frame -> {
            throw new StackOverflowError("even an Error must not escape");
        });
        RenderBridge.onRenderFrame(); // must not throw
        assertEquals(1L, RenderBridge.frameCounter());
    }

    // ----- RenderFrameInjector reset-failure lifecycle (MEDIUM#10 twin) -----

    @Test
    public void resetReturningFalseRetainsInstalledStateForRetry() {
        RenderFrameInjector injector = new RenderFrameInjector();
        AtomicInteger resetCalls = new AtomicInteger();
        injector.primeInstalledForTest(() -> {
            resetCalls.incrementAndGet();
            return false; // reset could not revert
        });
        assertTrue(injector.isInstalled());

        assertFalse("honest false when reset fails", injector.uninstall());
        assertEquals(1, resetCalls.get());
        assertTrue("state RETAINED so stale advice is not orphaned", injector.isInstalled());

        injector.primeInstalledForTest(() -> {
            resetCalls.incrementAndGet();
            return true;
        });
        assertTrue("retry succeeds once reset confirms revert", injector.uninstall());
        assertEquals(2, resetCalls.get());
        assertFalse("state cleared only after a confirmed revert", injector.isInstalled());
    }

    @Test
    public void resetThrowingRetainsInstalledStateForRetry() {
        RenderFrameInjector injector = new RenderFrameInjector();
        injector.primeInstalledForTest(() -> {
            throw new IllegalStateException("retransform blew up");
        });
        assertFalse("uninstall reports false when reset throws", injector.uninstall());
        assertTrue("state RETAINED after reset threw (advice still live)", injector.isInstalled());
    }

    @Test
    public void uninstallWhenNotInstalledIsFalse() {
        assertFalse(new RenderFrameInjector().uninstall());
    }
}
