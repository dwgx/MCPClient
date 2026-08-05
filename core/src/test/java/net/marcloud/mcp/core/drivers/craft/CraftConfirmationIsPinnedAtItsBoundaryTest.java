package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCraftResult;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;
import org.junit.Test;

/**
 * The boundary the confirm steps stand on: WHICH variant, and HOW LONG a verdict is waited for.
 *
 * <p>Every test here exists because a mutation survived the whole package on 2026-08-05, and the
 * reason they survived is one shape repeated six times -- the suite drove only fixtures on which the
 * mutated term was constant. THREE are metadata: every recipe the controller is ever driven with
 * (crafting_table, chest, stick) is all-wildcard planks with output metadata 0, so both halves of
 * {@code paysFor}'s comparison in SETTLING and the {@code outputMeta} term in {@code storesOutput} are
 * constant-true conjuncts on every path walked, and the live window's own read of a stack's variant was
 * asserted against a fixture whose variant was 0 anyway. ONE is the settle wait, asserted only through
 * expressions that SCALE with {@code SETTLE_TICKS} rather than pinning it. TWO have no headless caller
 * at all.
 *
 * <p><b>The fixture that fixes the metadata half is wool dyeing.</b> {@code RecipesDyes:20} registers
 * dye/(15-i) + white wool -> wool/i. Vanilla has several recipes with an exact-variant ingredient and a
 * nonzero output variant (stained glass, stained clay, carpet); this one is the smallest and needs no
 * table. What makes it the right one is that its OUTPUT and one of its INGREDIENTS are the same item at
 * different variants -- wool/i out of wool/0 -- so the white wool the player came in with is already a
 * fixture that differs from the crafted output in METADATA ALONE. That is the property a variant-blind
 * comparison cannot survive and an item-name fixture cannot detect.
 *
 * <p><b>Those last two are pinned differently, and labelled as such</b>:
 * {@code LiveCraftWindow.click}'s click MODE and the workable-inventory BOUND both
 * sit behind a live {@code Minecraft}, {@code EntityPlayerSP} and {@code PlayerControllerMP}. Their
 * tests here assert the CONSEQUENCE behaviourally against real vanilla objects and then pin the
 * production expression against the source, the same drift-net shape {@code NativeBridgeContractTest}
 * uses for the JNI table. That is stated in each message so no reader mistakes a source pin for
 * behavioural coverage of the write path.
 */
public class CraftConfirmationIsPinnedAtItsBoundaryTest {

    /** Bound far above the controller's own budget, so a stuck machine fails instead of hanging. */
    private static final int TICK_BOUND = 60;

    /** {@code EnumDyeColor.ORANGE}'s wool variant, and the one this file crafts. */
    private static final int ORANGE_WOOL = 1;

    /** Its neighbour, one dye variant away: the wrong answer that shares the item NAME. */
    private static final int MAGENTA_WOOL = 2;

    /**
     * The dyeing recipe for one wool variant, with the properties this file leans on asserted.
     *
     * <p>Asserted rather than assumed because the fixture is the whole argument: if the recipe ever
     * stopped demanding an EXACT dye variant, or its output variant became 0, every metadata claim
     * below would still pass while testing nothing -- which is precisely how the six mutations
     * survived.
     */
    private static RecipeView dyedWool(int woolMeta) {
        RecipeView view = CraftBench.find("wool", woolMeta);
        assertEquals("the fixture needs a NONZERO output variant, or storesOutput's metadata term is "
                + "a constant-true conjunct and a variant-blind confirm is undetectable",
            woolMeta, view.outputMeta());
        assertFalse("and it must fit the player's own 2x2 grid", view.requiresTable());
        return view;
    }

