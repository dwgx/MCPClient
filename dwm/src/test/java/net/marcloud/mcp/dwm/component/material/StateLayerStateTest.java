package net.marcloud.mcp.dwm.component.material;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Non-vacuous: wrong target opacities or no easing fail these.
 */
public class StateLayerStateTest {

    @Test
    public void hoverTargetIsMd3EightPercent() {
        StateLayerState s = new StateLayerState();
        s.setInteraction(StateLayerState.Interaction.HOVERED);
        s.tick(StateLayerState.TRANSITION_SECONDS);
        assertEquals(StateLayerState.HOVER_ALPHA, s.layerAlpha(), 0.0001f);
        assertEquals(0.08f, StateLayerState.HOVER_ALPHA, 0.0001f);
        assertFalse(s.animating());
    }

    @Test
    public void focusAndPressedAreTwelvePercent() {
        StateLayerState s = new StateLayerState();
        s.setInteraction(StateLayerState.Interaction.FOCUSED);
        s.tick(1f);
        assertEquals(StateLayerState.FOCUS_ALPHA, s.layerAlpha(), 0.0001f);
        assertEquals(0.12f, StateLayerState.FOCUS_ALPHA, 0.0001f);

        s.setInteraction(StateLayerState.Interaction.PRESSED);
        s.tick(1f);
        assertEquals(StateLayerState.PRESSED_ALPHA, s.layerAlpha(), 0.0001f);
        assertEquals(0.12f, StateLayerState.PRESSED_ALPHA, 0.0001f);
    }

    @Test
    public void tickEasesTowardTargetNotSnapOnSmallDt() {
        StateLayerState s = new StateLayerState();
        s.setInteraction(StateLayerState.Interaction.HOVERED);
        s.tick(StateLayerState.TRANSITION_SECONDS * 0.25f);
        float mid = s.layerAlpha();
        assertTrue("partial tick must be between 0 and hover", mid > 0f && mid < StateLayerState.HOVER_ALPHA);
        assertTrue(s.animating());
    }

    @Test
    public void noneReturnsToZero() {
        StateLayerState s = new StateLayerState();
        s.setInteraction(StateLayerState.Interaction.PRESSED);
        s.tick(1f);
        assertEquals(StateLayerState.PRESSED_ALPHA, s.layerAlpha(), 0.0001f);

        s.setInteraction(StateLayerState.Interaction.NONE);
        s.tick(1f);
        assertEquals(0f, s.layerAlpha(), 0.0001f);
        assertFalse(s.animating());
    }

    @Test
    public void elevatedBasePlusHoverBoost() {
        StateLayerState s = new StateLayerState();
        s.setBaseElevationDp(1f);
        s.setInteraction(StateLayerState.Interaction.HOVERED);
        s.tick(1f);
        // base 1 + hover boost 1 = 2
        assertEquals(2f, s.elevationDp(), 0.001f);
    }
}
