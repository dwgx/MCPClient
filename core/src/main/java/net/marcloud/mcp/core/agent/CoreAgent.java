package net.marcloud.mcp.core.agent;

import java.lang.instrument.Instrumentation;

/**
 * The MCP Core Java agent.
 *
 * <p>Its sole job is to capture the {@link Instrumentation} instance the JVM
 * hands to a Java agent, and expose it to the rest of Core. Instrumentation is
 * required for the two capabilities that a plain classloader cannot provide:
 * <ul>
 *   <li>redefining an already-loaded class's method body ({@code redefineClasses}),</li>
 *   <li>on a DCEVM-capable runtime (JetBrains Runtime + {@code
 *       -XX:+AllowEnhancedClassRedefinition}), adding fields/methods to a live class.</li>
 * </ul>
 *
 * <p>Preferred load path is {@code -javaagent:core-agent.jar} at JVM startup:
 * it is warning-free and future-proof (JEP 451). The {@code agentmain} entry
 * supports dynamic self-attach as a fallback (needs
 * {@code -Djdk.attach.allowAttachSelf=true} on JDK 9+).
 *
 * <p>This class is deliberately tiny and dependency-free so it can live in a
 * standalone agent jar with a minimal manifest.
 */
public final class CoreAgent {

    private static volatile Instrumentation instrumentation;

    private CoreAgent() {
    }

    /** Called by the JVM when loaded via {@code -javaagent} at startup. */
    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    /** Called by the JVM when dynamically attached at runtime. */
    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
    }

    /**
     * The captured Instrumentation, or {@code null} if the agent was never
     * loaded. Callers that require redefinition must handle the null case and
     * surface a clear "start with -javaagent" message.
     */
    public static Instrumentation instrumentation() {
        return instrumentation;
    }

    /** True once the agent has been loaded and Instrumentation is available. */
    public static boolean isLoaded() {
        return instrumentation != null;
    }
}
