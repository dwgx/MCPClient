package net.marcloud.mcp.core.seam;

import java.lang.reflect.Field;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.seam.events.SeamPacketOutboundEvent;
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
    /** Handler name -> the exact channel it was installed on, so a handler on a
     *  now-stale channel (after reconnect) can still be removed rather than leaked. */
    private final java.util.Map<String, Channel> installedOn = new java.util.concurrent.ConcurrentHashMap<>();

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
        try {
            ChannelPipeline p = ch.pipeline();
            if (p.get(name) != null) {
                // Already installed with this name.
                return false;
            }
            p.addLast(name, handler);
            installedOn.put(name, ch);
            return true;
        } catch (Exception e) {
            System.err.println("[NettyTap] failed to install handler '" + name + "': " + e);
            return false;
        }
    }

    /**
     * Remove a handler from the pipeline by name.
     *
     * @param name handler name
     * @return true if removed, false if not found or on failure
     */
    public boolean removeHandler(String name) {
        // Prefer the exact channel the handler was installed on (survives a
        // reconnect that moved trackedChannel), else fall back to the current one.
        Channel ch = installedOn.getOrDefault(name, trackedChannel);
        if (ch == null) {
            return false;
        }
        try {
            ChannelPipeline p = ch.pipeline();
            if (p.get(name) == null) {
                installedOn.remove(name);
                return false;
            }
            p.remove(name);
            installedOn.remove(name);
            return true;
        } catch (Exception e) {
            System.err.println("[NettyTap] failed to remove handler '" + name + "': " + e);
            return false;
        }
    }

    /** Remove every handler this tap installed, across all channels it tracked. */
    public void removeAll() {
        for (String name : new java.util.ArrayList<>(installedOn.keySet())) {
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

        private final EventBus bus;

        public PacketTapHandler(EventBus bus) {
            this.bus = bus;
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
         * subscriber must not be able to mutate the live buffer that continues
         * down the pipeline. For a {@link ByteBuf} we hand out a read-only view
         * over a duplicate (independent reader index, shared-but-unwritable
         * content); other message types (decoded Packets) are passed as-is —
         * observers may read them but the frozen-bytes rule is specifically about
         * the on-wire buffer.
         */
        private static Object frozen(Object msg) {
            if (msg instanceof ByteBuf b) {
                return Unpooled.unmodifiableBuffer(b.duplicate());
            }
            return msg;
        }
    }
}