    /**
     * The cell of {@code view} that places {@code item}, proven to demand ONE exact variant.
     *
     * <p>Found by ingredient rather than by coordinate so the tests do not depend on the order
     * {@code RecipeLayoutReader} synthesises a shapeless layout in.
     */
    private static RecipeView.Cell exactCellOf(RecipeView view, String item) {
        for (RecipeView.Cell cell : view.cells()) {
            if (cell.item().equals(item)) {
                assertFalse(item + " must be an EXACT-variant ingredient, or swapping its variant is "
                        + "something paysFor is entitled to accept", cell.anyMeta());
                return cell;
            }
        }
        throw new AssertionError(item + " is not an ingredient of " + view.output() + "/"
                + view.outputMeta() + "; cells: " + view.cells());
    }

    /**
     * A player window carrying EXACTLY one craft's worth of every ingredient, one stack each.
     *
     * <p>Exact counts on purpose: the pick-up empties its source slot and the placements empty the
     * cursor, so storage index 0 is free again by the time the output is parked. That makes where the
     * output lands deterministic, which is what lets a test reach in during the round trip after the
     * take and disturb that exact slot.
     */
    private static FakeCraftWindow carryingOneCraftFor(RecipeView view) {
        FakeCraftWindow win = FakeCraftWindow.playerWindow();
        int at = 0;
        for (RecipeView.Ingredient want : view.demand()) {
            win.carrying(at++, want.item(), want.meta(), want.count());
        }
        return win;
    }

    // ===== synthetic containers, for the reads that need a real vanilla Container =====

    /** A container we can add slots to; {@code addSlotToContainer} is protected in vanilla. */
    private static final class Bench extends Container {
        InventoryCrafting matrix;
        final InventoryCraftResult result = new InventoryCraftResult();
        final InventoryPlayer playerInv = new InventoryPlayer(null);

        Slot add(Slot s) {
            return addSlotToContainer(s);
        }

        @Override
        public boolean canInteractWith(EntityPlayer p) {
            return true;
        }
    }

