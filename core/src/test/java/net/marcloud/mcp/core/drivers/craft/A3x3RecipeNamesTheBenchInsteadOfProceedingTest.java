package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A 3x3 recipe against the player's own 2x2 grid must be REFUSED with the bench named, and must not
 * spend a single click finding that out.
 *
 * <p><b>Why the refusal has to be explicit.</b> The player always has a container open -- vanilla's
 * {@code inventoryContainer}, a 2x2 grid -- so "is a window open" is true and says nothing. If the
 * controller just started clicking, the 3x3 cells would map through {@code matrixSlot} to nothing (or,
 * worse, to slot numbers that mean something else) and the outcome a model would see is "I filled the
 * squares and got no output": indistinguishable from a missing ingredient. One of those is solved by
 * walking to a crafting table and the other by going mining, and a model that cannot tell them apart
 * retries forever. That is what makes this a controller rather than a tool -- opening a bench costs a
 * round trip (C08 -> S2D -> the client builds the screen), so it cannot be folded into the same pass.
 *
 * <p>The second test is the positive control, and it is what stops the first one from passing for the
 * wrong reason: the SAME recipe against a real 3x3 bench must succeed. Without it, a controller that
 * refused every chest recipe outright -- or could not craft a 3x3 at all -- would look correct here.
 */
public class A3x3RecipeNamesTheBenchInsteadOfProceedingTest {

    private static final int TICK_BOUND = 60;

    /** A chest: eight planks in a ring, 3x3, so it cannot be placed in a 2x2 grid at any offset. */
    private static RecipeView chest() {
        RecipeView chest = CraftBench.find("chest", 0);
        assertEquals("the chest recipe must really be 3 wide for this test to mean anything",
            3, chest.width());
        assertEquals(3, chest.height());
        assertTrue("and RecipeView must agree it needs a table", chest.requiresTable());
        return chest;
    }

    @Test
    public void aChestInThePlayers2x2WindowIsRefusedWithTheBenchNamedAndNothingClicked() {
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 8);
        CraftController c = new CraftController(chest());

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("must be terminal", out.terminal());
        assertFalse("a 3x3 recipe cannot be crafted in a 2x2 grid: " + out.message(), out.ok());
        assertTrue("the message must name the crafting table, which is the actionable part: "
                + out.message(), out.message().contains("crafting table"));
        assertTrue("and it must say what the shapes were, so the reason is checkable: " + out.message(),
            out.message().contains("3x3") && out.message().contains("2x2"));
        // The load-bearing assertion. "Not silently proceeding" is a claim about CLICKS, not about the
        // wording: a controller that emitted this message after filling two squares would have spent
        // ingredients and left them where the window close drops them.
        assertEquals("nothing may be clicked: the refusal is knowable before touching the window",
            0, win.clicks);
        assertEquals("the eight planks must be untouched", 8, win.serverStoredCount("planks", 0));
        assertEquals("", win.matrixContents());
        assertNull(win.cursor());
    }

    @Test
    public void theSameChestRecipeSucceedsOnceARealBenchIsOpen() {
        // The control: proves the refusal above is about the GRID, not about this recipe being beyond
        // the controller. Also the only test here that exercises a non-zero window id and a 3x3
        // matrixSlot mapping, where 1 + col + row * 3 differs from the 2x2 arithmetic.
        FakeCraftWindow win = FakeCraftWindow.bench().carrying(0, "planks", 0, 8);
        CraftController c = new CraftController(chest());

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("a chest is craftable at a bench: " + out.message(), out.ok());
        assertEquals("and vanilla's own matcher had to accept the ring layout for this to appear",
            1, win.serverStoredCount("chest", 0));
        assertEquals("the eight planks must be spent", 0, win.serverStoredCount("planks", 0));
        assertEquals("the bench matrix must be left empty -- ContainerWorkbench.onContainerClosed drops "
                + "every stack in it", "", win.matrixContents());
        assertNull(win.cursor());
    }

    @Test
    public void aWindowWithNoCraftingGridAtAllIsRefusedRatherThanTreatedAsA1x1() {
        // A chest or a furnace: windowOpen() is true and there is no matrix. Distinguished from the
        // 3x3 case on purpose -- "open a crafting table" is the wrong advice when the answer is "this
        // window cannot craft anything", and gridWidth 0 must not be read as a 1x1 grid that happens to
        // fit one-ingredient recipes.
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 4);
        win.hasGrid = false;
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertFalse("a gridless window cannot craft: " + out.message(), out.ok());
        assertTrue("and must say so rather than naming a missing ingredient: " + out.message(),
            out.message().contains("no crafting grid"));
        assertEquals("nothing may be clicked into a window with no matrix", 0, win.clicks);
    }
}
