package net.marcloud.mcp.core.kd;

/**
 * Maps the small set of {@code jvmtiError} codes the C6 bridge can return into
 * readable names, and throws {@link DebuggerException} on any non-zero code. The
 * native side returns the raw {@code jvmtiError} as an int; {@code 0} is
 * {@code JVMTI_ERROR_NONE} (success). Only the codes these debugger ops can
 * actually produce are named; anything else is reported by number.
 */
public final class JvmtiError {

    private JvmtiError() {
    }

    /** Throw {@link DebuggerException} if {@code code} is a non-zero jvmtiError. */
    public static void check(int code) {
        if (code != 0) {
            throw new DebuggerException("JVMTI error " + code + " (" + name(code) + ")");
        }
    }

    /** Readable name for the jvmtiError codes reachable from the C6 surface. */
    public static String name(int code) {
        return switch (code) {
            case 0 -> "JVMTI_ERROR_NONE";
            case 13 -> "JVMTI_ERROR_THREAD_NOT_SUSPENDED";
            case 14 -> "JVMTI_ERROR_THREAD_SUSPENDED";
            case 15 -> "JVMTI_ERROR_THREAD_NOT_ALIVE";
            case 24 -> "JVMTI_ERROR_INVALID_SLOT";
            case 28 -> "JVMTI_ERROR_DUPLICATE";
            case 31 -> "JVMTI_ERROR_INTERRUPT";
            case 32 -> "JVMTI_ERROR_INVALID_CLASS";
            case 34 -> "JVMTI_ERROR_INVALID_METHODID";
            case 35 -> "JVMTI_ERROR_INVALID_LOCATION";
            case 36 -> "JVMTI_ERROR_INVALID_FIELDID";
            case 42 -> "JVMTI_ERROR_NO_MORE_FRAMES";
            case 43 -> "JVMTI_ERROR_OPAQUE_FRAME";
            case 51 -> "JVMTI_ERROR_TYPE_MISMATCH";
            case 62 -> "JVMTI_ERROR_NOT_AVAILABLE";
            case 98 -> "JVMTI_ERROR_ABSENT_INFORMATION";
            case 99 -> "JVMTI_ERROR_INTERNAL";
            default -> "code_" + code;
        };
    }
}
