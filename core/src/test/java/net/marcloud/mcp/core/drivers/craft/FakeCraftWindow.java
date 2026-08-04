package net.marcloud.mcp.core.drivers.craft;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;

/**
 * A {@link CraftWindow} whose slots MUTATE on click, laid out and behaving like the vanilla container
 * it stands in for.
 *
 * <p><b>Why the slots have to move.</b> A recording fake would let "placed 4 ingredients" pass for a
 * controller that clicked the same square four times, or picked up nothing and placed it. A placement
 * SEQUENCE is only checkable if each click changes what the next one sees. That is also why
 * {@code ActActuator} was the wrong seam to reuse: it is about a player's body and has no slots to
 * move.
 *
 * <p><b>The result slot is computed by the REAL recipe table.</b>
 * {@code CraftingManager.findMatchingRecipe} is asked on a mirror {@code InventoryCrafting}, so the
 * output appears exactly when vanilla says it would and a controller that filled the right squares in
 * the wrong ARRANGEMENT gets nothing -- the mistake a hand-written "4 planks anywhere means a crafting
 * table" fake would have waved through. {@code Bootstrap.register()} builds the item registry and the
 * recipe list in a plain surefire JVM in under a second, so there is no reason to fake it.
 *
 * <p><b>Two servers, on purpose.</b> {@link #slots} is the CLIENT container, which
 * {@code PlayerControllerMP.windowClick} mutates immediately (:537), and {@link #serverSlots} is what
 * the server has actually applied. They move together until {@link #rejectAtClick} fires, which is the
 * one hazard this whole controller exists for: from then on the client keeps applying clicks locally
 * while the server drops them in silence, and the two disagree. Without modelling both, a test could
 * not tell an accepted craft from a locked window, and neither could the controller.
 */
final class FakeCraftWindow implements CraftWindow {

    /** Slot 0 is the result and 1..w*w the matrix, exactly as both vanilla crafting containers lay out. */
    private final int gridWidth;
    private final int storageStart;
    private ItemStack[] slots;
    private ItemStack[] serverSlots;
    private ItemStack cursor;
    private ItemStack serverCursor;

    // ---- programmable ----
    boolean open = true;
    int windowId;
    /** Click number (1-based) at which the server refuses; 0 never refuses. */
    int rejectAtClick;
    /**
     * Ticks after the refusing click before the resync lands.
     *
     * <p>Non-zero on purpose in the rejection test. The resync arriving LATER than the click is what
     * makes {@link CraftController#SETTLE_TICKS} load-bearing: read the matrix in the same pass and it
     * still shows the controller's own optimistic write, which reads as success.
     */
    int resyncDelayTicks = 1;
    /** Set false to model a window whose grid the controller must not address (a chest). */
    boolean hasGrid = true;
    /**
     * Item name that VANISHES from storage the moment the result is taken, or null.
     *
     * <p>Models something else emptying the slot the output lands in during the round trip after the
     * take: a hopper under the window, a second client on the same account, a resync that disagrees about
     * that slot. Needed because the alternative -- trusting that an output parked on the client is an
     * output the player owns -- is unobservable without it, and it is the last of the three facts the
     * confirm step checks.
     */
    String vanishesOnTake;

    /**
     * Run during the round trip AFTER the take, once, or null.
     *
     * <p>The general form of {@link #vanishesOnTake}: the window is not frozen while the controller waits
     * for a verdict, and this is the only interval in which the controller has finished acting and not yet
     * looked. Anything arriving in the matrix here reaches a craft that has otherwise succeeded, which is
     * the one way a {@code done} outcome can still be holding items.
     */
    Runnable duringTakeRoundTrip;

    // ---- observed ----
    int clicks;
    /** Clicks the server dropped in silence because the window was locked. */
    int droppedClicks;
    /**
     * Clicks landed on the RESULT slot, which is the click that spends the ingredients.
     *
     * <p>Counted separately because "did the controller try to take an output" is a different question
     * from "how many clicks did it make", and it is the one that says whether a controller pressed on
     * into a window the server had already locked. A total-click assertion cannot distinguish that from
     * cleanup.
     */
    int resultClicks;
    boolean locked;
    private int rejectPendingIn = -1;
    private int stealPendingIn = -1;

