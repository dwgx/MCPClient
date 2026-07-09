package org.lwjgl.opengl;

/**
 * LWJGL2-compatible unchecked OpenGL exception.
 *
 * <p>Re-implements the legacy {@code org.lwjgl.opengl.OpenGLException} used by
 * Minecraft 1.8.9's GL error checking paths. LWJGL3 removed this type, so it is
 * authored here as part of the LWJGL2 compatibility layer.</p>
 */
public class OpenGLException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    public OpenGLException()
    {
        super();
    }

    public OpenGLException(String message)
    {
        super(message);
    }

    /**
     * Builds an exception describing a GL error code, mirroring the LWJGL2
     * constructor that formats the numeric error into a readable message.
     *
     * @param glErrorCode the raw OpenGL error code
     */
    public OpenGLException(int glErrorCode)
    {
        super(createErrorMessage(glErrorCode));
    }

    public OpenGLException(String message, Throwable cause)
    {
        super(message, cause);
    }

    private static String createErrorMessage(int glErrorCode)
    {
        String hex = Integer.toHexString(glErrorCode).toUpperCase();
        return translateGLErrorString(glErrorCode) + " (0x" + hex + ")";
    }

    private static String translateGLErrorString(int glErrorCode)
    {
        switch (glErrorCode)
        {
            case 0x0:
                return "No error";
            case 0x500:
                return "Invalid enum";
            case 0x501:
                return "Invalid value";
            case 0x502:
                return "Invalid operation";
            case 0x503:
                return "Stack overflow";
            case 0x504:
                return "Stack underflow";
            case 0x505:
                return "Out of memory";
            case 0x506:
                return "Invalid framebuffer operation";
            case 0x8031:
                return "Table too large";
            default:
                return "Unknown error";
        }
    }
}