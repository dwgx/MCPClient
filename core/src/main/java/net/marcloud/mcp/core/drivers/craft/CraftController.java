package net.marcloud.mcp.core.drivers.craft;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Pure state machine that crafts ONE of a recipe through a {@link CraftWindow}. Ticked by whoever
 * owns the game thread; it never marshals threads itself.
 *
 * <p>Shaped after {@code DigController} and {@code HoldController}, the act package's proven durable
 * behaviour -- per-tick step, poll for completion, fail honestly when it makes no progress, and route
 * every terminal exit through one place. That last rule is not decoration here: a craft that gives up
 * halfway leaves ingredients in the matrix, and vanilla DROPS THEM ON THE FLOOR when the window
 * closes ({@code ContainerPlayer.onContainerClosed:83-98}, {@code ContainerWorkbench:62-78}, and the
 * cursor with them at {@code Container:516-525}). {@code HoldController} shipped twice with a
 * terminal path that abandoned state it owned; the fix was one funnel, and this class starts there.
 *
 * <p>States: CHECKING (window, grid size, can the player pay) -> PLACING (every ingredient click in
 * ONE pass) -> SETTLING (wait a round trip, then re-read the matrix to see whether the server agreed)
 * -> TAKING (click the result, park it) -> CONFIRMING (wait, then prove the matrix and cursor are
 * empty and the output is really in storage) -> done / failed / cancelled.
 *
 * <p><b>Why placement is one pass and verification is not.</b> N {@code windowClick} calls inside one
 * game-thread pass are not inherently multi-tick: each queues one packet and the server drains them
 * FIFO from the same starting state, so splitting them across ticks would buy nothing and would open a
 * window for a human's own clicks to interleave. What CANNOT be collapsed is the verdict. Acceptance
 * turns on the server re-running {@code slotClick} and comparing
 * {@code areItemStacksEqual(packet.getClickedItem(), its own result)}
 * ({@code NetHandlerPlayServer:1029}); until a round trip has passed there is nothing to read. Hence
 * a state machine rather than a tool that returns when its last click is queued.
 *
 * <p><b>The hazard being defended against.</b> On mismatch the server sends
 * {@code S32(accepted=false)}, resyncs the window and calls {@code setCanCraft(player, false)}
 * (:1041). The whole click body is gated on {@code getCanCraft} (:1012) with NO else branch -- so
 * every later click on that window is silently dropped, no error and no reply, until vanilla's own
 * confirm handler echoes the action number back (:1142-1147). A controller that kept clicking through
 * that would queue a full craft's worth of packets into a void and then report success. This is the
 * "reports healthy while being wrong" shape, and SETTLING exists solely to catch it.
 */
public final class CraftController {

    /**
     * Ticks to wait for the server's verdict before reading the window back.
     *
     * <p>A client tick is 50ms and the server drains packets on its own 50ms tick, so a verdict costs
     * one round trip plus up to a tick of granularity at each end. Four ticks (200ms) covers that with
     * margin. The number is a floor on honesty rather than a performance knob: reading at one tick
     * would call a rejection that had merely not arrived yet a success -- the exact direction of error
     * this class exists to prevent -- while a much larger wait would make every craft sluggish for no
     * extra certainty. Nowhere near the ~5s cap a GameBridge submission gets, since each tick here is
     * a separate submission.
     */
    public static final int SETTLE_TICKS = 4;

    private enum State { CHECKING, PLACING, SETTLING, TAKING, CONFIRMING }

    private final RecipeView recipe;

    private State state = State.CHECKING;
    private boolean done;
    private boolean cancelRequested;

    /** Window this craft was checked against, so a window CHANGE is detected rather than clicked into. */
    private int windowId = -1;
    private int gridWidth;
    /** Clicks issued, for status and for tests that need to know a path clicked nothing. */
    private int clicks;
    /** Ticks spent in the current wait. */
    private int settled;
    /** Cells in placement order; also the list SETTLING verifies and cleanup drains. */
    private List<RecipeView.Cell> plan = List.of();

    public CraftController(RecipeView recipe) {
        if (recipe == null) {
            throw new IllegalArgumentException("no recipe to craft");
        }
        this.recipe = recipe;
    }

