package net.marcloud.mcp.core.kd;

/**
 * Thrown when a native JVMTI call returned a non-zero {@code jvmtiError} (e.g.
 * the target thread was not suspended, an invalid slot, or a capability the JBR
 * build lacks). Carries the JVMTI error name so the failure is legible to the AI.
 */
public final class DebuggerException extends RuntimeException {

    public DebuggerException(String message) {
        super(message);
    }
}
