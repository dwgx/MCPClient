package net.marcloud.mcp.core.drivers.world;

/**
 * PHASE W.4 — one nearby entity. {@code hp}/{@code name} are null for non-living
 * entities (only surfaced "if visible" = living). Reference-free.
 */
public record EntityView(int id, String type, double x, double y, double z,
                         double dist, Integer hp, String name) {

    /**
     * Whether the SERVER would accept an interact/attack packet aimed at this entity.
     *
     * <p>{@code dist} here is the client's distance, and the server compares a SQUARE against 36.0
     * or 9.0 depending on whether it can see the player. So "in range" is not one fact: it is two,
     * and the honest report is the one the client cannot compute -- the sight half is the SERVER's
     * answer and is not readable from here.
     */
    public boolean reachable(boolean canSee) {
        return EntityCombat.serverAccepts(dist * dist, canSee);
    }

    /** Whether the crosshair can land on it: the 3.0 clamp, which is a different question. */
    public boolean hoverable() {
        return EntityCombat.pickable(dist);
    }

    /** Whether attacking it is legal, or would have the server kick the player. */
    public boolean attackable() {
        return EntityCombat.attackable(type);
    }

    /** Whether it attacks players, by vanilla's own hierarchy rather than by health. */
    public boolean hostile() {
        return EntityCombat.hostile(type);
    }
}