    /**
     * Matrix FIRST and result LAST -- deliberately not vanilla's arithmetic.
     *
     * <p>Copied from the convention {@code CraftWindowLayoutIsDerivedNotAssumedTest} established: an
     * implementation computing {@code 1 + col + row*width} passes both vanilla fixtures and fails only
     * on a container like this one. Here it also protects the variant reads below, which address slots
     * this container chose rather than slots the test predicted.
     */
    private static Bench resultLastBench() {
        Bench c = new Bench();
        c.matrix = new InventoryCrafting(c, 2, 2);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                c.add(new Slot(c.matrix, col + row * 2, 0, 0));
            }
        }
        c.add(new SlotCrafting(null, c.matrix, c.result, 0, 0, 0));
        for (int i = 0; i < c.playerInv.mainInventory.length; i++) {
            c.add(new Slot(c.playerInv, i, 0, 0));
        }
        return c;
    }

    /** Vanilla's {@code ContainerPlayer} shape: result 0, matrix 1-4, ARMOUR 5-8, then 9-44. */
    private static Bench containerPlayerShape() {
        Bench c = new Bench();
        c.matrix = new InventoryCrafting(c, 2, 2);
        c.add(new SlotCrafting(null, c.matrix, c.result, 0, 0, 0));
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                c.add(new Slot(c.matrix, col + row * 2, 0, 0));
            }
        }
        // ContainerPlayer:39 -- getSizeInventory() - 1 - k, i.e. player indices 39,38,37,36.
        for (int k = 0; k < 4; k++) {
            c.add(new Slot(c.playerInv, c.playerInv.getSizeInventory() - 1 - k, 0, 0));
        }
        for (int l = 0; l < 3; l++) {
            for (int j = 0; j < 9; j++) {
                c.add(new Slot(c.playerInv, j + (l + 1) * 9, 0, 0));
            }
        }
        for (int i = 0; i < 9; i++) {
            c.add(new Slot(c.playerInv, i, 0, 0));
        }
        return c;
    }

    // ===== LiveCraftWindow.held: the variant of every stack the controller compares =====

    /**
     * A stack's variant must survive the read, or every exact-variant ingredient becomes variant 0.
     *
     * <p>Two dye stacks that differ in METADATA ALONE, which is the only fixture shape that can catch
     * this: the neighbouring test read a single {@code new ItemStack(Items.stick, 7)}, whose damage is
     * 0, so its {@code assertEquals(0, there.meta())} held whether the code read the stack or returned
     * a constant.
     */
    @Test
    public void everyStackTheWindowReportsCarriesItsOwnVariantAndNotVariantZero() {
        CraftBench.boot();
        Bench c = resultLastBench();
        // RecipesDyes:20 -- dye/(15-i) is what dyes white wool to variant i.
        int orangeDye = 15 - ORANGE_WOOL;
        int magentaDye = 15 - MAGENTA_WOOL;
        c.playerInv.mainInventory[0] = new ItemStack(Items.dye, 3, orangeDye);
        c.playerInv.mainInventory[1] = new ItemStack(Items.dye, 3, magentaDye);
        c.playerInv.mainInventory[2] = new ItemStack(Items.dye, 3, 0);

        assertEquals("this container puts the result LAST, so the slots below are ones it chose "
                + "rather than ones the test predicted", 4, LiveCraftWindow.resultSlotOf(c));
        int[] storage = LiveCraftWindow.storageSlotsOf(c, c.playerInv,
                c.playerInv.mainInventory.length);
        CraftInventory.Held orange = LiveCraftWindow.stackAtOf(c, storage[0]);
        CraftInventory.Held magenta = LiveCraftWindow.stackAtOf(c, storage[1]);
        CraftInventory.Held inkSac = LiveCraftWindow.stackAtOf(c, storage[2]);

        assertNotNull("the fixture must be readable at all before its variant means anything", orange);
        assertEquals("the two fixtures must be the same ITEM -- if item identity alone told them "
                + "apart, a variant-blind read would still pass", orange.item(), magenta.item());
        assertEquals("dye/" + orangeDye + " must read as dye/" + orangeDye + ": read as 0 it becomes "
                + "an ink sac, so feasibility reports the ingredient missing, sourceSlot never finds "
                + "it, and SETTLING rejects a cell the controller itself filled correctly",
            orangeDye, orange.meta());
        assertEquals("and its neighbour must read as ITSELF, not as the other variant", magentaDye,
            magenta.meta());
        assertEquals("a genuinely variant-0 stack still reads 0, so this is a read and not a "
                + "blanket nonzero", 0, inkSac.meta());
        assertEquals("with the name normalised the way the ingredient side normalises it", "dye",
            orange.item());
        assertEquals("and the count not crossed with the variant", 3, orange.count());
    }

    // ===== SETTLING verifies with the rule placement used, not with "something is there" =====

    /**
     * A cell resynced to a DIFFERENT VARIANT of the right item is not this controller's own work.
     *
     * <p>{@code paysFor}'s javadoc states the rule outright: "a verification with looser rules than
     * the placement would accept a resynced cell as its own work". Every rejection fixture in the
     * package resyncs cells to EMPTY, so a check of mere non-emptiness fires identically on all of
     * them; only a wrong variant tells the two apart. And the consequence is not a wrong message -- the
     * looser check goes on to click the result on a grid it never verified and crafts the NEIGHBOURING
     * WOOL COLOUR, which is the model being handed an item it did not ask for.
     */
    @Test
    public void aCellResyncedToTheWrongVariantIsNotAcceptedAsThisControllersOwnWork() {
        RecipeView orange = dyedWool(ORANGE_WOOL);
        RecipeView.Cell dyeCell = exactCellOf(orange, "dye");
        int wrongDye = exactCellOf(dyedWool(MAGENTA_WOOL), "dye").meta();
        assertTrue("the swapped-in stack must differ from the planned one in METADATA ALONE: if the "
                + "ITEM differed too, a check that compares only item names would catch it and the "
                + "variant half of the comparison would still be untested",
            wrongDye != dyeCell.meta());
        FakeCraftWindow win = carryingOneCraftFor(orange);
        CraftController c = new CraftController(orange);

        // Swapped once the planned variant is in the cell and before the controller reads it back.
        // The round trip SETTLING waits out is the only interval a resync can land in, and a resync
        // hands back the SERVER's state -- which is a wrong cell just as easily as an empty one.
        boolean swapped = false;
        int ticks = 0;
        CraftOutcome out;
        do {
            out = c.tick(win);
            win.advanceTick();
            CraftInventory.Held there = win.stackAt(win.matrixSlot(dyeCell.row(), dyeCell.col()));
            if (!swapped && there != null && there.meta() == dyeCell.meta()) {
                win.putInMatrix(dyeCell.row(), dyeCell.col(), dyeCell.item(), wrongDye, 1);
                swapped = true;
            }
            assertTrue("must not run past the bound", ++ticks < TICK_BOUND);
        } while (!out.terminal());

        assertTrue("the swap must have landed, or nothing here was under test", swapped);
        assertFalse("a craft whose cell holds another variant is not a verified craft: "
                + out.message(), out.ok());
        assertTrue("it must be reported as the placement not being accepted: " + out.message(),
            out.message().contains("did not accept the placement"));
        assertTrue("and must name the variant it wanted against the variant it found, which is the "
                + "half of paysFor an all-wildcard recipe never exercises: " + out.message(),
            out.message().contains("cell (" + dyeCell.row() + "," + dyeCell.col() + ") should hold "
                    + dyeCell.item() + "/" + dyeCell.meta() + " and holds 1x " + dyeCell.item() + "/"
                    + wrongDye));
        assertEquals("the result must never be clicked on a grid that was not verified: that click is "
                + "what SPENDS the ingredients, and here it would spend them on the wrong colour",
            0, win.resultClicks);
        assertEquals("so no wool of the neighbouring variant may exist on the server -- that is the "
                + "item a looser verification would have crafted and reported",
            0, win.serverStoredCount("wool", MAGENTA_WOOL));
        assertEquals("and none of the one that was asked for either", 0,
            win.serverStoredCount("wool", ORANGE_WOOL));
        assertEquals("no ingredient may be left in the matrix on a terminal path", "",
            win.matrixContents());
        assertNull("and nothing on the cursor, which a window close drops on the floor", win.cursor());
    }

    /**
     * The positive counterpart: an undisturbed exact-variant craft delivers THAT variant.
     *
     * <p>Without this the rejections above could all be passing because the fixture cannot craft at
     * all. It is also the first time the controller is driven through a recipe whose output variant is
     * not 0, which is what makes the confirm step's variant term reachable in either direction.
     */
    @Test
    public void anExactVariantCraftDeliversThatVariantAndNotMerelyThatItem() {
        RecipeView orange = dyedWool(ORANGE_WOOL);
        int dyeMeta = exactCellOf(orange, "dye").meta();
        FakeCraftWindow win = carryingOneCraftFor(orange);
        CraftController c = new CraftController(orange);

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("a craft the player can pay for exactly must succeed: " + out.message(), out.ok());
        assertTrue("and say what it made: " + out.message(),
            out.message().contains("crafted " + orange.outputCount() + "x wool"));
        assertEquals("the crafted wool must be the variant that was asked for, on the SERVER's side "
                + "-- the client's count reads the same for a craft the server dropped in full",
            orange.outputCount(), win.serverStoredCount("wool", ORANGE_WOOL));
        assertEquals("and NOT the neighbouring colour", 0,
            win.serverStoredCount("wool", MAGENTA_WOOL));
        assertEquals("the white wool must be spent, not still held", 0,
            win.serverStoredCount("wool", 0));
        assertEquals("and the dye with it", 0, win.serverStoredCount("dye", dyeMeta));
        assertEquals("the result slot is clicked exactly once -- twice would craft twice and spend a "
                + "second set of ingredients", 1, win.resultClicks);
        assertEquals("the matrix must be empty: anything left there is dropped on the floor when the "
                + "window closes", "", win.matrixContents());
        assertNull("and nothing may ride the cursor, which is dropped the same way", win.cursor());
        assertEquals("the server must have applied every click", 0, win.droppedClicks);
    }

    // ===== the settle wait, pinned instead of scaled with =====

    /**
     * A verdict that arrives at the FAR END of the wait must still be read as a rejection.
     *
     * <p>The delay is a literal 4 rather than {@code SETTLE_TICKS}: the existing timing assertions
     * interpolate the constant ({@code ticks >= 2 * SETTLE_TICKS}, a 3-tick delay against a message
     * that names the constant) and are therefore true for any value it takes. This fixture puts the
     * resync a full four ticks after the refusing click, which is the margin the javadoc argues for --
     * one round trip plus up to a tick of granularity at each end. Read the window any earlier and the
     * controller finds its OWN optimistic writes, calls a rejection that had merely not arrived yet a
     * success, and clicks the result on a window the server has already locked.
     */
    @Test
    public void aVerdictArrivingAtTheFarEndOfTheWaitIsStillReadAsARejection() {
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 4);
        win.rejectAtClick = 3;
        win.resyncDelayTicks = 4;
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("the window must have locked, or this fixture tests nothing", win.locked);
        assertFalse("a craft the server refused is not a success: " + out.message(), out.ok());
        assertTrue("the wait must be long enough to SEE a verdict that lands four ticks after the "
                + "click; a shorter wait reads the client's own writes and reports the refusal as a "
                + "taken result instead: " + out.message(),
            out.message().contains("did not accept the placement"));
        assertEquals("and the result must never be clicked: reading the verdict too early is exactly "
                + "how that click reaches a locked window, where it is dropped in silence while the "
                + "craft is reported as done", 0, win.resultClicks);
        assertEquals("no ingredient may be stranded on a terminal path", "", win.matrixContents());
        assertNull(win.cursor());
        assertEquals("and every plank the client can still see must be back in the inventory", 4,
            win.storedCount("planks", 0));
    }

    /**
     * The floor itself, pinned as a value.
     *
     * <p>Asserted directly because the number is the CLAIM: a client tick is 50ms and the server
     * drains packets on its own 50ms tick, so a verdict costs one round trip plus up to a tick of
     * granularity at each end, and 4 is the smallest wait that covers it. The behavioural test above
     * fails for 1, 2 and 3, but it fails through the machine's later states; this says the number out
     * loud so a reader lowering it as a "performance knob" is told what it costs.
     */
    @Test
    public void theSettleWaitIsFourTicksBecauseThatIsWhatOneRoundTripCosts() {
        assertEquals("SETTLE_TICKS is a floor on honesty, not a tuning knob: below 4 the wait no "
                + "longer covers one round trip plus a tick of granularity at each end, so a verdict "
                + "still in flight is read as acceptance and the controller takes the result on a "
                + "window the server has locked", 4, CraftController.SETTLE_TICKS);
    }

    // ===== the confirm fact that says the player OWNS the output =====

    /**
     * Another VARIANT of the output item in the inventory is not proof the craft happened.
     *
     * <p>{@code storesOutput} is the third confirm fact and the only one that says the player now owns
     * the item, so it is the one that must not be satisfiable by something else. Every recipe the suite
     * drives has output variant 0, which makes the variant term constant-true on every path walked; the
     * failure it stops is concrete -- craft orange wool while already carrying white wool, the output
     * never arrives, and the model is told it holds an item it does not.
     */
    @Test
    public void anotherVariantOfTheOutputItemIsNotProofThePlayerOwnsTheCraft() {
        RecipeView orange = dyedWool(ORANGE_WOOL);
        FakeCraftWindow win = carryingOneCraftFor(orange);
        // The round trip after the take is the one interval in which the controller has stopped acting
        // and has not yet looked, which is what the fake's own hook exists for. A resync that disagrees
        // about the slot the output landed in hands back what the SERVER thinks is there -- here the
        // white wool the player came in with: the same ITEM as the output, a different variant.
        win.duringTakeRoundTrip = () -> win.carrying(0, "wool", 0, 1);
        CraftController c = new CraftController(orange);

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("must terminate", out.terminal());
        assertFalse("wool of another colour is not the wool that was crafted, and calling it done "
                + "tells the model it owns an item that never arrived: " + out.message(), out.ok());
        assertTrue("and it must be reported as the output not surviving the round trip, not as a "
                + "layout or ingredient problem: " + out.message(),
            out.message().contains("no wool reached the inventory"));
        assertEquals("the variant that was asked for must be genuinely absent, or this fixture is "
                + "not the case under test", 0, win.serverStoredCount("wool", ORANGE_WOOL));
        assertEquals("while the variant that IS present is the one that must not pass for proof -- a "
                + "name-only comparison cannot tell these two apart", 1,
            win.serverStoredCount("wool", 0));
    }

    // ===== the write seam and the workable bound: no headless caller, so pinned at the source =====

    /**
     * {@link LiveCraftWindow}'s own source text.
     *
     * <p>Surefire's working directory is the module directory, with the repo root as a fallback for a
     * runner started elsewhere. A missing file FAILS rather than {@code Assume}-skipping: a pin that
     * quietly stops checking is worse than no pin, and quiet is how both mutations below survived.
     */
    private static String liveCraftWindowSource() {
        Path[] candidates = {
            Path.of("src/main/java/net/marcloud/mcp/core/drivers/craft/LiveCraftWindow.java"),
            Path.of("core/src/main/java/net/marcloud/mcp/core/drivers/craft/LiveCraftWindow.java"),
        };
        for (Path at : candidates) {
            if (Files.isRegularFile(at)) {
                try {
                    return Files.readString(at);
                } catch (IOException e) {
                    throw new AssertionError("could not read " + at.toAbsolutePath(), e);
                }
            }
        }
        throw new AssertionError("LiveCraftWindow.java is at neither "
                + candidates[0].toAbsolutePath() + " nor " + candidates[1].toAbsolutePath());
    }

    /** The one capture of {@code pattern} in {@code source}, asserted to occur exactly once. */
    private static String onlyCapture(String source, String what, Pattern pattern) {
        Matcher m = pattern.matcher(source);
        assertTrue("no " + what + " found in LiveCraftWindow.java: this pin has drifted off the code "
                + "it guards and must be repaired, not deleted", m.find());
        String captured = m.group(1);
        assertFalse("more than one " + what + " in LiveCraftWindow.java, so this pin no longer "
                + "identifies the one expression under test", m.find());
        return captured;
    }

    /**
     * Craft clicks are issued in mode 0, which is the only mode LEFT and RIGHT mean anything in.
     *
     * <p>{@code Container.slotClick}'s mode-1 branch calls {@code transferStackInSlot} and ignores
     * {@code clickedButton} entirely, so under any other mode the two constants below stop meaning
     * take-all / place-one: no click loads the cursor, every placement becomes a shift-move of a whole
     * stack somewhere the controller did not choose, and the click on the result quick-moves the output
     * instead of triggering {@code SlotCrafting.onPickupFromSlot}.
     *
     * <p><b>The mode is pinned against the SOURCE, and that is not behavioural coverage.</b>
     * {@code LiveCraftWindow.click} needs a live {@code Minecraft}, {@code EntityPlayerSP} and
     * {@code PlayerControllerMP}, and {@code GameAccess} is final with no seam to fake, so the write
     * path is reachable only from a live client. {@code FakeCraftWindow.apply} hard-codes mode-0
     * semantics and is never handed a mode at all, which means the fake and the live class cannot
     * disagree about what a click MEANS in any test that exists. This is the drift net
     * {@code NativeBridgeContractTest} uses for the JNI table, applied to the same kind of gap.
     */
    @Test
    public void everyCraftClickIsIssuedInPickupModeWhereLeftAndRightStillMeanSomething() {
        assertEquals("CraftWindow.LEFT is vanilla's clickedButton 0 -- take or place the WHOLE stack",
            0, CraftWindow.LEFT);
        assertEquals("and RIGHT is 1 -- place exactly ONE item, which is what lets a single pick-up "
                + "serve a run of squares", 1, CraftWindow.RIGHT);

        String mode = onlyCapture(liveCraftWindowSource(), "windowClick call",
                Pattern.compile("windowClick\\(\\s*c\\.windowId\\s*,\\s*slot\\s*,\\s*button\\s*,"
                        + "\\s*([^,\\s]+)\\s*,\\s*p\\s*\\)"));
        assertEquals("the craft seam must click in mode 0 (PICKUP): button semantics exist only "
                + "inside it, so another mode turns every button above into a quick-move, the matrix "
                + "is never filled, and the controller reports clicks it believes placed something",
            "0", mode);
    }

    /**
     * The workable bound is the main inventory's own length, which is what keeps armour out.
     *
     * <p>Driven with BOTH candidate expressions against a real {@code InventoryPlayer} and a real
     * {@code ContainerPlayer}-shaped container, so the difference between them is a measured fact and
     * not a claim: the wider bound reaches player indices 36-39, and those are the armour slots.
     *
     * <p>Which bound PRODUCTION passes is then pinned at the source, for the same reason as the mode
     * above -- {@code workableInventorySize} takes a live {@code EntityPlayerSP}. The neighbouring test
     * retypes the production expression into its own harness and hands the result to
     * {@code storageSlotsOf}, so it proves that method honours whatever bound it is given and never
     * that production computes 36.
     */
    @Test
    public void theWorkableBoundIsTheMainInventoryLengthWhichIsWhatKeepsArmourOut() {
        Bench c = containerPlayerShape();
        int mainInventory = c.playerInv.mainInventory.length;
        int wholeInventory = c.playerInv.getSizeInventory();

        assertEquals("InventoryPlayer keeps four armour stacks PAST the main inventory "
                + "(InventoryPlayer:631-633), and that is the entire distance between the two "
                + "candidate bounds", mainInventory + 4, wholeInventory);
        int[] workable = LiveCraftWindow.storageSlotsOf(c, c.playerInv, mainInventory);
        int[] wholeThing = LiveCraftWindow.storageSlotsOf(c, c.playerInv, wholeInventory);

        assertEquals("the main-inventory bound must expose exactly the 36 workable slots", 36,
            workable.length);
        for (int slot : workable) {
            assertTrue("slot " + slot + " is one of the armour slots at 5-8: parking a crafted item "
                    + "there fails isItemValid, so it stays on the cursor and the window close drops "
                    + "it on the floor while the outcome reports the craft as done",
                slot < 5 || slot > 8);
        }
        assertEquals("the wider bound must reach four further slots, or it is not the bound this "
                + "test thinks it is", 40, wholeThing.length);
        assertArrayEquals("and those four ARE the armour slots -- player indices 36-39, which "
                + "ContainerPlayer:39 lays out as container slots 8,7,6,5. Measured rather than "
                + "asserted from a literal, because it is the whole reason the narrower bound is the "
                + "right one: " + Arrays.toString(wholeThing),
            new int[] {8, 7, 6, 5}, Arrays.copyOfRange(wholeThing, 36, 40));

        String bound = onlyCapture(liveCraftWindowSource(), "workableInventorySize body",
                Pattern.compile("workableInventorySize\\(EntityPlayerSP p\\)\\s*\\{\\s*"
                        + "return\\s+p\\.inventory\\.(\\S+)\\s*;"));
        assertEquals("production must take the main inventory's own length: getSizeInventory() is the "
                + "four-larger bound measured above, and passing it would make ContainerPlayer's "
                + "armour slots workable -- the controller would park a crafted sword into one",
            "mainInventory.length", bound);
    }
}
