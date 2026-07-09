package org.lwjgl.util.vector;

/**
 * LWJGL2-compatible mutable 4-component float vector.
 *
 * Minecraft 1.8.9 constructs Vector4f(float,float,float,float) and
 * Vector4f(Vector4f), reads and writes the public x/y/z/w fields directly
 * (FaceBakery, RenderGlobal), and passes instances through the static
 * Matrix4f.transform helper, so that is the full surface reproduced here.
 * LWJGL3 removed this package, so this class re-supplies the LWJGL2 shape
 * with an independent pure-Java impl.
 */
public class Vector4f
{
    public float x;
    public float y;
    public float z;
    public float w;

    public Vector4f()
    {
        this.x = 0.0F;
        this.y = 0.0F;
        this.z = 0.0F;
        this.w = 0.0F;
    }

    public Vector4f(float x, float y, float z, float w)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
    }

    public Vector4f(Vector4f src)
    {
        this.x = src.x;
        this.y = src.y;
        this.z = src.z;
        this.w = src.w;
    }

    public Vector4f set(float x, float y, float z, float w)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        this.w = w;
        return this;
    }

    public float lengthSquared()
    {
        return this.x * this.x + this.y * this.y + this.z * this.z + this.w * this.w;
    }

    public float length()
    {
        return (float)Math.sqrt((double)this.lengthSquared());
    }

    public String toString()
    {
        return "Vector4f[" + this.x + ", " + this.y + ", " + this.z + ", " + this.w + "]";
    }
}