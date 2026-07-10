package net.marcloud.mcp.core.agent;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.matcher.ElementMatchers;

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
 *       神器 attached, no MC source change.</li>
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
        installStartupHook(inst);
    }

    /** Called by the JVM when dynamically attached at runtime. */
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
                    .type(ElementMatchers.named(MINECRAFT))
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
