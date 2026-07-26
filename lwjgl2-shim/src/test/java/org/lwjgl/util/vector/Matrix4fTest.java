package org.lwjgl.util.vector;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Headless regression tests for {@link Matrix4f}. MC's net.minecraft.util.Matrix4f
 * extends this and relies on the exact LWJGL2 column-major layout (mCR = column C,
 * row R) plus the static mul/transpose/invert/transform/rotate helpers
 * (FaceBakery, ModelRotation, RenderGlobal, ShaderGroup). Pure math, fully headless.
 */
public class Matrix4fTest {

    private static final float EPS = 1e-5f;

    @Test
    public void newMatrixIsIdentity() {
        Matrix4f m = new Matrix4f();
        assertEquals(1.0f, m.m00, 0.0f);
        assertEquals(1.0f, m.m11, 0.0f);
        assertEquals(1.0f, m.m22, 0.0f);
        assertEquals(1.0f, m.m33, 0.0f);
        assertEquals(0.0f, m.m01, 0.0f);
        assertEquals(0.0f, m.m30, 0.0f);
    }

    @Test
    public void mulByIdentityIsUnchanged() {
        Matrix4f a = arbitrary();
        Matrix4f id = new Matrix4f();
        Matrix4f r = Matrix4f.mul(a, id, null);
        assertMatrixEquals(a, r);
    }

    /**
     * Column-major convention check: translation lives in the m3x column.
     * mul(T, v-as-column via transform) must add the translation.
     */
    @Test
    public void transformAppliesColumnMajorTranslation() {
        Matrix4f t = new Matrix4f();
        t.m30 = 5.0f;  // x translation in column 3
        t.m31 = -2.0f; // y translation
        t.m32 = 3.0f;  // z translation
        Vector4f p = new Vector4f(1.0f, 1.0f, 1.0f, 1.0f);
        Vector4f out = Matrix4f.transform(t, p, null);
        assertEquals(6.0f, out.x, EPS);
        assertEquals(-1.0f, out.y, EPS);
        assertEquals(4.0f, out.z, EPS);
        assertEquals(1.0f, out.w, EPS);
    }

    @Test
    public void transposeSwapsOffDiagonal() {
        Matrix4f a = arbitrary();
        Matrix4f t = Matrix4f.transpose(a, null);
        assertEquals(a.m01, t.m10, 0.0f);
        assertEquals(a.m10, t.m01, 0.0f);
        assertEquals(a.m23, t.m32, 0.0f);
        assertEquals(a.m30, t.m03, 0.0f);
        // Double transpose is identity.
        Matrix4f tt = Matrix4f.transpose(t, null);
        assertMatrixEquals(a, tt);
    }

    @Test
    public void invertTimesOriginalIsIdentity() {
        Matrix4f a = arbitrary();
        Matrix4f inv = Matrix4f.invert(a, null);
        assertNotNull("arbitrary matrix should be invertible", inv);
        Matrix4f prod = Matrix4f.mul(a, inv, null);
        assertMatrixEquals(new Matrix4f(), prod, 1e-3f);
    }

    @Test
    public void invertSingularReturnsNull() {
        Matrix4f zero = new Matrix4f();
        zero.m00 = zero.m11 = zero.m22 = zero.m33 = 0.0f; // all-zero => det 0
        assertNull(Matrix4f.invert(zero, null));
    }

    @Test
    public void identityDeterminantIsOne() {
        assertEquals(1.0f, new Matrix4f().determinant(), EPS);
    }

    /** A non-trivial but well-conditioned matrix for round-trip tests. */
    private static Matrix4f arbitrary() {
        Matrix4f m = new Matrix4f();
        m.m00 = 2.0f; m.m01 = 0.0f; m.m02 = 0.0f; m.m03 = 0.0f;
        m.m10 = 1.0f; m.m11 = 3.0f; m.m12 = 0.0f; m.m13 = 0.0f;
        m.m20 = 0.0f; m.m21 = 1.0f; m.m22 = 4.0f; m.m23 = 0.0f;
        m.m30 = 5.0f; m.m31 = 6.0f; m.m32 = 7.0f; m.m33 = 1.0f;
        return m;
    }

    private static void assertMatrixEquals(Matrix4f expected, Matrix4f actual) {
        assertMatrixEquals(expected, actual, EPS);
    }

    private static void assertMatrixEquals(Matrix4f e, Matrix4f a, float eps) {
        assertEquals(e.m00, a.m00, eps); assertEquals(e.m01, a.m01, eps);
        assertEquals(e.m02, a.m02, eps); assertEquals(e.m03, a.m03, eps);
        assertEquals(e.m10, a.m10, eps); assertEquals(e.m11, a.m11, eps);
        assertEquals(e.m12, a.m12, eps); assertEquals(e.m13, a.m13, eps);
        assertEquals(e.m20, a.m20, eps); assertEquals(e.m21, a.m21, eps);
        assertEquals(e.m22, a.m22, eps); assertEquals(e.m23, a.m23, eps);
        assertEquals(e.m30, a.m30, eps); assertEquals(e.m31, a.m31, eps);
        assertEquals(e.m32, a.m32, eps); assertEquals(e.m33, a.m33, eps);
    }
}
