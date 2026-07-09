package org.lwjgl.util.glu;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import org.lwjgl.opengl.GL11;

/**
 * LWJGL2-compatible GLU replacement.
 *
 * LWJGL3 dropped the GLU helper package, so this re-supplies only what
 * Minecraft 1.8.9 calls: gluPerspective (loads a perspective projection onto
 * the current GL matrix), gluErrorString (maps a GL error code to text), and
 * gluUnProject (maps window coords back to object coords). Implemented in pure
 * Java over the real org.lwjgl.opengl.GL11 supplied by LWJGL3.
 */
public class GLU
{
    private static final float[] IN = new float[4];
    private static final float[] OUT = new float[4];
    private static final float[] FINAL_MODELVIEW = new float[16];
    private static final float[] FINAL_PROJ = new float[16];
    private static final float[] TMP_MATRIX = new float[16];

    public static void gluPerspective(float fovy, float aspect, float zNear, float zFar)
    {
        float sine;
        float cotangent;
        float deltaZ;
        float radians = fovy / 2.0F * (float)Math.PI / 180.0F;

        deltaZ = zFar - zNear;
        sine = (float)Math.sin((double)radians);

        if (deltaZ == 0.0F || sine == 0.0F || aspect == 0.0F)
        {
            return;
        }

        cotangent = (float)Math.cos((double)radians) / sine;

        float[] m = TMP_MATRIX;
        setIdentity(m);
        m[0] = cotangent / aspect;
        m[5] = cotangent;
        m[10] = -(zFar + zNear) / deltaZ;
        m[11] = -1.0F;
        m[14] = -2.0F * zNear * zFar / deltaZ;
        m[15] = 0.0F;

        FloatBuffer buf = org.lwjgl.BufferUtils.createFloatBuffer(16);
        buf.put(m);
        buf.flip();
        GL11.glMultMatrixf(buf);
    }

    public static String gluErrorString(int error_code)
    {
        switch (error_code)
        {
            case GL11.GL_NO_ERROR:
                return "No error";

            case GL11.GL_INVALID_ENUM:
                return "Invalid enum (GL_INVALID_ENUM)";

            case GL11.GL_INVALID_VALUE:
                return "Invalid value (GL_INVALID_VALUE)";

            case GL11.GL_INVALID_OPERATION:
                return "Invalid operation (GL_INVALID_OPERATION)";

            case GL11.GL_STACK_OVERFLOW:
                return "Stack overflow (GL_STACK_OVERFLOW)";

            case GL11.GL_STACK_UNDERFLOW:
                return "Stack underflow (GL_STACK_UNDERFLOW)";

            case GL11.GL_OUT_OF_MEMORY:
                return "Out of memory (GL_OUT_OF_MEMORY)";

            default:
                return "Unknown error code (" + error_code + ")";
        }
    }

    public static boolean gluUnProject(float winx, float winy, float winz,
                                       FloatBuffer modelMatrix, FloatBuffer projMatrix,
                                       IntBuffer viewport, FloatBuffer obj_pos)
    {
        readBuffer(modelMatrix, FINAL_MODELVIEW);
        readBuffer(projMatrix, FINAL_PROJ);

        // finalMatrix = proj * modelview
        multMatrices(FINAL_PROJ, FINAL_MODELVIEW, TMP_MATRIX);

        if (invertMatrix(TMP_MATRIX, FINAL_PROJ) == null)
        {
            return false;
        }

        IN[0] = winx;
        IN[1] = winy;
        IN[2] = winz;
        IN[3] = 1.0F;

        // Map x and y from window coordinates
        IN[0] = (IN[0] - (float)viewport.get(viewport.position() + 0)) / (float)viewport.get(viewport.position() + 2);
        IN[1] = (IN[1] - (float)viewport.get(viewport.position() + 1)) / (float)viewport.get(viewport.position() + 3);

        // Map to range -1 to 1
        IN[0] = IN[0] * 2.0F - 1.0F;
        IN[1] = IN[1] * 2.0F - 1.0F;
        IN[2] = IN[2] * 2.0F - 1.0F;

        multMatrixVec(FINAL_PROJ, IN, OUT);

        if (OUT[3] == 0.0F)
        {
            return false;
        }

        OUT[3] = 1.0F / OUT[3];
        obj_pos.put(obj_pos.position() + 0, OUT[0] * OUT[3]);
        obj_pos.put(obj_pos.position() + 1, OUT[1] * OUT[3]);
        obj_pos.put(obj_pos.position() + 2, OUT[2] * OUT[3]);
        return true;
    }
    private static void setIdentity(float[] m)
    {
        for (int i = 0; i < 16; ++i)
        {
            m[i] = 0.0F;
        }

        m[0] = m[5] = m[10] = m[15] = 1.0F;
    }

    private static void readBuffer(FloatBuffer src, float[] dst)
    {
        int pos = src.position();

        for (int i = 0; i < 16; ++i)
        {
            dst[i] = src.get(pos + i);
        }
    }

    private static void multMatrixVec(float[] matrix, float[] in, float[] out)
    {
        for (int i = 0; i < 4; ++i)
        {
            out[i] = in[0] * matrix[i]
                   + in[1] * matrix[4 + i]
                   + in[2] * matrix[8 + i]
                   + in[3] * matrix[12 + i];
        }
    }

    private static void multMatrices(float[] a, float[] b, float[] r)
    {
        for (int i = 0; i < 4; ++i)
        {
            for (int j = 0; j < 4; ++j)
            {
                r[i * 4 + j] =
                      b[i * 4 + 0] * a[0 * 4 + j]
                    + b[i * 4 + 1] * a[1 * 4 + j]
                    + b[i * 4 + 2] * a[2 * 4 + j]
                    + b[i * 4 + 3] * a[3 * 4 + j];
            }
        }
    }

    private static float[] invertMatrix(float[] src, float[] inverse)
    {
        float[] tmp = new float[16];
        System.arraycopy(src, 0, tmp, 0, 16);
        setIdentity(inverse);

        for (int i = 0; i < 4; ++i)
        {
            int swap = i;

            for (int j = i + 1; j < 4; ++j)
            {
                if (Math.abs(tmp[j * 4 + i]) > Math.abs(tmp[i * 4 + i]))
                {
                    swap = j;
                }
            }

            if (swap != i)
            {
                for (int k = 0; k < 4; ++k)
                {
                    float t = tmp[i * 4 + k];
                    tmp[i * 4 + k] = tmp[swap * 4 + k];
                    tmp[swap * 4 + k] = t;

                    t = inverse[i * 4 + k];
                    inverse[i * 4 + k] = inverse[swap * 4 + k];
                    inverse[swap * 4 + k] = t;
                }
            }

            if (tmp[i * 4 + i] == 0.0F)
            {
                return null;
            }

            float t = tmp[i * 4 + i];

            for (int k = 0; k < 4; ++k)
            {
                tmp[i * 4 + k] /= t;
                inverse[i * 4 + k] /= t;
            }

            for (int j = 0; j < 4; ++j)
            {
                if (j != i)
                {
                    t = tmp[j * 4 + i];

                    for (int k = 0; k < 4; ++k)
                    {
                        tmp[j * 4 + k] -= tmp[i * 4 + k] * t;
                        inverse[j * 4 + k] -= inverse[i * 4 + k] * t;
                    }
                }
            }
        }

        return inverse;
    }
}
