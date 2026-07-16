package net.marcloud.mcp.dwm.skiko;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless regression for {@link SkikoRenderBackend#fbTargetMoved(int, int)} — the guard
 * that decides whether beginFrame rebuilds the Skia surface over a new FBO. The surface
 * build itself is live-only (real GL + skiko native), but the id decision is pure integer
 * logic and must never adopt a non-positive queried id (the {@code -1} unknown sentinel or
 * the default framebuffer {@code 0}) as MC's wrap target — doing so would rebuild over the
 * wrong FBO and drop a valid surface, a black-overlay path.
 */
public class SkikoFboGuardTest {

    @Test
    public void positiveNewIdMovesTarget() {
        assertTrue("a real, different FBO id must move the target",
                SkikoRenderBackend.fbTargetMoved(7, 3));
    }

    @Test
    public void sameIdDoesNotMoveTarget() {
        assertFalse("an unchanged id must not rebuild",
                SkikoRenderBackend.fbTargetMoved(3, 3));
    }

    @Test
    public void unknownSentinelNeverMovesTarget() {
        // Host returns -1 when it cannot resolve the binding; must keep the last valid id.
        assertFalse("unknown (-1) must not replace a valid wrapped FBO",
                SkikoRenderBackend.fbTargetMoved(-1, 3));
    }

    @Test
    public void defaultFramebufferZeroNeverMovesTarget() {
        // 0 is the default framebuffer, never MC's framebufferMc; must not become the target.
        assertFalse("default framebuffer (0) must not replace a valid wrapped FBO",
                SkikoRenderBackend.fbTargetMoved(0, 3));
    }

    @Test
    public void unknownDoesNotForceRebuildEvenFromZeroInitial() {
        // Before the first valid id (wrapped == 0), a -1 query still must not "move" to -1.
        assertFalse("unknown query must not move target off the initial 0",
                SkikoRenderBackend.fbTargetMoved(-1, 0));
    }
}
