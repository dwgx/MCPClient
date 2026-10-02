package net.marcloud.mcp.core.drivers.world;

/**
 * Vanilla's window-click {@code clickMode} vocabulary, transcribed, so a caller can build the
 * right C0E instead of guessing which integer means "drop this".
 *
 * <p><b>Why this is a fact and not a lookup table of magic numbers.</b> The gap matrix marks
 * quick-move (row 4.9), GUI-slot drop (4.11), drag-split (4.12) and double-click gather (4.13) all
 * PARTIAL for the same reason: {@code do_click_slot(mode=N)} can express them, but nothing in the
 * project says what N is, and the two ways a human performs them -- a Ctrl-click and the
 * release of a drag -- build the packet differently. Sending the wrong mode does not error; it
 * moves the wrong items.
 *
 * <p><b>Where each mode comes from, in vanilla.</b> {@code Container.slotClick:140-500} is one
 * flat {@code if}/{@code else if} chain over six behaviours, and {@code GuiContainer} reaches
 * each from a different gesture. Every line number below is a branch in that chain, because the
 * chain -- not the tool schema -- is what defines the vocabulary:
 * <ul>
 *   <li><b>0</b> pickup / place / right-click-halve -- {@code Container.java:229-386}. One
 *       branch for all three: {@code clickedButton} 0 moves the whole stack, 1 moves one (or half,
 *       when the cursor already holds something, via {@code (stackSize + 1) / 2}).</li>
 *   <li><b>1</b> shift-move -- {@code Container.java:253-275}, reached from
 *       {@code GuiContainer.mouseClicked:405-410} with shift held.</li>
 *   <li><b>2</b> swap with a hotbar slot -- {@code Container.java:388-428}, needing a second
 *       number.</li>
 *   <li><b>3</b> creative full-stack take -- {@code Container.java:429-444}, gated on creative
 *       mode.</li>
 *   <li><b>4</b> drop from a named slot -- {@code Container.java:445-457}.</li>
 *   <li><b>5</b> drag-split -- {@code Container.java:145-225}, a state machine over three
 *       packets rather than one.</li>
 *   <li><b>6</b> gather a stack -- {@code Container.java:458-495}, the timed double-click.</li>
 * </ul>
 *
 * <p><b>There is a second, unrelated drop.</b> Mode <b>0</b> with {@code slotId == -999} also
 * throws the cursor stack away ({@code Container.java:230-252}), and that is the one the Q key
 * produces ({@code GuiContainer.handleKeyTyped:705-710}) as well as a click outside the panel
 * body. So "drop what I'm holding" is reachable two ways that look alike and are not the same
 * packet: mode 0 with no slot drops one item or the whole cursor depending on the button, mode 4
 * drops out of a slot you name. {@link #NO_SLOT} exists because the first needs it.
 *
 * <p><b>Mode 5 is the reason this class exists rather than an enum.</b> A caller cannot send
 * "mode 5 at slot N"; vanilla sends a begin marker, one packet per slot, and an end marker, and
 * {@link #dragSplitPacket} builds those three so the bracket is not left open -- an unterminated
 * drag is not a partial split, it is a stuck cursor. The per-slot {@code button} value is not a
 * mode either: it is two independent fields packed into one int by
 * {@code Container.func_94534_d:700-703} ({@code action & 3 | (dragMode & 3) << 2}), which is
 * how one {@code button} byte carries "slot touched, and evenly" together.
 */
public final class SlotClickMode {

    /** Pick up the whole stack, or place the cursor stack down. A plain left click. */
    public static final int PICKUP = 0;

    /** Shift-move the stack between inventories. {@code transfer_item}'s path. */
    public static final int QUICK_MOVE = 1;

