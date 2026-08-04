package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Every way a craft can END must leave the matrix and the cursor empty, or say exactly what it could
 * not recover.
 *
 * <p><b>Why this is the sharpest rule in the package.</b> An abandoned matrix is not merely wrong
 * internal state, it is ITEMS, and vanilla hands them to the floor the moment the window closes:
 * {@code ContainerPlayer.onContainerClosed:83-98} and {@code ContainerWorkbench:62-78} drop every matrix
 * stack, {@code Container:516-525} drops the cursor. And they are invisible to the model:
 * {@code world_view} reads {@code mainInventory} only, never the craft matrix or the cursor stack, so a
 * stranded ingredient shows up as items mysteriously missing from the player's pockets with nothing in
 * any report pointing at the craft. {@code HoldController} shipped twice with a terminal path that
 * abandoned state it owned; the fix there was to funnel every exit through one place, and
 * {@link CraftController#finish} is that place here.
 *
 * <p>The last test is the honest-failure half of the same rule: when there is genuinely nowhere to put
 * an ingredient back, the outcome must NAME it rather than fall silent. Silence there is the same
 * "reports healthy while being wrong" shape as claiming a craft the server refused.
 */
public class NoTerminalPathStrandsAnIngredientTest {

    private static final int TICK_BOUND = 60;

    /**
     * Drive to the point where the matrix is FULL and the craft has not been taken yet.
     *
     * <p>Two ticks: CHECKING then PLACING, which issues every placement click in one pass. Asserted
     * rather than assumed, because if the machine ever stops filling the grid in one pass this helper
     * would silently start cancelling an EMPTY matrix and every test below would pass while testing
     * nothing.
     */
    private static void placeEverything(CraftController c, FakeCraftWindow win) {
        c.tick(win);
        win.advanceTick();
        c.tick(win);
        win.advanceTick();
        assertFalse("must still be mid-craft after placing", c.isDone());
        assertEquals("the matrix must be full for a cancel to have anything to recover",
            "1x minecraft:planks/0 at (0,0), 1x minecraft:planks/0 at (0,1), "
                + "1x minecraft:planks/0 at (1,0), 1x minecraft:planks/0 at (1,1)",
            win.matrixContents());
    }

    @Test
    public void aCancelMidCraftReturnsEveryPlacedIngredientToTheInventory() {
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 4);
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));
        placeEverything(c, win);

        c.requestCancel();
        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("a cancel is terminal", out.terminal());
        assertFalse("and is not a success", out.ok());
        assertTrue("it must say it was cancelled: " + out.message(), out.message().contains("cancelled"));
        assertEquals("the matrix must be emptied by the cancel itself, not left for the window close to "
                + "drop on the floor", "", win.matrixContents());
        assertNull("and nothing may be left on the cursor", win.cursor());
        assertEquals("all four planks must be back in the player's inventory",
            4, win.serverStoredCount("planks", 0));
        assertTrue("and the outcome must say the ingredients came back, so the caller knows it is not "
                + "down four planks: " + out.message(), out.message().contains("returned"));
    }

    @Test
    public void theWindowChangingUnderACraftEndsItAndStillClearsTheGrid() {
        // A bench closing mid-craft. The window id is what detects it, and the check matters beyond
        // tidiness: slot 5 is a matrix square in a 3x3 workbench and an ARMOUR slot in the player's own
        // 2x2 window, so a controller carrying stale indices would keep clicking into slots that exist
        // and mean something else -- reporting progress while stripping the player's armour.
        FakeCraftWindow win = FakeCraftWindow.bench().carrying(0, "planks", 0, 8);
        CraftController c = new CraftController(CraftBench.find("chest", 0));
        c.tick(win);
        win.advanceTick();
        c.tick(win);
        win.advanceTick();
        assertFalse("must be mid-craft", c.isDone());
        assertFalse("with a filled bench matrix", win.matrixContents().isEmpty());

        win.windowId = 11;
        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertFalse("a craft whose window changed must fail: " + out.message(), out.ok());
        assertTrue("and say the window changed rather than blaming an ingredient: " + out.message(),
            out.message().contains("window changed"));
        assertEquals("the grid must still be cleared on the way out", "", win.matrixContents());
        assertNull(win.cursor());
        assertEquals("and every plank recovered", 8, win.serverStoredCount("planks", 0));
    }

    @Test
    public void anIngredientThatCannotBeHandedBackIsNamedRatherThanSilentlyAbandoned() {
        // The honest-failure half. One storage slot, holding something that cannot merge with a plank, so
        // the cleanup genuinely has nowhere to put one. What must NOT happen is a quiet terminal outcome:
        // the plank is about to be dropped on the floor by the window close, world_view cannot see it, and
        // the report is the only chance the caller has of knowing.
        FakeCraftWindow win = new FakeCraftWindow(2, 1, 0).storageFullOf("cobblestone", 0, 64);
        win.putInMatrix(0, 0, "planks", 0, 1);
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));
        c.requestCancel();

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("must terminate rather than loop trying to put it down", out.terminal());
        assertFalse("and must not report success while an ingredient is about to hit the floor: "
                + out.message(), out.ok());
        assertTrue("the outcome must name what is stranded and where: " + out.message(),
            out.message().contains("stranded") && out.message().contains("planks"));
        assertTrue("and say what closing the window would do to it: " + out.message(),
            out.message().contains("DROP"));
        // Left where it is, deliberately: lifting it onto the cursor moves it from one thing the window
        // close drops to another, and costs a click.
        assertEquals("1x minecraft:planks/0 at (0,0)", win.matrixContents());
        assertNull("nothing may be left riding the cursor either", win.cursor());
    }

    @Test
    public void aPartialRecoveryReportsWhatCameBackAsWellAsWhatDidNot() {
        // Both halves of the cleanup in one outcome, which is what lets a caller reconcile its inventory:
        // told only what was stranded, it cannot tell whether the other four planks are gone too.
        //
        // The setup is a foreign item in a bench cell the recipe does not use. Vanilla then matches
        // nothing -- checkMatch requires every square outside the pattern to be EMPTY -- so this is also
        // the honest report for "the squares are right and there is still no output", and the diamond is
        // the reason. One free storage slot, so the four planks can come back and the diamond cannot.
        FakeCraftWindow win = FakeCraftWindow.bench().storageFullOf("cobblestone", 0, 64);
        win.carrying(0, "planks", 0, 4);
        win.putInMatrix(2, 2, "diamond", 0, 1);
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertFalse("an outcome leaving an ingredient on the way to the floor is not a success: "
                + out.message(), out.ok());
        assertTrue("it must say vanilla matched no recipe, not blame a missing ingredient: "
                + out.message(), out.message().contains("result slot is empty"));
        assertTrue("it must name what could not be handed back: " + out.message(),
            out.message().contains("stranded") && out.message().contains("diamond"));
        assertTrue("AND what did come back, or the caller cannot tell whether the planks are lost too: "
                + out.message(), out.message().contains("returned 4 ingredient stack(s)"));
        assertEquals("the four planks really are back", 4, win.serverStoredCount("planks", 0));
        assertEquals("and the diamond really is still in the cell the message names",
            "1x minecraft:diamond/0 at (2,2)", win.matrixContents());
    }
}
