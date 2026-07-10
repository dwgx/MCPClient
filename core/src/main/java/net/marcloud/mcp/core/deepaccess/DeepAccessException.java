package net.marcloud.mcp.core.deepaccess;

/**
 * Signals a DeepAccess operation failure: protected target, field/method not
 * found, coercion failure, Unsafe unavailable, or access-check denial.
 */
public final class DeepAccessException extends RuntimeException {

    public DeepAccessException(String message) {
        super(message);
    }

    public DeepAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
