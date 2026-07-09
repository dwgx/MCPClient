package org.lwjgl.opengl;

/**
 * LWJGL2-compatible immutable display mode holder.
 *
 * <p>Re-implements the subset of the legacy {@code org.lwjgl.opengl.DisplayMode}
 * API that Minecraft 1.8.9 relies upon. LWJGL3 removed this class, so it is
 * authored here as part of the LWJGL2 compatibility layer.</p>
 *
 * <p>Modes created with the two-argument constructor are considered
 * "windowed" descriptors (not fullscreen capable); modes created with the
 * four-argument constructor carry a bit-depth and refresh rate and are treated
 * as fullscreen capable, mirroring LWJGL2 semantics where fullscreen-capable
 * modes originate from the platform's enumerated mode list.</p>
 */
public final class DisplayMode
{
    private final int width;
    private final int height;
    private final int bitsPerPixel;
    private final int frequency;
    private final boolean fullscreenCapable;

    /**
     * Creates a windowed display mode with the given dimensions. Bit depth and
     * frequency default to zero and the mode is not fullscreen capable.
     *
     * @param width  the width in pixels
     * @param height the height in pixels
     */
    public DisplayMode(int width, int height)
    {
        this.width = width;
        this.height = height;
        this.bitsPerPixel = 0;
        this.frequency = 0;
        this.fullscreenCapable = false;
    }

    /**
     * Creates a fullscreen-capable display mode with the given dimensions,
     * bit depth and refresh rate.
     *
     * @param width        the width in pixels
     * @param height       the height in pixels
     * @param bitsPerPixel the color depth in bits per pixel
     * @param frequency    the refresh rate in hertz
     */
    public DisplayMode(int width, int height, int bitsPerPixel, int frequency)
    {
        this.width = width;
        this.height = height;
        this.bitsPerPixel = bitsPerPixel;
        this.frequency = frequency;
        this.fullscreenCapable = true;
    }

    public int getWidth()
    {
        return this.width;
    }

    public int getHeight()
    {
        return this.height;
    }

    public int getBitsPerPixel()
    {
        return this.bitsPerPixel;
    }

    public int getFrequency()
    {
        return this.frequency;
    }

    /**
     * @return {@code true} if this mode can be used for fullscreen display.
     */
    public boolean isFullscreenCapable()
    {
        return this.fullscreenCapable;
    }

    public boolean equals(Object obj)
    {
        if (this == obj)
        {
            return true;
        }

        if (!(obj instanceof DisplayMode))
        {
            return false;
        }

        DisplayMode other = (DisplayMode)obj;
        return this.width == other.width
            && this.height == other.height
            && this.bitsPerPixel == other.bitsPerPixel
            && this.frequency == other.frequency;
    }

    public int hashCode()
    {
        int result = this.width;
        result = 31 * result + this.height;
        result = 31 * result + this.bitsPerPixel;
        result = 31 * result + this.frequency;
        return result;
    }

    public String toString()
    {
        return this.width + " x " + this.height + " x " + this.bitsPerPixel + " @" + this.frequency + "Hz";
    }
}