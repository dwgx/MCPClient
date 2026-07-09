package org.lwjgl.input;

import java.nio.IntBuffer;

import org.lwjgl.LWJGLException;

/**
 * LWJGL2-compatible native cursor stub.
 *
 * <p>Re-implements the legacy {@code org.lwjgl.input.Cursor} surface referenced
 * by the LWJGL2 input API. Custom hardware/native cursors are not supported by
 * this compatibility layer, so this class intentionally carries no state and
 * reports no capabilities. It exists only so that code referencing the LWJGL2
 * {@code Cursor} type continues to compile and link.</p>
 */
public class Cursor
{
    /** Capability flag: hardware color cursors are supported. */
    public static final int CURSOR_ONE_BIT_TRANSPARENCY = 1;
    /** Capability flag: animated cursors are supported. */
    public static final int CURSOR_ANIMATION = 2;
    /** Capability flag: 8-bit alpha cursors are supported. */
    public static final int CURSOR_8_BIT_ALPHA = 4;

    /**
     * Constructs a cursor descriptor. Native cursors are unsupported by this
     * layer; the arguments are accepted for source compatibility only and no
     * native resources are allocated.
     *
     * @param width      cursor width in pixels
     * @param height     cursor height in pixels
     * @param xHotspot   hotspot x coordinate
     * @param yHotspot   hotspot y coordinate
     * @param numImages  number of animation frames
     * @param images     packed image data (ignored)
     * @param delays     per-frame delays (ignored)
     * @throws LWJGLException never thrown by this stub, declared for compatibility
     */
    public Cursor(int width, int height, int xHotspot, int yHotspot, int numImages,
                  IntBuffer images, IntBuffer delays) throws LWJGLException
    {
    }

    /**
     * @return {@code 0} — this layer advertises no native cursor capabilities.
     */
    public static int getCapabilities()
    {
        return 0;
    }

    /**
     * @return the minimum supported cursor size ({@code 0}; unsupported).
     */
    public static int getMinCursorSize()
    {
        return 0;
    }

    /**
     * @return the maximum supported cursor size ({@code 0}; unsupported).
     */
    public static int getMaxCursorSize()
    {
        return 0;
    }

    /**
     * Releases any native resources held by this cursor. No-op for this stub.
     */
    public void destroy()
    {
    }
}