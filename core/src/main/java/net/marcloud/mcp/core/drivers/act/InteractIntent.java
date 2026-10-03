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
 *   <li>{@link Kind#RELEASE} — let go of a sustained use nothing else ended.</li>
 *   <li>{@link Kind#DROP} — throw one named player-inventory slot out onto the floor.</li>
 * </ul>
 *
 * <p>Block coordinates + {@code face} are used by DIG/PLACE/USE-on-block;
 * {@code entityId} by ATTACK; {@code hotbarSlot} by HOTBAR; {@code playerSlot} by DROP;
 * {@code holdMode} + {@code holdTicks} by HOLD; {@code attackMode} by ATTACK. {@code hitX/Y/Z} is
 * the within-block hit offset for PLACE (defaults to the face center when unset).
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
 * @param playerSlot player inventory slot 0-35 ({@link Kind#DROP}), or {@code -1} for the
 *                   other kinds. Distinct from {@code hotbarSlot} because a drop can name any of
 *                   the 36 stacks while a hotbar select can only name the nine in hand, and
 *                   folding them into one field would make "slot 20" mean two different things
 *                   depending on which verb it arrived with.
 * @param holdMode   how a HOLD ends, or null for the other kinds
 * @param holdTicks  ticks to hold before releasing ({@link HoldMode#THEN_RELEASE}); ignored by
 *                   {@link HoldMode#UNTIL_DONE} and {@link HoldMode#WHILE_BLOCKING}
 * @param attackMode whether an ATTACK is a plain swing or is timed for a critical hit
 * @param craftItem the OUTPUT registry name to craft ({@link Kind#CRAFT}), or null for every
 *                  other kind. A NAME rather than a resolved recipe, so an intent stays plain
 *                  data and is safe to build off-thread: the recipe table and the player's
 *                  inventory are both live state, and {@code InteractApplier} reads them on the
 *                  game thread when it binds. Namespace-optional like every other registry name
 *                  in this surface.
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
        int playerSlot,
        HoldMode holdMode,
        int holdTicks,
        AttackMode attackMode,
        String craftItem) implements ActIntent {

    /**
     * As the canonical constructor, for a kind that carries no {@code craftItem}.
     *
     * <p>Every factory below writes {@code null} here, and so does any caller written before
     * CRAST existed. Without this overload, adding the component means editing every construction
     * site in the tree for a field only one kind reads, and a mechanical edit to unrelated call
     * sites is exactly the kind of change that hides a real one in its diff.
     */
    public InteractIntent(
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
            int playerSlot,
            HoldMode holdMode,
            int holdTicks,
            AttackMode attackMode) {
        this(kind, blockX, blockY, blockZ, hasBlock, face, hitX, hitY, hitZ, entityId, hotbarSlot,
                playerSlot, holdMode, holdTicks, attackMode, null);
    }

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
        /** Select hotbar slot 0-8. */
        HOTBAR,
        /**
         * Sustain a use for as long as it takes (see {@link HoldController}). USE starts a use and
         * lets vanilla cancel it a couple of ticks later; HOLD keeps vanilla's use key asserted so
         * the use actually runs, and ends by the rule the item plays by.
         */
        HOLD,
        /**
         * Let go of a sustained use, as a release of the hand rather than a replacement intent.
         *
         * <p>It exists because {@link HoldMode#THEN_RELEASE} and {@link HoldMode#UNTIL_DONE} both
         * END by letting go, and neither can express "let go now, when something else says so". A
         * caller that wants to block until an enemy is in reach has to end the block with a tick
         * count it cannot know, or abandon the slot — and abandoning it (a rebind, a cancel) takes
         * a different path through {@code InteractApplier} with a different message, so the one
         * thing the caller wants to read afterwards is not there. This is that read.
         */
        RELEASE,
        /**
         * Throw the whole stack in one named PLAYER inventory slot out onto the floor.
         *
         * <p>The verb the act layer had no word for. Every other kind acts on the WORLD -- a
         * block, a face, an entity -- and the one that acted on the player ({@link Kind#HOTBAR})
         * could only choose which of the nine hotbar slots is in hand, never empty one. So a full
         * bag was a terminal condition: the agent could refuse to dig rather than destroy blocks
         * it could not carry, and then had no move at all, because nothing in this vocabulary
         * puts a stack back on the ground.
         *
         * <p>It is {@code SlotClickMode#DROP_SLOT} ({@code Container.slotClick:445-457}) driven
         * by {@link DropController}, which confirms the slot afterwards rather than trusting the
         * click. Note the QUEUE it empties: {@code mainInventory[0..35]}, all 36 stacks, not the
         * nine in hand -- a drop you cannot name is not a drop.
         */
        DROP,
        /**
         * Craft the named item in whichever crafting window is open, over as many ticks as the
         * server's verdict takes.
         *
         * <p><b>Why this is a kind and not a separate tool.</b> It has the same shape as every
         * other kind here: a multi-tick state machine ({@code CraftController}, exactly as
         * {@link Kind#DIG} is {@link DigController}) driven by {@code ActTickLoop} on the game
         * thread, cancelable through {@code act_cancel}, observable through {@code act_status},
         * and sequenceable through {@code act_plan}. Giving it its own tool would have meant a
         * second place that has to be gated, a second status read, a second cancel, and a second
         * sequencer -- none of which the act layer lacks. See {@code CraftWire} for the full
         * argument, which is also why this is a kind rather than a flag on the read-only
         * {@code craft_plan}.
         *
         * <p>It needs an OPEN crafting window, which the model opens with {@link Kind#PLACE} on
         * a bench (or uses its own 2x2 grid). The controller says so by name when there is none,
         * rather than opening one itself: a craft that quietly walked to a bench would be doing
         * navigation nobody asked for.
         */
        CRAFT
    }


    /**
     * How an {@link Kind#ATTACK} is aimed.
     *
     * <p>Not a cosmetic flag: the two differ in whether the swing may be spent at all. {@link
     * #PLAIN} fires on the first tick, which is what a swing is. {@link #CRIT} waits for the body
     * state {@link CritWindow} names and refuses if it never arrives, because vanilla's 1.5x at
     * {@code EntityPlayer:1335} is the entire value of the swing and a swing taken on flat ground
     * spends it for a normal hit.
     */
    public enum AttackMode {
        /** Fire as soon as the target is reachable. What {@code kind=attack} has always meant. */
        PLAIN,
        /**
         * Wait for the falling instant {@link CritWindow} describes, then swing there.
         *
         * <p>The 1.5x is the server's to apply and nothing reports it back, so this is a
         * well-aimed swing and never a confirmed crit — {@link CritWindow}'s own doc names the
         * three of seven terms it cannot see.
         */
        CRIT
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
        THEN_RELEASE,
        /**
         * Hold for as long as the player blocks, and end when the caller says so.
         *
         * <p>The mode {@link HoldMode#THEN_RELEASE} cannot be, because the tick count is the one
         * thing a caller does not know when the question is "block until it is my turn to swing".
         * There is no clock on this: no deadline, no self-termination, no countdown. It ends on a
         * {@link Kind#RELEASE}, on the slot being cancelled or rebound, or on the use stopping for
         * a reason of its own (a GUI eating the key, the stack being taken away) — and each of
         * those is reported as what it was, never as a quiet success.
         *
         * <p>Refused outright unless vanilla itself says the use is a BLOCK
         * ({@link ActActuator#blocking()}), because holding indefinitely against a bow is a
         * drawn bow nobody released: that is a failure mode with an arrow in it, and the check is
         * one read.
         */
        WHILE_BLOCKING
    }

    /** Dig the given block, approaching from {@code face} (0-5). */
    public static InteractIntent dig(int x, int y, int z, int face) {
        return new InteractIntent(Kind.DIG, x, y, z, true, face, 0, 0, 0, -1, -1, -1, null, 0, null);
    }

    /** Use the held item in the air. */
    public static InteractIntent useInAir() {
        return new InteractIntent(Kind.USE, 0, 0, 0, false, -1, 0, 0, 0, -1, -1, -1, null, 0, null);
    }

    /** Place/activate against {@code face} of the given block at hit offset (hx,hy,hz). */
    public static InteractIntent place(int x, int y, int z, int face, double hx, double hy, double hz) {
        return new InteractIntent(Kind.PLACE, x, y, z, true, face, hx, hy, hz, -1, -1, -1, null, 0, null);
    }

    /** Attack the entity with the given id, as a plain swing. */
    public static InteractIntent attack(int entityId) {
        return new InteractIntent(Kind.ATTACK, 0, 0, 0, false, -1, 0, 0, 0, entityId, -1, -1, null, 0,
                AttackMode.PLAIN);
    }

    /**
     * Attack the entity with the given id, timed for a critical hit.
     *
     * @see AttackMode#CRIT
     */
    public static InteractIntent critAttack(int entityId) {
        return new InteractIntent(Kind.ATTACK, 0, 0, 0, false, -1, 0, 0, 0, entityId, -1, -1, null, 0,
                AttackMode.CRIT);
    }

    /** Select hotbar {@code slot} (0-8). */
    public static InteractIntent hotbar(int slot) {
        return new InteractIntent(Kind.HOTBAR, 0, 0, 0, false, -1, 0, 0, 0, -1, slot, -1, null, 0, null);
    }

    /**
     * Throw the whole stack in PLAYER inventory slot {@code playerSlot} (0-35, vanilla's
     * {@code mainInventory} order, so 0-8 is the hotbar) onto the floor.
     *
     * <p>The whole stack, not one item, and never the nine in hand without saying which one: a
     * caller that wants to make room in a full bag wants the room, and a caller that wants to
     * drop a single cobblestone out of a stack of 64 is asking for something a different verb
     * ({@code SlotClickMode#DROP_SLOT} with button 0) should say.
     *
     * <p>Nothing here picks the slot. Which stack a player throws away is a decision about what
     * they are carrying and what they are about to need, and a controller that guessed at it
     * would be destroying inventory on the strength of a name it did not earn.
     *
     * @see Kind#DROP
     */
    public static InteractIntent dropStack(int playerSlot) {
        return new InteractIntent(Kind.DROP, 0, 0, 0, false, -1, 0, 0, 0, -1, -1, playerSlot, null, 0,
                null);
    }

    /**
     * Craft {@code item} in the open crafting window, over as many ticks as it takes.
     *
     * <p>The name is taken as given and NOT resolved here. Resolving means reading the recipe
     * table and deciding which of an item's recipes to use, and both of those are the applier's
     * job on the game thread: the same name can be craftable or not depending on what the player
     * is carrying, so an intent that froze the choice at parse time would be a claim about the
     * world made from a worker thread. What this carries is the QUESTION; the answer is read
     * where it can be read honestly.
     *
     * <p>Blank is refused rather than defaulted. A craft of "" has no recipe, and the controller
     * would spend a state machine discovering that; naming it at the boundary costs one line.
     *
     * @see Kind#CRAFT
     */
    public static InteractIntent craftItem(String item) {
        return new InteractIntent(Kind.CRAFT, 0, 0, 0, false, -1, 0, 0, 0, -1, -1, -1, null, 0, null,
                item);
    }

    /**
     * Hold the use of the held item until vanilla ends it -- eating. See
     * {@link HoldMode#UNTIL_DONE}: fails honestly rather than hanging if the held item is one that
     * never self-terminates.
     */
    public static InteractIntent holdUntilDone() {
        return new InteractIntent(Kind.HOLD, 0, 0, 0, false, -1, 0, 0, 0, -1, -1, -1,
                HoldMode.UNTIL_DONE, 0, null);
    }

    /**
     * Hold the use for {@code ticks}, then release -- drawing and firing a bow, or blocking for a
     * chosen length of time. See {@link HoldMode#THEN_RELEASE}.
     */
    public static InteractIntent holdThenRelease(int ticks) {
        return new InteractIntent(Kind.HOLD, 0, 0, 0, false, -1, 0, 0, 0, -1, -1, -1,
                HoldMode.THEN_RELEASE, ticks, null);
    }

    /**
     * Block for as long as the caller keeps asking. See {@link HoldMode#WHILE_BLOCKING}; end it
     * with {@link #releaseUse()}.
     */
    public static InteractIntent block() {
        return new InteractIntent(Kind.HOLD, 0, 0, 0, false, -1, 0, 0, 0, -1, -1, -1,
                HoldMode.WHILE_BLOCKING, 0, null);
    }

    /** Let go of whatever sustained use is running. */
    public static InteractIntent releaseUse() {
        return new InteractIntent(Kind.RELEASE, 0, 0, 0, false, -1, 0, 0, 0, -1, -1, -1, null, 0,
                null);
    }

    @Override
    public ActSlot slot() {
        return ActSlot.INTERACT;
    }
}
