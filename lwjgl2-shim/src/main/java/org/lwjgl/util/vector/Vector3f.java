package org.lwjgl.util.vector;

/**
 * LWJGL2-compatible mutable 3-component float vector.
 *
 * Only the API surface Minecraft 1.8.9 actually references is provided:
 * public x/y/z fields, the (), (float,float,float) and copy constructors,
 * the instance set(float,float,float)/scale(float) mutators, length(), and
 * the static add/sub/cross/dot helpers. LWJGL3 removed this package, so this
 * class re-supplies the LWJGL2 shape with an independent pure-Java math impl.
 */
public class Vector3f
{
    public float x;
    public float y;
    public float z;

    public Vector3f()
    {
        this.x = 0.0F;
        this.y = 0.0F;
        this.z = 0.0F;
    }

    public Vector3f(float x, float y, float z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public Vector3f(Vector3f src)
    {
        this.x = src.x;
        this.y = src.y;
        this.z = src.z;
    }

    public Vector3f set(float x, float y, float z)
    {
        this.x = x;
        this.y = y;
        this.z = z;
        return this;
    }

    public Vector3f scale(float scale)
    {
        this.x *= scale;
        this.y *= scale;
        this.z *= scale;
        return this;
    }

    public float lengthSquared()
    {
        return this.x * this.x + this.y * this.y + this.z * this.z;
    }

    public float length()
    {
        return (float)Math.sqrt((double)this.lengthSquared());
    }

    public static Vector3f add(Vector3f left, Vector3f right, Vector3f dest)
    {
        if (dest == null)
        {
            return new Vector3f(left.x + right.x, left.y + right.y, left.z + right.z);
        }

        dest.set(left.x + right.x, left.y + right.y, left.z + right.z);
        return dest;
    }

    public static Vector3f sub(Vector3f left, Vector3f right, Vector3f dest)
    {
        if (dest == null)
        {
            return new Vector3f(left.x - right.x, left.y - right.y, left.z - right.z);
        }

        dest.set(left.x - right.x, left.y - right.y, left.z - right.z);
        return dest;
    }

    public static Vector3f cross(Vector3f left, Vector3f right, Vector3f dest)
    {
        if (dest == null)
        {
            dest = new Vector3f();
        }

        float cx = left.y * right.z - left.z * right.y;
        float cy = left.z * right.x - left.x * right.z;
        float cz = left.x * right.y - left.y * right.x;
        dest.set(cx, cy, cz);
        return dest;
    }

    public static float dot(Vector3f left, Vector3f right)
    {
        return left.x * right.x + left.y * right.y + left.z * right.z;
    }

    public String toString()
    {
        return "Vector3f[" + this.x + ", " + this.y + ", " + this.z + "]";
    }
}