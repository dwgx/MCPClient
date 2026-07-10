package net.marcloud.mcp.core.agent;

import java.lang.instrument.Instrumentation;

/**
 * The gated seam through which trusted Core internals obtain the JVM {@link
 * Instrumentation} handle.
 *
 * <p>Previously {@code CoreAgent.instrumentation()} was {@code public static} —
 * any code (including {@code eval_java} snippets and AI-authored tools) could
 * grab full Instrumentation and bypass the privilege model. That accessor is now
 * package-private to {@link CoreAgent}; the only sanctioned way across package
 * boundaries is through this class, which lives in the same {@code agent}
 * package and re-exposes the handle to the specific internal callers that need
 * it ({@code Redefiner} in {@code hotload}, {@code HookManager} in {@code hook},
 * introspection, etc.).
 *
 * <p><b>Honest boundary.</b> This is a package-visibility gate, not a
 * capability sandbox: any in-process code that can reference this class can call
 * {@code instrumentation()}. Its value is removing the trivial global grab and
 * giving one auditable choke point; {@link
 * net.marcloud.mcp.core.security.ProtectedClasses} stops the accessor from being
 * redefined out from under the guard, and the P-SECURE process is the real
 * cross-address-space wall.
 */
public final class AgentAccess {

    private AgentAccess() {
    }

    /**
     * The captured {@link Instrumentation}, or {@code null} if the agent was
     * never loaded. Callers that require it must handle the null case and
     * surface a clear "start with -javaagent" message.
     */
    public static Instrumentation instrumentation() {
        return CoreAgent.instrumentation();
    }

    /** True once the agent has been loaded and Instrumentation is available. */
    public static boolean isLoaded() {
        return CoreAgent.instrumentation() != null;
    }
}
