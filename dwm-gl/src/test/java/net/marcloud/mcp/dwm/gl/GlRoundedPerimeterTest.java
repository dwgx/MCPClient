package net.marcloud.mcp.dwm.gl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Non-vacuous geometry tests for {@link GlDrawContext#roundedPerimeter} — the pure vertex
 * walk behind the GL backend's per-corner rounded rect. Proves the TRUE per-corner
 * behavior that replaced the old max-radius approximation: each corner's arc stays within
 * its own radius, and a zero-radius corner collapses onto the sharp rect corner. GL quad
 * emission (glVertex) is live-only; this locks the math it feeds on.
 */
public class GlRoundedPerimeterTest {

    private static final int SEG = 6;                // GlDrawContext.CORNER_SEGMENTS
    private static final int PER_CORNER = SEG + 1;   // vertices per quarter-arc
    // Walk order is TR, BR, BL, TL (clockwise, y-down).
    private static final int TR = 0, BR = 1, BL = 2, TL = 3;

    /** Extract corner c's arc as an array of [x,y] points from the flat perimeter. */
    private static float[][] corner(float[] p, int c) {
        float[][] out = new float[PER_CORNER][2];
        int base = c * PER_CORNER * 2;
        for (int i = 0; i < PER_CORNER; i++) {
            out[i][0] = p[base + i * 2];
            out[i][1] = p[base + i * 2 + 1];
        }
        return out;
    }

    @Test
    public void perimeterHasFourArcsWorthOfVertices() {
        float[] p = GlDrawContext.roundedPerimeter(0, 0, 100, 40, 8, 8, 8, 8);
        assertEquals(4 * PER_CORNER * 2, p.length);
    }

    @Test
    public void uniformRadiusCornersAllStayWithinRadiusOfTheirCenter() {
        float x = 10, y = 20, w = 120, h = 40, r = 12;
        float[] p = GlDrawContext.roundedPerimeter(x, y, w, h, r, r, r, r);
        float[][] centers = {
                {x + w - r, y + r},     // TR
                {x + w - r, y + h - r}, // BR
                {x + r, y + h - r},     // BL
                {x + r, y + r},         // TL
        };
        for (int c = 0; c < 4; c++) {
            for (float[] v : corner(p, c)) {
                double d = Math.hypot(v[0] - centers[c][0], v[1] - centers[c][1]);
                assertEquals("corner " + c + " arc point must lie on its radius", r, d, 1e-3);
            }
        }
    }

    @Test
    public void independentRadiiEachStayOnTheirOwnCircle() {
        // The crux of TRUE per-corner: give four DIFFERENT radii and assert each arc lies
        // on ITS radius, not a single shared (max) one — this would FAIL the old code that
        // rounded all corners with max(=16).
        float x = 0, y = 0, w = 200, h = 80;
        float rTL = 4, rTR = 8, rBR = 16, rBL = 2;
        float[] p = GlDrawContext.roundedPerimeter(x, y, w, h, rTL, rTR, rBR, rBL);

        assertOnRadius(corner(p, TR), x + w - rTR, y + rTR, rTR);
        assertOnRadius(corner(p, BR), x + w - rBR, y + h - rBR, rBR);
        assertOnRadius(corner(p, BL), x + rBL, y + h - rBL, rBL);
        assertOnRadius(corner(p, TL), x + rTL, y + rTL, rTL);
    }

    private static void assertOnRadius(float[][] arc, float cx, float cy, float r) {
        for (float[] v : arc) {
            double d = Math.hypot(v[0] - cx, v[1] - cy);
            assertEquals("arc point off its own radius", r, d, 1e-3);
        }
    }

    @Test
    public void zeroRadiusCornerCollapsesToSharpRectCorner() {
        // A 0 radius on the top-left means every TL arc vertex sits exactly on the rect's
        // top-left corner (x,y) — a genuine sharp corner, impossible with the old uniform
        // max-radius path.
        float x = 5, y = 7, w = 100, h = 50;
        float[] p = GlDrawContext.roundedPerimeter(x, y, w, h, 0f, 10f, 10f, 10f);
        for (float[] v : corner(p, TL)) {
            assertEquals(x, v[0], 1e-4);
            assertEquals(y, v[1], 1e-4);
        }
        // ...while the bottom-right (r=10) does NOT collapse — it stays off the BR corner.
        float[][] br = corner(p, BR);
        boolean anyOffCorner = false;
        for (float[] v : br) {
            if (Math.hypot(v[0] - (x + w), v[1] - (y + h)) > 1e-3) {
                anyOffCorner = true;
                break;
            }
        }
        assertTrue("rounded BR corner must not collapse to the sharp corner", anyOffCorner);
    }

    @Test
    public void allVerticesLieInsideTheRectBounds() {
        // Sanity: the fan perimeter never escapes the rect (arcs curve inward from corners).
        float x = 0, y = 0, w = 60, h = 60, r = 20;
        float[] p = GlDrawContext.roundedPerimeter(x, y, w, h, r, r, r, r);
        for (int i = 0; i + 1 < p.length; i += 2) {
            assertTrue(p[i] >= x - 1e-3 && p[i] <= x + w + 1e-3);
            assertTrue(p[i + 1] >= y - 1e-3 && p[i + 1] <= y + h + 1e-3);
        }
    }
}
