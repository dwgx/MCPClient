package net.marcloud.mcp.core;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetHandlerPlayClient;
import net.minecraft.network.NetworkManager;

/**
 * The single façade over the {@link Minecraft} singleton. Everything in Core
 * that reads or touches the game goes through here, so there is exactly one
 * place that knows how to reach live game objects and one place to null-check
 * the many "not connected / not in world yet" states.
 *
 * <p>Read accessors are safe to call from any thread (they only fetch
 * references); anything that MUTATES game state must still be marshalled onto
 * the game thread by the caller (see {@code thread.MainThreadExecutor}).
 */
public final class GameAccess {

    /** The Minecraft client singleton (never null once the game is up). */
    public Minecraft mc() {
        return Minecraft.getMinecraft();
    }

    /** The local player, or null if not in a world. */
    public EntityPlayerSP player() {
        return mc().thePlayer;
    }

    /** The client world, or null if not in a world. */
    public WorldClient world() {
        return mc().theWorld;
    }

    /** The play-phase net handler, or null if not connected. */
    public NetHandlerPlayClient netHandler() {
        return mc().getNetHandler();
    }

    /** The active NetworkManager, or null if not connected. */
    public NetworkManager networkManager() {
        NetHandlerPlayClient h = netHandler();
        return h == null ? null : h.getNetworkManager();
    }

    /** True if there is a live, open connection. */
    public boolean isConnected() {
        NetworkManager nm = networkManager();
        return nm != null && nm.isChannelOpen();
    }

    /** True if the player is in a world. */
    public boolean isInWorld() {
        return player() != null && world() != null;
    }
}
