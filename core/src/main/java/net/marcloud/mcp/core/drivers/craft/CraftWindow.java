package net.marcloud.mcp.core.drivers.craft;

/**
 * The client-free seam between {@link CraftController} and a live crafting window.
 *
 * <p>Every container touch the controller needs is a method here; tests drive the controller through
 * a fake whose slots MUTATE on click, which is the only way a placement sequence can be checked at
 * all. Same wedge {@code ActActuator} is for the action layer, deliberately NOT that interface: the
 * act package owns it, its thirty-odd methods are about a player's body rather than a container, and
 * a craft needs the one thing it does not have -- slots whose contents change as a consequence of
 * the calls being made.
 *
 * <p><b>Every write goes through vanilla's {@code PlayerControllerMP.windowClick}</b>, and that
 * choice is the whole reason this seam is shaped around clicks rather than packets.
 * {@code windowClick} (PlayerControllerMP:534-540) calls {@code openContainer.slotClick} LOCALLY
 * first and sends the {@code ItemStack} that call returned as the packet's claimed item. The server
 * then runs its own {@code slotClick} and accepts only when
 * {@code ItemStack.areItemStacksEqual(packet.getClickedItem(), its own result)} holds
 * (NetHandlerPlayServer:1029). So the client's prediction IS the claim, and the two agree by
 * construction as long as the two sides started from the same state. A raw C0E path -- which
 * {@code do_click_slot} is -- never touches the client container, so it claims an item nobody
 * computed and desyncs the window on its first non-trivial click.
 *
 * <p><b>What a mismatch costs, since it is the hazard this seam exists to survive.</b> On mismatch
 * the server replies {@code S32(accepted=false)}, resyncs the window, and calls
 * {@code setCanCraft(player, false)} (:1041). The entire click body is gated on {@code getCanCraft}
 * (:1012), so from then on EVERY click on that window is silently dropped -- no error, no reply --
 * until the client echoes the action number back in C0F ({@code processConfirmTransaction}
 * :1142-1147), which vanilla's own {@code handleConfirmTransaction} does automatically. A controller
 * that kept clicking through that window would report a finished craft while the server had done
 * none of it. Nothing in this interface can detect that synchronously; detection is the controller's
 * job, by re-reading these slots after a round trip.
 *
 * <p>All methods run on the GAME THREAD. Reads return neutral values ({@code false}, {@code null},
 * an empty array) when there is no window rather than throwing, so the controller can fail honestly
 * instead of blowing up.
 */
public interface CraftWindow {

    /**
     * Whether there is a window to click into at all.
     *
     * <p>False means no world, no player, or no open container -- conditions no retry improves.
     * Note that in vanilla the player ALWAYS has a container open ({@code inventoryContainer}, its
     * 2x2 grid), so a true here says nothing about WHICH window is open; that is
     * {@link #gridWidth()} and {@link #windowId()}.
     */
    boolean windowOpen();

    /**
     * Vanilla's {@code Container.windowId} for the open window, or {@code -1} when none is open.
     *
     * <p>Exists so a craft in progress can notice the window CHANGED under it. The player's own
     * inventory container is id 0 and a server-opened bench is non-zero, so a bench closing
     * mid-craft moves this. Without it the controller would keep addressing 3x3 slot indices at a
     * 2x2 container: slot 5 is a matrix square in a workbench and an armour slot in the player's
     * window, so the clicks would land somewhere real and wrong, and the craft would report progress
     * while dismantling the player's armour.
     */
    int windowId();

    /**
     * Width of the open window's crafting matrix: 2 for the player's own grid, 3 for a bench.
     *
     * <p>Width alone rather than width and height because vanilla has no non-square crafting grid --
     * {@code ContainerPlayer} builds {@code new InventoryCrafting(this, 2, 2)} and
     * {@code ContainerWorkbench} {@code (this, 3, 3)}, and there is no third. Returning one number
     * for a square is honest; returning two would invite a caller to handle a shape that cannot
     * occur.
     *
     * <p>0 when the open window has no crafting matrix at all (a chest, a furnace), which is a
     * refusal to craft rather than a 1x1 grid.
     */
    int gridWidth();

