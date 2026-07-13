package net.marcloud.mcp.dwm.compositor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth tests for {@link DefaultUiStateStore}. Each fails on the wrong behavior:
 * identity across frames, grace-period eviction, animation-protected eviction,
 * tick-advances, anyAnimating, and id-churn (list reorder) not leaking.
 */
public final class DefaultUiStateStoreTest {

    private static WidgetId id(String k) {
        return WidgetId.root(k);
    }

    @Test
    public void sameIdReturnsSameInstanceAcrossFrames() {
        DefaultUiStateStore s = new DefaultUiStateStore();
        FakeWidgetState a = s.state(id("btn"), FakeWidgetState::new);
        s.endFrameGc(0);
        FakeWidgetState b = s.state(id("btn"), FakeWidgetState::new);
        // A ripple timeline must survive frames — the WHOLE point of the store.
        assertSame("same id must return the same instance across frames", a, b);
    }

    @Test
    public void differentIdsDistinctInstances() {
        DefaultUiStateStore s = new DefaultUiStateStore();
        FakeWidgetState a = s.state(id("btnA"), FakeWidgetState::new);
        FakeWidgetState b = s.state(id("btnB"), FakeWidgetState::new);
        assertNotSame(a, b);
        assertEquals(2, s.size());
    }

    @Test
    public void tickAllAdvancesEveryLiveState() {
        DefaultUiStateStore s = new DefaultUiStateStore();
        FakeWidgetState a = s.state(id("a"), FakeWidgetState::new);
        FakeWidgetState b = s.state(id("b"), FakeWidgetState::new);
        s.tickAll(0.016f);
        assertEquals(1, a.ticks);
        assertEquals(1, b.ticks);
        assertEquals(0.016f, a.accumulatedDt, 1e-6);
    }

    @Test
    public void untouchedStateEvictedAfterGracePeriod() {
        DefaultUiStateStore s = new DefaultUiStateStore(3); // evict after 3 idle frames
        s.state(id("gone"), FakeWidgetState::new);
        assertEquals(1, s.size());
        // Run frames WITHOUT touching it. idle counts from the frame after last touch.
        for (int i = 0; i < 5; i++) {
            s.endFrameGc(i);
        }
        assertEquals("untouched state must be evicted after grace period", 0, s.size());
    }

    @Test
    public void touchedStateSurvivesGc() {
        DefaultUiStateStore s = new DefaultUiStateStore(3);
        for (int i = 0; i < 10; i++) {
            s.state(id("kept"), FakeWidgetState::new); // touch every frame
            s.endFrameGc(i);
        }
        assertEquals("a state touched every frame is never evicted", 1, s.size());
    }

    @Test
    public void animatingStateNotEvictedMidAnimation() {
        DefaultUiStateStore s = new DefaultUiStateStore(2);
        // animating for 100 ticks — far beyond the 2-frame idle window.
        s.state(id("anim"), () -> new FakeWidgetState(100));
        // Never touch again, but keep ticking (as the compositor would).
        for (int i = 0; i < 6; i++) {
            s.tickAll(0.016f);
            s.endFrameGc(i);
        }
        assertEquals("a still-animating state must not be evicted", 1, s.size());
        assertTrue(s.anyAnimating());
    }

    @Test
    public void anyAnimatingReflectsLiveStates() {
        DefaultUiStateStore s = new DefaultUiStateStore();
        s.state(id("still"), FakeWidgetState::new);       // animatingForTicks=0 -> not animating
        assertFalse(s.anyAnimating());
        s.state(id("moving"), () -> new FakeWidgetState(5));
        assertTrue(s.anyAnimating());
    }

    @Test
    public void idChurnListReorderDoesNotLeak() {
        DefaultUiStateStore s = new DefaultUiStateStore(2);
        // Frame 1: a list of 3 items with stable per-item keys.
        for (String k : new String[]{"item-x", "item-y", "item-z"}) {
            s.state(WidgetId.of(id("list"), k), FakeWidgetState::new);
        }
        s.endFrameGc(0);
        assertEquals(3, s.size());
        // Frame 2+: list now shows only item-y (x and z removed). Touch only y.
        for (int i = 1; i < 6; i++) {
            s.state(WidgetId.of(id("list"), "item-y"), FakeWidgetState::new);
            s.endFrameGc(i);
        }
        // x and z must be evicted (no leak); y survives.
        assertEquals("dropped list items must be evicted, kept item survives", 1, s.size());
    }
}
