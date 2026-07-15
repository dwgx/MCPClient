package net.marcloud.mcp.core.drivers.world;

import java.util.List;

/**
 * PHASE W.2 — the player's authoritative self state, read from EntityPlayerSP.
 * Immutable, reference-free (primitives/String/List only).
 */
public record SelfView(
        double x, double y, double z,
        double vx, double vy, double vz,
        float yaw, float pitch,
        float health, int food, float saturation,
        int xpLevel, float xpProgress,
        int armor, int air,
        String gamemode, boolean sneaking, boolean sprinting, boolean onGround,
        List<Effect> effects) {

    /** One active potion effect. */
    public record Effect(int id, String name, int amplifier, int durationTicks) {
    }
}
