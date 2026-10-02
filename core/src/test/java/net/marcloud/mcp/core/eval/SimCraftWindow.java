package net.marcloud.mcp.core.eval;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.marcloud.mcp.core.drivers.craft.CraftInventory;
import net.marcloud.mcp.core.drivers.craft.CraftWindow;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;

/**
 * A {@link CraftWindow} over the eval's world, where every click changes what the next one sees.
 *
 * <p><b>Why the slots have to mutate.</b> A recording window would let a controller that clicked
 * the same square four times pass for one that placed four ingredients, and would let a controller
 * that picked up nothing and put it back pass for a craft. A placement SEQUENCE is only checkable
 * if each click changes what the next one sees.
 *
 * <p><b>The result is the REAL recipe table.</b> {@code CraftingManager.findMatchingRecipe} is
 * asked on a mirror {@code InventoryCrafting}, so the output appears exactly when vanilla says it
 * would, and a controller that filled the right squares in the wrong ARRANGEMENT gets nothing --
 * the mistake a hand-written "four planks anywhere means a crafting table" would wave through.
 * {@code Bootstrap.register()} builds the table in a plain JVM in under a second, so there is
 * nothing to gain by faking it.
 *
 * <p><b>Two inventories, on purpose, and the split covers the PLAYER'S OWN slots too.</b>
 * {@link #slots} is the CLIENT container, which {@code PlayerControllerMP.windowClick} mutates
 * locally before it queues the packet ({@code :537-538}), and {@link #serverSlots} /
 * {@link #serverStorage} are what the server has actually applied. They move together until
 * {@link #refuseFrom} fires. That window lock is the hazard the confirm step exists for: from that
 * click on, the client keeps applying locally and the server drops in silence, and a controller
 * that reports a finished craft has reported nothing that happened. Storage is split for the same
 * reason the matrix is: on a resync the server rewrites the WHOLE container, and the player's own
 * inventory is part of it, so a client that believes it moved a stack the server never accepted
 * would show an inventory the server has never heard of.
 *
 * <p><b>The layout is vanilla's, cell for cell, and that is the whole reason it is written out.</b>
 * Two containers put their slots in different orders and both are real:
 *
 * <ul>
 *   <li>{@code ContainerPlayer}: result 0, the 2x2 matrix at 1..4, <b>four ARMOUR slots at
 *       5..8</b>, then main inventory 9..35 and the hotbar 36..44. The armour slots come from
 *       {@code playerInventory.getSizeInventory() - 1 - k} (indices 36..39 of
 *       {@code InventoryPlayer}, i.e. the armour array) and are added BEFORE the player's own
 *       stacks.</li>
 *   <li>{@code ContainerWorkbench}: result 0, the 3x3 matrix at 1..9, then main inventory 10..36
 *       and the hotbar 37..45. No armour slots at all.</li>
 * </ul>
 *
 * <p>A window that computed {@code storageStart = 1 + w * w} and then read main inventory 0..35
 * from there would put its first "workable" slot on an ARMOUR slot in the player's own window --
 * the precise confusion {@code CraftController} refuses to work through, since a crafted sword
 * parked in an armour slot fails {@code isItemValid}, stays on the cursor, and is dropped on the
 * floor when the window closes. The armour gap is the only difference between the two layouts, so
 * it is a field rather than arithmetic that happens to come out right once.
 *
 * <p><b>Which player-inventory index a container slot holds.</b> Both containers list main
 * inventory 9..35 BEFORE the hotbar, so one mapping serves both: container slots run
 * {@code storageStart .. storageStart+26} onto {@code mainInventory[9..35]} and then
 * {@code storageStart+27 .. storageStart+35} onto {@code mainInventory[0..8]}. Note that
 * {@code mainInventory[0..8]} IS the hotbar, so the container's hotbar row is at the END of its
 * own slot list while being at the START of the array -- the inversion that makes a hand-written
 * offset quietly wrong.
 */
public final class SimCraftWindow implements CraftWindow {

    private final int gridWidth;
    private final int storageStart;
    private final SimWorld world;

    /** The CLIENT container: result, matrix, then storage read through the player's inventory. */
    private ItemStack[] slots;
    /** The SERVER's copy of the same container, kept separately so a refusal is observable. */
    private ItemStack[] serverSlots;
    /** The server's copy of the player's own 36 stacks. Null entries are empty. */
    private final ItemStack[] serverStorage;
    private ItemStack cursor;
    private ItemStack serverCursor;

