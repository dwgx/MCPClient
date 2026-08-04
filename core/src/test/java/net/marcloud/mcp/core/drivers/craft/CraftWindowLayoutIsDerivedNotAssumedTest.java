package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;
import org.junit.Test;

/**
 * {@link LiveCraftWindow} must DERIVE every slot index from the container, not assume vanilla's.
 *
 * <p>{@link CraftWindow#matrixSlot} says a controller hard-coding {@code 1 + col + row*width} "would
 * be silently wrong the first time it met a container that did not" lay the matrix out that way. The
 * live implementation therefore asks the container -- and that claim is worth exactly as much as a
 * test that can prove it, which is what this file is.
 *
 * <p><b>Headless, against a SYNTHETIC container.</b> Every vanilla constructor involved only STORES
 * its arguments -- {@code InventoryCrafting} keeps an event-handler reference
 * ({@code InventoryCrafting:23-30}), {@code SlotCrafting} keeps a player reference
 * ({@code SlotCrafting:27-32}), {@code InventoryPlayer} keeps a player reference
 * ({@code InventoryPlayer:42-45}) -- and none dereferences it on the paths used here, so a container
 * can be assembled with a null player. That is what makes this reachable without a live client, and
 * it is the same move that made {@code WorldViewCapture.entitiesSection} testable.
 *
 * <p>Two of the containers below mirror vanilla's real ones slot for slot, verified against
 * {@code ContainerPlayer:26-66} and {@code ContainerWorkbench:25-45}. The third is a container that
 * lays its matrix out DIFFERENTLY on purpose -- that one is the whole point: an implementation that
 * assumed the arithmetic would pass the first two and fail it.
 */
public class CraftWindowLayoutIsDerivedNotAssumedTest {

    /** A container we can add slots to; {@code addSlotToContainer} is protected in vanilla. */
    private static final class Bench extends Container {
        InventoryCrafting matrix;
        IInventory result;
        InventoryPlayer playerInv;

        Slot add(Slot s) {
            return addSlotToContainer(s);
        }

        @Override
        public boolean canInteractWith(net.minecraft.entity.player.EntityPlayer p) {
            return true;
        }
    }

    /** A one-slot inventory standing in for {@code InventoryCraftResult}. */
    private static final class Result implements IInventory {
        private ItemStack stack;

        @Override public int getSizeInventory() {
            return 1;
        }

        @Override public ItemStack getStackInSlot(int i) {
            return stack;
        }

        @Override public ItemStack decrStackSize(int i, int n) {
            ItemStack s = stack;
            stack = null;
            return s;
        }

        @Override public ItemStack removeStackFromSlot(int i) {
            return decrStackSize(i, 1);
        }

        @Override public void setInventorySlotContents(int i, ItemStack s) {
            stack = s;
        }

        @Override public int getInventoryStackLimit() {
            return 64;
        }

        @Override public void markDirty() {
        }

        @Override public boolean isUseableByPlayer(net.minecraft.entity.player.EntityPlayer p) {
            return true;
        }

        @Override public void openInventory(net.minecraft.entity.player.EntityPlayer p) {
        }

        @Override public void closeInventory(net.minecraft.entity.player.EntityPlayer p) {
        }

        @Override public boolean isItemValidForSlot(int i, ItemStack s) {
            return true;
        }

        @Override public int getField(int id) {
            return 0;
        }

        @Override public void setField(int id, int v) {
        }

        @Override public int getFieldCount() {
            return 0;
        }

        @Override public void clear() {
            stack = null;
        }

        @Override public String getName() {
            return "result";
        }

        @Override public boolean hasCustomName() {
            return false;
        }

        @Override public net.minecraft.util.IChatComponent getDisplayName() {
            return new net.minecraft.util.ChatComponentText("result");
        }
    }