    /**
     * Swap the clicked slot with the hotbar slot named by {@code clickedButton} (0-8).
     *
     * <p>{@code Container.slotClick:388-428} verbatim: it reads
     * {@code inventoryplayer.getStackInSlot(clickedButton)} and exchanges the two, falling back
     * to the first empty hotbar cell when the target will not accept the stack. So {@code mode=2}
     * needs a <em>second</em> number -- the hotbar slot -- which is why a bare "mode 2" swaps with
     * hotbar 0 rather than with another container slot.
     *
     * <p>This is the number behind row 4.14 (swap two slots): to swap two container slots rather
     * than a container slot and the hotbar, the caller swaps with each in turn.
     */
    public static final int SWAP_WITH_HOTBAR = 2;

    /**
     * Creative-only: put a full stack of the clicked slot's item on the cursor.
     *
     * <p>{@code Container.slotClick:429-444} guards on
     * {@code playerIn.capabilities.isCreativeMode} <em>and</em> an empty cursor, and copies the
     * stack up to {@code getMaxStackSize()}. On a survival server the guard fails and the click is
     * a no-op -- the packet is accepted and nothing happens, which is the worst kind of "sent".
     * This is the {@code C10PacketCreativeInventoryAction} surface wearing a {@code C0E} shape.
     */
    public static final int CREATIVE_TAKE_STACK = 3;

    /**
     * Throw items out of a container slot, straight onto the floor.
     *
     * <p>{@code Container.slotClick:445-457} verbatim: {@code slot3.decrStackSize(clickedButton == 0
     * ? 1 : slot3.getStack().stackSize)} then {@code dropPlayerItemWithRandomChoice}. So
     * {@code clickedButton} chooses one item or the whole stack, and unlike the {@code slotId == -999}
     * branch of mode 0 this operates on a <em>named slot</em> and requires {@code slotId >= 0}.
     *
     * <p>The item does not vanish -- it becomes an {@code EntityItem} at the player's feet -- so
     * the confirmable fact is a new entity near the player, never an empty slot. A caller that
     * reports "dropped" because the slot still holds the item is reading before the server has
     * applied anything, which is the failure mode {@code transfer_item}'s settle loop exists to
     * avoid.
     */
    public static final int DROP_SLOT = 4;

    /**
     * Even drag-split: distribute the cursor stack across a set of slots.
     *
     * <p>Sent only as the middle packet of the {@link #dragSplitPacket} triple. The division
     * itself is {@code Container.computeStackSize:742-744}, a floor division by the number of
     * slots, which is why an uneven split leaves items on the cursor rather than losing them.
     */
    public static final int DRAG_SPLIT = 5;

    /**
     * Gather a whole stack from matching slots -- the timed double-click.
     *
     * <p>{@code GuiContainer.mouseReleased:534-551} sends this once per matching slot. Vanilla
     * only reaches it when {@code doubleClick} is already set by the second {@code mouseClicked}
     * within 250 ms ({@code GuiContainer.mouseClicked:363-365}), so this mode is unusable without
     * the timed pair, and a caller sending it blind sends a gather on every slot it names.
     */
    public static final int GATHER_STACK = 6;

    /** The {@code slotNumber} vanilla sends for a packet that is not about one slot. */
    public static final int NO_SLOT = -999;

    private SlotClickMode() {
    }

    /**
     * Whether {@code mode} is a {@code C0E} click mode at all -- all seven are, including 3.
     *
     * <p>{@code Container.slotClick} branches on mode 3 ({@code Container.java:429-444}), so it is
     * a genuine window-click mode and belongs in the vocabulary. What makes it useless on a
     * survival server is the creative-mode guard inside that branch, which is a property of the
     * <em>player</em> rather than of the mode, so it is deliberately not modelled here: this
     * answers "is this a mode a packet can carry", and the answer for 3 is yes.
     *
     * <p>What this cannot tell a caller is whether the mode will <em>do</em> anything. Mode 3 on a
     * survival server is accepted and ignored, which is exactly why a send must be confirmed by a
     * re-read rather than by the absence of an error.
     */
    public static boolean isWindowClickMode(int mode) {
        return mode >= PICKUP && mode <= GATHER_STACK;
    }

