package net.marcloud.mcp.core.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.act.ActSlot;
import net.marcloud.mcp.core.drivers.act.InteractIntent;
import net.marcloud.mcp.core.drivers.act.RouteIntent;
import net.marcloud.mcp.core.drivers.act.DropController;
import net.marcloud.mcp.core.drivers.craft.Craft;
import net.marcloud.mcp.core.drivers.craft.CraftController;
import net.marcloud.mcp.core.drivers.craft.CraftOutcome;
import net.marcloud.mcp.core.drivers.craft.CraftWindow;
import net.marcloud.mcp.core.drivers.craft.RecipeView;
import net.marcloud.mcp.core.drivers.plan.Stance;
import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;

/**
 * A goal-directed policy: handed a goal and a world, it works out what is missing and goes to get
 * it, using nothing but the production controllers and the production query surface.
 *
 * <p><b>Why this exists when seventeen tasks already pass.</b> Every other task names the
 * controller it wants -- walk here, dig that, craft this -- so each exercises one capability and
 * none of them can fail because the capabilities do not FIT together. This class is the opposite:
 * it is told a goal and nothing about how to reach it, and it has to choose the next capability
 * itself. That is the only thing in the eval able to report "the agent held every piece and did not
 * sequence them", which is the question the Owner is actually asking.
 *
 * <p><b>The chain is derived, never written down.</b> There is no {@code if (goal == pickaxe) dig a
 * log} anywhere in this file. Each step is computed from world state at the moment it is taken:
 * {@link Craft#recipesFor} is asked what an item is made of, the world is asked what a block drops,
 * and vanilla's own {@code canHarvestBlock} is asked what can break it. The recursion in
 * {@link #obtain} means the depth of the chain is decided by the recipe table. Change the goal and
 * the chain changes with it.
 *
 * <p><b>Three rules do the work, and all three are generic.</b>
 * <ol>
 *   <li>To hold an item: if a recipe makes it, obtain the recipe's ingredients and craft it;
 *       otherwise mine it.</li>
 *   <li>To mine a block: if what is in hand cannot harvest it, first obtain <em>something that
 *       can</em> -- found by asking the item registry, not by naming a pickaxe.</li>
 *   <li>To craft: use the player's own 2x2 grid, or find a bench in the world, or make one, place
 *       it and open it.</li>
 * </ol>
 * Nothing here mentions wood, planks, cobblestone or a bench by name. Those names enter the run
 * because the recipe table produced them.
 *
 * <p><b>It is a policy, not a model.</b> No language, no world model, no ability to be wrong in the
 * way a language model is wrong. What it measures is narrower and worth stating exactly:
 * <b>given a plan, do the production controllers execute it against a real world?</b> A pass is
 * evidence the controllers compose; it is NOT evidence a model would produce this plan. A fail
 * localises the break to a controller or to a missing capability rather than to a planner, which
 * is the distinction that makes the result actionable.
 *
 * <p><b>It is now substitutable, and that is the change.</b> {@code implements Policy} with the
 * main-tree {@code net.marcloud.mcp.core.eval.Policy} seam is what makes a deliberately broken
 * decision runnable here: before it, this class was {@code public final} and constructed
 * directly at eleven sites, so no task could be run with any policy but this one and the suite
 * had no negative control. {@link #obtain(String)} was already the whole public decision
 * surface, so the interface cost nothing and moved nothing -- the seam existed in the shape
 * the class already had.
 *
 * <p><b>One substitution, declared.</b> The nearest-block search below is this file's own rather
 * than {@code BlockFinder}'s: {@code BlockFinder.search} is package-private and
 * {@code BlockFinder.find} needs a live {@code WorldClient}, so neither is reachable from here.
 * The world queries it makes -- {@link SimWorld#dropOf} and vanilla's {@code canHarvestBlock} --
 * are the real ones, so the harvest gate this task is about is the game's own and not a copy.
 */
public final class GoalPolicy implements Policy {

    /** Hard ceiling on the search radius, matching {@code BlockFinder.MAX_RADIUS}. */
    private static final int SEARCH_RADIUS = 24;

    /**
     * The width of the player's own crafting matrix, {@code ContainerPlayer}'s 2x2.
     *
     * <p>Named once so "does this recipe need a bench" is asked in a single place. It is a
     * CONTAINER fact rather than a recipe fact: {@code RecipeView.requiresTable()} asks the recipe's
     * own opinion of the same thing, and using the geometry keeps the two from having to agree.
     */
    private static final int PLAYER_GRID = 2;

    private final SimWorld world;
    private final EvalHarness harness;
    private final List<String> trace = new ArrayList<>();

    /**
     * How many times {@link #haveIngredientsFor} may re-read a recipe's bill before giving up.
     *
     * <p>A bound, not a strategy: the loop normally settles in two or three passes, and a recipe
     * graph where ingredients keep eating each other would otherwise be walked forever.
     */
    private static final int MAX_INGREDIENT_PASSES = 12;

    /** Items already proven unobtainable, so one dead end is not re-explored on every pass. */
    private final Set<String> deadEnds = new HashSet<>();

    /**
     * Live craft bills, as {@code item -> how many outstanding bills name it}.
     *
     * <p>This is the ledger that decides what a drop may throw away, and it exists because the
     * obvious answer is wrong. "Drop something the chain does not need" cannot be answered by
     * looking at the recipe tree of the GOAL: cobblestone has no recipe, so the tree rooted at it
     * is empty and every stack in the bag looks like junk -- including the sticks a wooden
     * pickaxe is four clicks from being built out of. The chain's real shape is only visible in
     * what it has ASKED for and not yet SPENT.
     *
     * <p>So {@link #craft} adds a recipe's whole bill here before it obtains anything and takes
     * it off again only once the craft it was for has actually been driven. An item in the bag
     * AND in this ledger is being held for a craft that has not run; an item in the bag that is
     * not in this ledger is one nothing is waiting on.
     *
     * <p><b>Counted rather than a stack of bills,</b> because nested crafts owe the same item at
     * the same moment: the wooden pickaxe owes 3 planks and 2 sticks while the sticks craft owes
     * 2 of those same planks, so the sticks craft finishing first must not declare the pickaxe's
     * planks spent. The value is the number of live bills, never a quantity of items -- only
     * MEMBERSHIP has to be conservative, and only membership is ever asked.
     */
    private final Map<String, Integer> owed = new LinkedHashMap<>();

    /** Every item any outstanding craft bill still owes, at its largest remaining count. */
    private Set<String> owedItems() {
        return new LinkedHashSet<>(owed.keySet());
    }

    /** The top-level item {@link #obtain} was asked for. Never throwable: see {@code chooseSlotToThrow}. */
    private String goal;

    /**
     * How many of the player's 36 slots are in hand ({@code InventoryPlayer.currentItem} indexes
     * {@code mainInventory[0..8]}). Named once so "prefer the pack over the bar" is a single
     * boundary rather than a 9 that appears in two places.
     */
    private static final int HOTBAR_SLOTS = 9;

    /** Recipes currently being satisfied, so a cycle in the recipe graph terminates. */
    private final Set<String> inProgress = new HashSet<>();

    public GoalPolicy(SimWorld world, EvalHarness harness) {
        this.world = world;
        this.harness = harness;
    }

    /** Everything the policy decided and everything the world said back, in order. */
    public List<String> trace() {
        return List.copyOf(trace);
    }