    // ---- programmable ----
    private boolean open = true;
    private final int windowId;
    private int rejectAtClick;
    private int resyncDelayTicks = 1;
    private int rejectPendingIn = -1;
    private boolean refuseResultTakes;
    private boolean locked;
    private int clicks;
    private int resultClicks;
    private int droppedClicks;

    public SimCraftWindow(SimWorld world, int gridWidth, int storageSlots, int windowId) {
        this.world = world;
        this.gridWidth = gridWidth;
        this.storageStart = storageStartFor(gridWidth);
        this.windowId = windowId;
        this.slots = new ItemStack[storageStart + storageSlots];
        this.serverSlots = new ItemStack[slots.length];
        this.serverStorage = new ItemStack[world.inventory().mainInventory.length];
        // The server's view starts as a copy of the player's real stacks, and stays a copy: a
        // client-side edit to the array must not be visible to the server, or the two could never
        // disagree and the whole point of having two is lost.
        syncServerStorageFromWorld();
        net.minecraft.init.Bootstrap.register();
    }

    /**
     * The first container slot after the matrix, which is where each container's own storage begins.
     *
     * <p>{@code ContainerPlayer} inserts four armour slots between the 2x2 matrix and the player's
     * stacks ({@code ContainerPlayer:39-56}); {@code ContainerWorkbench} does not
     * ({@code ContainerWorkbench:36-46}). One constant, read off the real constructors rather than
     * assumed, because the difference is four slots that all exist in a live window.
     */
    private static int storageStartFor(int gridWidth) {
        return 1 + gridWidth * gridWidth + (gridWidth == 2 ? 4 : 0);
    }

    /** The player's own 2x2 window: {@code ContainerPlayer}, window id 0, 36 workable slots. */
    public static SimCraftWindow playerWindow(SimWorld world) {
        return new SimCraftWindow(world, 2, 36, 0);
    }

    /** A bench the server opened: {@code ContainerWorkbench}, 3x3, non-zero id. */
    public static SimCraftWindow bench(SimWorld world) {
        return new SimCraftWindow(world, 3, 36, 7);
    }

    /**
     * Put a stack in the player's inventory, which the window's storage region reads through.
     *
     * @param playerSlot an {@code InventoryPlayer.mainInventory} index: 0..8 is the HOTBAR and
     *                   9..35 the main inventory, whichever the task means
     */
    public SimCraftWindow carrying(int playerSlot, String item, int meta, int count) {
        world.give(playerSlot, item, count);
        syncServerStorageFromWorld();
        return this;
    }

    /**
     * Refuse the server's copy from click N on, the way a mismatched {@code C0E} does.
     *
     * @param click     1-based click number to refuse; 0 never refuses
     * @param delayTicks ticks before the resync lands
     */
    public SimCraftWindow refuseFrom(int click, int delayTicks) {
        this.rejectAtClick = click;
        this.resyncDelayTicks = delayTicks;
        return this;
    }

    /**
     * Refuse the server's copy of the TAKE, and only the take.
     *
     * <p>Distinct from {@link #refuseFrom} because the two exercise different halves of the
     * controller. Refusing the first click fails the craft in SETTLING, before the output is ever
     * taken, so {@code CraftController} never reaches CONFIRMING and the confirm step is never
     * asked a question at all. Refusing only the result click lets the ingredients land on the
     * server and drops the one click that would have produced the output -- which is precisely
     * the case CONFIRMING exists to catch: the client has emptied its matrix optimistically and
     * believes it is holding an item no server ever granted it.
     *
     * <p>The refusal is by SLOT rather than by click number, so it does not encode the
     * controller's click strategy into the test and go stale the moment it clicks differently.
     *
     * @param delayTicks ticks before the resync lands
     */
    public SimCraftWindow refuseResultTake(int delayTicks) {
        this.refuseResultTakes = true;
        this.resyncDelayTicks = delayTicks;
        return this;
    }

    public boolean locked() {
        return locked;
    }

    public int clicks() {
        return clicks;
    }

    public int resultClicks() {
        return resultClicks;
    }

