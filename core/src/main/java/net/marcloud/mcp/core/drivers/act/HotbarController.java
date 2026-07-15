package net.marcloud.mcp.core.drivers.act;

/**
 * Pure state machine for {@link InteractIntent.Kind#HOTBAR}: select a hotbar slot
 * (0-8). Single-tick. Validates the slot BEFORE touching the actuator so an
 * out-of-range slot fails with no write, then confirms via
 * {@link ActActuator#heldSlot()} that the selection took.
 */
public final class HotbarController {

    /** Lowest valid hotbar slot. */
    public static final int MIN_SLOT = 0;
    /** Highest valid hotbar slot. */
    public static final int MAX_SLOT = 8;

    private final int slot;
    private boolean done;

    public HotbarController(InteractIntent intent) {
        this.slot = intent.hotbarSlot();
    }

    /** True once a terminal outcome has been produced. */
    public boolean isDone() {
        return done;
    }

    /** Advance one tick against {@code act}. Terminal in a single step. */
    public ActOutcome tick(ActActuator act) {
        if (slot < MIN_SLOT || slot > MAX_SLOT) {
            // Reject BEFORE any write.
            done = true;
            return ActOutcome.failed("hotbar slot " + slot + " out of range [0,8]");
        }
        if (!act.inWorld()) {
            done = true;
            return ActOutcome.failed("not in world");
        }
        act.setHeldSlot(slot);
        int now = act.heldSlot();
        done = true;
        if (now == slot) {
            return ActOutcome.done("selected hotbar slot " + slot);
        }
        return ActOutcome.failed("hotbar select did not take (wanted " + slot + ", is " + now + ")");
    }
}
