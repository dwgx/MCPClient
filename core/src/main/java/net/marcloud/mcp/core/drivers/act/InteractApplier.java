package net.marcloud.mcp.core.drivers.act;

import net.marcloud.mcp.core.drivers.craft.CraftController;
import net.marcloud.mcp.core.drivers.craft.CraftWire;
import net.marcloud.mcp.core.drivers.craft.CraftWindow;

/**
 * The {@link ActSlot#INTERACT} applier: routes an {@link InteractIntent} to the
 * right pure controller ({@link DigController} for multi-tick digging,
 * {@link HoldController} for a sustained use, {@link DropController} for emptying an inventory
 * slot, {@link InteractController} for use/place/attack, {@link HotbarController} for slot
 * select) and steps it over an
 * {@link ActActuator}.
 *
 * <p>Stateful, game-thread-only (driven by {@link ActTickLoop}), so no
 * synchronization. Caches the controller for the intent it is driving and
 * rebuilds when the slot's intent changes, so a new {@code submitInteract} always
 * starts fresh. Freshness is detected by intent IDENTITY, which is why nothing here
 * rewrites the slot's intent per tick: a hold lasts many ticks, and a per-tick swap
 * would make every one of them look like a new submission and restart the hold
 * forever.
 *
 * <p>A pending cancel is forwarded to whichever controller can be MID-something and
 * needs a real teardown on the game thread: a {@link DigController} to abort a break,
 * and a {@link HoldController} to release vanilla's use key -- a hold left asserted
 * would keep the player eating or blocking with nothing driving it.
 *
 * <p><b>The craft seam is separate and deliberately optional.</b> A craft runs over container
 * slots, not over a player's body, so it cannot go through {@link ActActuator} -- the craft
 * package states this itself ({@code CraftWindow} is "deliberately NOT that interface", because
 * the act package owns the actuator and its methods are about a body rather than about slots whose
 * contents change as a consequence of the calls being made). So {@link InteractIntent.Kind#CRAFT}
 * is driven over a {@code CraftWindow} supplied at construction, and an applier built without one
 * refuses a craft with a message naming the fact rather than throwing or silently doing nothing.
 * That is also what keeps every existing headless test constructing this applier with one
 * argument unchanged.
 */
public final class InteractApplier implements ActApplier {

    private final ActActuator actuator;

    private ActIntent boundTo;
    private DigController dig;
    private HoldController hold;
    private InteractController interact;
    private HotbarController hotbar;
    private DropController drop;
    private CraftController craft;
    /** The bound craft's live window, kept so cancel can tick the controller against it. */
    private CraftWindow craftWindow;
    /** Why the current bind refused, or null when it did not. A value, not a throw. */
    private String craftRefusal;

    public InteractApplier(ActActuator actuator) {
        this(actuator, null);
    }

    /**
     * As the one-argument form, with a crafting window for {@link InteractIntent.Kind#CRAFT}.
     *
     * <p>The live implementation is {@code LiveCraftWindow}; a headless test passes a fake whose
     * slots mutate on click. Null is legal and means "no craft can be driven here", which is
     * reported rather than guessed at.
     */
    public InteractApplier(ActActuator actuator, CraftWindow craftWindow) {
        this.actuator = actuator;
        this.craftWindow = craftWindow;
    }


    @Override
    public SlotRecord apply(SlotRecord current) {
        if (!(current.intent() instanceof InteractIntent ii)) {
            return current.withPhase(ActPhase.FAILED, "INTERACT slot given a non-interact intent");
        }
        boolean fresh = boundTo != current.intent();
        if (fresh) {
            bind(ii, current.intent());
        }

        // Cancellation: DIG can be mid-break and HOLD is mid-use, so both need a real teardown on
        // the game thread -- resetBlockRemoving for one, releasing vanilla's use key for the other.
        // The others are single-shot, so a cancel just ends them.
        if (current.cancelRequested()) {
            ActOutcome out = cancelLiveController();
            if (out != null) {
                reset();
                return current.markActive(current.lastAppliedTick(), out.message())
                        .withPhase(ActPhase.CANCELLED, out.message())
                        .withBelief(out.belief())
                .withUnreadCells(out.unreadCells());
            }
            reset();
            return current.withPhase(ActPhase.CANCELLED, "interact cancelled");
        }

        ActOutcome outcome = step(ii);
        long tick = current.lastAppliedTick();
        // The grade travels with the outcome onto the record, and this is the only place in the act
        // layer that copies one: act_status reads the slot, and a grade that stopped here would be
        // produced and discarded every terminal dig. Applied on the cancel path above too, because
        // a teardown that ran is a claim about the world in exactly the same way a completion is.
        if (outcome.terminal()) {
            reset();
            return current.markActive(tick, outcome.message())
                    .withPhase(outcome.state(), outcome.message())
                    .withBelief(outcome.belief())
                .withUnreadCells(outcome.unreadCells());
        }
        return current.markActive(tick, outcome.message());
    }

