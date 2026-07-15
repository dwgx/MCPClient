package net.marcloud.mcp.core.flt.seam;

import net.marcloud.mcp.core.flt.seam.events.SeamKeyEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamMouseEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketOutboundEvent;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

import java.lang.instrument.Instrumentation;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.boot.AgentAccess;
import net.marcloud.mcp.core.ke.event.EventBus;

/**
 * Orchestrates the three runtime seams: Netty pipeline tap, GLFW input hooks,
 * and tick event injection. Each seam can be installed and uninstalled
 * independently. Requires Instrumentation for ByteBuddy retransforms (tick
 * injector).
 *
 * <p>This is the lifecycle manager for seams. The MCP tools layer ({@link
 * SeamTools}) wraps these methods with permission gates and input parsing.
 */
public final class SeamController {

    private final EventBus bus;
    private final GameAccess game;
    private final NettyTap nettyTap;
    private final InputHook inputHook;
    private final TickInjector tickInjector;

    public SeamController(EventBus bus, GameAccess game) {
        this.bus = bus;
        this.game = game;
        this.nettyTap = new NettyTap(game, bus);
        this.inputHook = new InputHook(game, bus);
        this.tickInjector = new TickInjector(bus);
    }

    /**
     * True if seams CAN be installed (Instrumentation present and retransform
     * supported). This checks the precondition for ByteBuddy retransforms
     * (tick injector).
     */
    public boolean canInstall() {
        Instrumentation inst = AgentAccess.instrumentation();
        return inst != null && inst.isRetransformClassesSupported();
    }

    // --- Netty Tap ---

    /**
     * Install the built-in packet observer handler on the Netty pipeline.
     * This is a @Sharable handler that publishes SeamPacketInboundEvent and
     * SeamPacketOutboundEvent to the EventBus.
     *
     * @return true if installed, false if unavailable or already installed
     */
    public boolean installNettyTap() {
        // KI-9: install BEFORE the terminal packet handler so inbound packets are
        // observed (the vanilla NetworkManager at the tail consumes them otherwise).
        return nettyTap.installBuiltinTap("mcp_packet_tap", new NettyTap.PacketTapHandler(bus));
    }

    /**
     * Remove the built-in packet observer handler from the pipeline.
     *
     * @return true if removed, false if not installed
     */
    public boolean uninstallNettyTap() {
        return nettyTap.removeHandler("mcp_packet_tap");
    }

    /**
     * Check if the built-in packet observer handler is installed.
     */
    public boolean isNettyTapInstalled() {
        return nettyTap.isHandlerInstalled("mcp_packet_tap");
    }

    /**
     * Install a custom Netty handler. The handler must be a
     * ChannelDuplexHandler. If it is @Sharable, it can be removed and
     * re-added; otherwise it can only be added once.
     *
     * @param name handler name in the pipeline
     * @param handler the handler instance
     * @return true if installed, false on failure
     */
    public boolean installCustomNettyHandler(String name, io.netty.channel.ChannelDuplexHandler handler) {
        return nettyTap.installHandler(name, handler);
    }

    /**
     * Remove a custom Netty handler by name.
     */
    public boolean removeCustomNettyHandler(String name) {
        return nettyTap.removeHandler(name);
    }

    // --- Input Hook ---

    /**
     * Install the GLFW key callback observer. Publishes SeamKeyEvent to the
     * EventBus. Chains before the game's original callback so input still
     * works.
     *
     * @return true if installed, false if unavailable or already installed
     */
    public boolean installKeyHook() {
        return inputHook.installKeyCallback();
    }

    /**
     * Uninstall the GLFW key callback observer and restore the original.
     */
    public boolean uninstallKeyHook() {
        return inputHook.uninstallKeyCallback();
    }

    /**
     * Check if the key callback observer is installed.
     */
    public boolean isKeyHookInstalled() {
        return inputHook.isKeyCallbackInstalled();
    }

    /**
     * Install the GLFW mouse button callback observer. Publishes
     * SeamMouseEvent to the EventBus. Chains before the game's original
     * callback.
     *
     * @return true if installed, false if unavailable or already installed
     */
    public boolean installMouseHook() {
        return inputHook.installMouseCallback();
    }

    /**
     * Uninstall the GLFW mouse button callback observer and restore the
     * original.
     */
    public boolean uninstallMouseHook() {
        return inputHook.uninstallMouseCallback();
    }

    /**
     * Check if the mouse callback observer is installed.
     */
    public boolean isMouseHookInstalled() {
        return inputHook.isMouseCallbackInstalled();
    }

    // --- Tick Injector ---

    /**
     * Install the tick event injector. Retransforms Minecraft.runTick to
     * publish TickEvent on every game tick. Requires Instrumentation.
     *
     * @return true if installed, false if unavailable or already installed
     * @throws IllegalStateException if Instrumentation is unavailable
     */
    public boolean installTickInjector() {
        if (tickInjector.isInstalled()) {
            return false;
        }
        Instrumentation inst = AgentAccess.instrumentation();
        if (inst == null) {
            throw new IllegalStateException(
                    "Instrumentation unavailable. Start with -javaagent:core-agent.jar");
        }
        tickInjector.install(inst);
        return true;
    }

    /**
     * Check if the tick injector is installed.
     */
    public boolean isTickInjectorInstalled() {
        return tickInjector.isInstalled();
    }

    /**
     * Uninstall the tick injector: resets the retransform so TickEvent stops
     * firing and Minecraft.runTick reverts. Genuinely reversible (no restart).
     *
     * @return true if it was installed and reverted, false otherwise
     */
    public boolean uninstallTickInjector() {
        return tickInjector.uninstall();
    }

    /**
     * Uninstall all seams. Cleanup for shutdown or tests — reverts every seam,
     * including the tick injector (transformer reset).
     */
    public void uninstallAll() {
        nettyTap.removeAll();   // drains handlers across every channel tracked (reconnect-safe)
        uninstallKeyHook();
        uninstallMouseHook();
        tickInjector.uninstall();
    }
}
