package net.marcloud.mcp.core.flt.seam;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketOutboundEvent;
import net.minecraft.network.NetworkManager;

/**
 * Netty ChannelPipeline MITM seam. Installs a {@link ChannelDuplexHandler} on
 * the live game channel to observe inbound and outbound packets. Wire bytes
 * are frozen: handlers may inspect but must never mutate on-wire messages.
 *
 * <p>Handlers are installed at the pipeline tail. A {@link ChannelHandler.Sharable}
 * handler can be added/removed/re-added; a non-Sharable handler can only be
 * added once.
 */
public final class NettyTap {

    private final GameAccess game;
    private final EventBus bus;
    private volatile Channel trackedChannel;
    /** Handler name -> every channel it was installed on, including stale channels. */
    private final Map<String, Set<Channel>> installedOn = new ConcurrentHashMap<>();

    public NettyTap(GameAccess game, EventBus bus) {
        this.game = game;
        this.bus = bus;
    }

    /**
     * Acquire the live Netty channel from the game's NetworkManager. Uses
     * reflection to access the private {@code channel} field.
     *
     * @return the channel, or null if not connected or Minecraft not running
     */
    public Channel acquireChannel() {
        try {
            NetworkManager nm = game.networkManager();
            if (nm == null) {
                return null;
            }
            Field f = NetworkManager.class.getDeclaredField("channel");
            f.setAccessible(true);
            Channel ch = (Channel) f.get(nm);
            trackedChannel = ch;
            return ch;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            System.err.println("[NettyTap] failed to acquire channel: " + e);
            return null;
        } catch (NullPointerException e) {
            // Minecraft not running (headless test).
            return null;
        }
    }

    /**
     * Install a handler at the pipeline tail. If the handler is non-Sharable
     * and already in the pipeline, this will fail. Sharable handlers can be
     * re-added after removal.
     *
     * @param name handler name in the pipeline
     * @param handler the handler instance
     * @return true if installed, false on failure
     */
    public boolean installHandler(String name, ChannelDuplexHandler handler) {
        Channel ch = acquireChannel();
        if (ch == null) {
            return false;
        }
        return installHandler(ch, name, handler);
    }

    boolean installHandler(Channel ch, String name, ChannelDuplexHandler handler) {
        try {
            ChannelPipeline p = ch.pipeline();
            if (p.get(name) != null) {
                // Already installed with this name.
                return false;
            }
            p.addLast(name, handler);
            installedOn.computeIfAbsent(name, ignored -> ConcurrentHashMap.newKeySet()).add(ch);
            return true;
        } catch (Exception e) {
            System.err.println("[NettyTap] failed to install handler '" + name + "': " + e);
            return false;
        }
    }

    /**
     * Install the built-in packet observer <b>before</b> the terminal packet
     * handler (KI-9 fix). The vanilla {@code NetworkManager} is a
     * {@link io.netty.channel.SimpleChannelInboundHandler} sitting at the pipeline
     * tail; it consumes inbound packets without re-firing them, so a handler added
     * with {@code addLast} (behind it) never sees inbound. Installing before the
     * terminal makes this tap the last inbound handler that runs, so it observes
     * every decoded packet exactly once and the terminal still consumes it.
     *
     * <p>Outbound is unaffected (writes start at the tail and flow toward the head,
     * so the tap is still traversed exactly once). Removal is position-independent
     * ({@code pipeline.remove(name)}), so {@link #removeHandler}/{@link #removeAll}
     * work unchanged.
     *
     * @return true if installed, false if unavailable or already installed
     */
    public boolean installBuiltinTap(String name, ChannelDuplexHandler handler) {
        Channel ch = acquireChannel();
        if (ch == null) {
            return false;
        }
        // The terminal handler IS the NetworkManager instance (added to the pipeline
        // under some name — "packet_handler" in vanilla). Pass it so we can resolve
        // its actual name by identity, immune to a rename.
        ChannelHandler terminal = null;
        try {
            terminal = game.networkManager();
        } catch (Throwable ignored) {
            // No NetworkManager (headless / not connected) — resolveTerminalName falls back.
        }
        return installBefore(ch, name, handler, terminal);
    }

    boolean installBefore(Channel ch, String name, ChannelDuplexHandler handler,
                          ChannelHandler terminalInstance) {
        try {
            ChannelPipeline p = ch.pipeline();
            if (p.get(name) != null) {
                return false;
            }
            String terminalName = resolveTerminalName(p, terminalInstance);
            if (terminalName != null) {
                p.addBefore(terminalName, name, handler);
            } else {
                // No terminal consumer found: fall back to tail. Inbound may not be
                // observed, but this never breaks the pipeline (fail-safe).
                System.err.println("[NettyTap] no terminal handler found; "
                        + "installing '" + name + "' at tail (inbound may not fire)");
                p.addLast(name, handler);
            }
            installedOn.computeIfAbsent(name, ignored -> ConcurrentHashMap.newKeySet()).add(ch);
            return true;
        } catch (Exception e) {
            System.err.println("[NettyTap] failed to install handler '" + name
                    + "' before terminal: " + e);
            return false;
        }
    }