    /** The run as one line, for a task's report field. */
    public String traceSummary() {
        return trace.isEmpty() ? "(the policy did nothing)" : String.join(" | ", trace);
    }

    /**
     * The name a task's report prints for this decision.
     *
     * <p>Present because {@link BrokenPolicies} overrides it, and a report that names one
     * decision by what it is and the other by {@code GoalPolicy@6d06d69c} is a report a reader
     * has to interpret. Not part of {@link Policy}: the interface stays one method wide, and
     * every implementation of {@code toString} here is about legibility of a diagnostic, not
     * about behaviour.
     */
    @Override
    public String toString() {
        return "GoalPolicy (the reference decision: derives its chain from the recipe table)";
    }

    private void note(String what) {
        trace.add(what);
    }

    // ===== the one entry point =====

    /** Try to hold {@code item}, however many steps that takes. */
    public boolean obtain(String item) {
        return obtain(item, 1);
    }

    /**
     * Try to hold at least {@code count} of {@code item}.
     *
     * <p>The recursion is the measurement. Ingredients are obtained by this same method, so a goal
     * three recipes deep is three real crafts and however many walks and digs those cost, and
     * nothing in this file knows that in advance.
     *
     * <p><b>Only a CYCLE is remembered, and nothing else is.</b> The first version cached every
     * failure for the length of the run, which quietly turned a question asked at one moment into
     * an answer for all time. Measured on the tool-economy task: the first attempt to obtain
     * cobblestone happened WHILE the stone pickaxe that harvests it was itself being obtained, hit
     * the cycle guard, and cached the refusal. By the time the pickaxe existed and bare hands plus
     * a pickaxe could plainly mine the stone, the top-level retry consulted the cache and gave up
     * without asking the world again. The world had changed and the verdict had not.
     *
     * <p>So the cycle guard -- which exists to stop genuine recursion, not to record an answer --
     * is what is remembered, and a failure that came from an exhausted recipe list or an
     * unminable world is asked again on the next call. Re-asking costs a walk; caching a stale
     * refusal costs the run, and only one of those is a bug.
     */
    public boolean obtain(String item, int count) {
        String key = item + " x" + count;
        // Recorded on every entry, not once, because "the run's goal" is the outermost call's
        // item and the outermost call is the only one that knows it. An INGREDIENT of the chain
        // is protected by the owed ledger instead; the goal itself is protected by nothing else,
        // and it is the one stack that must never go.
        if (goal == null) {
            goal = item;
        }
        if (world.count(item) >= count) {
            return true;
        }
        if (deadEnds.contains(key)) {
            return false;
        }
        if (!inProgress.add(key)) {
            note("the recipe graph cycles: " + key + " is already being obtained, so it cannot be"
                    + " satisfied from inside itself");
            // The ONLY verdict that outlives the call, and only because re-asking it inside the
            // same chain would recurse. It is keyed on the exact request, so a different count --
            // which is what a later, differently-sized demand asks -- is still tried.
            deadEnds.add(key);
            return false;
        }
        try {
            return obtainInner(item, count);
        } finally {
            inProgress.remove(key);
        }
    }

    private boolean obtainInner(String item, int count) {
        Craft.Result recipes = Craft.recipesFor(item);
        if (!recipes.recipes().isEmpty()) {
            note("the recipe table makes " + item + " (" + recipes.recipes().size() + " way(s))");
            for (RecipeView r : recipes.recipes()) {
                if (craft(r)) {
                    return true;
                }
            }
            note("no recipe for " + item + " could be satisfied from what can be obtained");
            return false;
        }
        note("no recipe makes " + item + ", so it has to come out of the ground");
        // Repeated, because a dig yields ONE item. The first version mined a single block and then
        // asked whether it had enough, so a demand for three cobblestone could never be met no
        // matter how much stone stood around it -- a chain that dies on arithmetic rather than on
        // anything the controllers did.
        while (world.count(item) < count) {
            // Asked BEFORE the dig, and it stops the loop -- but not permanently. A full bag is
            // a real situation, not a verdict about the world, so the first thing tried is the
            // one move that changes it: throw a stack this chain is not waiting on, and ask
            // again. Only when there is nothing left that can be thrown away does this become the
            // refusal it used to be, which is the right answer THEN and was the only answer
            // before. Measured on the full-inventory task: the refusal cost the whole run,
            // because no rule in this file could put a stack back on the floor.
            while (world.freeSlots() == 0) {
                if (!makeRoom(item)) {
                    note("the inventory has 0 of 36 slots free and nothing in it can be thrown"
                            + " away without losing what this chain still needs, so mining " + item
                            + " would remove it from the world with nowhere to put it"
                            + " -- not mining any further");
                    return false;
                }
            }
            int before = world.count(item);
            if (!mine(item)) {
                break;
            }
            if (world.count(item) <= before) {
                note("mining " + item + " gained nothing, so more of it will not help either");
                break;
            }
        }
        if (world.count(item) >= count) {
            return true;
        }
        return false;
    }

    // ===== making room =====

    /**
     * Empty one inventory slot by THROWING a stack away, and report what the world said back.
     *
     * <p>One stack per call, never a whole category. A player who needs a slot drops one stack
     * of the thing they can most afford to lose and then carries on; a policy that empties every
     * dirt stack to gain 27 slots has made a decision nobody made, and the cost of being wrong
     * about one of them is the whole run. So the caller loops and re-asks, which is exactly the
     * shape of a person emptying a bag one handful at a time.
     *
     * <p>The verb is the real one: {@link InteractIntent#dropStack} through the production
     * INTERACT slot, driven by the production {@link net.marcloud.mcp.core.drivers.act.DropController},
     * which confirms the slot is empty before this returns true. Nothing here reaches into the
     * inventory to make the number change.
     */
    private boolean makeRoom(String wanted) {
        return makeRoom(wanted, 0, DropController.SLOT_COUNT - 1,
                "any of the 36 inventory slots");
    }

    /**
     * As above, but out of a NAMED RANGE of slots.
     *
     * <p>The range exists because "the bag is full" is two different problems and one fix does
     * not answer both. A full bag blocks a DIG because the drop has nowhere to land; it blocks
     * {@link #toHotbar} because there is no free slot IN THE BAR to drag into. Measured on the
     * full-inventory task: the first drop worked and the run still died, because the next
     * {@code toHotbar} reported "no free HOTBAR slot to move planks into", {@code craft} returned
     * false on a recipe that had in fact succeeded, and {@code obtainInner} walked on to the
     * next recipe for planks -- a Forge variant wanting {@code log2}, which nothing in the world
     * drops. A drop from the bar is a real cost, which is why the range is passed in rather than
     * the caller reaching for the widest possible throw.
     */
    private boolean makeRoom(String wanted, int loSlot, int hiSlot, String where) {
        int slot = chooseSlotToThrow(wanted, loSlot, hiSlot);
        if (slot < 0) {
            note("nothing in " + where + " can be thrown away without losing what this chain"
                    + " still needs, so there is no way to make room for " + wanted);
            return false;
        }
        String name = world.slotName(slot);
        int had = world.slotCount(slot);
        note("the bag is full and " + name + " x" + had + " in slot " + slot
                + " is not on any bill this chain still owes, so it is being thrown away to make"
                + " room for " + wanted);
        harness.submit(InteractIntent.dropStack(slot));
        harness.runInteract(60);
        boolean emptied = world.slotCount(slot) == 0;
        note("drop of slot " + slot + ": interact slot " + harness.phaseOf(ActSlot.INTERACT)
                + ", the slot now holds " + (emptied ? "nothing" : world.slotName(slot) + " x"
                + world.slotCount(slot) + " (STILL THERE)")
                + ", free slots " + world.freeSlots() + " of 36, thrown so far "
                + world.thrownByPlayer());
        return emptied;
    }

