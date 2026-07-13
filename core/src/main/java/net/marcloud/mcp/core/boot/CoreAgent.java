package net.marcloud.mcp.core.boot;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.marcloud.mcp.core.se.SeProtectedObjects;

/**
 * The MCP Core Java agent.
 *
 * <p>Two jobs:
 * <ul>
 *   <li>Capture the {@link Instrumentation} instance the JVM hands a Java agent
 *       (needed for {@code redefineClasses} and, on a DCEVM-capable runtime,
 *       adding fields/methods to a live class).</li>
 *   <li>Install a startup hook that ignites MCP Core when
 *       {@code Minecraft.startGame()} finishes — so the game boots WITH the
 *       Kernel attached, no MC source change.</li>
 * </ul>
 *
 * <p>Preferred load path is {@code -javaagent:core.jar} at JVM startup: it is
 * warning-free and future-proof (JEP 451). The {@code agentmain} entry supports
 * dynamic self-attach as a fallback (needs {@code -Djdk.attach.allowAttachSelf=true}).
 *
 * <p>ByteBuddy must be reachable from the agent's classloader (put core + its
 * deps on {@code -cp}, or use a fat agent jar).
 */
public final class CoreAgent {

    private static final String MINECRAFT = "net.minecraft.client.Minecraft";

    private static volatile Instrumentation instrumentation;

    private CoreAgent() {
    }

    /** Called by the JVM when loaded via {@code -javaagent} at startup. */
    public static void premain(String args, Instrumentation inst) {
        instrumentation = inst;
        // Compat patches MUST install here (premain), before any net.minecraft.*
        // class loads — load-time patching, not ldr hot-redefine. Inert by default
        // (empty database + fail-safe signer), never fatal.
        net.marcloud.mcp.core.compat.Compat.igniteAtPremain(inst);
        installStartupHook(inst);
    }

    /**
     * Called by the JVM when dynamically attached at runtime. Compat is NOT ignited
     * here: on a self-attach the game classes are already loaded, so load-time
     * patching cannot take effect (that is what {@code ldr} redefine is for). Only
     * the {@code -javaagent} premain path arms compat.
     */
    public static void agentmain(String args, Instrumentation inst) {
        instrumentation = inst;
        installStartupHook(inst);
    }

    /**
     * Weave {@link StartupAdvice} into {@code Minecraft.startGame()} so Core
     * ignites when the game finishes initializing. Installed on class load
     * (premain, before Minecraft is loaded) or via retransform (agentmain).
     * Failure here is logged, never fatal.
     */
    private static void installStartupHook(Instrumentation inst) {
        try {
            new AgentBuilder.Default()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .disableClassFormatChanges()
                    // Never retransform a protected Core class (defense-in-depth:
                    // the target here is Minecraft, but the guard is uniform
                    // across every installer so the set is enforced in one shape).
                    .type(ElementMatchers.named(MINECRAFT).and(notProtected()))
                    .transform((builder, type, loader, module, pd) ->
                            builder.visit(Advice.to(StartupAdvice.class)
                                    .on(ElementMatchers.named("startGame"))))
                    .installOn(inst);
        } catch (Throwable t) {
            System.err.println("[MCP Core] failed to install startup hook: " + t);
        }
    }

    /**
     * The captured Instrumentation, or {@code null} if the agent was never
     * loaded. <b>Package-private on purpose</b>: trusted Core internals reach it
     * through {@link AgentAccess#instrumentation()}, not directly, so there is no
     * ungated {@code public static} global for arbitrary in-process code (an
     * {@code eval_java} snippet, an AI-authored tool) to grab and bypass the
     * privilege model with. Callers that require redefinition must handle the
     * null case and surface a clear "start with -javaagent" message.
     */
    static Instrumentation instrumentation() {
        return instrumentation;
    }

    /**
     * A Byte Buddy matcher that rejects any {@linkplain SeProtectedObjects
     * protected} Core class, so an installer can never weave into the guard's
     * own machinery. Delegates to {@link SeProtectedObjects#isProtected} so the
     * protected name set stays defined in exactly one place.
     */
    private static ElementMatcher.Junction<TypeDescription> notProtected() {
        ElementMatcher<TypeDescription> isProtected =
                target -> SeProtectedObjects.isProtected(target.getName());
        return ElementMatchers.not(isProtected);
    }
}
