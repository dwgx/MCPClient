package net.marcloud.mcp.dwm.compositor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

/**
 * Teeth tests for {@link Compositor}: tick-before-draw ordering, frame id advance,
 * begin/end pairing, and the continuous-render signal.
 */
public final class CompositorTest {

    @Test
    public void beginFrameTicksBeforeDrawReads() {
        Compositor c = new Compositor();
        FakeWidgetState st = c.store().state(WidgetId.root("w"), FakeWidgetState::new);
        c.beginFrame(0.016f);
        // By the time UI "draws" (after beginFrame), the tick has already advanced.
        assertEquals("tick must run in beginFrame, before draw reads state", 1, st.ticks);
        c.endFrame();
    }

    @Test
    public void frameIdAdvancesPerFrame() {
        Compositor c = new Compositor();
        assertEquals(0, c.frameId());
        c.beginFrame(0.016f);
        c.endFrame();
        assertEquals(1, c.frameId());
    }

    @Test
    public void doubleBeginFails() {
        Compositor c = new Compositor();
        c.beginFrame(0.016f);
        try {
            c.beginFrame(0.016f);
            fail("expected IllegalStateException on double beginFrame");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void endWithoutBeginFails() {
        Compositor c = new Compositor();
        try {
            c.endFrame();
            fail("expected IllegalStateException on endFrame without beginFrame");
        } catch (IllegalStateException expected) {
            // ok
        }
    }

    @Test
    public void continuousRenderReflectsAnimation() {
        Compositor c = new Compositor();
        c.store().state(WidgetId.root("still"), FakeWidgetState::new);
        assertFalse("idle -> on-demand", c.shouldRenderContinuously());
        c.store().state(WidgetId.root("moving"), () -> new FakeWidgetState(3));
        assertTrue("animating -> continuous", c.shouldRenderContinuously());
    }
}