    /**
     * Which player inventory slot, if any, may be thrown away right now. -1 means none.
     *
     * <p><b>Which stack is chosen, and how "still needed" is decided rather than assumed.</b> A
     * slot is throwable only if its item is in NONE of these three sets:
     *
     * <ol>
     *   <li><b>{@code inFlight}</b> -- every item some {@link #obtain} call in this stack is
     *       currently working toward. Those are the chain's own live requests; an item on one is
     *       wanted by definition and no amount of it being surplus makes it junk.</li>
     *   <li><b>{@link #owedItems()} -- every item an outstanding craft bill still names.</li>
     *       This is the one that a recipe-tree view gets wrong. {@code craft(r)} adds
     *       {@code r}'s whole demand before it obtains anything and removes it only after the
     *       craft has run, so sticks gathered for a wooden pickaxe are still owed while the bag is
     *       full -- and the goal's own tree (cobblestone has no recipe) would call them junk.
     *   <li><b>{@code goal}</b> and {@code wanted} -- the thing the caller asked for, and the
     *       thing this particular dig is for. Throwing away the goal to make room for the goal is
     *       not a trade, it is a loop.</li>
     * </ol>
     *
     * <p><b>Order, once a slot is throwable at all. Three rules, all about what a human does:</b>
     *
     * <ul>
     *   <li><b>Never the slot in hand.</b> {@code canHarvestWithHeld} reads
     *       {@code getCurrentItem()}, so a drop of the held stack can turn the next dig from
     *       possible into impossible -- and the policy that just made room would be the reason.</li>
     *   <li><b>Most copies first.</b> Ranked by how many of that item are in the WHOLE bag, not by
     *       the slot number: throwing a sixty-fourth of thirty-six stacks of dirt costs nothing,
     *       and throwing the one plank out of a bag that already holds thirty-five stacks of dirt
     *       costs the run. A rule that named items would be a second copy of the recipe table;
     *       this one asks the bag, which is where the answer was all along.</li>
     *   <li><b>Main inventory before the hotbar, then the highest slot.</b> The nine in hand are
     *       the working set: the tool that makes stone harvestable, the blocks the craft chain is
     *       about to place. The twenty-seven above are overflow by construction, since
     *       {@code InventoryPlayer.getFirstEmptyStack} fills the bar first. The slot number is
     *       only the LAST tie-break, because between two equally throwable stacks it is
     *       arbitrary, and an arbitrary order wearing a decision's clothes is the thing this file
     *       keeps having to undo.</li>
     * </ul>
     *
     * <p><b>What this rule cannot know, stated rather than hidden.</b> The protected set is what
     * the chain has ASKED for, so a stack of something the run has not yet committed to is
     * throwable -- including, on a bag that starts full of planks, a plank that the very next
     * step will need. The protection is therefore not omniscient; it is CONSERVATIVE and BOUNDED.
     * The bound is what stops the guessing from compounding: once the chain declares an item, it
     * is protected for good, {@link #makeRoom} finds nothing throwable, and the policy refuses
     * with the reason written down instead of eating its own supply one stack at a time. A
     * speculative drop can cost one stack. It cannot cost the run silently.
     */
    private int chooseSlotToThrow(String wanted, int loSlot, int hiSlot) {
        Set<String> keep = inFlightItems();
        keep.addAll(owedItems());
        keep.add(goal);
        keep.add(wanted);
        int from = Math.max(0, loSlot);
        int to = Math.min(DropController.SLOT_COUNT - 1, hiSlot);
        // Ranked, not first-found. The key is deliberately the item, so every slot holding the
        // chosen item scores identically and the slot number only breaks that tie; that is what
        // makes "the one plank" lose to "the thirty-sixth dirt" for a reason rather than for the
        // accident of which index the plank happened to land in.
        int best = -1;
        int bestCopies = -1;
        for (int slot = from; slot <= to; slot++) {
            String name = world.slotName(slot);
            if (name == null || world.slotCount(slot) <= 0 || keep.contains(name)) {
                continue;
            }
            if (slot == world.heldSlot()) {
                // In hand, so a dig that follows would read an empty hand and stop being a dig.
                continue;
            }
            int copies = world.count(name);
            boolean overflow = slot >= HOTBAR_SLOTS;
            boolean bestOverflow = best < 0 || slot >= HOTBAR_SLOTS;
            if (best < 0
                    || copies > bestCopies
                    || (copies == bestCopies && overflow && !bestOverflow)
                    || (copies == bestCopies && overflow == bestOverflow && slot > best)) {
                best = slot;
                bestCopies = copies;
            }
        }
        return best;
    }

    /** Every item an {@link #obtain} call in this stack is currently working toward. */
    private Set<String> inFlightItems() {
        Set<String> names = new LinkedHashSet<>();
        for (String key : inProgress) {
            int at = key.lastIndexOf(" x");
            names.add(at < 0 ? key : key.substring(0, at));
        }
        return names;
    }

    /** Record {@code r}'s whole bill as owed, counting it against any bill already naming an item. */
    private void oweDemand(RecipeView r) {
        for (RecipeView.Ingredient need : r.demand()) {
            owed.merge(need.item(), 1, Integer::sum);
        }
    }

    /**
     * Withdraw {@code r}'s bill now that its craft has run.
     *
     * <p><b>Counted, not popped, and the count is the whole correctness of this file's one
     * destructive decision.</b> The wooden pickaxe and the sticks craft both owe planks at the
     * same moment -- the pickaxe's bill is added before its ingredients are obtained, and the
     * sticks craft's is added while they are -- so the sticks craft finishing first must not
     * declare the pickaxe's planks spent. A stack-and-pop ledger would, and the next time the
     * bag filled up the policy would be free to throw away the planks it was four clicks from
     * spending.
     */
    private void forgiveDemand(RecipeView r) {
        for (RecipeView.Ingredient need : r.demand()) {
            int bills = owed.getOrDefault(need.item(), 0) - 1;
            if (bills <= 0) {
                owed.remove(need.item());
            } else {
                owed.put(need.item(), bills);
            }
        }
    }

