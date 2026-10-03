package net.marcloud.mcp.core.drivers.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The one place where {@link CraftController} meets a caller that speaks {@code ActOutcome}.
 *
 * <p><b>Why the craft controller had no tool and still is not one.</b> The document this closes
 * recorded the block precisely: a craft is multi-tick (it must wait out a server round trip before
 * it may believe its own placements), and the only multi-tick machinery in this repository is the
 * act layer's -- {@code ActTickLoop} on the game thread, {@code ActPlanInterpreter} sequencing,
 * {@code act_cancel} cancelling, {@code act_status} reporting. A tool call is one submission on a
 * worker thread and returns when it returns; a craft that returned inside one submission would have
 * to sleep through ticks it does not own, which is the "one submission walks it to the end" rewrite
 * that {@code command-to-action.md} explicitly records as the alternative and rejects. So the
 * controller is driven by the tick loop, and this class is the adapter that makes its vocabulary
 * legible to the act layer.
 *
 * <p><b>Why CRAST is a kind on {@code act_set} rather than a new tool.</b> Weighed, not assumed:
 *
 * <ul>
 *   <li><b>The tool surface is already past the point.</b> {@code northstar-gap.md} records 84
 *       tools / 17k tokens as beyond every known inflection, with folding already queued. A
 *       capability that needs one more <em>kind</em> of an existing actuator does not need to be
 *       the reason the surface grows.</li>
 *   <li><b>The multi-step shape is already built, and only here.</b> Crafting is not one act: open
 *       a bench, then craft in it, then take the result. {@code act_plan} already sequences
 *       act-shaped steps and waits for each to COMPLETE. A separate tool would have had to be
 *       taught to sequence against a plan that could not describe it.</li>
 *   <li><b>Every gate follows for free, and only here.</b> Five tables key on the tool NAME. A new
 *       tool needs five new rows, and W6 shipped four HIGH writers with no L4 row at all, so
 *       "disable_privilege had no kill switch" is a shape this repository has already paid for
 *       once. As a kind on {@code act_set}, the craft inherits R1 / HIGH / SE_WORLD_WRITE /
 *       CAP_WORLD_WRITE from rows that are already there and already tested.</li>
 *   <li><b>The model does not have to choose correctly between two tools.</b> A wrong-tool
 *       probability is a real cost, but the alternative here is not "zero chance of confusion" --
 *       it is one more sibling to {@code craft_plan} whose names differ by two letters and whose
 *       effects differ by the entire world. One tool, two clearly-separated verbs, is strictly
 *       less confusable than two tools.</li>
 * </ul>
 *
 * <p><b>Why not an execute flag on {@code craft_plan}.</b> Because {@code craft_plan} is read-only
 * at R2 with CAP_WORLD_READ and no L4 privilege, and its description says so. An execute flag would
 * make one name mean both a read and a write, so the gate could only be the union of the two -- an
 * R2 observer would have to hold SE_WORLD_WRITE to plan a craft, and the tool's own description
 * would have to stop saying it changes nothing. The honest shape is two verbs with two gates.
 *
 * <p><b>Threading.</b> GAME THREAD ONLY, inherited from {@link CraftController} and
 * {@link CraftWindow}: this class reads the recipe table and the live inventory, and the only
 * caller is the applier's per-tick {@code apply}.
 */
public final class CraftWire {

    private CraftWire() {
    }

    /**
     * The controller for {@code item}, or a refusal naming why there isn't one.
     *
     * <p>Resolution happens HERE and not at parse time because it is a question about live state:
     * an item can have four recipes, which one is right depends on what the player is carrying, and
     * both the recipe table and the inventory are unreadable from the worker thread the intent was
     * parsed on. So the intent carries the question and this answers it where the answer is.
     *
     * <p>Every refusal names what to do next. "No such recipe" and "no recipe we can lay out" are
     * different facts and are reported separately, because they call for different moves: the first
     * means go and find a different route to the item, the second means the game CAN make it and
     * this code cannot express how.
     */
    public static Bound bind(String item, CraftWindow win) {
        if (win == null) {
            return Bound.refused("this client has no crafting window to work in, so nothing was "
                    + "crafted");
        }
        Craft.Result found = Craft.recipesFor(item);
        if (found.recipes().isEmpty()) {
            if (found.unsupported().isEmpty()) {
                return Bound.refused("no recipe makes '" + item + "'. If the game can make it under "
                        + "another name, craft_plan on that name lists them -- an item's registry name "
                        + "is not always the word it is known by");
            }
            // Non-empty unsupported plus no usable recipes is the case craft_plan's own description
            // calls out as a DIFFERENT answer from "there is no recipe", and it is worth the extra
            // sentence here: a model told only "no recipe" will go looking for another route to an
            // item it can in fact make by hand.
            return Bound.refused("the game has a recipe for '" + item + "' but this client cannot "
                    + "express its layout, so it cannot be automated: "
                    + String.join("; ", found.unsupported())
                    + ". Make it by hand, or reach the item another way");
        }
        // The first SATISFIABLE recipe, so an item with several routes takes one the player can
        // actually pay for rather than failing on the first and never trying the rest. Vanilla's
        // own resolution order is preserved among equals (Craft.plan walks the list in order).
        CraftInventory carried = storedIn(win);
        RecipeView chosen = null;
        List<String> shortfalls = new ArrayList<>();
        for (RecipeView r : found.recipes()) {
            if (CraftFeasibility.check(r, carried).satisfied()) {
                chosen = r;
                break;
            }
            shortfalls.add(r.output() + " (" + r.width() + "x" + r.height() + "): short "
                    + describe(CraftFeasibility.check(r, carried).missing()));
        }
        if (chosen == null) {
            // Every candidate named with what it lacks. "Cannot craft X" on its own is not
            // actionable; "you are one plank short" is, and it is the whole reason the craft
            // package models shortfalls as data.
            return Bound.refused("cannot craft '" + item + "' yet: " + String.join("; ", shortfalls)
                    + ". Run craft_plan on it for the full bill, gather what it names, then craft "
                    + "again");
        }
        return Bound.of(new CraftController(chosen), chosen);
    }

