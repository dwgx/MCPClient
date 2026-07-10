package net.marcloud.mcp.core.hook;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.marcloud.mcp.core.agent.AgentAccess;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.security.ProtectedClasses;

/**
 * Installs runtime hooks into the frozen MC networking code — WITHOUT editing
 * any MC source. Uses Byte Buddy's {@link AgentBuilder} with retransformation
 * to inline {@link NetworkAdvice} bodies into three already-loaded
 * {@code NetworkManager} methods:
 * <ul>
 *   <li>{@code channelRead0} — every inbound packet → {@link
 *       net.marcloud.mcp.core.event.events.PacketReceivedEvent}</li>
 *   <li>{@code sendPacket(Packet)} — every outbound packet → {@code PacketSentEvent}</li>
 *   <li>{@code closeChannel(IChatComponent)} — disconnect + reason → {@code
 *       DisconnectedEvent} (the "why was I kicked" seam)</li>
 * </ul>
 *
 * <p>Requires the {@link CoreAgent} Instrumentation (start with
 * {@code -javaagent:core-agent.jar}). Advice is inlined (not delegated), so it
 * only touches {@link HookBridge}'s public statics. Failures are surfaced, never
 * silently ignored, but installation is idempotent-safe.
 */
public final class HookManager implements HookSource {

    private static final String NETWORK_MANAGER = "net.minecraft.network.NetworkManager";

    private final EventBus bus;
    private volatile boolean installed;

    public HookManager(EventBus bus) {
        this.bus = bus;
    }

    /** The three fixed NetworkManager hooks, for the aggregate list_hooks tool. */
    @Override
    public java.util.List<HookInfo> hooks() {
        return java.util.List.of(
                new HookInfo(NETWORK_MANAGER, "channelRead0",
                        NetworkAdvice.ChannelRead0.class.getName(),
                        "bytebuddy-advice-retransform", installed),
                new HookInfo(NETWORK_MANAGER, "sendPacket",
                        NetworkAdvice.SendPacket.class.getName(),
                        "bytebuddy-advice-retransform", installed),
                new HookInfo(NETWORK_MANAGER, "closeChannel",
                        NetworkAdvice.CloseChannel.class.getName(),
                        "bytebuddy-advice-retransform", installed));
    }

    @Override
    public String sourceName() {
        return "HookManager";
    }

    /** True if hooks can be installed (Instrumentation present). */
    public boolean canInstall() {
        Instrumentation inst = AgentAccess.instrumentation();
        return inst != null && inst.isRetransformClassesSupported();
    }

    /**
     * Install the network hooks. Wires {@link HookBridge} to our bus and
     * retransforms NetworkManager. Safe to call once; repeated calls are no-ops.
     *
     * @throws IllegalStateException if Instrumentation is unavailable
     */
    public synchronized void install() {
        if (installed) {
            return;
        }
        Instrumentation inst = AgentAccess.instrumentation();
        if (inst == null || !inst.isRetransformClassesSupported()) {
            throw new IllegalStateException(
                    "Cannot install hooks: Instrumentation/retransform unavailable. "
                    + "Start with -javaagent:core-agent.jar");
        }
        HookBridge.setBus(bus);

        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .disableClassFormatChanges()
                .type(ElementMatchers.named(NETWORK_MANAGER).and(notProtected()))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder
                            .visit(Advice.to(NetworkAdvice.ChannelRead0.class)
                                    .on(ElementMatchers.named("channelRead0")))
                            .visit(Advice.to(NetworkAdvice.SendPacket.class)
                                    .on(ElementMatchers.named("sendPacket")
                                            .and(ElementMatchers.takesArguments(1))))
                            .visit(Advice.to(NetworkAdvice.CloseChannel.class)
                                    .on(ElementMatchers.named("closeChannel"))))
                .installOn(inst);

        installed = true;
    }

    public boolean isInstalled() {
        return installed;
    }

    /**
     * A Byte Buddy matcher rejecting any {@linkplain ProtectedClasses protected}
     * Core class, so the hook installer can never weave into the guard's own
     * machinery. Delegates to {@link ProtectedClasses#isProtected} so the
     * protected name set lives in one place.
     */
    private static ElementMatcher.Junction<TypeDescription> notProtected() {
        ElementMatcher<TypeDescription> isProtected =
                target -> ProtectedClasses.isProtected(target.getName());
        return ElementMatchers.not(isProtected);
    }
}