    /** Request cancellation; the next {@link #tick} clears the matrix and ends CANCELLED. */
    public void requestCancel() {
        this.cancelRequested = true;
    }

    /** Clicks issued so far (for status/tests). */
    public int clicks() {
        return clicks;
    }

    /** True once a terminal outcome has been produced. */
    public boolean isDone() {
        return done;
    }

    /** Advance one tick against {@code win}. */
    public CraftOutcome tick(CraftWindow win) {
        if (done) {
            return CraftOutcome.done("already finished");
        }
        if (cancelRequested) {
            return finish(win, CraftOutcome.cancelled("craft of " + recipe.output() + " cancelled"));
        }
        if (!win.windowOpen()) {
            return finish(win, CraftOutcome.failed("no window is open, so there is nothing to craft in"));
        }
        // Checked BEFORE every state that clicks, not just once. A bench closing mid-craft moves the
        // window id, and the slot indices do not travel: slot 5 is a matrix square in a workbench and
        // an ARMOUR slot in the player's own window, so stale indices would land somewhere real and
        // wrong -- the craft would report progress while stripping the player's armour.
        if (state != State.CHECKING && (win.windowId() != windowId || win.gridWidth() != gridWidth)) {
            return finish(win, CraftOutcome.failed("the window changed under this craft (was id "
                    + windowId + " with a " + gridWidth + "x" + gridWidth + " grid, now id "
                    + win.windowId() + " with " + win.gridWidth() + "x" + win.gridWidth()
                    + "), so its slot numbers no longer mean what they did"));
        }
        return switch (state) {
            case CHECKING -> check(win);
            case PLACING -> place(win);
            case SETTLING -> settle(win);
            case TAKING -> take(win);
            case CONFIRMING -> confirm(win);
        };
    }

    /**
     * Everything knowable before a single click, so a refusal costs no game state.
     *
     * <p>Order matters: the grid test comes before feasibility because "you need a crafting table" is
     * actionable while "you are short two planks" computed against the wrong grid may not even be
     * true. Nothing here clicks, so this path cannot strand anything -- which is why it is also the
     * only place allowed to fail without touching the matrix.
     */
    private CraftOutcome check(CraftWindow win) {
        int width = win.gridWidth();
        if (width <= 0) {
            return finish(win, CraftOutcome.failed("the open window has no crafting grid (a chest or a "
                    + "furnace), so " + recipe.output() + " cannot be crafted here"));
        }
        if (recipe.width() > width || recipe.height() > width) {
            // NOT attempted. A 3x3 recipe in a 2x2 grid has no placement at all, and the failure a
            // model would otherwise see is "filled the squares, got no output", which is
            // indistinguishable from a missing ingredient. Naming the bench is the difference between
            // a model that walks to one and a model that retries forever.
            return finish(win, CraftOutcome.failed(recipe.output() + " needs a " + recipe.width() + "x"
                    + recipe.height() + " layout and this window's grid is only " + width + "x" + width
                    + "; open a crafting table first (the player's own grid is 2x2)"));
        }
        int[] storage = win.storageSlots();
        if (storage.length == 0) {
            return finish(win, CraftOutcome.failed("this window exposes no slots to take ingredients "
                    + "from or park the result in"));
        }
        CraftFeasibility.Result pay = CraftFeasibility.check(recipe, storedInventory(win));
        if (!pay.satisfied()) {
            return finish(win, CraftOutcome.failed("cannot craft " + recipe.output() + ": " + shortfall(pay)));
        }

        windowId = win.windowId();
        gridWidth = width;
        plan = placementOrder();
        state = State.PLACING;
        return CraftOutcome.running("crafting " + recipe.output() + " in a " + width + "x" + width
                + " grid, " + plan.size() + " ingredient squares to fill");
    }

