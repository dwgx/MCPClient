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
 * <p>{@link AimMode} is the SECOND, independent axis: {@link Mode} decides where the
 * target angle comes from, {@code aim} decides when the intent is allowed to end. All
 * four combinations are meaningful, so neither is folded into the other.
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
 * @param aim            {@link AimMode#ONCE} (end on arrival) or {@link AimMode#KEEP} (hold aim)
 * @param durationTicks  {@link AimMode#KEEP} only: end after this many ticks;
 *                       {@code <= 0} holds until cancelled or replaced. Ignored by ONCE.
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
        float slewDegPerTick,
        AimMode aim,
        int durationTicks) implements ActIntent {

    /** How the target rotation is determined. */
    public enum Mode {
        /** Absolute yaw/pitch supplied by the caller. */
        SET,
        /** Aim at a block center or an entity, resolved from the eye each tick. */
        LOOK_AT
    }

    /**
     * When the aim is allowed to STOP. Orthogonal to {@link Mode}, which decides only where the
     * target angle is read from.
     *
     * <p>{@link Mode#LOOK_AT} already re-resolves the angle from the eye every tick, so half of
     * tracking was always present. The missing half was this: with {@link #ONCE} the controller
     * returns a terminal outcome the moment the crosshair lands, and the applier drops it, so
     * nothing corrects the aim on the NEXT tick when the target has moved. Re-resolving an angle
     * that no longer gets written is not tracking.
     *
     * <p>Rejected alternative: make LOOK_AT always keep aiming, since a moving target is the
     * obvious reason to name one. It breaks the commonest use of the slot -- turn to face a block,
     * then dig it -- because that caller needs the slot to REACH a terminal phase for
     * {@code act_status} to say the turn finished, and it needs the slot free for the next aim.
     * An intent that never completes would make "did I finish turning" unanswerable.
     */
    public enum AimMode {
        /**
         * End as soon as the crosshair reaches the target. The default, and what every caller
         * that aims-then-acts wants: the LOOK slot reports COMPLETE and is free again.
         */
        ONCE,
        /**
         * Keep aiming after arrival, correcting every tick, until something real ends it: the
         * caller cancels ({@code act_cancel}), a new LOOK intent replaces it, the targeted entity
         * is gone, the world goes away, or {@code durationTicks} runs out. Arrival is explicitly
         * NOT one of them -- that is the whole difference from {@link #ONCE}.
         *
         * <p>Costs the caller the slot for as long as it runs, and it writes rotation every tick,
         * so it fights a human moving the mouse and it overrides a server rotation packet
         * ({@code S08PacketPlayerPosLook}) on the tick after one lands. Both are consequences of
         * what was asked for rather than faults, but a caller holding a KEEP aim indefinitely has
         * taken the camera away from everything else, which is why {@code durationTicks} exists.
         */
        KEEP
    }

    /** An absolute-aim intent. Instant when {@code slewDegPerTick <= 0}. */
    public static LookIntent set(float yaw, float pitch, float slewDegPerTick) {
        return new LookIntent(Mode.SET, yaw, pitch, 0, 0, 0, false, -1, slewDegPerTick,
                AimMode.ONCE, 0);
    }

    /** Aim at the center of a block. */
    public static LookIntent lookAtBlock(int x, int y, int z, float slewDegPerTick) {
        return new LookIntent(Mode.LOOK_AT, 0f, 0f, x, y, z, true, -1, slewDegPerTick,
                AimMode.ONCE, 0);
    }

    /** Aim at an entity by id (tracked each tick while active). */
    public static LookIntent lookAtEntity(int entityId, float slewDegPerTick) {
        return new LookIntent(Mode.LOOK_AT, 0f, 0f, 0, 0, 0, false, entityId, slewDegPerTick,
                AimMode.ONCE, 0);
    }

    /**
     * Keep the crosshair on an entity for {@code durationTicks} ({@code <= 0} = until cancelled or
     * replaced). Unlike {@link #lookAtEntity}, arrival does not end it, so a mob that walks keeps
     * being followed.
     */
    public static LookIntent trackEntity(int entityId, float slewDegPerTick, int durationTicks) {
        return new LookIntent(Mode.LOOK_AT, 0f, 0f, 0, 0, 0, false, entityId, slewDegPerTick,
                AimMode.KEEP, durationTicks);
    }

    /**
     * Keep the crosshair on a block center for {@code durationTicks} ({@code <= 0} = until
     * cancelled or replaced).
     *
     * <p>Worth having even though a block does not move: the PLAYER does. A caller walking while
     * mining, or one being knocked back, needs the angle recomputed from the new eye position
     * every tick, and that is the same correction loop an entity needs.
     */
    public static LookIntent trackBlock(int x, int y, int z, float slewDegPerTick,
                                        int durationTicks) {
        return new LookIntent(Mode.LOOK_AT, 0f, 0f, x, y, z, true, -1, slewDegPerTick,
                AimMode.KEEP, durationTicks);
    }

    /**
     * Hold an absolute yaw/pitch for {@code durationTicks} ({@code <= 0} = until cancelled or
     * replaced) -- the SET analogue of the two above.
     *
     * <p>Its use is holding a heading against something else that writes rotation: a server
     * {@code S08PacketPlayerPosLook}, or a human nudging the mouse. Because it re-asserts every
     * tick it WINS those contests, which is the point and also the hazard.
     */
    public static LookIntent holdSet(float yaw, float pitch, float slewDegPerTick,
                                     int durationTicks) {
        return new LookIntent(Mode.SET, yaw, pitch, 0, 0, 0, false, -1, slewDegPerTick,
                AimMode.KEEP, durationTicks);
    }

    /** True if this is a LOOK_AT intent that tracks an entity. */
    public boolean hasEntity() {
        return mode == Mode.LOOK_AT && targetEntityId >= 0;
    }

    /** True if arrival does NOT end this intent (see {@link AimMode#KEEP}). */
    public boolean keepsAiming() {
        return aim == AimMode.KEEP;
    }

    @Override
    public ActSlot slot() {
        return ActSlot.LOOK;
    }
}
