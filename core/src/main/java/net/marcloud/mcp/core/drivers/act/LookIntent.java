package net.marcloud.mcp.core.drivers.act;

/**
 * A camera-aim intent for the {@link ActSlot#LOOK} slot. Two modes:
 *
 * <ul>
 *   <li>{@link Mode#SET} — aim at absolute {@code yaw}/{@code pitch} (degrees).
 *   <li>{@link Mode#LOOK_AT} — aim at a target: a block center ({@code targetBlockX/Y/Z})
 *       or an entity ({@code targetEntityId} {@code >= 0}). The controller resolves
 *       the yaw/pitch from the player's eye each tick, so a moving entity is tracked.
 * </ul>
 *
 * <p>{@code slewDegPerTick} caps the turn rate. {@code <= 0} means instant: the
 * whole rotation is applied on the first active tick (with prev==cur so the client
 * does not render an interpolated whip-around). A positive cap turns the shorter
 * way around the circle at that many degrees per tick until aligned.
 *
 * @param mode           SET (absolute) or LOOK_AT (resolve from target)
 * @param yaw            target yaw in degrees (SET mode)
 * @param pitch          target pitch in degrees (SET mode)
 * @param targetBlockX   block X (LOOK_AT block); ignored unless {@code hasBlock}
 * @param targetBlockY   block Y (LOOK_AT block)
 * @param targetBlockZ   block Z (LOOK_AT block)
 * @param hasBlock       true if this LOOK_AT targets a block
 * @param targetEntityId entity id (LOOK_AT entity), or {@code -1} for none
 * @param slewDegPerTick max degrees/tick; {@code <= 0} = instant snap
 */
public record LookIntent(
        Mode mode,
        float yaw,
        float pitch,
        int targetBlockX,
        int targetBlockY,
        int targetBlockZ,
        boolean hasBlock,
        int targetEntityId,
        float slewDegPerTick) implements ActIntent {

    /** How the target rotation is determined. */
    public enum Mode {
        /** Absolute yaw/pitch supplied by the caller. */
        SET,
        /** Aim at a block center or an entity, resolved from the eye each tick. */
        LOOK_AT
    }

    /** An absolute-aim intent. Instant when {@code slewDegPerTick <= 0}. */
    public static LookIntent set(float yaw, float pitch, float slewDegPerTick) {
        return new LookIntent(Mode.SET, yaw, pitch, 0, 0, 0, false, -1, slewDegPerTick);
    }

    /** Aim at the center of a block. */
    public static LookIntent lookAtBlock(int x, int y, int z, float slewDegPerTick) {
        return new LookIntent(Mode.LOOK_AT, 0f, 0f, x, y, z, true, -1, slewDegPerTick);
    }

    /** Aim at an entity by id (tracked each tick while active). */
    public static LookIntent lookAtEntity(int entityId, float slewDegPerTick) {
        return new LookIntent(Mode.LOOK_AT, 0f, 0f, 0, 0, 0, false, entityId, slewDegPerTick);
    }

    /** True if this is a LOOK_AT intent that tracks an entity. */
    public boolean hasEntity() {
        return mode == Mode.LOOK_AT && targetEntityId >= 0;
    }

    @Override
    public ActSlot slot() {
        return ActSlot.LOOK;
    }
}