    /**
     * The {@code button} byte for one packet of a drag-split, i.e. vanilla's
     * {@code Container.func_94534_d(action, dragMode)}.
     *
     * <p>Two fields in one int: {@code action & 3} in the low bits and {@code dragMode & 3} in
     * bits 2-3 ({@code Container.java:700-703}). The {@code dragMode} is the mouse button that
     * started the drag -- 0 for even, 1 for one-per-slot, 2 for creative-only
     * ({@code GuiContainer.mouseClicked:441-449}) -- and it is not the click mode, which is the
     * confusion that makes a hand-built mode-5 packet move a whole stack into every named slot.
     *
     * @param action   0 for the opening marker, 1 for a touched slot, 2 for the closing marker
     * @param dragMode 0 even, 1 one-per-slot, 2 creative whole-stack
     */
    public static int dragSplitPacket(int action, int dragMode) {
        return action & 3 | (dragMode & 3) << 2;
    }

    /**
     * The three packets a drag-split is, in the order they must be sent.
     *
     * <p>{@code GuiContainer.mouseReleased:617-625} verbatim: an opening {@link #NO_SLOT} click
     * with action 0, one click per slot with action 1, then a closing {@link #NO_SLOT} click with
     * action 2. All three carry {@link #DRAG_SPLIT} as the mode.
     *
     * <p>Return the packets rather than sending them, because the bracket is the whole point and
     * a caller that interleaves its own clicks into the sequence -- or gives up half way -- leaves
     * the server mid-drag. Sending all three back to back is what vanilla does.
     *
     * @param slotNumbers container slots the drag was released over, in the order collected
     * @param dragMode    0 even, 1 one-per-slot, 2 creative whole-stack
     * @return three packets: open, one per slot, close
     */
    public static int[][] dragSplitPacket(int[] slotNumbers, int dragMode) {
        int[] slots = slotNumbers == null ? new int[0] : slotNumbers;
        int[][] out = new int[slots.length + 2][3];
        out[0][0] = NO_SLOT;
        out[0][1] = dragSplitPacket(0, dragMode);
        out[0][2] = DRAG_SPLIT;
        for (int i = 0; i < slots.length; i++) {
            out[i + 1][0] = slots[i];
            out[i + 1][1] = dragSplitPacket(1, dragMode);
            out[i + 1][2] = DRAG_SPLIT;
        }
        int last = out.length - 1;
        out[last][0] = NO_SLOT;
        out[last][1] = dragSplitPacket(2, dragMode);
        out[last][2] = DRAG_SPLIT;
        return out;
    }

    /**
     * How many items an even drag-split puts in each of {@code slotCount} slots.
     *
     * <p>{@code Container.computeStackSize:742-744} verbatim:
     * {@code MathHelper.floor_float((float)stackSize / (float)slotCount)} -- a floor, and the
     * remainder stays on the cursor. That remainder is the reason a 64-stack dropped over 5 slots
     * gives 12 each and keeps 4, and it is why a caller must not pre-divide and assume it got
     * them all.
     *
     * <p>Zero slots is answered with {@code 0} rather than the division by zero vanilla would
     * produce; the caller is asking a question, not reproducing a crash.
     */
    public static int evenSplitEach(int stackSize, int slotCount) {
        if (slotCount <= 0 || stackSize <= 0) {
            return 0;
        }
        return Math.floorDiv(stackSize, slotCount);
    }

    /**
     * Items an even drag-split leaves on the cursor.
     *
     * <p>The floor division's remainder, which is the whole cost of {@link #evenSplitEach}. A
     * caller that reports the per-slot figure as "the split" is silently short by exactly this
     * much, and the shortfall is invisible in the world because the cursor is not a world slot.
     */
    public static int evenSplitRemainder(int stackSize, int slotCount) {
        if (slotCount <= 0 || stackSize <= 0) {
            return Math.max(0, stackSize);
        }
        return stackSize - evenSplitEach(stackSize, slotCount) * slotCount;
    }
}