    /**
     * Obtain everything ONE craft of {@code r} consumes, ingredient by ingredient.
     *
     * <p><b>Each ingredient is asked for in FULL, not for the shortfall.</b> Taking the recipe as
     * a one-time bill is the bug this comment exists to record: crafting consumes the ingredients,
     * so a policy that tops up only the gap ends up one craft short every time. It read "2 planks
     * short of 4" as "get 2 more", crafted, spent all 4, and then discovered on the next recipe
     * that it had none -- a chain that died of bookkeeping while every controller it touched had
     * behaved correctly.
     *
     * <p><b>And the bill is re-read until it stops moving.</b> Satisfying one ingredient can SPEND
     * another, because obtaining an ingredient is itself a chain: a wooden pickaxe wants three
     * planks and two sticks, the planks are obtained first and leave four in the bag, and then the
     * sticks are obtained -- which spends two of those same planks to make them. A single pass had
     * already decided the pickaxe was satisfied by the time the sticks took the planks back out, so
     * the craft that followed failed on an arithmetic gap while every controller it touched had
     * behaved correctly. That is the same class of death as the shortfall bug above, one level
     * further down: a chain that dies of bookkeeping.
     *
     * <p>So the loop runs until a whole pass obtains nothing, which is the only fixed point that
     * means anything here: every ingredient is present at the same time, which is the state the
     * craft needs. The pass cap is not the termination argument -- a pass that obtains something
     * makes the demanded counts grow, and one that obtains nothing ends the loop -- it is a bound on
     * how long a pathological recipe graph can spend before the answer is "not reachable", which
     * {@code obtain} reports honestly rather than by exhausting the loop.
     */
    private boolean haveIngredientsFor(RecipeView r) {
        for (int pass = 0; pass < MAX_INGREDIENT_PASSES; pass++) {
            boolean obtainedAny = false;
            for (RecipeView.Ingredient need : r.demand()) {
                int have = world.count(need.item());
                if (have >= need.count()) {
                    continue;
                }
                note("short " + (need.count() - have) + " " + need.item() + " for " + r.output()
                        + " (need " + need.count() + ", have " + have
                        + "), and a craft SPENDS them");
                if (!obtain(need.item(), need.count())) {
                    note("so " + r.output() + " is out of reach");
                    return false;
                }
                obtainedAny = true;
            }
            if (!obtainedAny) {
                return true;
            }
        }
        note("the ingredients for " + r.output() + " would not settle after " + MAX_INGREDIENT_PASSES
                + " passes -- each one this recipe asks for is being spent by obtaining another, so "
                + r.output() + " is out of reach");
        return false;
    }

    // ===== the three rules =====

    /**
     * Rule 1: craft it, or mine it.
     *
     * <p><b>The grid is opened BEFORE the ingredients are reserved, and the order is the whole
     * trick.</b> Making a bench is itself a craft, and that craft SPENDS planks -- four of them,
     * out of the same pile the recipe being attempted also draws from. The first version reserved
     * the recipe's ingredients and only then went looking for a grid, so building the bench left
     * the pickaxe one plank short and the craft failed at the last step with the whole chain
     * already working. Opening first means the bench is paid for out of what is left, and
     * {@link #haveIngredientsFor} then tops the remainder back up.
     *
     * <p>Nothing here is a special case for benches. {@link #gridFor} asks the world for a grid
     * wide enough, and growing one from nothing -- four planks, a placement and a right-click --
     * is what the world says is required.
     */
    private boolean craft(RecipeView r) {
        // Any screen already open is set aside rather than closed, because getting a grid is
        // itself a craft and that craft opens its own. A recursive obtain() therefore nests
        // windows: the outer bench must survive the inner planks craft. The first version closed
        // unconditionally, so the inner craft tore down the outer window and the outer craft then
        // clicked a screen that no longer existed -- which the window reported as the server
        // refusing a placement, blaming a server that had never been asked.
        SimCraftWindow outer = world.openWindow();
        setScreen(null);
        int need = Math.max(r.width(), r.height());
        CraftWindow win = gridFor(r, need);
        if (win == null) {
            note("no grid is available for " + r.output() + ", so it cannot be crafted");
            setScreen(outer);
            return false;
        }
        oweDemand(r);
        try {
            if (!haveIngredientsFor(r)) {
                setScreen(outer);
                return false;
            }
            note("crafting " + r.outputCount() + "x " + r.output() + " in a " + win.gridWidth()
                    + "x" + win.gridWidth() + " grid");
            CraftOutcome out = driveNewCraft(r, (SimCraftWindow) win);
            boolean ok = out != null && out.ok();
            note("craft " + r.output() + " -> "
                    + (out == null ? "never reached a verdict" : out.message()));
            // Close only what this craft opened, then put back whatever was there before it.
            if (win != outer) {
                closeScreen();
            }
            setScreen(outer);
            if (!ok) {
                return false;
            }
            return toHotbar(r.output());
        } finally {
            // The bill stops being owed only here, once the craft it was for has RUN. An
            // ingredient that has been gathered but not yet spent is still wanted, and a drop
            // that ignored that would take the planks a pickaxe was about to be built from.
            forgiveDemand(r);
        }
    }

    /**
     * Drive one {@link CraftController} to a verdict, ticking the window's own clock.
     *
     * <p>The window advance is the round trip. {@code CraftController} waits
     * {@code SETTLE_TICKS} before it re-reads the matrix, and those are the ticks in which a
     * server's answer would arrive; driving the controller without them tests a machine that can
     * never learn it was refused.
     */
    private CraftOutcome driveNewCraft(RecipeView r, SimCraftWindow win) {
        CraftController c = new CraftController(r);
        CraftOutcome out = null;
        for (int i = 0; i < 300 && (out == null || !out.terminal()); i++) {
            out = c.tick(win);
            win.advanceTick();
        }
        return out;
    }

    /**
     * The narrowest grid that can hold this recipe: the player's own 2x2, or a bench.
     *
     * <p>Asked of the world rather than of the recipe, so a bench is found, or made and placed,
     * or the craft is reported impossible -- and the middle answer runs the whole craft-a-table
     * chain, which is where composition either happens or does not. {@code need} is the recipe's
     * own width and height, so this stays a question about geometry and not about item names.
     */
    private CraftWindow gridFor(RecipeView r, int need) {
        if (need <= SimCraftWindow.playerWindow(world).gridWidth()) {
            return SimCraftWindow.playerWindow(world);
        }
        return aBench();
    }

    /**
     * An open 3x3 grid: an existing bench, or one made, carried, placed and opened.
     *
     * <p>Placing is a real placement -- the table has to be in the HOTBAR and clicked against a
     * real face -- because that is the only way a bench comes into existence on a live client, and
     * a bench conjured from thin air would skip the step this task exists to exercise.
     */
    private CraftWindow aBench() {
        Cell existing = nearestBlock("crafting_table");
        if (existing == null) {
            note("no crafting table stands within " + SEARCH_RADIUS + " blocks, so one has to be made");
            if (!obtain("crafting_table") || !toHotbar("crafting_table")) {
                note("so there is no way to get a bench, and every 3x3 recipe is out of reach");
                return null;
            }
            Cell spot = placeHere("crafting_table");
            if (spot == null) {
                note("could not place the crafting table anywhere within reach");
                return null;
            }
            existing = new Cell(spot.x, spot.y, spot.z, "crafting_table");
        }
        return openAt(existing);
    }

    /** Walk to a block and right-click it, which is how a bench's window opens. */
    private CraftWindow openAt(Cell bench) {
        note("found a crafting table at " + bench);
        Stance beside = stanceBeside(bench.x, bench.y, bench.z);
        if (beside == null || !walkTo(beside, "the crafting table")) {
            note("so the bench cannot be reached and its window cannot be opened");
            return null;
        }
        harness.submit(InteractIntent.place(bench.x, bench.y, bench.z,
                ActActuator.Face.UP.index(), 0.5D, 0.5D, 0.5D));
        harness.runInteract(60);
        SimCraftWindow win = world.openWindow();
        note("right-clicked the bench: interact slot " + harness.phaseOf(ActSlot.INTERACT)
                + ", the world now reports a "
                + (win == null ? "closed" : win.gridWidth() + "x" + win.gridWidth()) + " window");
        return win;
    }

