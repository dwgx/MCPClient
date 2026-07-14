package net.marcloud.mcp.dwm.backend.imgui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import net.marcloud.mcp.dwm.backend.DrawContext.Corners;

/**
 * Non-vacuous tests for {@link ImGuiDrawContext#clampedCorners} — the per-corner radius
 * clamping behind the imgui backend's TRUE per-corner rounded rect (which now builds the
 * shape from per-corner pathArcToFast arcs instead of the old single max-radius
 * addRectFilled). The native path emission is live-only; this locks the clamping math the
 * arcs are handed, in [tl, tr, br, bl] order.
 */
public class ImGuiPerCornerTest {

    @Test
    public void distinctRadiiArePreservedNotFlattenedToMax() {
        // The whole point of per-corner: four different radii survive as four values. The
        // old code collapsed them to max(=16) for every corner — this asserts they don't.
        float[] r = ImGuiDrawContext.clampedCorners(200f, 80f, new Corners(4f, 8f, 16f, 2f));
        assertEquals(4f, r[0], 1e-5f);   // tl
        assertEquals(8f, r[1], 1e-5f);   // tr
        assertEquals(16f, r[2], 1e-5f);  // br
        assertEquals(2f, r[3], 1e-5f);   // bl
    }

    @Test
    public void eachCornerClampedToHalfShortSide() {
        // Short side = 40 -> half = 20; over-large radii clamp to 20 independently.
        float[] r = ImGuiDrawContext.clampedCorners(100f, 40f, new Corners(999f, 5f, 999f, 10f));
        assertEquals(20f, r[0], 1e-5f);
        assertEquals(5f, r[1], 1e-5f);
        assertEquals(20f, r[2], 1e-5f);
        assertEquals(10f, r[3], 1e-5f);
    }

    @Test
    public void negativeRadiusBecomesSharpZero() {
        float[] r = ImGuiDrawContext.clampedCorners(100f, 40f, new Corners(-3f, 0f, 8f, -1f));
        assertEquals(0f, r[0], 0f);
        assertEquals(0f, r[1], 0f);
        assertEquals(8f, r[2], 1e-5f);
        assertEquals(0f, r[3], 0f);
    }

    @Test
    public void orderIsTlTrBrBl() {
        // Guards the array order the pathArcToFast calls depend on.
        float[] r = ImGuiDrawContext.clampedCorners(400f, 400f, new Corners(1f, 2f, 3f, 4f));
        assertEquals(1f, r[0], 0f);
        assertEquals(2f, r[1], 0f);
        assertEquals(3f, r[2], 0f);
        assertEquals(4f, r[3], 0f);
    }
}
