package net.marcloud.mcp.core.deepaccess;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Wrapper around {@code jdk.internal.misc.Unsafe} (primary) or {@code sun.misc.Unsafe}
 * (fallback), obtained reflectively. Used ONLY for writing final and static-final
 * fields that {@code MethodHandles} refuses. VarHandle handles all other field ops.
 *
 * <p><b>Honest JDK-25 limit:</b> Unsafe writes the STORAGE of a final field but
 * cannot un-inline compile-time constants that javac/JIT already folded (JLS
 * 13.4.9/15.29). Static final primitives/Strings with constant initializers remain
 * inlined at call sites. Only non-constant finals (final Object, computed finals)
 * mutate observably.
 *
 * <p>Method resolution is defensive (putReference OR putObject) to survive JDK
 * internal-API renames. Degrades gracefully if Unsafe is removed: {@link #available()}
 * returns false and put* throw {@link DeepAccessException}.
 */
final class UnsafeAccess {

    private final Object unsafe;
    private final Method objectFieldOffsetMethod;
    private final Method staticFieldBaseMethod;
    private final Method staticFieldOffsetMethod;
    // primitive putters
    private final Method putIntMethod;
    private final Method putLongMethod;
    private final Method putBooleanMethod;
    private final Method putByteMethod;
    private final Method putShortMethod;
    private final Method putCharMethod;
    private final Method putFloatMethod;
    private final Method putDoubleMethod;
    // reference putter: putReference (modern) OR putObject (legacy)
    private final Method putReferenceMethod;

    UnsafeAccess() {
        Object u = null;
        Method objectFieldOffset = null;
        Method staticFieldBase = null;
        Method staticFieldOffset = null;
        Method putInt = null, putLong = null, putBoolean = null, putByte = null;
        Method putShort = null, putChar = null, putFloat = null, putDouble = null;
        Method putReference = null;

        try {
            // Primary: jdk.internal.misc.Unsafe (JDK 9+, needs --add-opens java.base/jdk.internal.misc)
            Class<?> c = Class.forName("jdk.internal.misc.Unsafe");
            Field f = c.getDeclaredField("theUnsafe");
            f.setAccessible(true);
            u = f.get(null);

            objectFieldOffset = c.getMethod("objectFieldOffset", Field.class);
            staticFieldBase = c.getMethod("staticFieldBase", Field.class);
            staticFieldOffset = c.getMethod("staticFieldOffset", Field.class);

            putInt = c.getMethod("putInt", Object.class, long.class, int.class);
            putLong = c.getMethod("putLong", Object.class, long.class, long.class);
            putBoolean = c.getMethod("putBoolean", Object.class, long.class, boolean.class);
            putByte = c.getMethod("putByte", Object.class, long.class, byte.class);
            putShort = c.getMethod("putShort", Object.class, long.class, short.class);
            putChar = c.getMethod("putChar", Object.class, long.class, char.class);
            putFloat = c.getMethod("putFloat", Object.class, long.class, float.class);
            putDouble = c.getMethod("putDouble", Object.class, long.class, double.class);

            // Try putReference first (modern), fallback to putObject (legacy)
            try {
                putReference = c.getMethod("putReference", Object.class, long.class, Object.class);
            } catch (NoSuchMethodException e) {
                putReference = c.getMethod("putObject", Object.class, long.class, Object.class);
            }

        } catch (Exception primaryFail) {
            // Fallback: sun.misc.Unsafe (deprecated-for-removal, requires --sun-misc-unsafe-memory-access=allow)
            try {
                Class<?> c = Class.forName("sun.misc.Unsafe");
                Field f = c.getDeclaredField("theUnsafe");
                f.setAccessible(true);
                u = f.get(null);

                objectFieldOffset = c.getMethod("objectFieldOffset", Field.class);
                staticFieldBase = c.getMethod("staticFieldBase", Field.class);
                staticFieldOffset = c.getMethod("staticFieldOffset", Field.class);

                putInt = c.getMethod("putInt", Object.class, long.class, int.class);
                putLong = c.getMethod("putLong", Object.class, long.class, long.class);
                putBoolean = c.getMethod("putBoolean", Object.class, long.class, boolean.class);
                putByte = c.getMethod("putByte", Object.class, long.class, byte.class);
                putShort = c.getMethod("putShort", Object.class, long.class, short.class);
                putChar = c.getMethod("putChar", Object.class, long.class, char.class);
                putFloat = c.getMethod("putFloat", Object.class, long.class, float.class);
                putDouble = c.getMethod("putDouble", Object.class, long.class, double.class);

                // putObject for sun.misc.Unsafe
                putReference = c.getMethod("putObject", Object.class, long.class, Object.class);

            } catch (Exception fallbackFail) {
                // Both failed: Unsafe unavailable
            }
        }

        this.unsafe = u;
        this.objectFieldOffsetMethod = objectFieldOffset;
        this.staticFieldBaseMethod = staticFieldBase;
        this.staticFieldOffsetMethod = staticFieldOffset;
        this.putIntMethod = putInt;
        this.putLongMethod = putLong;
        this.putBooleanMethod = putBoolean;
        this.putByteMethod = putByte;
        this.putShortMethod = putShort;
        this.putCharMethod = putChar;
        this.putFloatMethod = putFloat;
        this.putDoubleMethod = putDouble;
        this.putReferenceMethod = putReference;
    }

    boolean available() {
        return unsafe != null;
    }

    /**
     * Write {@code value} to the instance field {@code f} on target object {@code t}.
     * Handles primitives via primitive putters, references via putReference/putObject.
     */
    void putInstance(Object t, Field f, Object value) {
        if (!available()) {
            throw new DeepAccessException("Unsafe unavailable; final-field write not possible");
        }
        try {
            long offset = (long) objectFieldOffsetMethod.invoke(unsafe, f);
            Class<?> type = f.getType();

            if (type == int.class) {
                putIntMethod.invoke(unsafe, t, offset, ((Number) value).intValue());
            } else if (type == long.class) {
                putLongMethod.invoke(unsafe, t, offset, ((Number) value).longValue());
            } else if (type == boolean.class) {
                putBooleanMethod.invoke(unsafe, t, offset, (Boolean) value);
            } else if (type == byte.class) {
                putByteMethod.invoke(unsafe, t, offset, ((Number) value).byteValue());
            } else if (type == short.class) {
                putShortMethod.invoke(unsafe, t, offset, ((Number) value).shortValue());
            } else if (type == char.class) {
                putCharMethod.invoke(unsafe, t, offset, (Character) value);
            } else if (type == float.class) {
                putFloatMethod.invoke(unsafe, t, offset, ((Number) value).floatValue());
            } else if (type == double.class) {
                putDoubleMethod.invoke(unsafe, t, offset, ((Number) value).doubleValue());
            } else {
                // reference
                putReferenceMethod.invoke(unsafe, t, offset, value);
            }
        } catch (Exception e) {
            throw new DeepAccessException("Unsafe putInstance failed: " + e.getMessage(), e);
        }
    }

    /**
     * Write {@code value} to the static field {@code f}. Uses staticFieldBase +
     * staticFieldOffset to reach the storage.
     */
    void putStatic(Field f, Object value) {
        if (!available()) {
            throw new DeepAccessException("Unsafe unavailable; static-final write not possible");
        }
        try {
            Object base = staticFieldBaseMethod.invoke(unsafe, f);
            long offset = (long) staticFieldOffsetMethod.invoke(unsafe, f);
            Class<?> type = f.getType();

            if (type == int.class) {
                putIntMethod.invoke(unsafe, base, offset, ((Number) value).intValue());
            } else if (type == long.class) {
                putLongMethod.invoke(unsafe, base, offset, ((Number) value).longValue());
            } else if (type == boolean.class) {
                putBooleanMethod.invoke(unsafe, base, offset, (Boolean) value);
            } else if (type == byte.class) {
                putByteMethod.invoke(unsafe, base, offset, ((Number) value).byteValue());
            } else if (type == short.class) {
                putShortMethod.invoke(unsafe, base, offset, ((Number) value).shortValue());
            } else if (type == char.class) {
                putCharMethod.invoke(unsafe, base, offset, (Character) value);
            } else if (type == float.class) {
                putFloatMethod.invoke(unsafe, base, offset, ((Number) value).floatValue());
            } else if (type == double.class) {
                putDoubleMethod.invoke(unsafe, base, offset, ((Number) value).doubleValue());
            } else {
                // reference
                putReferenceMethod.invoke(unsafe, base, offset, value);
            }
        } catch (Exception e) {
            throw new DeepAccessException("Unsafe putStatic failed: " + e.getMessage(), e);
        }
    }
}
