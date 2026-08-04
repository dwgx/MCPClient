package net.marcloud.mcp.core.drivers.craft;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.core.GameAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.PlayerControllerMP;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.inventory.Slot;
import net.minecraft.inventory.SlotCrafting;
import net.minecraft.item.ItemStack;

/**
 * The sole {@code net.minecraft} implementation of {@link CraftWindow}: the live open container.
 *
 * <p>Counterpart of {@code LivePlayerActuator} for the craft package, and the piece whose absence
 * made {@link CraftController} unreachable outside tests -- the controller and its fake were both
 * written, verified and mutation-tested, while nothing existed that could drive it against a real
 * window. Five handoffs listed "craft click side has never touched a real client" for that reason.
 *
 * <p><b>Nothing about the slot layout is hard-coded, and that is deliberate.</b> Both vanilla
 * crafting containers happen to place the result at 0 and the matrix at {@code 1 + col + row*width}
 * ({@code ContainerPlayer:26-32}, {@code ContainerWorkbench:25-31}), and {@link CraftWindow#matrixSlot}
 * says in as many words that a controller hard-coding that arithmetic "would be silently wrong the
 * first time it met a container that did not". So every index here is ASKED of the container through
 * public API:
 *
 * <ul>
 *   <li>the result slot is the one that IS a {@code SlotCrafting} -- that class is what consumes the
 *       matrix on pickup ({@code SlotCrafting.onPickupFromSlot}), so it is the definition of "the
 *       slot whose click crafts", not a position;
 *   <li>the matrix is whichever slots draw on an {@code InventoryCrafting}, and the width comes from
 *       that inventory's own {@code getWidth()};
 *   <li>a matrix cell maps to a container slot through {@code Container.getSlotFromInventory}, which
 *       is the container answering rather than us predicting;
 *   <li>storage is the player's main inventory indices {@code 0..35} resolved the same way, which
 *       EXCLUDES the four armour slots by construction: {@code ContainerPlayer:39} adds them at
 *       {@code getSizeInventory() - 1 - k}, i.e. player-inventory indices 36-39, so they can never
 *       appear in a scan of 0..35. Excluding them matters -- {@link CraftWindow#storageSlots} notes
 *       that parking a crafted sword in an armour slot fails {@code isItemValid} and leaves it on the
 *       cursor, and the cursor is dropped on the floor when the window closes.
 * </ul>
 *
 * <p>The cost is a short scan of {@code inventorySlots} per call rather than an array lookup. That is
 * paid once per controller tick against a 46-slot list, which is nothing beside a game tick, and it
 * buys a window implementation that is correct for a container nobody has written yet.
 *
 * <p><b>Reads are of the CLIENT container, on purpose.</b> {@code windowClick} applies the click to
 * the client container locally before sending it ({@code PlayerControllerMP:534-540}), so the client
 * copy is the prediction whose agreement with the server decides acceptance -- and it is therefore
 * exactly what the controller must re-read after a round trip to catch a rejection. Reading the
 * server's container instead would hide the disagreement the SETTLING state exists to find.
 *
 * <p>Every method returns a neutral value when there is no window, no player or no controller, rather
 * than throwing, so {@link CraftController} fails honestly instead of blowing up. All methods run on
 * the GAME THREAD; this class marshals nothing.
 */
public final class LiveCraftWindow implements CraftWindow {

    /**
     * Player-inventory indices the craft may work in: the main inventory including the hotbar.
     *
     * <p>{@code InventoryPlayer.mainInventory} is 36 long and the four armour stacks live past it, so
     * this bound is what separates workable storage from armour without naming either container's
     * layout. Read from the array rather than written as 36 so a change to vanilla's size does not
     * silently leave four slots unusable -- or, worse, start clicking armour.
     */
    private static int workableInventorySize(EntityPlayerSP p) {
        return p.inventory.mainInventory.length;
    }

    private final GameAccess game;