    /**
     * Rule 2: mine it -- and get a tool first if nothing in hand can harvest it.
     *
     * <p>The escalation is the interesting half and it is stated as a general rule, not as a
     * special case for stone. A block that will not drop for the current tool is found by asking
     * the world for the cell regardless of the gate; if the held stack cannot harvest it, the item
     * registry is asked which items CAN, and the cheapest buildable one is obtained. So "I need a
     * pickaxe" is never written here -- it falls out of {@code canHarvestBlock} and the recipe
     * table, and a goal that needed an axe or a shovel would escalate the same way.
     *
     * <p><b>The target is looked up AGAIN after the escalation, and it has to be.</b> Building a
     * tool is itself a chain that DUGS -- the cobblestone for a stone pickaxe comes out of the
     * ground, out of whichever stone cell was nearest at that moment. Measured on the tool-economy
     * task: the policy resolved an outcrop cell, spent it and two more on building the pickaxe,
     * then walked back to dig a cell that was already air, dug nothing, and reported "mining
     * cobblestone gained nothing" while standing beside unmined stone with the right tool in hand.
     * It read as a dead end and the run gave up, on a world full of the thing it wanted.
     *
     * <p>So the lookup is repeated once the tool is in hand, the first moment the answer can
     * differ. The escalation is not a side effect to be tolerated -- it is this policy's own
     * digging, and it changes exactly the state this method reads.
     */
    private boolean mine(String item) {
        // Said out loud before anything else, because once the footing is gone every later message
        // is a lie told by the planner on the agent's behalf.
        if (footingIsGone()) {
            return false;
        }
        Cell ownFloor = floorUnderfoot();
        Cell target = nearestUngatedDrop(item, ownFloor);
        if (target == null) {
            note("nothing within " + SEARCH_RADIUS + " blocks drops " + item + ", even ungated");
            return false;
        }
        if (!canHarvestWithHeld(target) && !acquireAToolFor(target)) {
            return false;
        }
        // Re-resolved, because acquiring the tool dug the ground this target was found in, and
        // because the walk to get the tool may have moved the body off its own floor onto another.
        Cell afterTool = nearestUngatedDrop(item, floorUnderfoot());
        if (afterTool == null) {
            note("nothing within " + SEARCH_RADIUS + " blocks drops " + item
                    + " any more, even ungated -- the world changed while a tool was being built");
            return false;
        }
        if (!afterTool.equals(target)) {
            note("the tool build moved the ground under " + target + ", so the nearest " + item
                    + " is now " + afterTool);
        }
        Stance beside = stanceBeside(afterTool.x, afterTool.y, afterTool.z);
        if (beside == null) {
            note("no standable cell next to " + afterTool + " to dig it from");
            return false;
        }
        if (!walkTo(beside, "the " + afterTool.name)) {
            return false;
        }
        return dig(afterTool);
    }

    /**
     * Whether this block can be harvested with what is in hand right now.
     *
     * <p><b>This asks vanilla's question, in full.</b> The first version asked only
     * {@code held.canHarvestBlock(block)} and reported that a PICKAXE was needed to break a log --
     * a block a player breaks bare-handed in the first ten seconds of the game, and one this same
     * class went on to fail to mine. The missing half is the material test: {@code Block.isMined}
     * (and {@code SimWorld.canHarvest}, which is this harness's copy of the same rule) drops
     * anything whose material does not require a tool regardless of what is held, and only gates
     * the rest. Wood does not require a tool; iron ore does.
     *
     * <p>The cost of getting this wrong was not a red gate, which is the dangerous kind of wrong.
     * It made the policy conclude that no tool could be built, because the one ingredient every
     * pickaxe needs -- a log -- appeared to be unobtainable, and a chain that dies at step two
     * teaches nothing about the controllers it never reached.
     */
    private boolean canHarvestWithHeld(Cell c) {
        Block block = SimWorld.block(c.name);
        if (block.getMaterial().isToolNotRequired()) {
            return true;
        }
        ItemStack held = world.inventory().getCurrentItem();
        return held != null && held.stackSize > 0 && held.canHarvestBlock(block);
    }

    /**
     * Obtain something that can harvest {@code c}.
     *
     * <p><b>The first version of this walked the registry and took the first hit, and it was wrong
     * in a way worth recording.</b> Registry order is arbitrary, and for iron ore it starts at
     * {@code iron_pickaxe} -- which needs three iron ingots, which need a furnace this policy
     * cannot use. So the run died on a tool far more expensive than the goal required, and the
     * failure said nothing at all about the controllers. That is a defect in the POLICY, not a
     * finding about the agent, and it is fixed here rather than reported as one.
     *
     * <p>The replacement is still derived and still names no tool: every craftable item that
     * harvests the block is costed by <b>whether its ingredients can actually be obtained</b>, and
     * the cheapest is built. For iron ore that ranks the stone pickaxe (cobblestone and sticks,
     * both minable here) below the iron and diamond ones (ingots and diamonds, which are not), so
     * the run builds a stone pickaxe without this file ever having heard of stone.
     *
     * <p>Costing by real obtainability rather than by a hand-written tool tier is what keeps the
     * rule general. A tier list would be a second copy of the recipe table, to be updated per
     * block; asking the recipe graph which leaves are reachable is the same question the rest of
     * this class already asks.
     */
    private boolean acquireAToolFor(Cell c) {
        Block block = SimWorld.block(c.name);
        note("nothing in hand harvests " + c.name + ", so a tool is needed first");
        List<String> candidates = new ArrayList<>();
        for (Item candidate : net.minecraft.item.Item.itemRegistry) {
            if (candidate == null) {
                continue;
            }
            String name = SimWorld.nameOf(candidate);
            if (!new ItemStack(candidate).canHarvestBlock(block)) {
                continue;
            }
            if (Craft.recipesFor(name).recipes().isEmpty()) {
                // No recipe, so it cannot be built at all. This policy mines and crafts but does
                // not smelt, so an ingot- or diamond-bound tool is out of reach at any price.
                continue;
            }
            candidates.add(name);
        }
        if (candidates.isEmpty()) {
            note("no craftable item in the registry harvests " + c.name + ", so nothing this policy"
                    + " can build will break it");
            return false;
        }
        note("the registry offers " + String.join(", ", candidates) + " for " + c.name
                + "; costing each by how reachable its ingredients are");
        candidates.sort(Comparator.comparingInt(this::toolCost));
        for (String name : candidates) {
            note("trying " + name + " (cost " + toolCost(name) + ")");
            if (obtain(name) && toHotbar(name)) {
                note("built " + name + " and it is in hand (" + world.slotName(world.heldSlot())
                        + "), which harvests " + c.name + " = "
                        + new ItemStack(SimWorld.item(name)).canHarvestBlock(block));
                return true;
            }
            note(name + " could not be obtained, so trying the next one");
        }
        note("no tool that harvests " + c.name + " could be built from what this world offers");
        return false;
    }