    /** Clicks the server silently dropped, which is the failure {@code ClickVerdict} has no name for. */
    public int droppedClicks() {
        return droppedClicks;
    }

    /** Whether the open window has a crafting matrix at all. */
    public boolean hasGrid() {
        return open && gridWidth > 0;
    }

    /**
     * Advance the rest of the game tick, delivering a pending resync.
     *
     * <p>Non-zero delay on purpose in any task that arms it: a resync arriving in the same pass as
     * the click leaves the matrix still showing the controller's own optimistic write, which reads
     * as success.
     */
    public void advanceTick() {
        if (rejectPendingIn > 0) {
            rejectPendingIn--;
            if (rejectPendingIn == 0) {
                // NetHandlerPlayServer:1035-1049: resync the window to the server's state and lock
                // it, so the client's optimistic writes are overwritten and further clicks are
                // dropped without an error. The player's own stacks are part of that resync.
                slots = copy(serverSlots);
                cursor = serverCursor == null ? null : serverCursor.copy();
                writeWorldStorageFromServer();
                world.setCursor(cursor);
                locked = true;
                rejectPendingIn = -1;
            }
        }
    }

    private void syncServerStorageFromWorld() {
        ItemStack[] inv = world.inventory().mainInventory;
        for (int i = 0; i < serverStorage.length && i < inv.length; i++) {
            serverStorage[i] = inv[i] == null ? null : inv[i].copy();
        }
    }

    private void writeWorldStorageFromServer() {
        ItemStack[] inv = world.inventory().mainInventory;
        for (int i = 0; i < inv.length && i < serverStorage.length; i++) {
            inv[i] = serverStorage[i] == null ? null : serverStorage[i].copy();
        }
    }

    private static ItemStack[] copy(ItemStack[] from) {
        ItemStack[] out = new ItemStack[from.length];
        for (int i = 0; i < from.length; i++) {
            out[i] = from[i] == null ? null : from[i].copy();
        }
        return out;
    }

    // ---- CraftWindow ----

    @Override
    public boolean windowOpen() {
        return open;
    }

    @Override
    public int windowId() {
        return open ? windowId : -1;
    }

    @Override
    public int gridWidth() {
        return hasGrid() ? gridWidth : 0;
    }

    @Override
    public int matrixSlot(int row, int col) {
        if (!hasGrid() || row < 0 || col < 0 || row >= gridWidth || col >= gridWidth) {
            return -1;
        }
        // ContainerPlayer:32 / ContainerWorkbench:29 add matrix cells as `j + i * width` over the
        // InventoryCrafting, i.e. COLUMN-major, one past the result slot. The same order
        // RecipeLayoutReader reads, and the transpose that gets it backwards is invisible until a
        // 3x3 recipe comes out as a different item.
        return 1 + col + row * gridWidth;
    }

    @Override
    public int resultSlot() {
        return hasGrid() ? 0 : -1;
    }

    @Override
    public int[] storageSlots() {
        if (!open) {
            return new int[0];
        }
        int[] out = new int[36];
        for (int i = 0; i < out.length; i++) {
            out[i] = storageStart + i;
        }
        return out;
    }

    @Override
    public CraftInventory.Held stackAt(int slot) {
        return client().stackAt(slot);
    }

    /** What the SERVER holds in a container slot, for a task that watches both sides. */
    public CraftInventory.Held serverStackAt(int slot) {
        return server().stackAt(slot);
    }

    @Override
    public CraftInventory.Held cursor() {
        return held(cursor);
    }

    /** What the server holds on ITS cursor. */
    public CraftInventory.Held serverCursor() {
        return held(serverCursor);
    }