    /**
     * Tear down whichever controller is holding live game state, or return null if none is.
     *
     * <p>Null rather than an outcome means "nothing to undo", which is the honest answer for a
     * single-shot controller, and keeps the caller's distinction between a teardown that ran and one
     * that was not needed.
     */
    private ActOutcome cancelLiveController() {
        if (dig != null) {
            dig.requestCancel();
            return dig.tick(actuator);
        }
        if (hold != null) {
            hold.requestCancel();
            return hold.tick(actuator);
        }
        // A craft holds ITEMS, so it joins dig and hold here rather than being treated as a
        // single-shot: requestCancel drives it into finish(), whose sweep returns whatever is in
        // the matrix to the inventory. Without this branch a cancel would null the controller with
        // a partly-filled grid still in the window, and vanilla DROPS those stacks on close
        // (ContainerPlayer:83-98, ContainerWorkbench:62-78) -- invisible to world_view, so the model
        // would learn about it only as items missing from its pockets.
        if (craft != null && craftWindow != null) {
            craft.requestCancel();
            return CraftWire.toActOutcome(craft.tick(craftWindow));
        }
        return null;
    }

    private ActOutcome step(InteractIntent ii) {
        return switch (ii.kind()) {
            case DIG -> dig.tick(actuator);
            case HOLD -> hold.tick(actuator);
            case HOTBAR -> hotbar.tick(actuator);
            case DROP -> drop.tick(actuator);
            // A refused bind has no controller, so it is reported as the terminal failure it is
            // rather than dereferenced. See CraftWire.Bound#terminal for why it does not retry.
            case CRAFT -> craftRefusal != null
                    // Graded OBSERVED: the refusal was reached by reading the recipe table and
                    // the live inventory on the game thread, so it is a statement about what the
                    // player is actually holding -- not a guess and not an absence.
                    ? ActOutcome.failed(craftRefusal, ActOutcome.READ_DIRECTLY)
                    : craftWindow == null
                    // Graded UNKNOWN, and deliberately: there is no window object to read, so the
                    // answer is not about the world at all. A caller must not read this as "the
                    // world says no" -- it says nothing was asked of the world.
                    ? ActOutcome.failed("this client was built without a crafting window, so "
                            + "kind='craft' cannot be driven here. Nothing was crafted",
                            ActOutcome.READ_CAME_BACK_EMPTY)
                    : CraftWire.toActOutcome(craft.tick(craftWindow));
            // USE / PLACE / ATTACK all route to InteractController. Kept as a default rather than
            // spelled out, because adding a kind must not silently reroute an existing one: a new
            // entry point lands here and has to be classified deliberately.
            default -> interact.tick(actuator);
        };
    }

    private void bind(InteractIntent ii, ActIntent identity) {
        // Tear down before dropping the reference, not just after cancel. A live HOLD owns state
        // OUTSIDE this object -- vanilla's use key, asserted in a static KeyBinding -- so nulling the
        // field abandons an assertion that nothing is left to lift. Vanilla then re-fires
        // rightClickMouse whenever nothing is in use (Minecraft.java:2158, at most once every five
        // ticks -- rightClickMouse sets rightClickDelayTimer to 4 and it decrements one per tick), which
        // eats the rest
        // of the stack or re-draws the bow forever, and act_cancel cannot rescue it because the slot
        // no longer holds the controller that knows how to let go. The cancel path always did this;
        // the replace path beside it did not, and a test that asserted only "the new intent ran"
        // could not tell the difference.
        reset();
        boundTo = identity;
        // CRAFT is bound BEFORE the switch rather than in it, and the reason is its own shape: every
        // arm below is a single field assignment, while a craft has to resolve a recipe -- which can
        // REFUSE, and the refusal has to be kept -- before it has a controller to assign. Spelled as
        // a case it would be the one arm in this switch that is not an assignment.
        if (ii.kind() == InteractIntent.Kind.CRAFT) {
            bindCraft(ii);
            return;
        }
        switch (ii.kind()) {
            case DIG -> dig = new DigController(ii);
            case HOLD -> hold = new HoldController(ii);
            case HOTBAR -> hotbar = new HotbarController(ii);
            case DROP -> drop = new DropController(ii);
            // The kinds with no dedicated controller share InteractController. A kind added later
            // lands here rather than being routed by accident.
            default -> interact = new InteractController(ii);
        }
    }

    /**
     * Resolve {@code ii}'s item name to a recipe and arm the controller.
     *
     * <p>Resolution happens HERE, on the game thread, rather than when the intent was parsed on a
     * worker thread: it reads the recipe table and the live inventory, and both are state a worker
     * must not touch. The refusal is kept as a FIELD rather than thrown because "you are short
     * planks" is this path's most common answer, not an exceptional one, and {@code step} reports
     * it on the next tick as the terminal failure it is.
     */
    private void bindCraft(InteractIntent ii) {
        CraftWire.Bound bound = CraftWire.bind(ii.craftItem(), craftWindow);
        craft = bound.controller();
        craftRefusal = bound.craftable() ? null : bound.message();
    }

    /**
     * Drop every controller, releasing any live game state first.
     *
     * <p>The release is unconditional rather than "only when replacing": every path that reaches here
     * is one where this applier stops driving the controller, and an asserted key with no driver is
     * the same failure regardless of which path abandoned it.
     */
    private void reset() {
        if (hold != null) {
            hold.releaseIfHolding(actuator);
        }
        boundTo = null;
        dig = null;
        hold = null;
        interact = null;
        drop = null;
        hotbar = null;
        craft = null;
        craftRefusal = null;
    }
}
