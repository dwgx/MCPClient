package net.marcloud.mcp.dwm.desktop;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for the click-edge state machine: a click fires only on release-while-hovered, and
 * dragging off before release does NOT click. Pure logic, no GL.
 */
public class ClickStateTest {

    @Test
    public void clickFiresOnReleaseWhileHovered() {
        ClickState st = new ClickState();
        assertFalse("hover, no button — no click", st.update(true, false));
        assertFalse("press edge — no click yet", st.update(true, true));
        assertTrue("holding while pressed", st.holding());
        assertTrue("release while hovered — CLICK", st.update(true, false));
        assertFalse("no longer holding", st.holding());
    }

    @Test
    public void draggingOffBeforeReleaseDoesNotClick() {
        ClickState st = new ClickState();
        st.update(true, true);                 // press on the widget
        assertFalse("moved off while still pressed", st.update(false, true));
        assertFalse("release OFF the widget — NO click", st.update(false, false));
    }

    @Test
    public void secondaryClickFiresOnReleaseWhileHovered() {
        ClickState st = new ClickState();
        assertFalse("hover, no secondary button — no click", st.updateSecondary(true, false));
        assertFalse("secondary press edge — no click yet", st.updateSecondary(true, true));
        assertTrue("secondary release while hovered — CLICK", st.updateSecondary(true, false));
    }

    @Test
    public void secondaryDraggingOffBeforeReleaseDoesNotClick() {
        ClickState st = new ClickState();
        st.updateSecondary(true, true);                  // secondary press on the widget
        assertFalse("moved off while pressed", st.updateSecondary(false, true));
        assertFalse("release OFF the widget — NO click", st.updateSecondary(false, false));
    }

    @Test
    public void primaryAndSecondaryEdgesAreIndependent() {
        ClickState st = new ClickState();
        // A primary press+hold must not consume or block a secondary click, and vice versa.
        st.update(true, true);                           // primary down
        assertFalse(st.updateSecondary(true, true));     // secondary down (no click yet)
        assertTrue("secondary release fires independent of held primary",
                st.updateSecondary(true, false));
        assertTrue("primary release still fires its own click",
                st.update(true, false));
    }

    @Test
    public void hoverAlphaEasesTowardTarget() {
        ClickState st = new ClickState();
        st.update(true, false);                // hovered → target 1
        float before = st.hoverAlpha();
        st.tick(0.06f);
        assertTrue("hover alpha rises toward 1", st.hoverAlpha() > before);
        // settle
        for (int i = 0; i < 20; i++) {
            st.tick(0.06f);
        }
        assertTrue("hover alpha reaches ~1", st.hoverAlpha() > 0.99f);
        assertFalse("settled — not animating", st.animating());
    }
}
