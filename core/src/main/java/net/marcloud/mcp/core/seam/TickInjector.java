package net.marcloud.mcp.core.seam;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.security.ProtectedClasses;

/**
 * Installs a runtime hook into {@code Minecraft.runTick} to fire {@link
 * net.marcloud.mcp.core.event.events.TickEvent} every game tick. Uses Byte
 * Buddy's {@link AgentBuilder} with retransformation to inline {@link
 * TickAdvice} at the method entry.
 *
 * <p>Mirrors {@link net.marcloud.mcp.core.hook.HookManager}'s pattern:
 * retransform via Instrumentation, advice is inlined (not delegated), and
 * failures are surfaced. Installation is idempotent-safe.
 */
public final class TickInjector {

    private static final String MINECRAFT = "net.minecraft.client.Minecraft";

    private final EventBus bus;
    private volatile boolean installed;

    public TickInjector(EventBus bus) {
        this.bus = bus;
    }

    /**
     * Install the tick hook. Wires {@link TickBridge} to our bus and
     * retransforms Minecraft.runTick. Safe to call once; repeated calls are
     * no-ops.
     *
     * @throws IllegalStateException if Instrumentation is unavailable
     */
    public synchronized void install(Instrumentation inst) {
        if (installed) {
            return;
        }
        if (inst == null || !inst.isRetransformClassesSupported()) {
            throw new IllegalStateException(
                    "Cannot install tick injector: Instrumentation/retransform unavailable. "
                    + "Start with -javaagent:core-agent.jar");
        }
        TickBridge.setBus(bus);

        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .disableClassFormatChanges()
                .type(ElementMatchers.named(MINECRAFT).and(notProtected()))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(TickAdvice.class)
                                .on(ElementMatchers.named("runTick"))))
                .installOn(inst);

        installed = true;
    }

    public boolean isInstalled() {
        return installed;
    }

    /**
     * A Byte Buddy matcher rejecting any {@linkplain ProtectedClasses protected}
     * Core class. Minecraft is never protected, but we keep the guard for
     * uniformity with HookManager.
     */
    private static ElementMatcher.Junction<TypeDescription> notProtected() {
        ElementMatcher<TypeDescription> isProtected =
                target -> ProtectedClasses.isProtected(target.getName());
        return ElementMatchers.not(isProtected);
    }
}
