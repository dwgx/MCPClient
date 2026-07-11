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
        return exec.invokeAndWait(() -> {
            if (game.player() == null) {
                return false; // not in world — nothing sent
            }
            game.player().sendChatMessage(message);
            return true;
        }, defaultTimeoutMillis);
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
                return false;
            }
            nm.sendPacket(packet);
            return true;
        }, defaultTimeoutMillis);
    }
}