    /**
     * Every placement click, in one pass.
     *
     * <p>One pick-up serves a run of squares: {@code Container.slotClick} places exactly ONE item for
     * {@code clickedButton == 1} ({@code Container:303}), so the plan is grouped by ingredient and the
     * cursor is reloaded only when what it holds cannot pay for the next cell. The rejected
     * alternative was pick-up / place-all / take-back per square, which triples the clicks and leaves
     * the cursor loaded between them -- and the cursor is dropped on the floor if the window closes.
     */
    private CraftOutcome place(CraftWindow win) {
        for (RecipeView.Cell cell : plan) {
            int slot = win.matrixSlot(cell.row(), cell.col());
            if (slot < 0) {
                return finish(win, CraftOutcome.failed("matrix cell (" + cell.row() + "," + cell.col()
                        + ") is not a slot in this window"));
            }
            if (!cursorPaysFor(win.cursor(), cell)) {
                CraftOutcome parked = parkCursor(win);
                if (parked != null) {
                    return parked;
                }
                int source = sourceSlot(win, cell);
                if (source < 0) {
                    // Feasibility passed, so this is not a shortfall: between the check and here the
                    // stack moved, or it is in a slot this window does not expose for work.
                    return finish(win, CraftOutcome.failed("no reachable stack of " + describe(cell)
                            + " to fill matrix cell (" + cell.row() + "," + cell.col()
                            + "), though the inventory check had passed"));
                }
                if (!click(win, source, CraftWindow.LEFT)) {
                    return finish(win, CraftOutcome.failed("could not pick up " + describe(cell)
                            + " from slot " + source));
                }
            }
            if (!click(win, slot, CraftWindow.RIGHT)) {
                return finish(win, CraftOutcome.failed("could not place " + describe(cell)
                        + " into matrix cell (" + cell.row() + "," + cell.col() + ")"));
            }
        }
        // Before SETTLING, not as part of cleanup: whatever is left of the last pick-up belongs in
        // storage either way, and leaving it on the cursor for a round trip is a stack one window
        // close would put on the floor.
        CraftOutcome parked = parkCursor(win);
        if (parked != null) {
            return parked;
        }
        state = State.SETTLING;
        settled = 0;
        return CraftOutcome.running("placed " + plan.size() + " ingredient squares in " + clicks
                + " clicks; waiting " + SETTLE_TICKS + " ticks for the server's verdict");
    }

    /**
     * Wait a round trip, then ASK THE WINDOW whether the placement survived.
     *
     * <p>This is the state that exists because of the silent-drop hazard, and re-reading the matrix is
     * the only synchronous evidence available. On a rejection the server resyncs the window to its own
     * truth, so the cells this controller filled read back empty or wrong; nothing else the client can
     * poll distinguishes an accepted craft from a window that has been locked and is dropping clicks.
     * In particular the RESULT slot proves nothing -- it is computed from the CLIENT matrix, so it
     * shows a perfectly good output for a placement the server never applied.
     */
    private CraftOutcome settle(CraftWindow win) {
        if (settled < SETTLE_TICKS) {
            settled++;
            return CraftOutcome.running("waiting for the server's verdict on " + plan.size()
                    + " placements (" + settled + "/" + SETTLE_TICKS + ")");
        }
        for (RecipeView.Cell cell : plan) {
            int slot = win.matrixSlot(cell.row(), cell.col());
            CraftInventory.Held there = slot < 0 ? null : win.stackAt(slot);
            if (!paysFor(there, cell)) {
                // Stop rather than press on. Every further click on a locked window is dropped in
                // silence, so continuing would queue a whole craft into a void and then report it as
                // done. Cleanup still runs through finish(): if the window was resynced the matrix is
                // already empty and it is a no-op, and if this was something else entirely the
                // ingredients come back.
                return finish(win, CraftOutcome.failed("the server did not accept the placement: matrix "
                        + "cell (" + cell.row() + "," + cell.col() + ") should hold " + describe(cell)
                        + " and holds " + held(there) + ". A rejected click makes the server resync the "
                        + "window and call setCanCraft(false) (NetHandlerPlayServer:1029-1041), after "
                        + "which it SILENTLY DROPS every click until the client echoes the action "
                        + "number back -- so no further clicking is attempted here. The ingredients "
                        + "were not spent"));
            }
        }
        state = State.TAKING;
        return CraftOutcome.running("the server accepted all " + plan.size()
                + " placements; taking the result");
    }

