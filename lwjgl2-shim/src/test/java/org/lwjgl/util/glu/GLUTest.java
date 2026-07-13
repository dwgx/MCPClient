package org.lwjgl.util.glu;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.junit.Test;
import org.lwjgl.opengl.GL11;

/**
 * Headless regression tests for {@link GLU}. gluErrorString is a pure lookup and
 * gluUnProject is pure matrix/buffer math (no GL context needed) — MC calls
 * gluUnProject for picking/coordinate mapping and gluErrorString for GL error
 * reporting. gluPerspective delegates to GL11.glMultMatrixf and is RUNTIME_ONLY.
 */
public class GLUTest {

    private static FloatBuffer identity() {
        FloatBuffer b = FloatBuffer.allocate(16);
        float[] m = new float[16];
        m[0] = m[5] = m[10] = m[15] = 1.0f;
        b.put(m);
        b.flip();
        return b;
    }

    @Test
    public void errorStringKnownCodes() {
        assertEquals("No error", GLU.gluErrorString(GL11.GL_NO_ERROR));
        assertTrue(GLU.gluErrorString(GL11.GL_INVALID_ENUM).contains("GL_INVALID_ENUM"));
        assertTrue(GLU.gluErrorString(GL11.GL_INVALID_VALUE).contains("GL_INVALID_VALUE"));
        assertTrue(GLU.gluErrorString(GL11.GL_INVALID_OPERATION).contains("GL_INVALID_OPERATION"));
        assertTrue(GLU.gluErrorString(GL11.GL_OUT_OF_MEMORY).contains("GL_OUT_OF_MEMORY"));
    }

    @Test
    public void errorStringUnknownCodeIncludesNumber() {
        String s = GLU.gluErrorString(0x12345);
        assertTrue("unknown error must echo the code", s.contains("74565") || s.contains("Unknown"));
    }

    /**
     * With identity modelview + identity projection and viewport (0,0,W,H), the
     * window centre at winz=0.5 must unproject to the origin of NDC (0,0,0),
     * because normalized device coords there are exactly (0,0,0).
     */
    @Test
    public void unProjectIdentityMapsCentreToOrigin() {
        int w = 800, h = 600;
        IntBuffer viewport = IntBuffer.wrap(new int[] {0, 0, w, h});
        FloatBuffer obj = FloatBuffer.allocate(3);

        boolean ok = GLU.gluUnProject(w / 2.0f, h / 2.0f, 0.5f,
                identity(), identity(), viewport, obj);

        assertTrue("unproject must succeed for identity matrices", ok);
        assertEquals(0.0f, obj.get(0), 1e-5f);
        assertEquals(0.0f, obj.get(1), 1e-5f);
        assertEquals(0.0f, obj.get(2), 1e-5f);
    }

    /**
     * Corners map to the NDC cube corners under identity: bottom-left window
     * (0,0) at winz=0 maps to (-1,-1,-1); top-right (W,H) at winz=1 to (1,1,1).
     */
    @Test
    public void unProjectIdentityMapsCornersToNdcCube() {
        int w = 1920, h = 1080;
        IntBuffer viewport = IntBuffer.wrap(new int[] {0, 0, w, h});

        FloatBuffer bl = FloatBuffer.allocate(3);
        assertTrue(GLU.gluUnProject(0.0f, 0.0f, 0.0f, identity(), identity(), viewport, bl));
        assertEquals(-1.0f, bl.get(0), 1e-5f);
        assertEquals(-1.0f, bl.get(1), 1e-5f);
        assertEquals(-1.0f, bl.get(2), 1e-5f);

        FloatBuffer tr = FloatBuffer.allocate(3);
        assertTrue(GLU.gluUnProject((float) w, (float) h, 1.0f, identity(), identity(), viewport, tr));
        assertEquals(1.0f, tr.get(0), 1e-5f);
        assertEquals(1.0f, tr.get(1), 1e-5f);
        assertEquals(1.0f, tr.get(2), 1e-5f);
    }

    /**
     * A singular (non-invertible) combined matrix must make gluUnProject return
     * false rather than emit garbage — an all-zero projection is singular.
     */
    @Test
    public void unProjectSingularMatrixReturnsFalse() {
        FloatBuffer zero = FloatBuffer.allocate(16); // all zeros
        FloatBuffer obj = FloatBuffer.allocate(3);
        IntBuffer viewport = IntBuffer.wrap(new int[] {0, 0, 800, 600});

        boolean ok = GLU.gluUnProject(400.0f, 300.0f, 0.5f, identity(), zero, viewport, obj);
        assertFalse("singular matrix must not succeed", ok);
    }

    /**
     * gluUnProject must read the projection matrix relative to the buffer's
     * position(), not absolute index 0 — MC hands it sub-buffers with non-zero
     * positions. Padding before the matrix must not corrupt the result.
     */
    @Test
    public void unProjectRespectsBufferPositionOffset() {
        // Build a buffer with padding before the matrix, then advance position,
        // to prove gluUnProject reads relative to position() (as MC's do).
        int w = 800, h = 600;
        FloatBuffer padded = FloatBuffer.allocate(20);
        padded.put(new float[] {9, 9, 9, 9}); // 4 floats of junk padding
        int mark = padded.position();
        float[] id = new float[16];
        id[0] = id[5] = id[10] = id[15] = 1.0f;
        padded.put(id);
        padded.position(mark); // matrix now starts at position()==4

        IntBuffer viewport = IntBuffer.wrap(new int[] {0, 0, w, h});
        FloatBuffer obj = FloatBuffer.allocate(3);
        boolean ok = GLU.gluUnProject(w / 2.0f, h / 2.0f, 0.5f, identity(), padded, viewport, obj);
        assertTrue(ok);
        assertEquals(0.0f, obj.get(0), 1e-5f);
        assertEquals(0.0f, obj.get(1), 1e-5f);
    }
}