    FakeCraftWindow(int gridWidth, int storageSlots, int windowId) {
        this.gridWidth = gridWidth;
        this.storageStart = 1 + gridWidth * gridWidth;
        this.windowId = windowId;
        this.slots = new ItemStack[storageStart + storageSlots];
        this.serverSlots = new ItemStack[slots.length];
        CraftBench.boot();
    }

    /** The player's own window: a 2x2 grid, id 0, with 36 inventory slots. */
    static FakeCraftWindow playerWindow() {
        return new FakeCraftWindow(2, 36, 0);
    }

    /** A crafting table the server opened: a 3x3 grid and a non-zero id. */
    static FakeCraftWindow bench() {
        return new FakeCraftWindow(3, 36, 7);
    }

    /** Put a stack in the n-th storage slot, as the player carrying it. */
    FakeCraftWindow carrying(int storageIndex, String item, int meta, int count) {
        int slot = storageStart + storageIndex;
        slots[slot] = stack(item, meta, count);
        serverSlots[slot] = stack(item, meta, count);
        return this;
    }

    /** Put a stack directly into a matrix cell, for tests about state left behind. */
    void putInMatrix(int row, int col, String item, int meta, int count) {
        slots[matrixSlot(row, col)] = stack(item, meta, count);
        serverSlots[matrixSlot(row, col)] = stack(item, meta, count);
    }

    /**
     * Fill EVERY storage slot with one unmergeable item, so nothing can be put down.
     *
     * <p>For the paths where the cleanup itself has nowhere to go. A full inventory is the ordinary way
     * a craft ends up unable to hand an ingredient back, and the controller's own answer to it decides
     * whether a stranded stack is reported or quietly left for the window close to drop on the floor.
     */
    FakeCraftWindow storageFullOf(String item, int meta, int count) {
        for (int slot = storageStart; slot < slots.length; slot++) {
            slots[slot] = stack(item, meta, count);
            serverSlots[slot] = stack(item, meta, count);
        }
        return this;
    }

    /**
     * Tick the controller to a terminal outcome, stepping the fake's game tick in between.
     *
     * <p>Same shape as {@code HoldControllerTest.run}, and for the same reason: a verdict that arrived
     * in the same pass as the click would not be a round trip, and the wait the controller is built
     * around would be unobservable. {@code maxTicks} is deliberately far larger than any budget the
     * controller has, so a machine that never terminates fails an assertion instead of hanging.
     */
    static CraftOutcome drive(CraftController c, FakeCraftWindow win, int maxTicks) {
        CraftOutcome out = null;
        for (int i = 0; i < maxTicks; i++) {
            out = c.tick(win);
            win.advanceTick();
            if (out.terminal()) {
                return out;
            }
        }
        throw new AssertionError("the craft never terminated in " + maxTicks + " ticks; last: "
                + (out == null ? "no outcome" : out.message()));
    }

    private static ItemStack stack(String item, int meta, int count) {
        Item registered = Item.getByNameOrId(item);
        if (registered == null) {
            throw new AssertionError("not in the item registry: " + item);
        }
        return new ItemStack(registered, count, meta);
    }