    /**
     * Take the output, which is also what SPENDS the ingredients.
     *
     * <p>Clicking the result is not a read of a finished item, it is the trigger:
     * {@code SlotCrafting.onPickupFromSlot:134-160} decrements every occupied matrix cell by one. Left
     * button, then park, so the output does not ride the cursor into the next tick.
     */
    private CraftOutcome take(CraftWindow win) {
        int result = win.resultSlot();
        if (result < 0) {
            return finish(win, CraftOutcome.failed("this window has no result slot"));
        }
        if (win.stackAt(result) == null) {
            // The squares are right and vanilla still sees no recipe. Reported as the layout being
            // refused rather than as a missing ingredient, because the ingredients are demonstrably in
            // place -- SETTLING just proved it -- and cleanup below returns them.
            return finish(win, CraftOutcome.failed("all " + plan.size() + " squares are filled as "
                    + recipe.output() + " requires, but the result slot is empty, so vanilla does not "
                    + "match this layout"));
        }
        if (!click(win, result, CraftWindow.LEFT)) {
            return finish(win, CraftOutcome.failed("could not click the result slot"));
        }
        CraftOutcome parked = parkCursor(win);
        if (parked != null) {
            return parked;
        }
        state = State.CONFIRMING;
        settled = 0;
        return CraftOutcome.running("took the result; waiting " + SETTLE_TICKS
                + " ticks to confirm the server agreed");
    }

    /**
     * Prove the craft actually happened, one round trip after the take.
     *
     * <p>Three separate facts, because any one of them alone can be true of a failure. The matrix must
     * be empty (the take really consumed it), the cursor must be empty (nothing is riding where a
     * window close would drop it), and the output must be IN STORAGE -- which is the only one that
     * says the player now owns the item. A rejected take is caught here the same way a rejected
     * placement is caught in SETTLING: the resync puts the ingredients back, so the matrix is not empty.
     */
    private CraftOutcome confirm(CraftWindow win) {
        if (settled < SETTLE_TICKS) {
            settled++;
            return CraftOutcome.running("confirming the craft (" + settled + "/" + SETTLE_TICKS + ")");
        }
        for (RecipeView.Cell cell : plan) {
            int slot = win.matrixSlot(cell.row(), cell.col());
            CraftInventory.Held there = slot < 0 ? null : win.stackAt(slot);
            if (there != null) {
                return finish(win, CraftOutcome.failed("took the result but matrix cell (" + cell.row()
                        + "," + cell.col() + ") still holds " + held(there)
                        + ", so the server did not accept the take"));
            }
        }
        if (win.cursor() != null) {
            return finish(win, CraftOutcome.failed("the craft finished but " + held(win.cursor())
                    + " is still on the cursor, where closing the window would drop it"));
        }
        if (!storesOutput(win)) {
            return finish(win, CraftOutcome.failed("the matrix is empty but no " + recipe.output()
                    + " reached the inventory, so the output did not survive the round trip"));
        }
        return finish(win, CraftOutcome.done("crafted " + recipe.outputCount() + "x " + recipe.output()
                + " in " + clicks + " clicks"));
    }

    /**
     * Produce a terminal outcome, guaranteeing nothing is left in the matrix or on the cursor.
     *
     * <p>The clearing lives HERE rather than at each of the dozen terminal sites for the reason
     * {@code HoldController.finish} gives about the use key: it is the state this controller owns that
     * OUTLIVES it, and auditing a dozen call sites is the kind of discipline that fails silently on the
     * thirteenth. That controller shipped twice with a path that abandoned what it held, and this one
     * has strictly more of it -- an abandoned matrix is not just wrong state, it is ITEMS, and vanilla
     * hands them to the floor the moment the window closes: {@code ContainerPlayer:83-98} and
     * {@code ContainerWorkbench:62-78} drop every matrix stack, {@code Container:516-525} drops the
     * cursor. World_view reads {@code mainInventory} only, so a stranded ingredient is invisible to
     * every observation tool the model has; it would learn about it as items missing from its pockets.
     *
     * <p>A {@code done} outcome runs the same sweep. On the success path the matrix is already empty
     * and it costs nothing, but if it ever finds something, saying so is the whole point -- silence
     * there is what "reports healthy while being wrong" looks like.
     */
    private CraftOutcome finish(CraftWindow win, CraftOutcome out) {
        done = true;
        Sweep swept = sweep(win);
        if (swept.note().isEmpty()) {
            return out;
        }
        // Recovered items are appended; UNRECOVERED ones flip a success, because an outcome that says
        // "crafted" while an ingredient sits where a window close will drop it is not a success. The flag
        // is carried out of the sweep rather than recovered by matching its own prose: the note now
        // reports recoveries and strandings TOGETHER, so any prefix test would have read the wrong half
        // exactly when both happened -- the case where the caller most needs the verdict to be right.
        if (out.ok() && swept.stranded()) {
            return CraftOutcome.failed(out.message() + "; but " + swept.note());
        }
        return new CraftOutcome(true, out.ok(), out.message() + "; " + swept.note());
    }