    private static CraftInventory.Held held(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) {
            return null;
        }
        return new CraftInventory.Held(
                Item.itemRegistry.getNameForObject(stack.getItem()).toString(),
                stack.getMetadata(), stack.stackSize);
    }

    @Override
    public boolean click(int slot, int button) {
        if (!open || slot < 0 || (!hasGrid() && slot < storageStart)) {
            return false;
        }
        // The server's copy of the player's stacks has to learn about anything the WORLD did to
        // them, or a stack that arrived after this window opened is visible to the client and
        // invisible to the server. That is not a hypothetical: the goal-directed policy digs a log
        // into an open inventory, and the server then lifted nothing from an empty slot while the
        // client had already picked the log up -- so the two sides disagreed about whether the
        // player owned it at all.
        //
        // Refused windows are exempt, and that exemption is the whole correctness argument. Once a
        // refusal is armed or has landed, the server's copy is DELIBERATELY divergent from the
        // client's -- it holds what the server actually applied and nothing more. Refreshing it
        // from the world would copy in the client's optimistic writes, which is precisely the
        // fiction the two-sided model exists to expose, and the refusal tasks would be asserting
        // against evidence the harness had just manufactured. On a healthy window the server
        // applies every click, so the refresh is a no-op by construction.
        if (!divergenceInPlay()) {
            syncServerStorageFromWorld();
        }
        clicks++;
        boolean isResult = slot == 0;
        if (isResult) {
            resultClicks++;
        }
        boolean rejects = (refuseResultTakes && isResult)
                || (rejectAtClick > 0 && clicks == rejectAtClick);
        if (rejects && rejectPendingIn < 0) {
            rejectPendingIn = Math.max(1, resyncDelayTicks);
        }
        // The client applies locally and optimistically whatever the server does with the click --
        // not a simplification, that is what PlayerControllerMP:537-538 does before the packet is
        // even sent, and it is exactly why a refused window shows a craft that looks complete.
        boolean serverApplies = !locked && !rejects
                && (rejectAtClick <= 0 || clicks < rejectAtClick);
        if (!serverApplies) {
            droppedClicks++;
        }
        cursor = client().click(slot, button, cursor);
        world.setCursor(cursor);
        if (serverApplies) {
            serverCursor = server().click(slot, button, serverCursor);
        }
        return true;
    }

    /**
     * Whether the server's copy of this window is deliberately out of step with the client's.
     *
     * <p>True once a refusal has been armed or has landed, and while a resync is in flight. Each
     * term is a distinct way the two copies are allowed to differ, and missing one would let a
     * refresh paper over the evidence of the very refusal it is meant to preserve.
     */
    private boolean divergenceInPlay() {
        return locked || rejectPendingIn >= 0 || rejectAtClick > 0 || refuseResultTakes;
    }

    /**
     * One side's view of the container, over a chosen set of storage stacks.
     *
     * <p>The client side reads and writes {@code SimWorld}'s own {@code mainInventory}, because that
     * is what a live client's storage IS -- the container's storage slots point at the player's
     * inventory object. The server side reads and writes {@link #serverStorage}, a private mirror.
     */
    private final class Side {

        private final boolean isClient;

        Side(boolean isClient) {
            this.isClient = isClient;
        }

        /**
         * This side's MATRIX, which is a different array on each side.
         *
         * <p>The storage region was split from the start and the matrix was not: every read and
         * write of a cell went through the shared {@code slots} array, so the server applying a
         * click into the matrix read the CLIENT's cell and mutated it. That contradicts the
         * field's own comment ("{@code #slots} is the CLIENT container") and it made the two sides
         * unable to disagree about the matrix at all -- the one thing the resync exists to show.
         *
         * <p>What it cost, measured: the client placed an ingredient, the server's own click
         * re-read that same cell and lifted the ingredient back off it onto the server cursor, and
         * the controller then read an empty matrix and reported that the server had refused a
         * placement the server had in fact accepted. {@link #serverSlots} existed for exactly this
         * and was written by nothing, so the resync in {@link #advanceTick} restored an array that
         * was always empty -- which is why T17 passed before it was right.
         */
        private ItemStack[] matrix() {
            return isClient ? slots : serverSlots;
        }

        ItemStack storageAt(int playerIndex) {
            return isClient
                    ? world.inventory().mainInventory[playerIndex]
                    : serverStorage[playerIndex];
        }

        void setStorageAt(int playerIndex, ItemStack stack) {
            if (isClient) {
                world.inventory().mainInventory[playerIndex] = stack;
            } else {
                serverStorage[playerIndex] = stack;
            }
        }

        CraftInventory.Held stackAt(int slot) {
            if (!open || slot < 0) {
                return null;
            }
            if (slot == 0) {
                return held(resultPreview(this));
            }
            if (slot < storageStart) {
                return held(matrix()[slot]);
            }
            int playerIndex = playerIndexOf(slot);
            return playerIndex < 0 ? null : held(storageAt(playerIndex));
        }

        ItemStack click(int slot, int button, ItemStack onCursor) {
            if (slot == 0) {
                return takeResult(this, onCursor);
            }
            if (slot >= storageStart) {
                return clickStorage(playerIndexOf(slot), button, onCursor);
            }
            ItemStack[] matrix = matrix();
            ItemStack there = matrix[slot];
            if (onCursor == null) {
                if (there == null) {
                    return null;
                }
                // Container:325 -- button 0 takes the whole stack, button 1 half rounded up.
                int take = button == LEFT ? there.stackSize : (there.stackSize + 1) / 2;
                ItemStack lifted = there.splitStack(take);
                if (there.stackSize == 0) {
                    matrix[slot] = null;
                }
                return lifted;
            }
            if (there == null) {
                // Container:303 -- button 0 places the whole cursor, button 1 exactly one.
                int place = button == LEFT ? onCursor.stackSize : 1;
                matrix[slot] = onCursor.splitStack(place);
                return onCursor.stackSize == 0 ? null : onCursor;
            }
            if (there.getItem() == onCursor.getItem()
                    && there.getMetadata() == onCursor.getMetadata()) {
                int room = there.getMaxStackSize() - there.stackSize;
                int place = Math.min(room, button == LEFT ? onCursor.stackSize : 1);
                there.stackSize += place;
                onCursor.stackSize -= place;
                return onCursor.stackSize == 0 ? null : onCursor;
            }
            // Different items: vanilla swaps them.
            matrix[slot] = onCursor;
            return there;
        }

        private ItemStack clickStorage(int playerIndex, int button, ItemStack onCursor) {
            if (playerIndex < 0) {
                return onCursor;
            }
            ItemStack there = storageAt(playerIndex);
            if (onCursor == null) {
                if (there == null) {
                    return null;
                }
                int take = button == LEFT ? there.stackSize : (there.stackSize + 1) / 2;
                ItemStack lifted = there.splitStack(take);
                if (there.stackSize == 0) {
                    setStorageAt(playerIndex, null);
                }
                return lifted;
            }
            if (there == null) {
                int place = button == LEFT ? onCursor.stackSize : 1;
                setStorageAt(playerIndex, onCursor.splitStack(place));
                return onCursor.stackSize == 0 ? null : onCursor;
            }
            if (there.getItem() == onCursor.getItem() && there.getMetadata() == onCursor.getMetadata()) {
                int room = there.getMaxStackSize() - there.stackSize;
                int place = Math.min(room, button == LEFT ? onCursor.stackSize : 1);
                there.stackSize += place;
                onCursor.stackSize -= place;
                return onCursor.stackSize == 0 ? null : onCursor;
            }
            setStorageAt(playerIndex, onCursor);
            return there;
        }
    }

    private Side client;

    private Side server;

    private Side client() {
        if (client == null) {
            client = new Side(true);
        }
        return client;
    }

    private Side server() {
        if (server == null) {
            server = new Side(false);
        }
        return server;
    }

    /**
     * The {@code InventoryPlayer.mainInventory} index a container slot holds, or -1.
     *
     * <p>Both containers list main inventory 9..35 before the hotbar, so the order is the same for
     * both and differs only in where it starts ({@link #storageStart}). The hotbar is
     * {@code mainInventory[0..8]} -- the START of the array and the END of the container's slot
     * list -- which is the inversion a hand-written offset gets backwards.
     */
    private int playerIndexOf(int slot) {
        int i = slot - storageStart;
        if (i < 0 || i >= 36) {
            return -1;
        }
        return i < 27 ? 9 + i : i - 27;
    }

    /**
     * Take the crafting output, which is what SPENDS the ingredients.
     *
     * <p>{@code SlotCrafting.onPickupFromSlot:134-161} decrements every occupied matrix cell by
     * one. Modelled rather than assumed, so a controller that clicked the result twice consumes
     * twice and a task can see it.
     */
    private ItemStack takeResult(Side side, ItemStack onCursor) {
        ItemStack out = resultPreview(side);
        if (out == null) {
            return onCursor;
        }
        if (onCursor != null) {
            boolean same = onCursor.getItem() == out.getItem()
                    && onCursor.getMetadata() == out.getMetadata()
                    && onCursor.stackSize + out.stackSize <= onCursor.getMaxStackSize();
            if (!same) {
                return onCursor;
            }
        }
        // This side's own cells, not the shared array: consuming the ingredients is the one thing
        // a take does, and a take the client performed but the server refused must leave the
        // SERVER's ingredients in place for the resync to hand back.
        ItemStack[] matrix = side.matrix();
        for (int i = 0; i < gridWidth * gridWidth; i++) {
            ItemStack cell = matrix[1 + i];
            if (cell != null) {
                cell.stackSize--;
                if (cell.stackSize <= 0) {
                    matrix[1 + i] = null;
                }
            }
        }
        if (onCursor == null) {
            return out;
        }
        onCursor.stackSize += out.stackSize;
        return onCursor;
    }

    /** What the real recipe table makes of this side's matrix as it currently stands. */
    private ItemStack resultPreview(Side side) {
        if (!hasGrid()) {
            return null;
        }
        // A Container is needed only because InventoryCrafting reports matrix changes to one; the
        // null World is the same argument FakeCraftWindow makes and the same vanilla allows, since
        // findMatchingRecipe never touches it.
        InventoryCrafting mirror = new InventoryCrafting(new SilentContainer(), gridWidth, gridWidth);
        ItemStack[] matrix = side.matrix();
        for (int i = 0; i < gridWidth * gridWidth; i++) {
            ItemStack cell = matrix[1 + i];
            mirror.setInventorySlotContents(i, cell == null ? null : cell.copy());
        }
        return CraftingManager.getInstance().findMatchingRecipe(mirror, null);
    }

    /** The empty container an {@link InventoryCrafting} needs only to have something to notify. */
    private static final class SilentContainer extends Container {
        @Override
        public boolean canInteractWith(net.minecraft.entity.player.EntityPlayer playerIn) {
            return true;
        }
    }

    // ---- facts for the report ----

    /** Every non-empty matrix cell as "count x item/meta at (row,col)". Empty when the grid is clear. */
    public String matrixContents() {
        if (!hasGrid()) {
            return "(no grid)";
        }
        List<String> parts = new ArrayList<>();
        for (int row = 0; row < gridWidth; row++) {
            for (int col = 0; col < gridWidth; col++) {
                ItemStack cell = slots[1 + col + row * gridWidth];
                if (cell != null) {
                    parts.add(String.format(Locale.ROOT, "%dx %s/%d at (%d,%d)", cell.stackSize,
                            Item.itemRegistry.getNameForObject(cell.getItem()),
                            cell.getMetadata(), row, col));
                }
            }
        }
        return parts.isEmpty() ? "(clear)" : String.join(", ", parts);
    }

    /** How many of one item variant the CLIENT's copy believes the player owns. */
    public int clientStoredCount(String item, int meta) {
        return storedCount(item, meta, true);
    }

    /** How many of one item variant the SERVER's copy believes the player owns. */
    public int serverStoredCount(String item, int meta) {
        return storedCount(item, meta, false);
    }

    private int storedCount(String item, int meta, boolean clientSide) {
        int total = 0;
        String want = new CraftInventory.Held(item, 0, 1).item();
        ItemStack[] arr = clientSide ? world.inventory().mainInventory : serverStorage;
        for (int i = 0; i < arr.length; i++) {
            CraftInventory.Held there = held(arr[i]);
            if (there != null && there.item().equals(want) && there.meta() == meta) {
                total += there.count();
            }
        }
        return total;
    }

    /** Close the window, as {@code displayGuiScreen(null)} would. */
    public void close() {
        this.open = false;
    }

    /**
     * Put this window back on screen, as {@code displayGuiScreen(this)} would.
     *
     * <p>{@link #close()} sets a flag nothing ever cleared, so a window that was set aside and then
     * handed back came back DEAD: {@link #hasGrid()} is false, {@link #gridWidth()} answers 0, and
     * a controller driving it was told "no window is open, so there is nothing to craft in" about a
     * bench that was standing right there in the world. The goal-directed policy hit exactly that
     * -- it printed "restored the 0x0 screen the nested craft interrupted" -- and the only way to
     * tell a 2x2 player grid from a dead window was to read the number, which is the symptom
     * masquerading as a diagnosis.
     */
    public void reopen() {
        this.open = true;
    }
}
