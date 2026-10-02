package net.marcloud.mcp.core.eval;

import java.util.List;

import net.marcloud.mcp.core.drivers.act.ActActuator;
import net.marcloud.mcp.core.drivers.craft.Craft;
import net.marcloud.mcp.core.drivers.craft.CraftController;
import net.marcloud.mcp.core.drivers.craft.CraftOutcome;
import net.marcloud.mcp.core.drivers.craft.CraftWindow;
import net.marcloud.mcp.core.drivers.craft.RecipeView;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * The ingredient a window learns about AFTER it opened.
 *
 * <p><b>The gap that let a whole eleven-step chain die.</b> A goal-only run ("break the iron ore")
 * reached the bench recursion having dug its own log, and died on the first planks craft with a
 * refusal naming a server that had never refused anything. Every capability it had touched before
 * that point had behaved correctly, which is exactly the kind of failure a single-capability task
 * cannot see: T12 and T13 both craft, and both place their ingredients <em>before</em> the window
 * exists. Nothing in the suite put an ingredient into a window that was already open.
 *
 * <p><b>What was actually wrong, in two parts, both in the substrate.</b>
 *
 * <ol>
 *   <li>The window's SERVER copy of the player's stacks was a snapshot taken at construction. A log
 *       dropped into the world afterwards was in the client's view (which reads the world's real
 *       inventory) and absent from the server's, so the server lifted nothing from an empty slot
 *       while the client had already picked the log up -- and the two sides then disagreed about
 *       whether the player owned it at all.</li>
 *   <li>The MATRIX was one shared array. The server applying a click into the matrix therefore read
 *       and mutated the CLIENT's cell, lifting the freshly-placed ingredient back off it onto the
 *       server cursor. {@code serverSlots} existed for exactly this and was written by nothing.</li>
 * </ol>
 *
 * <p>The symptom each produced was the same and neither named itself: the matrix read back empty,
 * so {@code CraftController}'s SETTLING step reported a refused placement. Its refusal was CORRECT
 * given what the window told it. The defect was upstream of the controller, in the thing the
 * controller reads.
 *
 * <p><b>Scored on the world, not on the controller's opinion.</b> The planks are in the player's
 * inventory, the log is not, the matrix is clear and the cursor is empty. The outcome's own verdict
 * is asserted too, but as the SECOND half: a controller that reported success while the world held
 * no planks is precisely the failure this whole harness exists to catch.
 */
public class AnIngredientThatArrivesAfterTheWindowOpenedTest {

    private static final int TICK_BOUND = 400;

    /** One log turned into four planks, on the real recipe table. */
    private static RecipeView planks() {
        List<RecipeView> recipes = Craft.recipesFor("planks").recipes();
        assertTrue("the real recipe table has no planks recipe", !recipes.isEmpty());
        return recipes.get(0);
    }

    private static CraftOutcome drive(SimCraftWindow win) {
        CraftController c = new CraftController(planks());
        CraftOutcome out = null;
        for (int i = 0; i < TICK_BOUND && (out == null || !out.terminal()); i++) {
            out = c.tick(win);
            win.advanceTick();
        }
        assertNotNull("the controller never reached a verdict", out);
        return out;
    }

    /**
     * The ingredient is in the bag BEFORE the window opens.
     *
     * <p>The control arm, and the shape T12 and T13 both have. It passes today, which is why the
     * defect survived: every existing craft task looks like this one.
     */
    @Test
    public void anIngredientPresentBeforeTheWindowOpenedIsCrafted() {
        SimWorld world = new SimWorld();
        SimCraftWindow win = SimCraftWindow.playerWindow(world).carrying(0, "log", 0, 1);

        CraftOutcome out = drive(win);

        assertTrue("the craft must succeed when the ingredient was there all along: " + out.message(),
                out.ok());
        assertEquals("four planks must exist", 4, world.count("planks"));
        assertEquals("the log must have been spent", 0, world.count("log"));
    }

    /**
     * The ingredient arrives while the window is ALREADY open, which is what a dig does.
     *
     * <p>This is the arm that separated the cause. Nothing else differs from the control: same
     * recipe, same grid, same controller, same click sequence. Only the TIMING of the log's arrival
     * changes, and that alone used to fail.
     */
    @Test
    public void anIngredientThatArrivesWhileTheWindowIsOpenIsStillCrafted() {
        SimWorld world = new SimWorld();
        SimCraftWindow win = SimCraftWindow.playerWindow(world);

        world.give(0, "log", 1);

        CraftOutcome out = drive(win);

        assertTrue("a log the world handed the player must be craftable: " + out.message(), out.ok());
        assertEquals("four planks must exist", 4, world.count("planks"));
        assertEquals("the log must have been spent", 0, world.count("log"));
        assertEquals("the matrix must be clear", "(clear)", win.matrixContents());
        assertEquals("nothing may be left on the cursor", null, win.cursor());
    }

