package net.marcloud.mcp.core.drivers.act;

/**
 * Pure state machine for {@link InteractIntent.Kind#DROP}: throw the whole stack in one named
 * PLAYER inventory slot out onto the floor, and prove it left before saying so.
 *
 * <p><b>The capability this closes.</b> The act layer could dig, use, place, attack, hold, release
 * and select a hotbar slot, and nothing could take a stack OUT of the bag. That is not a small
 * gap: a full inventory is an ordinary situation, and every one of the other verbs gets worse in
 * it, because a dig whose drop has nowhere to land removes the block from the world and returns
 * nothing. The agent could refuse -- and refusing is the right instinct -- but a refusal with no
 * next move is not a policy. This is the next move, and it is vanilla's own: a mode-4 window
 * click ({@link net.marcloud.mcp.core.drivers.world.SlotClickMode#DROP_SLOT},
 * {@code Container.slotClick:445-457}), the same click {@code GuiContainer.keyTyped:707-710}
 * makes when a player holds Ctrl+Q over a slot.
 *
 * <p><b>Why it takes two ticks and what the second one is for.</b> The first tick sends; the
 * second reads {@link ActActuator#slotStackSize} back. The gap is not politeness. A mode-4 click is
 * accepted by the container whether or not it does anything -- an empty slot, a slot whose
 * {@code canTakeStack} refuses, a spectator, a missing {@code PlayerControllerMP} -- and the
 * return value of the send answers only "the click was issued". Reading the slot on the SAME tick
 * would be measuring the write that just happened rather than the state that resulted from it,
 * which is the difference between a report and an echo. So: send on one tick, ask on the next.
 *
 * <p><b>And the confirmation is "the slot is EMPTY", not "the click returned".</b> The whole
 * stack is what was thrown, so a partial change means the click did something a human would not
 * have asked for and is reported as a failure rather than rounded down to a smaller success. The
 * stack did not vanish -- it became an {@code EntityItem} at the player's feet
 * ({@code Container.java:454}) -- but the confirmable fact on this side of the wire is the slot,
 * because that is the thing the caller asked to change.
 *
 * <p>Two ticks is not a bound this controller invents. It is the shortest schedule on which the
 * read-back can be a read-back: poll every tick after the send, the way {@link DigController}
 * polls for a block that has gone.
 */
public final class DropController {

    /** First index of the player's own 36 slots ({@code InventoryPlayer.mainInventory[0]}). */
    public static final int FIRST_SLOT = 0;

    /** How many slots the player owns: {@code InventoryPlayer.mainInventory.length} in 1.8.9. */
    public static final int SLOT_COUNT = 36;

    private final int slot;

    /** How many were in the slot when the click went out, or -1 before the first read. */
    private int before = -1;

    private boolean sent;
    private boolean done;

    public DropController(InteractIntent intent) {
        this.slot = intent.playerSlot();
    }

    /** The player inventory slot this controller was asked to empty. */
    public int slot() {
        return slot;
    }

    /** True once a terminal outcome has been produced. */
    public boolean isDone() {
        return done;
    }

    /**
     * Advance one tick against {@code act}.
     *
     * <p>Terminal on the first tick for every refusal (nothing to drop, no world, a refused
     * write), on the second for every outcome that carries a real slot read.
     */
    public ActOutcome tick(ActActuator act) {
        // Refused BEFORE any write, for HotbarController's reason: a slot number that names
        // nothing must not produce a click aimed at slot 36, which in a player container is the
        // first row of the CRAFTING GRID rather than an inventory stack at all.
        if (slot < FIRST_SLOT || slot >= SLOT_COUNT) {
            done = true;
            return ActOutcome.failed("player inventory slot " + slot + " out of range [0,"
                    + (SLOT_COUNT - 1) + "]", ActOutcome.READ_DIRECTLY);
        }
        if (!act.inWorld()) {
            done = true;
            return ActOutcome.failed("not in world", ActOutcome.READ_DIRECTLY);
        }
        int now = act.slotStackSize(slot);
        if (now <= 0) {
            done = true;
            // The success arm is DERIVED_FROM_READS and the difference from the refusal arm is the
            // whole of this class: "it held 12 and reads 0 now" is a COMPARISON between a baseline
            // sampled on the first tick and a count read this tick. Both are live, so the derivation
            // is honest -- but the drop itself was never observed happening, only that the stack is
            // no longer in the slot. That is a different claim from "the item is on the floor", and
            // it was the one this sentence had been making.
            return sent
                    ? ActOutcome.done("threw player inventory slot " + slot + " onto the floor: it "
                            + "held " + before + " and reads " + now + " now",
                            ActOutcome.DERIVED_FROM_READS)
                    : ActOutcome.failed("player inventory slot " + slot + " is empty, so there is"
                            + " no stack there to throw", ActOutcome.READ_DIRECTLY);
        }
        if (!sent) {
            before = now;
            sent = true;
            if (!act.dropStack(slot)) {
                done = true;
                return ActOutcome.failed("the drop from player inventory slot " + slot + " was"
                        + " refused, so the " + before + " in it are still there",
                        ActOutcome.READ_DIRECTLY);
            }
            return ActOutcome.running("threw the " + before + " in player inventory slot " + slot
                    + " onto the floor; reading the slot back", ActOutcome.READ_DIRECTLY);
        }
        // Sent, and the slot still holds the same stack. Not a slow server: one tick has already
        // passed, and a container click the server refused resyncs the window rather than leaving
        // the client's copy wrong for long. So this is a drop that did not happen, reported as
        // one, with the numbers on both sides of it.
        //
        // DERIVED_FROM_READS for the same reason the success arm is: "still holds 12 after the drop
        // of the 12 it held" is a comparison across two ticks' reads.
        done = true;
        return ActOutcome.failed("player inventory slot " + slot + " still holds " + now
                + " after the drop of the " + before + " it held was sent, so the throw did not"
                + " land", ActOutcome.DERIVED_FROM_READS);
    }
}