    /**
     * Resolve the pipeline name of the terminal inbound consumer, in order of
     * robustness: (1) by identity if {@code terminalInstance} is in the pipeline
     * (rename-immune); (2) the conventional vanilla name {@code "packet_handler"};
     * (3) the last {@link io.netty.channel.SimpleChannelInboundHandler} in the
     * pipeline. Returns null if none found (caller falls back to addLast).
     */
    private static String resolveTerminalName(ChannelPipeline p, ChannelHandler terminalInstance) {
        if (terminalInstance != null) {
            try {
                ChannelHandlerContext ctx = p.context(terminalInstance);
                if (ctx != null) {
                    return ctx.name();
                }
            } catch (Exception ignored) {
                // fall through
            }
        }
        try {
            if (p.get("packet_handler") != null) {
                return "packet_handler";
            }
        } catch (Exception ignored) {
            // fall through
        }
        String last = null;
        try {
            for (Map.Entry<String, ChannelHandler> e : p) {
                if (e.getValue() instanceof io.netty.channel.SimpleChannelInboundHandler) {
                    last = e.getKey();
                }
            }
        } catch (Exception ignored) {
            // fall through
        }
        return last;
    }

    /**
     * Remove a handler from the pipeline by name.
     *
     * @param name handler name
     * @return true if removed, false if not found or on failure
     */
    public boolean removeHandler(String name) {
        Set<Channel> channels = installedOn.remove(name);
        if (channels == null || channels.isEmpty()) {
            Channel current = trackedChannel;
            channels = current == null ? Set.of() : Set.of(current);
        }
        if (channels.isEmpty()) {
            return false;
        }
        boolean removed = false;
        for (Channel ch : channels) {
            try {
                ChannelPipeline p = ch.pipeline();
                if (p.get(name) != null) {
                    p.remove(name);
                    removed = true;
                }
            } catch (Exception e) {
                System.err.println("[NettyTap] failed to remove handler '" + name + "': " + e);
            }
        }
        return removed;
    }

    /** Remove every handler this tap installed, across all channels it tracked. */
    public void removeAll() {
        for (String name : new ArrayList<>(installedOn.keySet())) {
            removeHandler(name);
        }
    }

    /**
     * Check if a handler is installed in the pipeline.
     *
     * @param name handler name
     * @return true if present
     */
    public boolean isHandlerInstalled(String name) {
        Channel ch = trackedChannel;
        if (ch == null) {
            return false;
        }
        try {
            return ch.pipeline().get(name) != null;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * The built-in observer handler: a @Sharable tap that publishes
     * SeamPacketInboundEvent and SeamPacketOutboundEvent to the EventBus.
     * Passes all messages through unmodified (wire bytes frozen).
     */
    @ChannelHandler.Sharable
    public static final class PacketTapHandler extends ChannelDuplexHandler {

        /**
         * Immutable metadata published for decoded, mutable message objects: the
         * packet class name plus a reference-free {@code summary} String produced
         * synchronously by the summarizer registry (PHASE P.3/P.4). The live packet
         * is never retained — only these two Strings escape the callback.
         */
        public record MessageSnapshot(String className, String summary) {
        }

        private final EventBus bus;
        private final net.marcloud.mcp.core.flt.seam.summarize.PacketSummarizerRegistry summarizers;

        public PacketTapHandler(EventBus bus) {
            this(bus, net.marcloud.mcp.core.flt.seam.summarize.PacketSummarizerRegistry.defaults());
        }

        public PacketTapHandler(EventBus bus,
                net.marcloud.mcp.core.flt.seam.summarize.PacketSummarizerRegistry summarizers) {
            this.bus = bus;
            this.summarizers = summarizers;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            try {
                bus.publish(new SeamPacketInboundEvent(frozen(msg)));
            } catch (Throwable ignored) {
                // Observation fault must not break the Netty pipeline.
            }
            super.channelRead(ctx, msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise)
                throws Exception {
            try {
                bus.publish(new SeamPacketOutboundEvent(frozen(msg)));
            } catch (Throwable ignored) {
            }
            super.write(ctx, msg, promise);
        }

        /**
         * Enforce the wire-bytes-frozen contract at the observation boundary: a
         * subscriber must not be able to mutate the live message that continues
         * down the pipeline. Byte buffers are copied into read-only snapshots;
         * decoded objects are represented only by immutable class metadata.
         */
        private Object frozen(Object msg) {
            if (msg instanceof ByteBuf b) {
                byte[] bytes = new byte[b.readableBytes()];
                b.getBytes(b.readerIndex(), bytes);
                return Unpooled.unmodifiableBuffer(Unpooled.wrappedBuffer(bytes));
            }
            String className = msg == null ? "null" : msg.getClass().getName();
            // Summarize SYNCHRONOUSLY on the live decoded packet, before it
            // continues down the pipeline. The registry never throws; only the
            // resulting String escapes — the packet reference is not retained.
            String summary = msg == null ? "" : summarizers.summarize(msg);
            return new MessageSnapshot(className, summary);
        }
    }
}
