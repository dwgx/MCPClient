package org.lwjgl;

/**
 * LWJGL2-compatible checked exception.
 *
 * <p>Re-implements the legacy {@code org.lwjgl.LWJGLException} that Minecraft
 * 1.8.9 declares in {@code throws} clauses around display creation and input
 * initialisation. LWJGL3 removed this type, so it is authored here as part of
 * the LWJGL2 compatibility layer.</p>
 */
public class LWJGLException extends Exception
{
    private static final long serialVersionUID = 1L;

    public LWJGLException()
    {
        super();
    }

    public LWJGLException(String message)
    {
        super(message);
    }

    public LWJGLException(String message, Throwable cause)
    {
        super(message, cause);
    }

    public LWJGLException(Throwable cause)
    {
        super(cause);
    }
}