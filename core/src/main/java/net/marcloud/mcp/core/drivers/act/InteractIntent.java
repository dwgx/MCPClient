package net.marcloud.mcp.core.drivers.act;

/**
 * A world-interaction intent for the {@link ActSlot#INTERACT} slot. One record
 * covers the whole interaction family, discriminated by {@link Kind}:
 *
 * <ul>
 *   <li>{@link Kind#DIG} — break a block (multi-tick: start, pump, poll for gone).
 *   <li>{@link Kind#USE} — right-click use in air or on a block.
 *   <li>{@link Kind#PLACE} — place/activate against a block face at a hit point.
 *   <li>{@link Kind#ATTACK} — left-click attack an entity.
 *   <li>{@link Kind#HOTBAR} — select a hotbar slot (0-8).
 *   <li>{@link Kind#HOLD} — sustain a use across ticks (eat / draw a bow / block).
 * </ul>
 *
 * <p>Block coordinates + {@code face} are used by DIG/PLACE/USE-on-block;
 * {@code entityId} by ATTACK; {@code hotbarSlot} by HOTBAR; {@code holdMode} +
 * {@code holdTicks} by HOLD. {@code hitX/Y/Z} is the
 * within-block hit offset for PLACE (defaults to the face center when unset).
 * All fields are plain data — no live game references — so the intent is safe to
 * build off-thread.
 *
 * @param kind      which interaction
 * @param blockX    target block X (DIG/PLACE/USE-on-block)
 * @param blockY    target block Y
 * @param blockZ    target block Z
 * @param hasBlock  true if a block target is supplied
 * @param face      block face index 0-5 (D-U-N-S-W-E), or {@code -1} for none
 * @param hitX      within-block hit offset X (PLACE), typically 0..1
 * @param hitY      within-block hit offset Y (PLACE)
 * @param hitZ      within-block hit offset Z (PLACE)
 * @param entityId   target entity id (ATTACK), or {@code -1}
 * @param hotbarSlot hotbar slot 0-8 (HOTBAR)
 * @param holdMode   how a HOLD ends, or null for the other kinds
 * @param holdTicks  ticks to hold before releasing ({@link HoldMode#THEN_RELEASE}); ignored by
 *                   {@link HoldMode#UNTIL_DONE}
 */
