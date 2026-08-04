package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The happy path, end to end: a 2x2 craft in the player's OWN window, with the real recipe table
 * deciding whether the output appears.
 *
 * <p><b>Why this is not a trivial test.</b> The output in {@link FakeCraftWindow} is computed by
 * {@code CraftingManager.findMatchingRecipe} on a mirror of whatever the controller actually put in the
 * matrix. So this passes only if every ingredient reached the right SQUARE -- a controller that clicked
 * one cell four times, or filled the grid transposed, gets no result and fails here rather than
 * reporting a craft it did not do. That is the whole reason the fake's slots mutate.
 *
 * <p>crafting_table is the recipe under test because it is the smallest thing that exercises the real
 * mechanics: four squares of one wildcard ingredient ({@code Blocks.planks} enters the table as
 * metadata 32767, see {@code CraftingManager.addRecipe}), which means one pick-up must serve a run of
 * four right-click placements, and it fits the 2x2 grid a player always has -- the one case the task
 * says can be done without a bench.
 */
public class A2x2CraftPlacesEveryIngredientAndTakesTheResultTest {

    /** Bound far above the controller's own budget, so a stuck machine fails instead of hanging. */
    private static final int TICK_BOUND = 60;

    @Test
    public void aCraftingTableIsCraftedInThePlayersOwn2x2GridAndEndsWithACleanWindow() {
        RecipeView table = CraftBench.find("crafting_table", 0);
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 4);
        CraftController c = new CraftController(table);

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("a payable 2x2 craft in a 2x2 window must succeed: " + out.message(), out.ok());
        assertTrue("and say what it made: " + out.message(), out.message().contains("crafted 1x crafting_table"));
        // The real matcher produced this, so the four planks were in the four right squares.
        assertEquals("the crafted table must be in the inventory ON THE SERVER's side -- the client's own "
                + "count would read the same for a craft the server dropped in full",
            1, win.serverStoredCount("crafting_table", 0));
        assertEquals("and the four planks must be spent, not still held",
            0, win.serverStoredCount("planks", 0));
        assertEquals("the matrix must be empty: anything left there is dropped on the floor when the "
                + "window closes (ContainerPlayer.onContainerClosed)", "", win.matrixContents());
        assertNull("and nothing may ride the cursor, which is dropped the same way", win.cursor());
        assertEquals("the server must have applied every click; a dropped one means the craft only "
                + "happened on the client", 0, win.droppedClicks);
    }

    @Test
    public void oneRightClickPerSquareIsUsedRatherThanAPickUpPerSquare() {
        // Pins the click ECONOMY, which is the reason placement can be one game-thread pass at all.
        // Container:303 places exactly ONE item for button 1, so a single pick-up covers all four
        // squares: 1 pick-up + 4 placements + 1 take + 1 park = 7. The rejected alternative --
        // pick up, place all, take the remainder back, per square -- is 12 and leaves the cursor loaded
        // between squares, where a window close drops it.
        RecipeView table = CraftBench.find("crafting_table", 0);
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 4);
        CraftController c = new CraftController(table);

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("must have succeeded for the count to mean anything: " + out.message(), out.ok());
        assertEquals("7 clicks: one pick-up, four one-item placements, the result, and parking it",
            7, win.clicks);
        assertEquals("and the result slot is clicked exactly once -- twice would craft twice and spend "
                + "a second set of ingredients", 1, win.resultClicks);
    }

    @Test
    public void aCraftIsNotClaimedUntilTheServerHasHadARoundTripToDisagree() {
        // The controller must not report done on the pass that queues its clicks. Acceptance turns on
        // the server re-running slotClick and comparing areItemStacksEqual (NetHandlerPlayServer:1029),
        // so there is nothing to read until a round trip has passed. A machine that terminated in one
        // or two ticks would be claiming a verdict that had not been issued.
        RecipeView table = CraftBench.find("crafting_table", 0);
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 4);
        CraftController c = new CraftController(table);

        int ticks = 0;
        CraftOutcome out;
        do {
            out = c.tick(win);
            win.advanceTick();
            ticks++;
            assertTrue("must not run past the bound", ticks < TICK_BOUND);
        } while (!out.terminal());

        assertTrue("must have succeeded: " + out.message(), out.ok());
        // Two waits of SETTLE_TICKS each, one after placing and one after taking, plus the states
        // between them. Asserting the floor rather than the exact number: the point is that BOTH
        // verdicts were waited for, not the bookkeeping.
        assertTrue("a craft must span at least both round trips (2 x " + CraftController.SETTLE_TICKS
                + " ticks), took " + ticks, ticks >= 2 * CraftController.SETTLE_TICKS);
        assertTrue("the controller must be done once it says so", c.isDone());
    }
}