    /**
     * How hard a tool is to build, counted over its recipe's whole ingredient tree.
     *
     * <p>Depth-limited on purpose. The recipe graph genuinely cycles here -- this client's
     * {@code CraftingManager} is Forge-flavoured and registers BOTH directions for several items,
     * so iron_ingot wants iron_block and iron_block wants nine iron_ingot, and an unbounded cost
     * function would recurse forever. A hang is a worse failure than an approximate number, so the
     * recursion stops and charges a large finite price, which is enough to sort a smelt-bound tool
     * last.
     *
     * <p><b>A minable leaf is priced by whether this policy can actually harvest it right now,
     * which is what the flat 1 got wrong.</b> Measured on the tool-economy task: the cost function
     * priced {@code cobblestone} at 1 (no recipe, therefore "cheap") and {@code planks} at 2, so it
     * ranked the stone pickaxe CHEAPER than the wooden one and tried to build the very tool whose
     * ingredient needed that tool. The number it produced -- stone_pickaxe 14 against wooden 17 --
     * was not merely a close call, it was the wrong order, and the run ended up building BOTH
     * pickaxes, spending the second one's planks and logs on a tool it no longer needed.
     *
     * <p>The harvest gate is the question the rest of this class already asks, so the cost
     * function now asks it too: a leaf that needs a tool this run cannot hold yet is not free, it
     * is the thing being built. Charging it the depth cap puts every tool behind the one whose
     * ingredients are gated on it, which is the ordering the world actually imposes.
     */
    private int toolCost(String tool) {
        return recipeCost(tool, 0);
    }

    /** The price charged for a leaf this policy cannot harvest with what it holds. */
    private static final int GATED_LEAF_COST = 1000;

    /** The recursion's own ceiling, shared so the two numbers cannot drift apart. */
    private static final int MAX_RECIPE_DEPTH = 6;

    private int recipeCost(String item, int depth) {
        if (depth > MAX_RECIPE_DEPTH) {
            return GATED_LEAF_COST;
        }
        var recipes = Craft.recipesFor(item).recipes();
        if (recipes.isEmpty()) {
            // No recipe means it has to be dug. Whether the dig will actually yield depends on the
            // HARVEST GATE, not on the recipe table, so that is what is asked -- and a leaf gated
            // behind a tool this run does not hold yet is the very tool being costed, so charging
            // it the depth cap is what puts the cheap-looking pickaxe behind the wooden one.
            return minableNow(item) ? 1 : GATED_LEAF_COST;
        }
        int best = Integer.MAX_VALUE;
        for (RecipeView r : recipes) {
            int sum = 1;
            for (RecipeView.Ingredient need : r.demand()) {
                sum += need.count() * recipeCost(need.item(), depth + 1);
            }
            best = Math.min(best, sum);
        }
        return best == Integer.MAX_VALUE ? GATED_LEAF_COST : best;
    }

    /**
     * Whether some block in reach drops {@code item} and can be harvested with what is in hand.
     *
     * <p>Two questions, not one, and the second is the one the old flat price skipped: the block
     * must EXIST near the player, and the held stack must be allowed to harvest it. Asking only
     * whether the block exists prices cobblestone at 1 for a stone pickaxe whose ingredients are
     * three cobblestone -- the definition of a tool that cannot be built first.
     */
    private boolean minableNow(String item) {
        Cell cell = nearestUngatedDrop(item);
        return cell != null && canHarvestWithHeld(cell);
    }

