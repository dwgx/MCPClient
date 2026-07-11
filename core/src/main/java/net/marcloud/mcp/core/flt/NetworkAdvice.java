package net.marcloud.mcp.core.flt;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice bodies inlined into NetworkManager methods. Each captures
 * the relevant argument and forwards it to {@link HookBridge} on method entry.
 * These run on whatever thread the MC method runs on; they only observe.
 */
public final class NetworkAdvice {

    private NetworkAdvice() {
    }

    /** Inlined at the entry of {@code channelRead0(ctx, packet)} — arg index 1. */
    public static final class ChannelRead0 {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(1) Object packet) {
            HookBridge.onPacketReceived(packet);
        }
    }

    /** Inlined at the entry of {@code sendPacket(packet, ...)} — arg index 0. */
    public static final class SendPacket {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) Object packet) {
            HookBridge.onPacketSent(packet);
        }
    }

    /** Inlined at the entry of {@code closeChannel(message)} — arg index 0. */
    public static final class CloseChannel {
        @Advice.OnMethodEnter
        static void enter(@Advice.Argument(0) Object reason) {
            HookBridge.onDisconnect(reason);
        }
    }
}