    /**
     * What the cleanup did, and whether anything is still where a window close would drop it.
     *
     * @param note     prose for the outcome message, empty when the cleanup had nothing to do
     * @param stranded true when an item remains in the matrix or on the cursor
     */
    private record Sweep(String note, boolean stranded) {
    }

    /**
     * Return anything in the matrix or on the cursor to storage, and report what became of it.
     *
     * <p>Sweeps the whole grid rather than only the planned cells: a craft that failed between two
     * clicks may hold a cell the plan had not reached, and a resync may have left something in a cell
     * this recipe never used. Best-effort by construction -- if the window is locked these clicks are
     * dropped in silence like any other -- so it reports what it OBSERVES afterwards rather than
     * assuming its own clicks worked.
     *
     * <p><b>The cursor is emptied FIRST, and the order is the correctness argument.</b> With a loaded
     * cursor, a LEFT click on a cell holding the same item does not lift that cell -- it DEPOSITS into
     * it ({@code Container:337-359} merges cursor into slot whenever item, metadata and NBT match).
     * Draining the grid before parking the cursor therefore pushes MORE ingredients into the matrix, and
     * the path where that happens is the one that matters most: a rejected click makes the server resync
     * the window and hand back its own state, which mid-placement means a partly-filled matrix AND a
     * loaded cursor of the same item. Measured on the rejection test before this order was fixed -- one
     * stranded plank became four, so the cleanup left the player strictly worse off than doing nothing.
     */
    private Sweep sweep(CraftWindow win) {
        if (!win.windowOpen() || win.gridWidth() <= 0) {
            // Nothing addressable. Not silent: if the window vanished mid-craft the items went with it.
            return new Sweep("", false);
        }
        int recovered = 0;
        if (returnCursorQuietly(win)) {
            recovered++;
        }
        for (int row = 0; row < win.gridWidth(); row++) {
            for (int col = 0; col < win.gridWidth(); col++) {
                int slot = win.matrixSlot(row, col);
                CraftInventory.Held there = slot < 0 ? null : win.stackAt(slot);
                if (there == null) {
                    continue;
                }
                // Look before lifting. With nowhere to put it, lifting only moves the stack from a cell
                // to the cursor -- both dropped on the floor by the same window close -- and costs a
                // click that also has to be reported. Leaving it in the cell keeps the report pointing at
                // where the items actually are.
                if (parkSlot(win, there) < 0) {
                    continue;
                }
                if (click(win, slot, CraftWindow.LEFT) && returnCursorQuietly(win)) {
                    recovered++;
                }
            }
        }
        List<String> left = new ArrayList<>();
        for (int row = 0; row < win.gridWidth(); row++) {
            for (int col = 0; col < win.gridWidth(); col++) {
                int slot = win.matrixSlot(row, col);
                CraftInventory.Held there = slot < 0 ? null : win.stackAt(slot);
                if (there != null) {
                    left.add(held(there) + " at (" + row + "," + col + ")");
                }
            }
        }
        if (win.cursor() != null) {
            left.add(held(win.cursor()) + " on the cursor");
        }
        // BOTH facts, when both are true. A cleanup that hands three ingredients back and cannot place
        // the fourth previously reported only the stranding, so a caller trying to reconcile its
        // inventory was told what it lost and not what it got back -- and the two numbers are what make
        // the report add up.
        List<String> parts = new ArrayList<>();
        if (recovered > 0) {
            parts.add("returned " + recovered + " ingredient stack(s) to the inventory");
        }
        if (!left.isEmpty()) {
            parts.add("stranded " + String.join(", ", left)
                    + " -- closing the window will DROP these on the floor");
        }
        return new Sweep(String.join("; ", parts), !left.isEmpty());
    }

