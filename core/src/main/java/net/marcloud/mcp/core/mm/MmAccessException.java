package net.marcloud.mcp.core.mm;

/**
 * Signals a MmAccess operation failure: protected target, field/method not
 * found, coercion failure, Unsafe unavailable, or access-check denial.
 */
public final class MmAccessException extends RuntimeException {

    public MmAccessException(String message) {
        super(message);
    }

    public MmAccessException(String message, Throwable cause) {
        super(message, cause);
    }
}
