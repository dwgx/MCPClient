package net.marcloud.mcp.core.debug;

/**
 * Thrown when a debugger operation is attempted but the native JVMTI agent
 * ({@code core-jvmti.dll}) was not loaded — a clean domain error, never a leaked
 * {@link UnsatisfiedLinkError}. Callers (the {@code debug_*} MCP tools) translate
 * this into an {@code isError} result so the tools stay honest instead of dead:
 * they register and are callable, but report that the {@code -agentpath} launch
 * flag is missing.
 */
public final class DebuggerUnavailableException extends RuntimeException {

    public DebuggerUnavailableException(String message) {
        super(message);
    }
}
