package org.lwjgl.opengl;

/**
 * LWJGL2-compatibility shim for the {@code GLContext} entry point that Minecraft
 * 1.8.9 uses to reach {@link ContextCapabilities}. LWJGL3 removed this class; GL
 * capabilities now live behind {@link org.lwjgl.opengl.GL#getCapabilities()}.
 *
 * The capability snapshot is cached per thread, mirroring LWJGL3's own
 * thread-local capability model, and is built lazily on first access after
 * {@code GL.createCapabilities()} has run for the current context.
 */
public final class GLContext
{
    private static final ThreadLocal<ContextCapabilities> CAPABILITIES = new ThreadLocal<ContextCapabilities>();

    private GLContext()
    {
    }

    /**
     * Returns the cached {@link ContextCapabilities} for the calling thread,
     * building it from the current LWJGL3 capabilities on first use.
     */
    public static ContextCapabilities getCapabilities()
    {
        ContextCapabilities contextcapabilities = CAPABILITIES.get();

        if (contextcapabilities == null)
        {
            contextcapabilities = new ContextCapabilities();
            CAPABILITIES.set(contextcapabilities);
        }

        return contextcapabilities;
    }
}