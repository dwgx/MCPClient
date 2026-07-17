package net.marcloud.mcp.dwm.skiko;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless regression for {@link SkikoRenderBackend#shouldRebuild} — the decision beginFrame
 * takes on resize (window maximize / windowed). The actual rebuild (new DirectContext +
 * Surface over the recreated framebufferMc) is live-only, but the DECISION must be correct:
 * a size change forces a rebuild even when the FBO id is unchanged or unknown, and it must
 * NOT regress the {@link SkikoRenderBackend#fbTargetMoved} guard (never adopt a {@code <=0} id).
 *
 * <p>This locks the fix for the "world goes black, overlay stays perfect, only after resize"
 * bug: the root cause was that a resize rebuilds the Skia surface over the recreated
 * framebufferMc while the stale DirectContext's cache discards the world pixels; the fix
 * rebuilds the context too, and this test proves the rebuild is actually triggered on resize.
 */
public class SkikoResizeDecisionTest {

    @Test
    public void resizeForcesRebuildWhenFboIdUnchanged() {
        // Maximize: MC kept (or GL recycled) the same positive FBO id, but size grew.
        assertTrue("a size change alone must force a rebuild",
                SkikoRenderBackend.shouldRebuild(5, 5, 1920, 1080, 854, 480));
        // And the id must NOT be treated as moved (keep the valid wrapped id).
        assertFalse("same id is not an FBO move",
                SkikoRenderBackend.fbTargetMoved(5, 5));
    }

    @Test
    public void resizeWithRecreatedFboRebuildsAndAdoptsNewId() {
        // Maximize + MC recreated framebufferMc with a new positive id.
        assertTrue("size + id change must rebuild",
                SkikoRenderBackend.shouldRebuild(9, 5, 1920, 1080, 854, 480));
        assertTrue("a new positive id must move the target",
                SkikoRenderBackend.fbTargetMoved(9, 5));
    }

    @Test
    public void resizeRebuildsButKeepsGuardWhenQueryUnknown() {
        // Maximize while the FBO-binding query returned -1 (unknown): size still forces a
        // rebuild, but the bogus id must NOT be adopted (fix must not regress the <=0 guard).
        assertTrue("size change forces rebuild even when the id query is unknown",
                SkikoRenderBackend.shouldRebuild(-1, 5, 1920, 1080, 854, 480));
        assertFalse("unknown (-1) must never move the target",
                SkikoRenderBackend.fbTargetMoved(-1, 5));
    }

    @Test
    public void resizeRebuildsWhenQueryIsDefaultFramebuffer() {
        // Query returned 0 (default framebuffer) during a resize: rebuild on size, keep guard.
        assertTrue("size change forces rebuild even when the query is 0",
                SkikoRenderBackend.shouldRebuild(0, 5, 1280, 720, 854, 480));
        assertFalse("default framebuffer (0) must never move the target",
                SkikoRenderBackend.fbTargetMoved(0, 5));
    }

    @Test
    public void noRebuildWhenNothingChanged() {
        // Steady state: same id, same size — no rebuild (avoids per-frame surface churn).
        assertFalse("unchanged id + size must not rebuild",
                SkikoRenderBackend.shouldRebuild(5, 5, 854, 480, 854, 480));
    }

    @Test
    public void heightOnlyChangeAlsoRebuilds() {
        assertTrue("a height-only change must rebuild",
                SkikoRenderBackend.shouldRebuild(5, 5, 854, 900, 854, 480));
    }
}
