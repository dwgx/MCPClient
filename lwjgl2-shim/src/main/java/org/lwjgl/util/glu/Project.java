package org.lwjgl.util.glu;

import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL11;

/**
 * LWJGL2-compatible GLU Project replacement.
 *
 * Minecraft 1.8.9 only uses Project.gluPerspective (8 call sites in
 * EntityRenderer/GuiMainMenu/GuiEnchantment). LWJGL3 dropped this class, so we
 * re-supply just that method, building the perspective matrix and multiplying
 * it onto the current GL matrix through the real org.lwjgl.opengl.GL11.
 * Semantics match LWJGL2's gluPerspective exactly.
 */
public class Project
{
    private static final float[] MATRIX = new float[16];

    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar)
    {
        if (!buildPerspective(MATRIX, fovy, aspect, zNear, zFar))
        {
            return;
        }

        FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(16);
        buf.put(MATRIX);
        buf.flip();
        GL11.glMultMatrixf(buf);
    }

    /**
     * Builds the LWJGL2 gluPerspective projection matrix into {@code m} (16 floats,
     * column-major). Returns {@code false} without touching {@code m} for degenerate
     * inputs (zero depth range, zero field-of-view sine, or zero aspect), mirroring
     * LWJGL2's silent no-op; otherwise fills {@code m} and returns {@code true}.
     *
     * <p>Package-visible so the perspective math can be verified headlessly, without
     * a live GL context. The public {@link #gluPerspective} feeds the result straight
     * into {@code GL11.glMultMatrixf}, so this is the same matrix MC uploads.</p>
     */
    static boolean buildPerspective(float[] m, float fovy, float aspect, float zNear, float zFar)
    {
        float radians = fovy / 2.0F * (float)Math.PI / 180.0F;
        float deltaZ = zFar - zNear;
        float sine = (float)Math.sin((double)radians);

        if (deltaZ == 0.0F || sine == 0.0F || aspect == 0.0F)
        {
            return false;
        }

        float cotangent = (float)Math.cos((double)radians) / sine;

        for (int i = 0; i < 16; ++i)
        {
            m[i] = 0.0F;
        }

        m[0] = cotangent / aspect;
        m[5] = cotangent;
        m[10] = -(zFar + zNear) / deltaZ;
        m[11] = -1.0F;
        m[14] = -2.0F * zNear * zFar / deltaZ;
        return true;
    }
}