    /**
     * Container slot index for matrix cell ({@code row}, {@code col}), or {@code -1} if out of the
     * open grid.
     *
     * <p>The arithmetic lives HERE, not in the controller, for the reason {@link RecipeView} refuses
     * to emit slot indices: it depends on the container. Both vanilla crafting containers happen to
     * lay the matrix out as {@code 1 + col + row * width} directly after the result slot, but that is
     * a fact about those two classes, and a controller that hard-coded it would be silently wrong the
     * first time it met a container that did not.
     */
    int matrixSlot(int row, int col);

    /**
     * Container slot index of the crafting OUTPUT, or {@code -1} when the window has no matrix.
     *
     * <p>Clicking it is what consumes the ingredients: vanilla's {@code SlotCrafting.onPickupFromSlot}
     * decrements every occupied matrix cell by one (SlotCrafting:134-160). So this is not a read of a
     * finished item, it is the trigger.
     */
    int resultSlot();

    /**
     * Container slot indices the craft may take ingredients FROM and park output IN, in the order the
     * controller should prefer them.
     *
     * <p>Deliberately excludes the matrix, the result and the armour slots: parking a crafted sword
     * in an armour slot fails {@code isItemValid} and leaves it on the cursor, and a click that lands
     * in the matrix is an ingredient the controller did not plan for. An empty array means there is
     * nowhere to work, which is a refusal, not an empty inventory.
     */
    int[] storageSlots();

    /**
     * What is in container slot {@code slot}, or {@code null} for empty / out of range.
     *
     * <p>Reuses {@link CraftInventory.Held} rather than adding a third item-value record next to it
     * and {@link RecipeView.Cell}: it is already (item, meta, count) with the namespace stripping and
     * lowercasing that makes a window slot comparable to an ingredient name, and two conventions for
     * one concept in one package is a trap. It also means {@link CraftFeasibility} can be run against
     * a live window with no adapter.
     */
    CraftInventory.Held stackAt(int slot);

    /** What is held on the cursor, or {@code null} when the cursor is empty. */
    CraftInventory.Held cursor();

    /**
     * Click a slot through {@code PlayerControllerMP.windowClick}; returns whether the write TOOK.
     *
     * <p>False means the click could not be issued at all -- no window, no player controller, slot
     * out of range. It does NOT mean the server refused: a click that was issued has, at this
     * instant, already been applied to the CLIENT container and queued as one packet, and whether the
     * server agreed is unknowable until a round trip has passed. Reading acceptance into this
     * boolean is exactly the "reports healthy while being wrong" shape -- it would be true on every
     * click of a craft the server dropped in full.
     *
     * <p>N calls inside one game-thread pass are not inherently multi-tick: each queues one packet
     * and the server drains them FIFO from the same starting state.
     *
     * @param slot   container slot index, as {@link #matrixSlot}, {@link #resultSlot} and
     *               {@link #storageSlots} report them
     * @param button vanilla's {@code clickedButton}: {@link #LEFT} takes or places the WHOLE stack,
     *               {@link #RIGHT} takes half or places exactly ONE
     */
    boolean click(int slot, int button);

    /** Vanilla's {@code clickedButton} 0: take the whole stack, or place the whole cursor. */
    int LEFT = 0;

    /**
     * Vanilla's {@code clickedButton} 1: take half, or place exactly one.
     *
     * <p>What makes filling a grid affordable. {@code Container.slotClick} splits exactly one off the
     * cursor for button 1 ({@code int k2 = clickedButton == 0 ? itemstack10.stackSize : 1}), so one
     * pick-up covers a whole run of squares. The rejected alternative was pick-up / place-all /
     * take-back per square, which triples the clicks and leaves the cursor loaded between them.
     */
    int RIGHT = 1;
}