    /** What the window's workable slots hold, as the feasibility check consumes it. */
    private static CraftInventory storedIn(CraftWindow win) {
        List<CraftInventory.Held> held = new ArrayList<>();
        if (win != null) {
            for (int slot : win.storageSlots()) {
                CraftInventory.Held there = win.stackAt(slot);
                if (there != null && there.item() != null && there.count() > 0) {
                    held.add(there);
                }
            }
        }
        return new CraftInventory(List.copyOf(held));
    }

    private static String describe(List<CraftFeasibility.Missing> missing) {
        List<String> parts = new ArrayList<>(missing.size());
        for (CraftFeasibility.Missing m : missing) {
            parts.add(m.available() + "/" + m.need() + " " + m.item());
        }
        return parts.isEmpty() ? "nothing" : String.join(", ", parts);
    }

    /**
     * One bound craft, or the reason there is not one.
     *
     * <p>A refusal is a VALUE rather than an exception because it is an ordinary outcome, not a
     * programming error: "you are short two planks" is the single most common answer this whole
     * path produces, and throwing it would put the most frequent result on the exceptional path
     * where the applier has to catch it. {@link #message()} is what the slot reports either way.
     */
    public record Bound(CraftController controller, RecipeView recipe, String message) {

        static Bound of(CraftController controller, RecipeView recipe) {
            return new Bound(controller, recipe,
                    "crafting " + recipe.outputCount() + "x " + recipe.output() + " (recipe #"
                            + recipe.index() + ", " + recipe.width() + "x" + recipe.height() + ")");
        }

        static Bound refused(String why) {
            return new Bound(null, null, why);
        }

        /** True when there is a controller to tick. */
        public boolean craftable() {
            return controller != null;
        }

        /**
         * Whether this refusal is TERMINAL as the act layer means it.
         *
         * <p>True, deliberately. "You are short planks" does not become true by waiting, and a
         * non-terminal refusal would leave the INTERACT slot spinning until the lease expired,
         * reporting itself ACTIVE while doing nothing -- which is the exact shape of the
         * {@code act_set move} fake-success this repository rates as its highest historical risk.
         * The model is told what is missing and the slot is free for the next thing it asks for.
         */
        public boolean terminal() {
            return true;
        }
    }

    /**
     * The act-layer view of a craft outcome.
     *
     * <p>{@link CraftOutcome} is deliberately its own type (see its own javadoc: it is a sequence
     * of container clicks with no act slot behind it), so this is the one place the two vocabularies
     * meet. A craft CANCELLED through {@code act_cancel} maps onto {@code ActPhase#CANCELLED} and
     * everything else terminal maps onto FAILED, so a cancel reads as a cancel rather than as a
     * failure the model should retry.
     */
    public static net.marcloud.mcp.core.drivers.act.ActOutcome toActOutcome(CraftOutcome out) {
        if (!out.terminal()) {
            return net.marcloud.mcp.core.drivers.act.ActOutcome.running(out.message());
        }
        return out.ok()
                ? net.marcloud.mcp.core.drivers.act.ActOutcome.done(out.message())
                : out.message().contains("cancelled")
                ? net.marcloud.mcp.core.drivers.act.ActOutcome.cancelled(out.message())
                : net.marcloud.mcp.core.drivers.act.ActOutcome.failed(out.message());
    }

    /** Namespace-stripped and lowercased, matching {@code Craft}'s own name handling. */
    public static String normalise(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        return colon < 0 ? s : s.substring(colon + 1);
    }
}