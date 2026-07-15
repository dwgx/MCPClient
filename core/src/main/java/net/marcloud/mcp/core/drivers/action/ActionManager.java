package net.marcloud.mcp.core.drivers.action;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.ke.KeGameDispatcher;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;

/**
 * Control surface: actions the AI can perform on the game. Every action that
 * touches game state or the network is marshalled onto the game thread via
 * {@link KeGameDispatcher}, because MC's networking and world state are not
 * thread-safe.
 *
 * <p>Includes the phase-3 capability: sending an ARBITRARY, caller-constructed
 * {@link Packet} straight down the pipeline — the raw protocol-experiment tool.
 */
public final class ActionManager {

    private final GameAccess game;
    private final KeGameDispatcher exec;
    private final long defaultTimeoutMillis;

    public ActionManager(GameAccess game, KeGameDispatcher exec) {
        this(game, exec, 5000L);
    }

    public ActionManager(GameAccess game, KeGameDispatcher exec, long defaultTimeoutMillis) {
        this.game = game;
        this.exec = exec;
        this.defaultTimeoutMillis = defaultTimeoutMillis;
    }

    /**
     * Send a chat message (or run a command if it starts with '/'), exactly as
     * pressing enter in chat would. Runs on the game thread.
     */
    public boolean sendChat(String message)
            throws InterruptedException, ExecutionException, TimeoutException {
        // PHASE E.1: give board chips a PRE veto over AI-originated chat. Board absent
        // ⇒ publishChatSend returns a non-cancelled result ⇒ behavior unchanged. A chip
        // that vetoes stops the send; the reason is surfaced to the caller. Published on
        // the game thread below (Trace.publish is synchronous) BEFORE the real send.
        return exec.invokeAndWait(() -> {
            if (game.player() == null) {
                return false; // not in world — nothing sent
            }
            net.marcloud.mcp.core.link.BoardTraceLink.ChatSendResult veto =
                    net.marcloud.mcp.core.link.BoardTraceLink.shared().publishChatSend(message);
            if (veto.cancelled()) {
                throw new ChatVetoedException(veto.reason());
            }
            game.player().sendChatMessage(message);
            return true;
        }, defaultTimeoutMillis);
    }

    /** Thrown when a board chip vetoes an outgoing chat (PHASE E.1). Carries the veto reason. */
    public static final class ChatVetoedException extends RuntimeException {
        public ChatVetoedException(String reason) {
            super(reason == null ? "vetoed" : reason);
        }
    }

    /**
     * Phase-3: send an arbitrary protocol packet down the current connection.
     * The caller constructs the {@link Packet}; this just dispatches it on the
     * game thread through the live {@link NetworkManager}. No filtering — this is
     * the raw experiment primitive.
     *
     * @return true if dispatched, false if not connected
     */
    public boolean sendRawPacket(Packet<?> packet)
            throws InterruptedException, ExecutionException, TimeoutException {
        return exec.invokeAndWait(() -> {
            NetworkManager nm = game.networkManager();
            if (nm == null || !nm.isChannelOpen()) {
                return false; // not connected — nothing sent, no veto published
            }
            // Give board chips a PRE veto over AI-originated packet sends (mirrors
            // sendChat's ChatSendSignal). Published AFTER the connection check so a
            // not-connected send never spuriously fires a veto. Board absent ⇒
            // publishPacketSend returns a non-cancelled result ⇒ behavior unchanged.
            String packetClass = packet == null ? "null" : packet.getClass().getName();
            net.marcloud.mcp.core.link.BoardTraceLink.PacketSendResult veto =
                    net.marcloud.mcp.core.link.BoardTraceLink.shared().publishPacketSend(packetClass);
            if (veto.cancelled()) {
                throw new PacketVetoedException(veto.reason());
            }
            nm.sendPacket(packet);
            return true;
        }, defaultTimeoutMillis);
    }

    /** Thrown when a board chip vetoes an outgoing packet. Carries the veto reason. */
    public static final class PacketVetoedException extends RuntimeException {
        public PacketVetoedException(String reason) {
            super(reason == null ? "vetoed" : reason);
        }
    }
}