    /**
     * The order cells are filled in: exact-variant ingredients first, then grouped by ingredient.
     *
     * <p><b>Exact before wildcard is the same rule {@link CraftFeasibility} allocates by, and it has to
     * be.</b> A wildcard square accepts any variant and an exact one only its own, so filling a
     * wildcard square from the oak stack first can leave an exact "oak planks" square unfillable while
     * a spruce stack sat unspent -- and unlike the feasibility check, which would merely have
     * MISREPORTED that, here the ingredients are already in the matrix when it happens. The check would
     * have said satisfied and the craft would then strand what it placed. Two orderings for one
     * allocation is the trap; this is that one.
     *
     * <p>Grouping by ingredient after that is what makes one pick-up serve a run of squares. Ties fall
     * back to (row, col) so the order is total and a failure is reproducible.
     */
    private List<RecipeView.Cell> placementOrder() {
        List<RecipeView.Cell> cells = new ArrayList<>(recipe.cells());
        cells.sort(Comparator.comparing((RecipeView.Cell c) -> c.anyMeta())
                .thenComparing(RecipeView.Cell::item)
                .thenComparingInt(RecipeView.Cell::meta)
                .thenComparingInt(RecipeView.Cell::row)
                .thenComparingInt(RecipeView.Cell::col));
        return List.copyOf(cells);
    }

    /** What the window's workable slots hold, as the feasibility check consumes it. */
    private CraftInventory storedInventory(CraftWindow win) {
        List<CraftInventory.Held> held = new ArrayList<>();
        for (int slot : win.storageSlots()) {
            CraftInventory.Held there = win.stackAt(slot);
            if (there != null && there.item() != null && there.count() > 0) {
                held.add(there);
            }
        }
        return new CraftInventory(List.copyOf(held));
    }

    /**
     * A storage slot holding something that pays for {@code cell}, preferring the SMALLEST stack.
     *
     * <p>Smallest first so a wildcard square eats the remnant rather than breaking into a full stack,
     * which keeps the inventory tidy and, more usefully, leaves the larger stack whole for a later
     * exact square of the same item.
     */
    private int sourceSlot(CraftWindow win, RecipeView.Cell cell) {
        int best = -1;
        int bestCount = Integer.MAX_VALUE;
        for (int slot : win.storageSlots()) {
            CraftInventory.Held there = win.stackAt(slot);
            if (paysFor(there, cell) && there.count() < bestCount) {
                best = slot;
                bestCount = there.count();
            }
        }
        return best;
    }

    /**
     * Whether {@code held} satisfies {@code cell}: item and metadata only.
     *
     * <p>The same comparison vanilla's own matchers make -- {@code ShapedRecipes.checkMatch} and
     * {@code ShapelessRecipes.matches} test {@code getItem()} then {@code getMetadata()} and look at
     * neither NBT nor stack size. Used both to choose a source stack and to verify what came back after
     * a round trip, deliberately: a verification with looser rules than the placement would accept a
     * resynced cell as its own work.
     */
    private static boolean paysFor(CraftInventory.Held held, RecipeView.Cell cell) {
        if (held == null || held.item() == null || held.count() <= 0) {
            return false;
        }
        return held.item().equals(cell.item()) && (cell.anyMeta() || held.meta() == cell.meta());
    }

    /** Whether the cursor can fill {@code cell} without a fresh pick-up. */
    private static boolean cursorPaysFor(CraftInventory.Held cursor, RecipeView.Cell cell) {
        return paysFor(cursor, cell);
    }

    /**
     * Put whatever is on the cursor back into storage. Returns a terminal outcome only if it cannot.
     *
     * <p>Called before every reload and on the way out of PLACING and TAKING, because the cursor is
     * dropped on the floor when the window closes ({@code Container:516-525}) and it is invisible to
     * {@code world_view}, which reads {@code mainInventory} only.
     */
    private CraftOutcome parkCursor(CraftWindow win) {
        CraftInventory.Held cursor = win.cursor();
        if (cursor == null) {
            return null;
        }
        int slot = parkSlot(win, cursor);
        if (slot < 0) {
            return finish(win, CraftOutcome.failed("nowhere to put " + held(cursor)
                    + " down: every workable slot is full, and the cursor is dropped on the floor when "
                    + "the window closes"));
        }
        if (!click(win, slot, CraftWindow.LEFT)) {
            return finish(win, CraftOutcome.failed("could not put " + held(cursor)
                    + " down in slot " + slot));
        }
        if (win.cursor() != null) {
            // The click was issued and the cursor is still loaded: the slot refused the stack
            // (isItemValid) or it filled to its stack limit. Either way, saying so beats looping.
            return finish(win, CraftOutcome.failed("slot " + slot + " did not accept "
                    + held(cursor) + ", so it is still on the cursor"));
        }
        return null;
    }

