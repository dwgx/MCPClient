package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The three facts {@link CraftController}'s confirm step checks, each shown to be load-bearing, plus the
 * one placement error a symmetric recipe cannot catch.
 *
 * <p>Every test here exists because a mutation of the production side SURVIVED the rest of this package.
 * Recorded as the reason, because a test whose absence nothing noticed is the only kind worth adding:
 *
 * <ul>
 *   <li>Filling the grid TRANSPOSED passed every other test in the package. crafting_table (2x2 of one
 *       item) and chest (a ring) are both symmetric about the diagonal, so swapping row and col produces
 *       the identical grid. This is the exact hazard the recipe reader documents --
 *       {@code InventoryCrafting.getStackInRowAndColumn(row, column)} indexes {@code row + column *
 *       inventoryWidth}, so its first argument is really the COLUMN, and code written from the parameter
 *       names transposes every grid and still compiles.
 *   <li>Deleting the "did the output reach the inventory" check passed everything. It is the only one of
 *       the three that says the player now OWNS the item.
 *   <li>Deleting the "is the matrix empty after the take" check passed everything. It is what catches a
 *       REJECTED take, where the resync puts the ingredients back.
 * </ul>
 */
public class TheCraftIsProvenNotAssumedTest {

    private static final int TICK_BOUND = 60;

    /**
     * Sticks: one plank above another. 1 wide, 2 tall -- the only vanilla recipe shape that is
     * unambiguously asymmetric AND fits the player's own 2x2 grid.
     */
    private static RecipeView sticks() {
        RecipeView sticks = CraftBench.find("stick", 0);
        assertEquals("the stick recipe must be 1 wide for the transposition to be detectable", 1,
            sticks.width());
        assertEquals("and 2 tall", 2, sticks.height());
        assertFalse("it must fit the player's own grid", sticks.requiresTable());
        return sticks;
    }

    @Test
    public void anAsymmetricRecipeReachesTheRightSquaresAndNotTheirTransposition() {
        // Vanilla slides the pattern over every offset and accepts the horizontal MIRROR
        // (ShapedRecipes.matches:56-75), but never a rotation -- so two planks side by side do not match a
        // recipe of two planks stacked, and the real matcher behind the fake's result slot is what decides
        // that. A transposed placement therefore yields no output here, while on the symmetric recipes it
        // is indistinguishable from a correct one.
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 2);
        CraftController c = new CraftController(sticks());

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("a vertical 1x2 recipe must craft in a 2x2 grid: " + out.message(), out.ok());
        assertEquals("and the output count comes from the recipe, not from the number of squares",
            4, win.serverStoredCount("stick", 0));
        assertTrue("the message must carry that count: " + out.message(),
            out.message().contains("crafted 4x stick"));
        assertEquals("both planks spent", 0, win.serverStoredCount("planks", 0));
        assertEquals("", win.matrixContents());
        assertNull(win.cursor());
    }

    @Test
    public void anOutputThatDoesNotReachTheInventoryIsNotReportedAsCrafted() {
        // The third confirm fact, and the only one that says the player OWNS the item. Everything up to
        // here can be true of a craft whose output went somewhere else: the matrix really emptied, the
        // cursor really is clear, and the client really did see an output. Something emptying that slot
        // during the round trip -- a hopper under the window, a resync that disagrees about it -- leaves
        // exactly that state, and reporting "crafted" for it tells the model it has an item it does not.
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 4);
        win.vanishesOnTake = "crafting_table";
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("must terminate", out.terminal());
        assertFalse("an output that never arrived is not a crafted item: " + out.message(), out.ok());
        assertTrue("and the message must say the output did not survive rather than blaming the layout: "
                + out.message(), out.message().contains("no crafting_table reached the inventory"));
        assertEquals("which the window agrees with", 0, win.serverStoredCount("crafting_table", 0));
    }

    @Test
    public void aCraftThatSucceededButLeftAnIngredientBehindIsReportedAsAFailure() {
        // The success path runs the same cleanup, and if it ever finds something, saying so IS the point:
        // "crafted 1x crafting_table" while an ingredient sits where the window close drops it is the
        // healthy-looking report this codebase keeps finding.
        //
        // Reachable, not contrived. The confirm step proves the PLANNED cells and the cursor are empty, so
        // the only thing it can miss is a cell the recipe never used -- and at the moment of the take those
        // were provably empty too, because vanilla's checkMatch refuses a grid with anything outside the
        // pattern, and the output did appear. That leaves exactly one interval: the round trip after the
        // take, during which the controller has stopped acting and has not yet looked. A hopper, a second
        // client, or a resync delivering into a bench cell all land there.
        //
        // A 2x2 recipe at a 3x3 bench, so there are cells outside the plan. Storage is full so the arrival
        // cannot be handed back, which is what makes it STRANDED rather than merely recovered.
        FakeCraftWindow win = FakeCraftWindow.bench().storageFullOf("cobblestone", 0, 64);
        win.carrying(0, "planks", 0, 4);
        win.duringTakeRoundTrip = () -> win.putInMatrix(2, 2, "diamond", 0, 1);
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("the craft itself must have gone through, or this is testing the wrong path: "
                + out.message(), out.message().contains("crafted 1x crafting_table"));
        assertFalse("but an outcome holding an ingredient on its way to the floor is not a success: "
                + out.message(), out.ok());
        assertTrue("and it must name what is stranded and where: " + out.message(),
            out.message().contains("stranded") && out.message().contains("diamond"));
        assertEquals("the crafted table is genuinely owned, which is what makes the flip a judgement "
                + "about the stranding rather than about the craft",
            1, win.serverStoredCount("crafting_table", 0));
    }

    @Test
    public void aRejectedTakeIsCaughtByTheMatrixNotBeingEmptyAfterwards() {
        // The second confirm fact. The take is the click that SPENDS the ingredients
        // (SlotCrafting.onPickupFromSlot:134-160 decrements every occupied cell), so a take the server
        // refuses is a craft that did not happen -- while on the client the matrix emptied and an output
        // appeared. The resync then puts the ingredients back, and that is the evidence.
        //
        // Click 6 is the take: 1 pick-up + 4 placements + the result.
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 4);
        win.rejectAtClick = 6;
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("the window must have locked for this to be the case under test", win.locked);
        assertFalse("a take the server refused is not a craft: " + out.message(), out.ok());
        assertTrue("and must be reported as the take being refused: " + out.message(),
            out.message().contains("did not accept the take"));
        assertEquals("no crafting table on the server's side", 0,
            win.serverStoredCount("crafting_table", 0));
        // Same terminal-path rule as everywhere else: the resynced ingredients must not be left in the
        // grid for the window close to drop.
        assertEquals("the recovered ingredients must not be left in the matrix", "",
            win.matrixContents());
        assertNull(win.cursor());
    }
}