    /**
     * Vanilla's own {@code ContainerPlayer} layout: result at 0, 2x2 matrix at 1-4, FOUR ARMOUR
     * SLOTS at 5-8, main inventory at 9-35, hotbar at 36-44.
     *
     * <p>The armour slots are the load-bearing part of this fixture. They sit between the matrix and
     * the workable storage, so an implementation that took "every slot after the matrix" would include
     * them -- and parking a crafted item into one leaves it on the cursor to be dropped on the floor.
     */
    private static Bench containerPlayer() {
        Bench c = new Bench();
        c.matrix = new InventoryCrafting(c, 2, 2);
        c.result = new Result();
        c.playerInv = new InventoryPlayer(null);
        c.add(new SlotCrafting(null, c.matrix, c.result, 0, 144, 36));
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                c.add(new Slot(c.matrix, col + row * 2, 88 + col * 18, 26 + row * 18));
            }
        }
        // ContainerPlayer:39 -- getSizeInventory() - 1 - k, i.e. player indices 39,38,37,36.
        for (int k = 0; k < 4; k++) {
            c.add(new Slot(c.playerInv, c.playerInv.getSizeInventory() - 1 - k, 8, 8 + k * 18));
        }
        for (int l = 0; l < 3; l++) {
            for (int j = 0; j < 9; j++) {
                c.add(new Slot(c.playerInv, j + (l + 1) * 9, 8 + j * 18, 84 + l * 18));
            }
        }
        for (int i = 0; i < 9; i++) {
            c.add(new Slot(c.playerInv, i, 8 + i * 18, 142));
        }
        return c;
    }

    /** Vanilla's {@code ContainerWorkbench}: result 0, 3x3 matrix 1-9, inventory 10-36, hotbar 37-45. */
    private static Bench containerWorkbench() {
        Bench c = new Bench();
        c.matrix = new InventoryCrafting(c, 3, 3);
        c.result = new Result();
        c.playerInv = new InventoryPlayer(null);
        c.add(new SlotCrafting(null, c.matrix, c.result, 0, 124, 35));
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 3; col++) {
                c.add(new Slot(c.matrix, col + row * 3, 30 + col * 18, 17 + row * 18));
            }
        }
        for (int k = 0; k < 3; k++) {
            for (int i = 0; i < 9; i++) {
                c.add(new Slot(c.playerInv, i + k * 9 + 9, 8 + i * 18, 84 + k * 18));
            }
        }
        for (int l = 0; l < 9; l++) {
            c.add(new Slot(c.playerInv, l, 8 + l * 18, 142));
        }
        return c;
    }

    /**
     * A container that does NOT use vanilla's arithmetic: the matrix comes FIRST and the result LAST.
     *
     * <p>The case that separates deriving from assuming. Under vanilla's layout the result is slot 0
     * and cell (0,0) is slot 1; here the result is slot 4 and cell (0,0) is slot 0. An implementation
     * computing {@code 1 + col + row*width} would return 1 for cell (0,0) -- a real slot, holding the
     * wrong thing -- and 0 for the result, which is a matrix square. It would click a full craft's
     * worth of squares into the wrong places and never notice.
     */
    private static Bench containerWithResultLast() {
        Bench c = new Bench();
        c.matrix = new InventoryCrafting(c, 2, 2);
        c.result = new Result();
        c.playerInv = new InventoryPlayer(null);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 2; col++) {
                c.add(new Slot(c.matrix, col + row * 2, 0, 0));
            }
        }
        c.add(new SlotCrafting(null, c.matrix, c.result, 0, 0, 0));
        for (int i = 0; i < 36; i++) {
            c.add(new Slot(c.playerInv, i, 0, 0));
        }
        return c;
    }

    private static int workable(Bench c) {
        return c.playerInv.mainInventory.length;
    }

    // ===== the two real layouts =====

    @Test
    public void thePlayersOwnGridIsDerivedAsVanillaLaysItOut() {
        Bench c = containerPlayer();
        assertEquals("the player's own grid is 2x2", 2, LiveCraftWindow.gridWidthOf(c));
        assertEquals("result is the SlotCrafting, which vanilla puts first", 0,
                LiveCraftWindow.resultSlotOf(c));
        assertEquals(1, LiveCraftWindow.matrixSlotOf(c, 0, 0));
        assertEquals(2, LiveCraftWindow.matrixSlotOf(c, 0, 1));
        assertEquals(3, LiveCraftWindow.matrixSlotOf(c, 1, 0));
        assertEquals(4, LiveCraftWindow.matrixSlotOf(c, 1, 1));
    }

    @Test
    public void aBenchIsDerivedAsVanillaLaysItOut() {
        Bench c = containerWorkbench();
        assertEquals(3, LiveCraftWindow.gridWidthOf(c));
        assertEquals(0, LiveCraftWindow.resultSlotOf(c));
        assertEquals("cell (0,0) is the slot right after the result", 1,
                LiveCraftWindow.matrixSlotOf(c, 0, 0));
        assertEquals("cell (2,2) is the ninth matrix slot", 9,
                LiveCraftWindow.matrixSlotOf(c, 2, 2));
        assertEquals("and the middle row starts at 4", 4, LiveCraftWindow.matrixSlotOf(c, 1, 0));
    }

    /**
     * THE ONE THAT MATTERS: a container whose layout is not vanilla's must still be read correctly.
     *
     * <p>Everything above passes for an implementation that hard-codes the arithmetic. This does not.
     */
    @Test
    public void aContainerThatLaysTheMatrixOutDifferentlyIsStillReadCorrectly() {
        Bench c = containerWithResultLast();
        assertEquals("the result is the SlotCrafting wherever it sits -- here LAST, not first", 4,
                LiveCraftWindow.resultSlotOf(c));
        assertEquals("cell (0,0) is slot 0 in this container, not slot 1", 0,
                LiveCraftWindow.matrixSlotOf(c, 0, 0));
        assertEquals(3, LiveCraftWindow.matrixSlotOf(c, 1, 1));
        assertEquals("the width still comes from the crafting inventory itself", 2,
                LiveCraftWindow.gridWidthOf(c));

        // And the arithmetic an assuming implementation would have used is DEMONSTRABLY wrong here,
        // so the test above is not merely passing for a coincidental reason.
        assertFalse("if 1 + col + row*width happened to be right, this fixture would prove nothing",
                LiveCraftWindow.matrixSlotOf(c, 0, 0) == 1 + 0 + 0 * 2);
    }

    // ===== storage, and the armour boundary =====

    /**
     * Storage must be the 36 main-inventory slots and must EXCLUDE the four armour slots.
     *
     * <p>The consequence of getting this wrong is concrete: {@link CraftWindow#storageSlots} notes that
     * parking a crafted sword in an armour slot fails {@code isItemValid} and leaves it on the cursor,
     * and {@code Container:516-525} drops the cursor on the floor when the window closes. A craft
     * would report finishing and the item would be on the ground.
     */
    @Test
    public void storageIsTheMainInventoryAndNeverTheArmourSlots() {
        Bench c = containerPlayer();
        int[] storage = LiveCraftWindow.storageSlotsOf(c, c.playerInv, workable(c));

        assertEquals("36 main-inventory slots, no more", 36, storage.length);
        for (int slot : storage) {
            assertTrue("slot " + slot + " must be past the matrix", slot > 4);
            assertTrue("and must NOT be one of the four armour slots at 5-8, where a parked "
                    + "craft is refused and then dropped on the floor", slot < 5 || slot > 8);
        }
        // Ascending player-inventory index means the hotbar comes first, which is where a human
        // expects a freshly crafted item to land.
        assertEquals("the first workable slot is hotbar index 0, which ContainerPlayer puts at 36",
                36, storage[0]);
    }

    @Test
    public void aBenchExposesTheSameThirtySixWorkableSlots() {
        Bench c = containerWorkbench();
        int[] storage = LiveCraftWindow.storageSlotsOf(c, c.playerInv, workable(c));
        assertEquals(36, storage.length);
        for (int slot : storage) {
            assertTrue("a bench has no armour slots, so every workable slot is past the 3x3 matrix",
                    slot > 9);
        }
        assertEquals("hotbar index 0 is slot 37 in a workbench", 37, storage[0]);
    }

    /**
     * The workable bound is what excludes armour, so a bound that reached further must include them.
     *
     * <p>Pins the boundary by driving it wrong on purpose: with the bound at 40 rather than 36 the
     * armour slots appear. Without this the 36 above could be a coincidence of the fixture.
     */
    @Test
    public void awiderWorkableBoundWouldReachTheArmourSlotsWhichIsWhyItIsTheInventorySize() {
        Bench c = containerPlayer();
        int[] tooWide = LiveCraftWindow.storageSlotsOf(c, c.playerInv, 40);
        assertEquals("four more slots appear once the bound passes the main inventory", 40,
                tooWide.length);
        boolean anyArmour = false;
        for (int slot : tooWide) {
            if (slot >= 5 && slot <= 8) {
                anyArmour = true;
            }
        }
        assertTrue("and they ARE the armour slots -- which is exactly what mainInventory.length "
                + "keeps out", anyArmour);
    }

    // ===== reads =====

    @Test
    public void stackAtReadsTheContainersOwnSlotAndNormalisesTheName() {
        // The only test here that needs the item registry, so the bootstrap is triggered here rather
        // than in a @BeforeClass: the layout derivations above touch no registry at all, and paying
        // Bootstrap's cost for them would hide that fact. Idempotent -- CraftBench.boot() exists
        // exactly so every caller may invoke it.
        CraftBench.boot();
        Bench c = containerWorkbench();
        c.playerInv.mainInventory[0] = new ItemStack(net.minecraft.init.Items.stick, 7);

        int hotbar0 = LiveCraftWindow.storageSlotsOf(c, c.playerInv, workable(c))[0];
        CraftInventory.Held there = LiveCraftWindow.stackAtOf(c, hotbar0);
        assertEquals("the name must be namespace-stripped and lowercased, the same normalisation "
                + "the ingredient side uses", "stick", there.item());
        assertEquals(7, there.count());
        assertEquals(0, there.meta());
    }

    @Test
    public void stackAtIsNullForAnEmptySlotAndForOutOfRange() {
        Bench c = containerWorkbench();
        assertNull("an empty slot has no stack", LiveCraftWindow.stackAtOf(c, 1));
        assertNull("negative is out of range", LiveCraftWindow.stackAtOf(c, -1));
        assertNull("past the end is out of range", LiveCraftWindow.stackAtOf(c, 9999));
    }

    // ===== a window with no matrix at all =====

    /**
     * A chest must be a REFUSAL to craft, not a 1x1 grid.
     *
     * <p>{@link CraftWindow#gridWidth} says 0 "is a refusal to craft rather than a 1x1 grid". A 1 here
     * would let a single-ingredient recipe start clicking into a chest.
     */
    @Test
    public void aWindowWithNoCraftingMatrixRefusesRatherThanLookingLikeAOneByOneGrid() {
        Bench c = new Bench();
        c.playerInv = new InventoryPlayer(null);
        for (int i = 0; i < 36; i++) {
            c.add(new Slot(c.playerInv, i, 0, 0));
        }

        assertEquals("no matrix means width 0, which the controller refuses on", 0,
                LiveCraftWindow.gridWidthOf(c));
        assertEquals("and no result slot to click", -1, LiveCraftWindow.resultSlotOf(c));
        assertEquals(-1, LiveCraftWindow.matrixSlotOf(c, 0, 0));
        assertNull(LiveCraftWindow.matrixOf(c));
    }

    @Test
    public void everyDerivationIsNeutralRatherThanThrowingWhenThereIsNoWindow() {
        // The contract CraftWindow states: reads return neutral values so the controller can fail
        // honestly instead of blowing up.
        assertEquals(0, LiveCraftWindow.gridWidthOf(null));
        assertEquals(-1, LiveCraftWindow.resultSlotOf(null));
        assertEquals(-1, LiveCraftWindow.matrixSlotOf(null, 0, 0));
        assertNull(LiveCraftWindow.matrixOf(null));
        assertNull(LiveCraftWindow.stackAtOf(null, 0));
        assertArrayEquals(new int[0], LiveCraftWindow.storageSlotsOf(null, null, 36));
    }

    /** Out-of-grid cells are refused rather than wrapping onto a real slot. */
    @Test
    public void aCellOutsideTheGridIsRefusedRatherThanWrappingOntoARealSlot() {
        Bench c = containerPlayer();   // 2x2
        assertEquals("row 2 does not exist in a 2x2", -1, LiveCraftWindow.matrixSlotOf(c, 2, 0));
        assertEquals("nor does col 2", -1, LiveCraftWindow.matrixSlotOf(c, 0, 2));
        assertEquals(-1, LiveCraftWindow.matrixSlotOf(c, -1, 0));
        assertEquals(-1, LiveCraftWindow.matrixSlotOf(c, 0, -1));
    }

    /**
     * A 3x3 recipe's cells must be out of grid on a 2x2 window, which is how the controller knows to
     * name the bench instead of clicking.
     */
    @Test
    public void aThreeByThreeCellIsOutOfGridOnThePlayersOwnWindow() {
        Bench small = containerPlayer();
        Bench big = containerWorkbench();
        assertEquals("cell (2,2) has nowhere to go in a 2x2", -1,
                LiveCraftWindow.matrixSlotOf(small, 2, 2));
        assertEquals("and it is a real slot in a 3x3", 9,
                LiveCraftWindow.matrixSlotOf(big, 2, 2));
    }
}