    public LiveCraftWindow(GameAccess game) {
        this.game = game == null ? new GameAccess() : game;
    }

    // ===== the open window =====

    /** The open container, or null when there is no player. */
    private Container container() {
        EntityPlayerSP p = game.player();
        return p == null ? null : p.openContainer;
    }

    @Override
    public boolean windowOpen() {
        return container() != null && playerController() != null;
    }

    @Override
    public int windowId() {
        Container c = container();
        return c == null ? -1 : c.windowId;
    }

    /**
     * The crafting inventory backing the open window's matrix, or null when it has none.
     *
     * <p>Found by asking which slots draw on an {@code InventoryCrafting} rather than by reaching for
     * {@code ContainerPlayer.craftMatrix} / {@code ContainerWorkbench.craftMatrix}: those are two
     * public fields on two unrelated classes with no common supertype, so naming them would mean an
     * {@code instanceof} ladder that silently refuses every future container. A chest or a furnace has
     * no such slot and yields null here, which {@link #gridWidth} turns into a refusal to craft.
     */
    private InventoryCrafting matrix() {
        return matrixOf(container());
    }

    /**
     * Package-private and static so the derivation can be driven by a SYNTHETIC container.
     *
     * <p>Same reason {@code WorldViewCapture.entitiesSection} and {@code ActActuator}'s read
     * accessors are reachable: the property worth pinning -- that the layout is derived and not
     * assumed -- lives on a path that otherwise needs a live client, and a mutation survives forever
     * if no test can reach it. Every vanilla constructor involved
     * ({@code InventoryCrafting}, {@code SlotCrafting}, {@code InventoryPlayer}) only STORES its
     * arguments, so a container mirroring vanilla's own layout can be built headlessly with a null
     * player -- which is what {@code CraftWindowLayoutIsDerivedNotAssumedTest} does.
     */
    static InventoryCrafting matrixOf(Container c) {
        if (c == null) {
            return null;
        }
        for (Slot s : c.inventorySlots) {
            if (s != null && s.inventory instanceof InventoryCrafting ic) {
                return ic;
            }
        }
        return null;
    }

    @Override
    public int gridWidth() {
        return gridWidthOf(container());
    }

    static int gridWidthOf(Container c) {
        InventoryCrafting m = matrixOf(c);
        // 0, not 1, when there is no matrix: CraftWindow#gridWidth calls that "a refusal to craft
        // rather than a 1x1 grid", and a 1 here would let a single-ingredient recipe try to craft in
        // a chest.
        return m == null ? 0 : m.getWidth();
    }

    @Override
    public int matrixSlot(int row, int col) {
        return matrixSlotOf(container(), row, col);
    }

    static int matrixSlotOf(Container c, int row, int col) {
        InventoryCrafting m = matrixOf(c);
        if (c == null || m == null) {
            return -1;
        }
        int width = m.getWidth();
        if (row < 0 || col < 0 || row >= m.getHeight() || col >= width) {
            return -1;
        }
        // The container maps its own inventory index to its own slot number. col + row*width is
        // InventoryCrafting's OWN indexing (ContainerPlayer:32 adds slots as j + i*2 over that same
        // inventory), not an assumption about where the container put them.
        Slot s = c.getSlotFromInventory(m, col + row * width);
        return s == null ? -1 : s.slotNumber;
    }

    @Override
    public int resultSlot() {
        return resultSlotOf(container());
    }

    static int resultSlotOf(Container c) {
        if (c == null) {
            return -1;
        }
        for (Slot s : c.inventorySlots) {
            if (s instanceof SlotCrafting) {
                return s.slotNumber;
            }
        }
        return -1;
    }

    @Override
    public int[] storageSlots() {
        EntityPlayerSP p = game.player();
        return p == null ? new int[0]
                : storageSlotsOf(container(), p.inventory, workableInventorySize(p));
    }

