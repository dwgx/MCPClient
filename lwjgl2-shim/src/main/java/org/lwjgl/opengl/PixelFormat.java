package org.lwjgl.opengl;

/**
 * LWJGL2-compatible immutable pixel format descriptor.
 *
 * <p>Re-implements the legacy {@code org.lwjgl.opengl.PixelFormat} builder that
 * Minecraft 1.8.9 uses when creating the display (e.g.
 * {@code new PixelFormat().withDepthBits(24)}). LWJGL3 dropped this class in
 * favour of GLFW window hints, so it is authored here as part of the LWJGL2
 * compatibility layer.</p>
 *
 * <p>Instances are immutable; every {@code withXxx} method returns a copy with
 * the requested attribute changed, matching LWJGL2 behaviour. The values held
 * here are consumed by the {@code Display} shim when it translates them into
 * the corresponding GLFW window/framebuffer hints.</p>
 */
public final class PixelFormat
{
    private final int bpp;
    private final int alpha;
    private final int depth;
    private final int stencil;
    private final int samples;
    private final int colorSamples;
    private final int auxBuffers;
    private final int accumBpp;
    private final int accumAlpha;
    private final boolean stereo;
    private final boolean floatingPoint;
    private final boolean floatingPointPacked;
    private final boolean sRGB;

    /**
     * Creates a pixel format with LWJGL2 defaults: 0 bpp (use desktop), 8 alpha
     * bits, 0 depth bits, 0 stencil bits and no multisampling.
     */
    public PixelFormat()
    {
        this(0, 8, 0, 0, 0, 0, 0, 0, 0, false, false, false, false);
    }

    private PixelFormat(int bpp, int alpha, int depth, int stencil, int samples,
                        int colorSamples, int auxBuffers, int accumBpp, int accumAlpha,
                        boolean stereo, boolean floatingPoint, boolean floatingPointPacked,
                        boolean sRGB)
    {
        this.bpp = bpp;
        this.alpha = alpha;
        this.depth = depth;
        this.stencil = stencil;
        this.samples = samples;
        this.colorSamples = colorSamples;
        this.auxBuffers = auxBuffers;
        this.accumBpp = accumBpp;
        this.accumAlpha = accumAlpha;
        this.stereo = stereo;
        this.floatingPoint = floatingPoint;
        this.floatingPointPacked = floatingPointPacked;
        this.sRGB = sRGB;
    }

    public int getBitsPerPixel()
    {
        return this.bpp;
    }

    public int getAlphaBits()
    {
        return this.alpha;
    }

    public int getDepthBits()
    {
        return this.depth;
    }

    public int getStencilBits()
    {
        return this.stencil;
    }

    public int getSamples()
    {
        return this.samples;
    }

    public int getColorSamples()
    {
        return this.colorSamples;
    }

    public int getAuxBuffers()
    {
        return this.auxBuffers;
    }

    public int getAccumulationBitsPerPixel()
    {
        return this.accumBpp;
    }

    public int getAccumulationAlpha()
    {
        return this.accumAlpha;
    }

    public boolean isStereo()
    {
        return this.stereo;
    }

    public boolean isFloatingPoint()
    {
        return this.floatingPoint;
    }

    public boolean isFloatingPointPacked()
    {
        return this.floatingPointPacked;
    }

    public boolean isSRGB()
    {
        return this.sRGB;
    }

    public PixelFormat withBitsPerPixel(int bpp)
    {
        return new PixelFormat(bpp, this.alpha, this.depth, this.stencil, this.samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withAlphaBits(int alpha)
    {
        return new PixelFormat(this.bpp, alpha, this.depth, this.stencil, this.samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withDepthBits(int depth)
    {
        return new PixelFormat(this.bpp, this.alpha, depth, this.stencil, this.samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withStencilBits(int stencil)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, stencil, this.samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withSamples(int samples)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, this.stencil, samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withColorSamples(int colorSamples)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, this.stencil, this.samples,
            colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withAuxBuffers(int auxBuffers)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, this.stencil, this.samples,
            this.colorSamples, auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withAccumulationBitsPerPixel(int accumBpp)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, this.stencil, this.samples,
            this.colorSamples, this.auxBuffers, accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withAccumulationAlpha(int accumAlpha)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, this.stencil, this.samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withStereo(boolean stereo)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, this.stencil, this.samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            stereo, this.floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withFloatingPoint(boolean floatingPoint)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, this.stencil, this.samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, floatingPoint, this.floatingPointPacked, this.sRGB);
    }

    public PixelFormat withFloatingPointPacked(boolean floatingPointPacked)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, this.stencil, this.samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, floatingPointPacked, this.sRGB);
    }

    public PixelFormat withSRGB(boolean sRGB)
    {
        return new PixelFormat(this.bpp, this.alpha, this.depth, this.stencil, this.samples,
            this.colorSamples, this.auxBuffers, this.accumBpp, this.accumAlpha,
            this.stereo, this.floatingPoint, this.floatingPointPacked, sRGB);
    }

    public String toString()
    {
        return "PixelFormat[bpp=" + this.bpp + ", alpha=" + this.alpha
            + ", depth=" + this.depth + ", stencil=" + this.stencil
            + ", samples=" + this.samples + "]";
    }
}