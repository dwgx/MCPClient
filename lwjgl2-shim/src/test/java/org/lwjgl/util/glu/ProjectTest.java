package org.lwjgl.util.glu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Headless regression tests for {@link Project#buildPerspective}, the pure-math
 * core of {@code Project.gluPerspective} that MC calls from EntityRenderer /
 * GuiMainMenu / GuiEnchantment (8 sites). The public gluPerspective feeds this
 * exact matrix into {@code GL11.glMultMatrixf}, so pinning the matrix pins what
 * MC uploads. The GL upload itself is RUNTIME_ONLY (needs a context).
 *
 * <p>Expected values come from the canonical GLU gluPerspective definition:
 * with {@code f = cot(fovy/2)} the column-major matrix is
 * m0 = f/aspect, m5 = f, m10 = (zFar+zNear)/(zNear-zFar),
 * m11 = -1, m14 = (2*zFar*zNear)/(zNear-zFar), all others 0.</p>
 */
public class ProjectTest {

    private static final float EPS = 1e-5f;

    /** Reference GLU perspective matrix computed independently in double precision. */
    private static float[] reference(double fovy, double aspect, double zNear, double zFar) {
        double f = 1.0 / Math.tan(Math.toRadians(fovy) / 2.0);
        float[] m = new float[16];
        m[0] = (float) (f / aspect);
        m[5] = (float) f;
        m[10] = (float) ((zFar + zNear) / (zNear - zFar));
        m[11] = -1.0f;
        m[14] = (float) ((2.0 * zFar * zNear) / (zNear - zFar));
        return m;
    }

    /** fovy=90, aspect=1 gives f=cot(45)=1: a clean hand-checkable case. */
    @Test
    public void ninetyDegreeSquareAspect() {
        float[] m = new float[16];
        assertTrue(Project.buildPerspective(m, 90.0f, 1.0f, 0.1f, 100.0f));

        assertEquals(1.0f, m[0], EPS);   // f/aspect = 1/1
        assertEquals(1.0f, m[5], EPS);   // f = 1
        assertEquals(-1.0f, m[11], 0.0f);
        // m10 = (100+0.1)/(0.1-100), m14 = (2*100*0.1)/(0.1-100)
        assertEquals((100.1f) / (0.1f - 100.0f), m[10], EPS);
        assertEquals((2.0f * 100.0f * 0.1f) / (0.1f - 100.0f), m[14], EPS);
    }

    /** MC's typical projection (fovy~70, 16:9, near 0.05, far 1000) matches the GLU formula. */
    @Test
    public void mcLikePerspectiveMatchesReference() {
        float fovy = 70.0f, aspect = 16.0f / 9.0f, zNear = 0.05f, zFar = 1000.0f;
        float[] m = new float[16];
        assertTrue(Project.buildPerspective(m, fovy, aspect, zNear, zFar));

        float[] ref = reference(fovy, aspect, zNear, zFar);
        for (int i = 0; i < 16; i++) {
            assertEquals("element " + i, ref[i], m[i], 1e-4f);
        }
    }

    /** Every entry except the five perspective terms must be exactly zero. */
    @Test
    public void nonPerspectiveEntriesAreZero() {
        float[] m = new float[16];
        // pre-dirty the buffer to prove buildPerspective zeroes it
        for (int i = 0; i < 16; i++) {
            m[i] = 7.0f;
        }
        assertTrue(Project.buildPerspective(m, 45.0f, 1.5f, 1.0f, 50.0f));

        for (int i = 0; i < 16; i++) {
            if (i == 0 || i == 5 || i == 10 || i == 11 || i == 14) {
                continue;
            }
            assertEquals("element " + i + " should be zero", 0.0f, m[i], 0.0f);
        }
    }

    /** m[15] must be 0 (perspective, not affine) — a classic porting-bug check. */
    @Test
    public void bottomRightIsZeroNotOne() {
        float[] m = new float[16];
        assertTrue(Project.buildPerspective(m, 60.0f, 1.0f, 1.0f, 10.0f));
        assertEquals(0.0f, m[15], 0.0f);
    }

    /** Degenerate zNear==zFar (zero depth range) must no-op and leave the buffer untouched. */
    @Test
    public void zeroDepthRangeIsNoOp() {
        float[] m = new float[16];
        java.util.Arrays.fill(m, 3.0f);
        assertFalse(Project.buildPerspective(m, 70.0f, 1.0f, 5.0f, 5.0f));
        for (int i = 0; i < 16; i++) {
            assertEquals("buffer must be untouched on no-op", 3.0f, m[i], 0.0f);
        }
    }

    /** Degenerate aspect==0 must no-op (avoids divide-by-zero in f/aspect). */
    @Test
    public void zeroAspectIsNoOp() {
        float[] m = new float[16];
        assertFalse(Project.buildPerspective(m, 70.0f, 0.0f, 0.1f, 100.0f));
    }

    /** fovy=180 gives sin(90*pi/180)... actually sin(radians)=sin(pi/2)!=0; use fovy=0 for sine==0. */
    @Test
    public void zeroFovyIsNoOp() {
        float[] m = new float[16];
        assertFalse(Project.buildPerspective(m, 0.0f, 1.0f, 0.1f, 100.0f));
    }
}