    /**
     * Storage slots derived from a container and the inventory whose indices count as workable.
     *
     * <p>{@code workable} is the exclusive upper bound on PLAYER-INVENTORY index, and it is what keeps
     * the armour slots out: {@code ContainerPlayer:39} adds them at
     * {@code getSizeInventory() - 1 - k}, i.e. indices 36-39 of a 40-slot inventory, so a scan of
     * {@code 0..35} can never reach one. Passed in rather than read from a live player so the boundary
     * itself is testable -- it is the one number here whose being wrong would make the controller
     * park a crafted item into an armour slot, where {@code isItemValid} refuses it and it stays on
     * the cursor to be dropped on the floor when the window closes.
     */
    static int[] storageSlotsOf(Container c, net.minecraft.inventory.IInventory playerInv,
                                int workable) {
        if (c == null || playerInv == null) {
            return new int[0];
        }
        int size = Math.max(0, workable);
        List<Integer> out = new ArrayList<>(size);
        // Ascending player-inventory index, so the hotbar (0-8) comes before the main rows. The
        // controller picks a SOURCE by smallest matching stack rather than by position, so this order
        // only decides where the cursor gets PARKED -- and the hotbar is where a human would expect a
        // freshly crafted item to land.
        for (int i = 0; i < size; i++) {
            Slot s = c.getSlotFromInventory(playerInv, i);
            if (s != null) {
                out.add(s.slotNumber);
            }
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    // ===== reads =====

    @Override
    public CraftInventory.Held stackAt(int slot) {
        return stackAtOf(container(), slot);
    }

    static CraftInventory.Held stackAtOf(Container c, int slot) {
        if (c == null || slot < 0 || slot >= c.inventorySlots.size()) {
            return null;
        }
        Slot s = c.getSlot(slot);
        return s == null ? null : held(s.getStack());
    }

    @Override
    public CraftInventory.Held cursor() {
        EntityPlayerSP p = game.player();
        return p == null ? null : held(p.inventory.getItemStack());
    }

    /**
     * A window stack as the feasibility check and the controller compare them, or null when empty.
     *
     * <p>Name resolution is delegated to {@link RecipeLayoutReader#itemName}, the same method that
     * produced the ingredient names on the other side of every comparison. That is the point: an
     * independent copy here would be a second normalisation rule, and the first time the two
     * disagreed a stack the player was holding would be reported as a missing ingredient.
     */
    private static CraftInventory.Held held(ItemStack st) {
        if (st == null || st.stackSize <= 0) {
            return null;
        }
        String name = RecipeLayoutReader.itemName(st.getItem());
        if (name == null) {
            return null;
        }
        return new CraftInventory.Held(name, st.getItemDamage(), st.stackSize);
    }

    // ===== the write =====

    private PlayerControllerMP playerController() {
        Minecraft mc = game.mc();
        return mc == null ? null : mc.playerController;
    }

    @Override
    public boolean click(int slot, int button) {
        Container c = container();
        EntityPlayerSP p = game.player();
        PlayerControllerMP pc = playerController();
        if (c == null || p == null || pc == null) {
            return false;
        }
        if (slot < 0 || slot >= c.inventorySlots.size()) {
            return false;
        }
        try {
            // Mode 0 (PICKUP), which is the only mode this seam exposes: CraftWindow documents LEFT
            // and RIGHT as vanilla's clickedButton, and button semantics only mean take-all /
            // take-half inside mode 0. windowClick applies the click to the CLIENT container first
            // and sends what that returned as the packet's claimed item, which is what makes the
            // prediction and the claim the same value by construction.
            pc.windowClick(c.windowId, slot, button, 0, p);
            return true;
        } catch (Throwable t) {
            // False means the write could not be ISSUED. Deliberately not conflated with the server
            // refusing -- CraftWindow#click says reading acceptance into this boolean "would be true
            // on every click of a craft the server dropped in full", and the controller's SETTLING
            // state is what actually detects a rejection.
            return false;
        }
    }
}