    /**
     * Rule 3a: get the item into the HOTBAR.
     *
     * <p>Not a convenience. A dig's harvest test reads {@code getCurrentItem()}, which is a hotbar
     * slot, so a pickaxe sitting in the main inventory is a pickaxe that does not work. Crafting
     * puts its output in the first free slot, which is slot 9, so this runs after every craft and
     * is the step where "I made the thing" and "I can use the thing" come apart.
     */
    private boolean toHotbar(String item) {
        for (int slot = 0; slot < 9; slot++) {
            if (item.equals(world.slotName(slot)) && world.slotCount(slot) > 0) {
                if (world.heldSlot() != slot) {
                    harness.submit(InteractIntent.hotbar(slot));
                    harness.runInteract(60);
                    note("selected hotbar slot " + slot + " holding " + item + ": interact slot "
                            + harness.phaseOf(ActSlot.INTERACT) + ", in hand now "
                            + world.slotName(world.heldSlot()));
                }
                return true;
            }
        }
        // Not in the bar: move it there through the container, which is what a player drags with.
        SimCraftWindow win = SimCraftWindow.playerWindow(world);
        int source = -1;
        for (int slot : win.storageSlots()) {
            var held = win.stackAt(slot);
            if (held != null && held.item().equals(item)) {
                source = slot;
                break;
            }
        }
        if (source < 0) {
            note("no slot holds " + item + ", so it cannot be moved into the hotbar");
            return false;
        }
        // Into the HOTBAR specifically, not merely into the first empty slot. The container lists
        // main inventory 9..35 BEFORE the hotbar, so "first empty" is a main-inventory slot and the
        // drag lands the item one row away from where a dig can see it. The first version did
        // exactly that and then reported the move as failed, having moved nothing.
        int dest = freeHotbarContainerSlot(win);
        if (dest < 0) {
            // The full bag has a second face, and refusing here loses the run rather than a step.
            // Returning false makes `craft` report failure on a recipe that had already SUCCEEDED,
            // and `obtainInner` then walks on to the NEXT recipe for the same item -- on the
            // full-inventory task that was a Forge variant of the planks recipe wanting `log2`,
            // which nothing in the world drops, so the chain died on a phantom ingredient.
            //
            // So the room is made, out of the BAR, because the bar is what is full: 27 overflow
            // slots elsewhere would not have made this particular drag land.
            note("no free HOTBAR slot to move " + item + " into and the bar itself is full, so a"
                    + " stack from the bar has to go before the drag can land");
            if (!makeRoom(item, 0, HOTBAR_SLOTS - 1, "the 9 HOTBAR slots")) {
                note("so " + item + " stays where it is, out of reach of any dig that reads"
                        + " getCurrentItem()");
                return false;
            }
            dest = freeHotbarContainerSlot(win);
        }
        if (dest < 0) {
            note("the bar still reads no free slot after dropping from it, so " + item
                    + " cannot be moved into the hotbar");
            return false;
        }
        win.click(source, CraftWindow.LEFT);
        win.click(dest, CraftWindow.LEFT);
        note("dragged " + item + " from container slot " + source + " to the hotbar slot " + dest);
        int landed = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (item.equals(world.slotName(slot))) {
                landed = slot;
                break;
            }
        }
        if (landed < 0) {
            note("the drag did not land " + item + " in the hotbar; inventory is now "
                    + world.describeInventory());
            return false;
        }
        harness.submit(InteractIntent.hotbar(landed));
        harness.runInteract(60);
        note("selected hotbar slot " + landed + ": in hand now " + world.slotName(world.heldSlot()));
        return true;
    }

    /**
     * Whether a container slot is one of the nine HOTBAR slots.
     *
     * <p>Read off the window rather than assumed: {@code storageSlots()} returns
     * {@code storageStart..+35}, and the mapping back to {@code mainInventory} is main inventory
     * first and hotbar last, so the last nine are the bar. This is the same inversion T11 exists
     to pin, and getting it backwards is how an item ends up moved to a slot no dig can use.
     */
    private static boolean isHotbarSlot(SimCraftWindow win, int containerSlot) {
        int[] storage = win.storageSlots();
        return containerSlot >= storage[storage.length - 9];
    }

    /**
     * The first container slot that is a free HOTBAR cell, or -1 when the bar is full.
     *
     * <p>Re-asked rather than remembered, because the slot a drag should land in is a fact about
     * the window at this instant, and {@link #makeRoom} may have just changed it. Reading
     * {@code win.stackAt} rather than {@code world.slotName} is the point: the window is what the
     * click is aimed at, so a slot the window still shows as occupied is occupied.
     */
    private static int freeHotbarContainerSlot(SimCraftWindow win) {
        for (int slot : win.storageSlots()) {
            if (isHotbarSlot(win, slot) && win.stackAt(slot) == null) {
                return slot;
            }
        }
        return -1;
    }

    /**
     * Rule 3b: put a placeable block into the world next to the player.
     *
     * <p>Aims at a real solid face with an empty cell in front of it, which is the whole of what
     * {@code rightClickBlock} needs; anything else and the click is refused by the world, correctly.
     */
    private Cell placeHere(String item) {
        if (!item.equals(world.slotName(world.heldSlot()))) {
            note("not holding " + item + ", so it cannot be placed");
            return null;
        }
        Stance feet = world.stance();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (ActActuator.Face face : new ActActuator.Face[] {ActActuator.Face.UP,
                        ActActuator.Face.NORTH, ActActuator.Face.SOUTH, ActActuator.Face.EAST,
                        ActActuator.Face.WEST}) {
                    int sx = feet.x() + dx;
                    int sy = feet.y();
                    int sz = feet.z() + dz;
                    if (!world.isSolid(sx, sy, sz)) {
                        continue;
                    }
                    int tx = sx + (face == ActActuator.Face.EAST ? 1
                            : face == ActActuator.Face.WEST ? -1 : 0);
                    int ty = sy + (face == ActActuator.Face.UP ? 1
                            : face == ActActuator.Face.DOWN ? -1 : 0);
                    int tz = sz + (face == ActActuator.Face.SOUTH ? 1
                            : face == ActActuator.Face.NORTH ? -1 : 0);
                    if (!world.canPlaceAt(tx, ty, tz)) {
                        continue;
                    }
                    harness.submit(InteractIntent.place(sx, sy, sz, face.index(),
                            0.5D, 0.5D, 0.5D));
                    harness.runInteract(60);
                    if (item.equals(world.blockAt(tx, ty, tz))) {
                        note("placed " + item + " at " + tx + "," + ty + "," + tz
                                + " by clicking the " + face + " face of " + sx + "," + sy + "," + sz);
                        return new Cell(tx, ty, tz, item);
                    }
                }
            }
        }
        return null;
    }

    // ===== digging and walking =====

    /**
     * Dig one block, and report what the world actually did.
     *
     * <p>Both facts are read back: the block's identity at the cell, and what the inventory gained.
     * A dig that reports success and drops nothing is a real outcome here -- it is what bare hands
     * on stone are -- so the two are never collapsed into one.
     *
     * <p><b>A dig that gained nothing because the BAG IS FULL is named as that.</b> The first
     * version reported it the same way as a dig of a block that drops nothing, and the caller could
     * only conclude "mine something else". Measured on the full-inventory task: the policy dug
     * eight logs out of the world, the world lost all eight, the bag gained none, and the chain
     * repeated "mining log gained nothing, so more of it will not help either" eight times while
     * the real answer -- 36 of 36 slots full, so every further dig is a deliberate waste -- went
     * unrecorded. Digging is now refused before it happens when there is no room, because a dig
     * that provably cannot land is not a retry, it is damage.
     */
    private boolean dig(Cell c) {
        // The block this body is standing on. Digging it is not a hole a player digs: it leaves the
        // stance with no floor, and the planner then -- correctly -- refuses to plan a walk out of
        // a cell it cannot stand in, so the run dies on "the start stance is not occupiable" with
        // the real cause three steps back in the trace. Measured on the from-nothing task: the
        // policy dug the log at (3,63,-4) while standing in stance (3,64,-4), and every route
        // afterwards was refused. The escalation that made that log minable is the same escalation
        // that created the hole, so the capability and the accident arrive together.
        Cell ownFloor = floorUnderfoot();
        if (ownFloor != null && ownFloor.x == c.x && ownFloor.y == c.y && ownFloor.z == c.z) {
            note("REFUSING to dig " + c + ": it is the block this agent is standing on. Removing it"
                    + " would leave the stance at " + world.stance() + " with nothing under it,"
                    + " which is not a shortcut and not a route -- it is a hole the agent cannot"
                    + " climb out of");
            return false;
        }
        // Sampled BEFORE the dig, while the block is still standing. Asking afterwards is the
        // ordering bug SimWorld.pumpDig was fixed for: the cell is empty once the block has gone,
        // so the question "what did this drop" answers "nothing" for a dig that dropped plenty.
        String drop = world.dropOf(c.x, c.y, c.z);
        String dropName = drop == null ? "nothing" : drop;
        int before = drop == null ? 0 : world.count(drop);
        int freeBefore = world.freeSlots();
        harness.submit(InteractIntent.dig(c.x, c.y, c.z, faceTowardPlayer(c).index()));
        harness.runInteract(900);
        boolean gone = !c.name.equals(world.blockAt(c.x, c.y, c.z));
        int gained = (drop == null ? 0 : world.count(drop)) - before;
        // The bag being full is a DIFFERENT fact from the block dropping nothing, and it is the one
        // that decides whether another dig is worth trying.
        boolean noRoom = drop != null && gained <= 0 && freeBefore == 0;
        note("dug " + c + ": interact slot " + harness.phaseOf(ActSlot.INTERACT) + ", the cell now holds "
                + (gone ? "air (the block is gone)"
                        : world.blockAt(c.x, c.y, c.z) + " (STILL STANDING)")
                + ", and it dropped " + dropName + " of which the player gained " + gained
                + (noRoom ? " -- the inventory has 0 of 36 slots free, so the drop had nowhere to"
                        + " go and the block is simply gone from the world"
                        : ""));
        return gone && !noRoom;
    }

    /** The face of a block pointing back at the player, i.e. the one a click would hit. */
    private ActActuator.Face faceTowardPlayer(Cell c) {
        double px = world.posX() - (c.x + 0.5D);
        double pz = world.posZ() - (c.z + 0.5D);
        if (Math.abs(px) >= Math.abs(pz)) {
            return px >= 0 ? ActActuator.Face.EAST : ActActuator.Face.WEST;
        }
        return pz >= 0 ? ActActuator.Face.SOUTH : ActActuator.Face.NORTH;
    }

    /**
     * Walk to a stance by route, and report the endpoint.
     *
     * <p>Never asserts a tick count: {@code NavController}'s reaction delay is a
     * {@code ThreadLocalRandom} draw of 4..8 ticks, so only the endpoint is reproducible.
     *
     * <p>The block budget is 0: this policy carries its own building blocks and does its own
     * placing, so a route that wanted to bridge would be taking a decision away from the caller.
     */
    private boolean walkTo(Stance goal, String what) {
        harness.submit(new RouteIntent(goal.x(), goal.y(), goal.z(), 0));
        harness.runMove(1200);
        double d = world.horizontalDistanceTo(goal.x() + 0.5D, goal.z() + 0.5D);
        boolean there = d <= 1.2D && Math.abs(world.posY() - goal.y()) <= 1.5D;
        note((there ? "reached " : "DID NOT reach ") + what + ": " + String.format(Locale.ROOT,
                "%.2f blocks away, standing at (%.2f,%.2f,%.2f), move slot %s", d, world.posX(),
                world.posY(), world.posZ(), harness.phaseOf(ActSlot.MOVE)));
        return there;
    }

    /**
     * A standable cell from which {@code (bx,by,bz)} is close enough to dig.
     *
     * <p><b>Searched over the neighbourhood, not over the six face neighbours.</b> Both earlier
     * versions were wrong, and this records why. Offering only the four sides plus above and below
     * cannot mine a log at EYE LEVEL, which is where the second log of a tree stands once the first
     * is felled -- the chain died with "no standable cell next to (4,65,-3)" on an ordinary oak.
     * Adding the cell below did not help either: standing there puts the player's own body inside
     * the block, which {@code hasRoom} correctly refuses, so the failure looks identical.
     *
     * <p>What works, and what a player does, is to stand on the ground BESIDE the trunk and look
     * up; the log is then within the eye-to-block reach the dig controller measures for itself. So
     * this walks outward and takes the nearest cell a body fits in, preferring the lowest on a tie
     * so the player is not lifted onto a ledge it must climb back down.
     *
     * <p>Reach is deliberately NOT checked here. {@code DigController} measures it against the
     * real {@code reachDistance} and fails honestly when the stance is too far, which is the
     * evidence worth having; a second copy of that arithmetic in the policy could only disagree
     * with it.
     */
    private Stance stanceBeside(int bx, int by, int bz) {
        Stance target = new Stance(bx, by, bz);
        for (int radius = 1; radius <= 2; radius++) {
            Stance best = null;
            double bestDist = Double.MAX_VALUE;
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        if (Math.max(Math.max(Math.abs(dx), Math.abs(dy)), Math.abs(dz)) != radius) {
                            continue;
                        }
                        Stance s = new Stance(bx + dx, by + dy, bz + dz);
                        if (!s.isStandable(world)) {
                            continue;
                        }
                        // The stance must not STAND ON the block it is about to dig. A cell
                        // directly above the target passes isStandable precisely because the target
                        // is its floor, and this search preferred the LOWEST such cell, so on a
                        // ground-level block it returned the cell on top of it: the agent walked
                        // across, climbed onto the block, and then tried to break the thing holding
                        // it up. That is the whole of the from-nothing task's death, and it is a
                        // mistake no player makes -- you stand BESIDE a block to dig it.
                        Stance under = s.floorCell();
                        if (under.x() == bx && under.y() == by && under.z() == bz) {
                            continue;
                        }
                        double dist = s.horizontalDistanceTo(target);
                        boolean lower = best == null || (dist == bestDist && s.y() < best.y());
                        if (dist < bestDist || lower) {
                            bestDist = dist;
                            best = s;
                        }
                    }
                }
            }
            if (best != null) {
                return best;
            }
        }
        return null;
    }

    // ===== world queries =====

    /** One block, by cell. */
    private record Cell(int x, int y, int z, String name) {
    }

    /** The nearest cell within {@link #SEARCH_RADIUS} holding a block of this name. */
    private Cell nearestBlock(String name) {
        Stance at = world.stance();
        Cell best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    int bx = at.x() + dx;
                    int by = at.y() + dy;
                    int bz = at.z() + dz;
                    if (!name.equals(world.blockAt(bx, by, bz))) {
                        continue;
                    }
                    double dist = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new Cell(bx, by, bz, name);
                    }
                }
            }
        }
        return best;
    }

    /**
     * The nearest cell whose block drops {@code item} IF the tool were right.
     *
     * <p>Deliberately asks the UNGATED question -- {@code Block.getItemDropped} with no harvest test
     * -- because the gated question is the one the player has not yet earned the answer to. Asking
     * only the gated question would make the tool escalation unreachable: the policy would report
     * "nothing drops stone" and stop, never learning that a tool is what it lacks. The gate itself
     * is still applied, one step later, by {@link #canHarvestWithHeld}.
     */
    private Cell nearestUngatedDrop(String item) {
        return nearestUngatedDrop(item, null);
    }

    /**
     * As above, but never returns {@code skip}.
     *
     * <p>That one cell is the block under the agent's own feet, and a dig there is a hole the agent
     * cannot leave. Skipping it is what lets the chain CARRY ON: refusing the dig outright would
     * turn a recoverable accident into a dead end, so the caller asks for the next nearest cell
     * instead and the run continues against a target it can actually stand next to.
     */
    private Cell nearestUngatedDrop(String item, Cell skip) {
        Stance at = world.stance();
        Cell best = null;
        double bestDist = Double.MAX_VALUE;
        if (skip != null) {
            note("the nearest " + item + " is the block under the agent's own feet, which cannot be"
                    + " dug, so the search is taking the next one instead");
        }
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    int bx = at.x() + dx;
                    int by = at.y() + dy;
                    int bz = at.z() + dz;
                    Block b = blockAt(bx, by, bz);
                    if (b == null) {
                        continue;
                    }
                    Item dropped = b.getItemDropped(b.getStateFromMeta(0), new Random(0L), 0);
                    if (dropped == null || !item.equals(SimWorld.nameOf(dropped))) {
                        continue;
                    }
                    if (skip != null && skip.x == bx && skip.y == by && skip.z == bz) {
                        continue;
                    }
                    double dist = Math.sqrt((double) dx * dx + (double) dy * dy + (double) dz * dz);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = new Cell(bx, by, bz, world.blockAt(bx, by, bz));
                    }
                }
            }
        }
        return best;
    }

    /**
     * The solid block this body is standing on, or null when there is nothing under it.
     *
     * <p>Read off the world's own cells rather than off {@code onGround()}: the question every
     * caller here actually asks is "what is the block below my feet", and a body resting on a
     * ladder or in water answers that differently from a body on stone.
     */
    private Cell floorUnderfoot() {
        Stance feet = world.stance();
        if (!world.isSolid(feet.x(), feet.y() - 1, feet.z())) {
            return null;
        }
        return new Cell(feet.x(), feet.y() - 1, feet.z(), world.blockAt(feet.x(), feet.y() - 1,
                feet.z()));
    }

    /**
     * Whether this agent has already removed its own footing, said in the agent's own words.
     *
     * <p>The second half of the fix, and it is the half that matters for a report. Once the body
     * is standing on nothing, the planner's refusal -- "the start stance is not occupiable" -- is
     * true and useless: it describes the planner, not the cause. A policy that dug its own floor
     * out has to say THAT, because the difference between "I cannot walk from here" and "I removed
     * what I was standing on" is the difference between a world problem and an agent problem.
     */
    private boolean footingIsGone() {
        Stance feet = world.stance();
        if (feet.isStandable(world)) {
            return false;
        }
        note("THE AGENT HAS NO GROUND UNDER ITS OWN FEET at " + feet + " -- the cell below reads "
                + String.valueOf(world.blockAt(feet.x(), feet.y() - 1, feet.z())) + ". No route can"
                + " be planned from here, and the reason is not the terrain: something in this chain"
                + " removed what this body was standing on. The goal is unreachable until something"
                + " is placed back underneath");
        return true;
    }

    private Block blockAt(int bx, int by, int bz) {
        String name = world.blockAt(bx, by, bz);
        return name == null ? null : SimWorld.block(name);
    }

    /**
     * Take down whatever screen is up, as a player does with ESC.
     *
     * <p>Silent when there is nothing open. It runs on every craft and on every failed craft, and a
     * note for each would bury the chain in lines about a screen that was never raised.
     */
    private void closeScreen() {
        if (world.openWindow() != null) {
            world.setScreen(null);
        }
    }

    /** Put a screen back after a nested sequence, saying so only when there was one. */
    private void setScreen(SimCraftWindow window) {
        if (window != null && world.openWindow() != window) {
            world.setScreen(window);
            note("restored the " + window.gridWidth() + "x" + window.gridWidth() + " screen the"
                    + " nested craft interrupted");
        }
    }
}