    /**
     * The same, with the log arriving the way it arrives in a real run: dug out of the ground.
     *
     * <p>Included because "the world put it there" and "a dig put it there" are the same fact here,
     * and the dig is the one the failing chain actually did. If only the {@code give} arm were
     * fixed, this one would keep failing while the suite looked green.
     */
    @Test
    public void anIngredientDugWhileTheWindowIsOpenIsStillCrafted() {
        SimWorld world = new SimWorld().plain(64, "stone", -2, 2, -2, 2).standOn(0, 64, 0);
        world.box(1, 64, 0, 1, 64, 0, "log");
        SimCraftWindow win = SimCraftWindow.playerWindow(world);

        world.instantBreak(1, 64, 0, ActActuator.Face.UP);
        assertEquals("the dig must have yielded the log this test is about", 1, world.count("log"));

        CraftOutcome out = drive(win);

        assertTrue("a dug log must be craftable: " + out.message(), out.ok());
        assertEquals("four planks must exist", 4, world.count("planks"));
        assertEquals("the log must have been spent", 0, world.count("log"));
    }


    /**
     * The server's copy of the player's stacks has to follow the world's.
     *
     * <p>The substrate's own two-sided claim, asserted directly rather than through a craft. The
     * craft arms above prove the BEHAVIOUR; this proves the CAUSE, and it is the arm that would
     * still fail if the mirror went stale again in a way the recipes happened to route around.
     *
     * <p>Read on both sides of the same container slot after one click: a dig's log must be visible
     * to the client (it reads the world's real inventory) AND to the server (its private mirror),
     * or the two disagree about whether the player owns it.
     */
    @Test
    public void theServersCopyOfTheInventoryFollowsTheWorld() {
        SimWorld world = new SimWorld();
        SimCraftWindow win = SimCraftWindow.playerWindow(world);

        world.give(0, "log", 1);
        // Any click reconciles the two copies; the pick-up is the cheapest one that does.
        int slot = slotHolding(win, "log");
        assertTrue("the container must expose the hotbar slot the log is in", slot >= 0);
        win.click(slot, CraftWindow.LEFT);

        // The pick-up moved the log to the cursor on the client. The server must have done the same:
        // a server that still believed the log was in the slot would disagree with the client about
        // where the player's only item is, which is the defect in its rawest form.
        assertEquals("the client's cursor", "log", win.cursor() == null ? null : win.cursor().item());
        assertEquals("the server's cursor", "log",
                win.serverCursor() == null ? null : win.serverCursor().item());
        assertEquals("both sides must agree the slot is now empty", null, win.stackAt(slot));
        assertEquals("and so must the server", null, win.serverStackAt(slot));
    }

    /**
     * Placing an ingredient must leave it in the matrix on BOTH sides.
     *
     * <p>This is the second half of the defect, and it is invisible to every craft above. When the
     * matrix was one shared array, the server applying the placement click re-read the CLIENT's
     * just-filled cell and lifted the ingredient back off it onto the server cursor -- so the cell
     * read back empty and the controller reported a refused placement the server had accepted. A
     * craft still came out right when the recipe wanted the cell emptied anyway (the take consumed
     * it), which is exactly why T12 and T13 stayed green.
     *
     * <p>So the assertion is on the matrix cell immediately after the placement click and BEFORE
     * any take: both sides must still hold the ingredient. Reading it after the take would test
     * nothing, because a take empties the cell either way.
     */
    @Test
    public void placingAnIngredientLeavesItInTheMatrixOnBothSides() {
        SimWorld world = new SimWorld();
        SimCraftWindow win = SimCraftWindow.playerWindow(world).carrying(0, "log", 0, 1);

        // Pick the log up, then place it into cell (0,0) -- the two clicks a placement is.
        win.click(slotHolding(win, "log"), CraftWindow.LEFT);
        win.click(win.matrixSlot(0, 0), CraftWindow.RIGHT);

        assertEquals("the client must have the log in the cell", 1,
                count(win.stackAt(win.matrixSlot(0, 0))));
        assertEquals("the server must ALSO have it there -- a server click must not lift the"
                        + " client's ingredient back off the cell", 1,
                count(win.serverStackAt(win.matrixSlot(0, 0))));
        assertEquals("and the server's cursor must be empty", 0, count(win.serverCursor()));
    }

    /** A stack's size, or 0 for an empty one, so an assertion reads as a count rather than a null. */
    private static int count(net.marcloud.mcp.core.drivers.craft.CraftInventory.Held held) {
        return held == null ? 0 : held.count();
    }

    /** The container storage slot currently holding {@code item}, or -1. */
    private static int slotHolding(SimCraftWindow win, String item) {
        for (int slot : win.storageSlots()) {
            var held = win.stackAt(slot);
            if (held != null && item.equals(held.item())) {
                return slot;
            }
        }
        return -1;
    }
}