    /**
     * Advance the rest of the game tick: deliver a pending resync.
     *
     * <p>Tests step this between controller ticks the way {@code FakeActuator.advanceGameTick} does,
     * because a verdict that arrived instantly would not be a round trip and the wait under test would
     * be unobservable.
     */
    void advanceTick() {
        if (stealPendingIn > 0) {
            stealPendingIn--;
            if (stealPendingIn == 0) {
                if (vanishesOnTake != null) {
                    // Both sides: this models the item genuinely being gone, not a client/server
                    // disagreement about it. That case is what rejectAtClick is for.
                    steal(slots, vanishesOnTake);
                    steal(serverSlots, vanishesOnTake);
                }
                if (duringTakeRoundTrip != null) {
                    duringTakeRoundTrip.run();
                }
                stealPendingIn = -1;
            }
        }
        if (rejectPendingIn > 0) {
            rejectPendingIn--;
            if (rejectPendingIn == 0) {
                // What NetHandlerPlayServer:1035-1049 does on mismatch: resync the window to the
                // server's own state and lock it. The client's optimistic writes are overwritten.
                slots = copy(serverSlots);
                cursor = serverCursor == null ? null : serverCursor.copy();
                locked = true;
                rejectPendingIn = -1;
            }
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

    public boolean windowOpen() {
        return open;
    }

    public int windowId() {
        return open ? windowId : -1;
    }

    public int gridWidth() {
        return open && hasGrid ? gridWidth : 0;
    }

    public int matrixSlot(int row, int col) {
        if (!open || !hasGrid || row < 0 || col < 0 || row >= gridWidth || col >= gridWidth) {
            return -1;
        }
        return 1 + col + row * gridWidth;
    }

    public int resultSlot() {
        return open && hasGrid ? 0 : -1;
    }

    public int[] storageSlots() {
        if (!open) {
            return new int[0];
        }
        int[] out = new int[slots.length - storageStart];
        for (int i = 0; i < out.length; i++) {
            out[i] = storageStart + i;
        }
        return out;
    }

    public CraftInventory.Held stackAt(int slot) {
        if (!open || slot < 0 || slot >= slots.length) {
            return null;
        }
        return held(slot == 0 ? resultPreview(slots) : slots[slot]);
    }

    public CraftInventory.Held cursor() {
        return held(cursor);
    }

    private static CraftInventory.Held held(ItemStack stack) {
        if (stack == null || stack.stackSize <= 0) {
            return null;
        }
        return new CraftInventory.Held(Item.itemRegistry.getNameForObject(stack.getItem()).toString(),
                stack.getMetadata(), stack.stackSize);
    }

    /**
     * Apply a click to the client container, and to the server's unless the window is locked.
     *
     * <p>The client half is unconditional even when locked, which is not an oversight: it is what
     * {@code windowClick} does. It calls {@code openContainer.slotClick} locally and only THEN queues
     * the packet (:537-538), so a locked window still moves on the client while the server ignores it.
     * That divergence is the defect being tested for.
     */
    public boolean click(int slot, int button) {
        if (!open || slot < 0 || slot >= slots.length || !hasGrid && slot < storageStart) {
            return false;
        }
        clicks++;
        if (slot == 0) {
            resultClicks++;
        }
        if (rejectAtClick > 0 && clicks == rejectAtClick && rejectPendingIn < 0) {
            rejectPendingIn = Math.max(1, resyncDelayTicks);
        }
        boolean serverApplies = !locked && (rejectAtClick <= 0 || clicks < rejectAtClick);
        if (!serverApplies) {
            droppedClicks++;
        }
        cursor = apply(slots, slot, button, cursor);
        if (serverApplies) {
            serverCursor = apply(serverSlots, slot, button, serverCursor);
        }
        if (slot == 0 && (vanishesOnTake != null || duringTakeRoundTrip != null)) {
            // Armed here, fired on the next tick. The output is on the CURSOR at this instant and the
            // controller parks it later in the same pass, so stealing now would take it from the cursor
            // and the park would simply put it back. The gap being modelled is the round trip AFTER the
            // parking click, which is the only window in which the controller has already stopped
            // watching and the confirm step has not yet looked.
            stealPendingIn = 1;
        }
        return true;
    }

    /** Empty every storage slot holding {@code item}, wherever the take just put it. */
    private void steal(ItemStack[] target, String item) {
        String wanted = new CraftInventory.Held(item, 0, 1).item();
        for (int slot = storageStart; slot < target.length; slot++) {
            CraftInventory.Held there = held(target[slot]);
            if (there != null && there.item().equals(wanted)) {
                target[slot] = null;
            }
        }
    }

    /**
     * Vanilla's {@code Container.slotClick} for the two modes this controller uses.
     *
     * @return the new cursor
     */
    private ItemStack apply(ItemStack[] target, int slot, int button, ItemStack onCursor) {
        if (slot == 0) {
            return takeResult(target, onCursor);
        }
        ItemStack there = target[slot];
        if (onCursor == null) {
            if (there == null) {
                return null;
            }
            // Container:325 -- button 0 takes the whole stack, button 1 takes half rounded up.
            int take = button == LEFT ? there.stackSize : (there.stackSize + 1) / 2;
            ItemStack lifted = there.splitStack(take);
            if (there.stackSize == 0) {
                target[slot] = null;
            }
            return lifted;
        }
        if (there == null) {
            // Container:303 -- button 0 places the whole cursor, button 1 places exactly ONE.
            int place = button == LEFT ? onCursor.stackSize : 1;
            target[slot] = onCursor.splitStack(place);
            return onCursor.stackSize == 0 ? null : onCursor;
        }
        if (there.getItem() == onCursor.getItem() && there.getMetadata() == onCursor.getMetadata()) {
            int room = there.getMaxStackSize() - there.stackSize;
            int place = Math.min(room, button == LEFT ? onCursor.stackSize : 1);
            there.stackSize += place;
            onCursor.stackSize -= place;
            return onCursor.stackSize == 0 ? null : onCursor;
        }
        // Different items: vanilla swaps them.
        target[slot] = onCursor;
        return there;
    }

    /**
     * Take the crafting output, which is what SPENDS the ingredients.
     *
     * <p>{@code SlotCrafting.onPickupFromSlot:134-160} decrements every occupied matrix cell by one.
     * Modelled here rather than assumed, so a controller that clicked the result twice consumes twice
     * and a test can see it.
     */
    private ItemStack takeResult(ItemStack[] target, ItemStack onCursor) {
        ItemStack out = resultPreview(target);
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
        for (int i = 0; i < gridWidth * gridWidth; i++) {
            ItemStack cell = target[1 + i];
            if (cell != null) {
                cell.stackSize--;
                if (cell.stackSize <= 0) {
                    target[1 + i] = null;
                }
            }
        }
        if (onCursor == null) {
            return out;
        }
        onCursor.stackSize += out.stackSize;
        return onCursor;
    }

    /** What the real recipe table makes of the matrix as it currently stands. */
    private ItemStack resultPreview(ItemStack[] target) {
        if (!hasGrid) {
            return null;
        }
        InventoryCrafting mirror = new InventoryCrafting(new CraftBench.Bench(), gridWidth, gridWidth);
        for (int i = 0; i < gridWidth * gridWidth; i++) {
            ItemStack cell = target[1 + i];
            mirror.setInventorySlotContents(i, cell == null ? null : cell.copy());
        }
        return CraftingManager.getInstance().findMatchingRecipe(mirror, null);
    }

    // ---- assertions helpers ----

    /** Every non-empty matrix cell, as "count x item/meta at (row,col)". Empty when the grid is clear. */
    String matrixContents() {
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < gridWidth; row++) {
            for (int col = 0; col < gridWidth; col++) {
                ItemStack cell = slots[1 + col + row * gridWidth];
                if (cell != null) {
                    sb.append(sb.length() == 0 ? "" : ", ").append(cell.stackSize).append("x ")
                            .append(Item.itemRegistry.getNameForObject(cell.getItem()))
                            .append('/').append(cell.getMetadata())
                            .append(" at (").append(row).append(',').append(col).append(')');
                }
            }
        }
        return sb.toString();
    }

    /** How many of one item variant sit in the CLIENT's storage slots. */
    int storedCount(String item, int meta) {
        return count(slots, item, meta);
    }

    /**
     * The same count on the SERVER's side, which is the only one that says what the player really owns.
     *
     * <p>Separate from {@link #storedCount} because on a rejected craft the two disagree, and the
     * client's number is the one a naive test would have believed.
     */
    int serverStoredCount(String item, int meta) {
        return count(serverSlots, item, meta);
    }

    private int count(ItemStack[] target, String item, int meta) {
        int total = 0;
        for (int slot = storageStart; slot < target.length; slot++) {
            ItemStack there = target[slot];
            CraftInventory.Held there1 = held(there);
            if (there1 != null && there1.meta() == meta
                    && there1.item().equals(new CraftInventory.Held(item, 0, 1).item())) {
                total += there.stackSize;
            }
        }
        return total;
    }
}
