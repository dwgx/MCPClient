package org.lwjgl;

/**
 * LWJGL2-compatible platform/utility helper.
 *
 * <p>Re-implements the small subset of the legacy {@code org.lwjgl.LWJGLUtil}
 * that other compatibility-layer classes may reference: the platform constants
 * and platform detection. LWJGL3 replaced this with {@code org.lwjgl.system.Platform},
 * so it is authored here as part of the LWJGL2 compatibility layer.</p>
 */
public final class LWJGLUtil
{
    public static final int PLATFORM_LINUX = 1;
    public static final int PLATFORM_MACOSX = 2;
    public static final int PLATFORM_WINDOWS = 3;

    public static final String PLATFORM_LINUX_NAME = "linux";
    public static final String PLATFORM_MACOSX_NAME = "macosx";
    public static final String PLATFORM_WINDOWS_NAME = "windows";

    /** When {@code true}, {@link #log(CharSequence)} writes to stderr. */
    public static boolean DEBUG = false;

    private static final int PLATFORM;

    static
    {
        String osName = System.getProperty("os.name", "").toLowerCase();

        if (osName.startsWith("windows"))
        {
            PLATFORM = PLATFORM_WINDOWS;
        }
        else if (osName.startsWith("mac") || osName.startsWith("darwin"))
        {
            PLATFORM = PLATFORM_MACOSX;
        }
        else
        {
            PLATFORM = PLATFORM_LINUX;
        }
    }

    private LWJGLUtil()
    {
    }

    /**
     * @return one of {@link #PLATFORM_WINDOWS}, {@link #PLATFORM_LINUX} or
     *         {@link #PLATFORM_MACOSX}.
     */
    public static int getPlatform()
    {
        return PLATFORM;
    }

    public static String getPlatformName()
    {
        switch (PLATFORM)
        {
            case PLATFORM_WINDOWS:
                return PLATFORM_WINDOWS_NAME;
            case PLATFORM_MACOSX:
                return PLATFORM_MACOSX_NAME;
            case PLATFORM_LINUX:
            default:
                return PLATFORM_LINUX_NAME;
        }
    }

    /**
     * Logs a message when {@link #DEBUG} is enabled; otherwise a no-op, matching
     * LWJGL2's quiet-by-default logging.
     *
     * @param msg the message to log
     */
    public static void log(CharSequence msg)
    {
        if (DEBUG)
        {
            System.err.println("[LWJGL] " + msg);
        }
    }
}