    /**
     * {@link #parkCursor} without an outcome: puts the cursor down if it can, and reports whether it did.
     *
     * <p>Separate from {@code parkCursor} because {@link #sweep} runs INSIDE {@link #finish}, and
     * {@code parkCursor} answers a failure by calling {@code finish} itself. Reusing it there would nest
     * a terminal outcome inside the one being produced -- the inner one's message discarded, {@code done}
     * set twice, and {@code sweep} entered a second time from within its own first call. That is a
     * confusing shape to reason about and, if the recursion ever failed to bottom out on an empty matrix,
     * a StackOverflowError instead of a report. The distinction that makes the split honest: a cursor
     * that cannot be parked mid-craft ENDS the craft, while one that cannot be parked during cleanup is
     * something to report, since the craft has already ended.
     */
    private boolean returnCursorQuietly(CraftWindow win) {
        CraftInventory.Held cursor = win.cursor();
        if (cursor == null) {
            return false;
        }
        int slot = parkSlot(win, cursor);
        if (slot < 0) {
            return false;
        }
        click(win, slot, CraftWindow.LEFT);
        // Asks the window rather than trusting the click. On a locked window the click was applied to the
        // client and dropped by the server, and this reads the client -- which is the best evidence
        // available synchronously and is stated as such in the caller's report.
        return win.cursor() == null;
    }

    /**
     * Where a stack can be put down: an empty workable slot, or one already holding the same variant.
     *
     * <p>Empty slots are preferred over merging because a merge can silently cap at the stack limit and
     * leave a remainder on the cursor, which {@link #parkCursor} would then have to report as a
     * failure. {@link CraftWindow#storageSlots} has already excluded the matrix, the result and the
     * armour slots, so nothing here can put an ingredient back into the grid it was just taken from.
     */
    private int parkSlot(CraftWindow win, CraftInventory.Held stack) {
        for (int slot : win.storageSlots()) {
            if (win.stackAt(slot) == null) {
                return slot;
            }
        }
        for (int slot : win.storageSlots()) {
            CraftInventory.Held there = win.stackAt(slot);
            if (there != null && there.item().equals(stack.item()) && there.meta() == stack.meta()) {
                return slot;
            }
        }
        return -1;
    }

    /** Whether any workable slot holds this recipe's output. */
    private boolean storesOutput(CraftWindow win) {
        for (int slot : win.storageSlots()) {
            CraftInventory.Held there = win.stackAt(slot);
            if (there != null && recipe.output().equals(there.item())
                    && there.meta() == recipe.outputMeta()) {
                return true;
            }
        }
        return false;
    }

    private boolean click(CraftWindow win, int slot, int button) {
        clicks++;
        return win.click(slot, button);
    }

    /** An ingredient as a caller would recognise it, wildcard included rather than shown as 32767. */
    private static String describe(RecipeView.Cell cell) {
        return cell.anyMeta() ? cell.item() + " (any variant)" : cell.item() + "/" + cell.meta();
    }

    private static String held(CraftInventory.Held stack) {
        return stack == null ? "nothing" : stack.count() + "x " + stack.item() + "/" + stack.meta();
    }

    /** The shortfall, ingredient by ingredient, in the recipe's own order. */
    private static String shortfall(CraftFeasibility.Result pay) {
        List<String> parts = new ArrayList<>();
        for (CraftFeasibility.Missing m : pay.missing()) {
            parts.add("short " + m.shortBy() + " " + (m.anyMeta() ? m.item() + " (any variant)"
                    : m.item() + "/" + m.meta()) + " (need " + m.need() + ", have " + m.available() + ")");
        }
        return String.join("; ", parts);
    }
}
