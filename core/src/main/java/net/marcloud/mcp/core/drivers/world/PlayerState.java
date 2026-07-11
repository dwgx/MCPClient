package net.marcloud.mcp.core.drivers.world;

import net.marcloud.mcp.core.GameAccess;
import net.minecraft.client.entity.EntityPlayerSP;

/**
 * Immutable snapshot of the local player's live state. Built on the game thread
 * (or any thread — it only reads), then handed to the MCP layer to serialize.
 * A null snapshot ({@link #present} false) means "not in a world".
 */
public record PlayerState(boolean present,
                          String name,
                          double x, double y, double z,
                          float yaw, float pitch,
                          float health,
                          boolean onGround) {

    /** "Not in world" snapshot. */
    public static PlayerState absent() {
        return new PlayerState(false, null, 0, 0, 0, 0, 0, 0, false);
    }

    /** Capture the current player state from the game. */
    public static PlayerState capture(GameAccess game) {
        EntityPlayerSP p = game.player();
        if (p == null) {
            return absent();
        }
        return new PlayerState(
                true,
                p.getName(),
                p.posX, p.posY, p.posZ,
                p.rotationYaw, p.rotationPitch,
                p.getHealth(),
                p.onGround);
    }
}