public record InteractIntent(
        Kind kind,
        int blockX,
        int blockY,
        int blockZ,
        boolean hasBlock,
        int face,
        double hitX,
        double hitY,
        double hitZ,
        int entityId,
        int hotbarSlot,
        HoldMode holdMode,
        int holdTicks) implements ActIntent {

    /** The interaction family. */
    public enum Kind {
        /** Break a block over multiple ticks. */
        DIG,
        /** Right-click use (in air, or on a block if a block target is set). */
        USE,
        /** Place/activate against a block face at a hit point. */
        PLACE,
        /** Left-click attack an entity. */
        ATTACK,
        /** Select a hotbar slot. */
        HOTBAR,
        /**
         * Sustain a use for as long as it takes (see {@link HoldController}). USE starts a use and
         * lets vanilla cancel it a couple of ticks later; HOLD keeps vanilla's use key asserted so
         * the use actually runs, and ends by the rule the item plays by.
         */
        HOLD
    }

    /**
     * How a {@link Kind#HOLD} ends. The caller states this rather than the controller inferring it
     * from the held item, and that is a deliberate split.
     *
     * <p>Vanilla gives the three interesting uses three genuinely different endings, so there is no
     * single "hold until done" that covers them:
     *
     * <ul>
     *   <li><b>Food</b> self-terminates. {@code ItemFood.getMaxItemUseDuration} is 32 ticks; the
     *       server finishes the meal and tells the client ({@code handleStatusUpdate} id 9), at which
     *       point the use is over whether or not anyone let go. "Until done" is meaningful here and
     *       it is the only kind for which it is.
     *   <li><b>A bow</b> never self-terminates -- {@code ItemBow.getMaxItemUseDuration} is 72000
     *       ticks, an hour -- and RELEASE is the action: the arrow is created inside
     *       {@code ItemBow.onPlayerStoppedUsing}, and a draw shorter than
     *       {@link HoldController#BOW_MIN_CHARGE_TICKS} fires nothing at all. So "hold then release"
     *       is not a convenience wrapper, it is the actual semantic of shooting.
     *   <li><b>Blocking</b> with a sword has no ending whatsoever: also 72000 ticks, and
     *       {@code onPlayerStoppedUsing} does nothing. The only thing that can end it is a decision
     *       about how long to block, which is caller knowledge, not item knowledge.
     * </ul>
     *
     * <p>The rejected alternative was to read the item's use action across the seam and pick the
     * rule automatically. It fails on the third case: BOW and BLOCK are indistinguishable by
     * duration, and blocking for the right length of time is a tactical choice the kernel has no
     * basis to invent. Auto-detection would have to guess, and a guess wearing an item type's
     * authority is worse than an argument. The controller still refuses an impossible combination --
     * UNTIL_DONE on a 72000-tick item fails immediately instead of hanging.
     */
    public enum HoldMode {
        /**
         * Hold until vanilla itself ends the use, then report what happened. For food. Fails
         * honestly if the item turns out not to self-terminate.
         */
        UNTIL_DONE,
        /**
         * Hold for {@code holdTicks}, then release and confirm the use ended. For a bow (release
         * fires the arrow) and for blocking (release is the only way to stop).
         */
        THEN_RELEASE
    }

    /** Dig the given block, approaching from {@code face} (0-5). */
    public static InteractIntent dig(int x, int y, int z, int face) {
        return new InteractIntent(Kind.DIG, x, y, z, true, face, 0, 0, 0, -1, -1, null, 0);
    }

    /** Use the held item in the air. */
    public static InteractIntent useInAir() {
        return new InteractIntent(Kind.USE, 0, 0, 0, false, -1, 0, 0, 0, -1, -1, null, 0);
    }

    /** Place/activate against {@code face} of the given block at hit offset (hx,hy,hz). */
    public static InteractIntent place(int x, int y, int z, int face, double hx, double hy, double hz) {
        return new InteractIntent(Kind.PLACE, x, y, z, true, face, hx, hy, hz, -1, -1, null, 0);
    }

    /** Attack the entity with the given id. */
    public static InteractIntent attack(int entityId) {
        return new InteractIntent(Kind.ATTACK, 0, 0, 0, false, -1, 0, 0, 0, entityId, -1, null, 0);
    }

    /** Select hotbar {@code slot} (0-8). */
    public static InteractIntent hotbar(int slot) {
        return new InteractIntent(Kind.HOTBAR, 0, 0, 0, false, -1, 0, 0, 0, -1, slot, null, 0);
    }

    /**
     * Hold the use of the held item until vanilla ends it -- eating. See
     * {@link HoldMode#UNTIL_DONE}: fails honestly rather than hanging if the held item is one that
     * never self-terminates.
     */
    public static InteractIntent holdUntilDone() {
        return new InteractIntent(Kind.HOLD, 0, 0, 0, false, -1, 0, 0, 0, -1, -1,
                HoldMode.UNTIL_DONE, 0);
    }

    /**
     * Hold the use for {@code ticks}, then release -- drawing and firing a bow, or blocking for a
     * chosen length of time. See {@link HoldMode#THEN_RELEASE}.
     */
    public static InteractIntent holdThenRelease(int ticks) {
        return new InteractIntent(Kind.HOLD, 0, 0, 0, false, -1, 0, 0, 0, -1, -1,
                HoldMode.THEN_RELEASE, ticks);
    }

    @Override
    public ActSlot slot() {
        return ActSlot.INTERACT;
    }
}
