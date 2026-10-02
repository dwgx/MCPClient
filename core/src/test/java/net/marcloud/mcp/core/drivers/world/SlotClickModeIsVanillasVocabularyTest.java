package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Teeth for {@link SlotClickMode}: that the click-mode vocabulary matches what vanilla's
 * {@code Container.slotClick} chain actually branches on, and that a drag-split is a complete
 * bracket rather than one packet.
 *
 * <p>The load-bearing half is {@link #theVocabularyIsExactlyTheModesVanillaBranchesOn}. The rest
 * of the class is arithmetic a caller can get wrong in isolation -- an unterminated drag, a floor
 * division that quietly loses the remainder -- and each of those is pinned as a property so it
 * holds for every input rather than at a chosen example.
 */
public final class SlotClickModeIsVanillasVocabularyTest {

    /**
     * Every mode {@link SlotClickMode} publishes is a real branch of {@code Container.slotClick},
     * and every mode that branch handles is published.
     *
     * <p>Expressed as a closure over both sets rather than as an equality of two hand-written
     * lists, because a hand-written list is exactly what goes stale: add a seventh mode upstream
     * and a pinned table keeps asserting six. Deriving from the declared constants means a new
     * mode has to be named here deliberately, and the "no gaps" half says so out loud.
     */
    @Test
    public void theVocabularyIsExactlyTheModesVanillaBranchesOn() {
        int[] declared = {
            SlotClickMode.PICKUP,
            SlotClickMode.QUICK_MOVE,
            SlotClickMode.SWAP_WITH_HOTBAR,
            SlotClickMode.CREATIVE_TAKE_STACK,
            SlotClickMode.DROP_SLOT,
            SlotClickMode.DRAG_SPLIT,
            SlotClickMode.GATHER_STACK,
        };
        for (int mode : declared) {
            assertTrue("mode " + mode + " must be a mode a C0E can carry",
                    SlotClickMode.isWindowClickMode(mode));
        }
        // Container.slotClick:145,229,253,388,429,445,458 -- one branch per declared mode, and
        // the chain has no other mode tests. So 0..6 is the whole vocabulary, contiguously.
        for (int mode = 0; mode <= SlotClickMode.GATHER_STACK; mode++) {
            assertTrue("mode " + mode + " must be reachable", SlotClickMode.isWindowClickMode(mode));
        }
        assertFalse("a mode past the chain is not a mode",
                SlotClickMode.isWindowClickMode(SlotClickMode.GATHER_STACK + 1));
        assertFalse(SlotClickMode.isWindowClickMode(-1));
    }

    /**
     * The modes must stay distinct, because a caller selects one by number.
     *
     * <p>Two constants sharing a value would make {@code isWindowClickMode} unable to tell them
     * apart and would make every doc comment in the class a lie. This is the cheapest possible
     * guard against a copy-paste in the constant block.
     */
    @Test
    public void everyModeIsItsOwnNumber() {
        int[] declared = {
            SlotClickMode.PICKUP,
            SlotClickMode.QUICK_MOVE,
            SlotClickMode.SWAP_WITH_HOTBAR,
            SlotClickMode.CREATIVE_TAKE_STACK,
            SlotClickMode.DROP_SLOT,
            SlotClickMode.DRAG_SPLIT,
            SlotClickMode.GATHER_STACK,
        };
        java.util.Set<Integer> unique = new java.util.HashSet<>();
        for (int mode : declared) {
            assertTrue("mode " + mode + " is declared twice", unique.add(mode));
        }
        assertEquals(declared.length, unique.size());
    }

    /**
     * The {@code button} byte packs an action and a drag mode into one int, the way vanilla's
     * {@code Container.func_94534_d:700-703} does.
     *
     * <p>Reproduced as the same expression rather than as a table of expected bytes, because the
     * interesting property is the bit layout: the two fields must not collide, so every
     * action/dragMode pair must produce a distinct byte and the low two bits must recover the
     * action exactly.
     */
    @Test
    public void theDragSplitButtonBytePacksTwoFieldsWithoutCollision() {
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        for (int action = 0; action <= 3; action++) {
            for (int dragMode = 0; dragMode <= 3; dragMode++) {
                int button = SlotClickMode.dragSplitPacket(action, dragMode);
                assertEquals("the low two bits must be the action", action, button & 3);
                assertEquals("the next two bits must be the drag mode",
                        dragMode, (button >> 2) & 3);
                assertTrue("action=" + action + " dragMode=" + dragMode + " collided",
                        seen.add(button));
            }
        }
        assertEquals(16, seen.size());
    }

    /**
     * A drag-split is a bracket: an opening marker, one packet per slot, and a closing marker.
     *
     * <p>This is the property that matters and the one a hand-built packet cannot have. Vanilla
     * sends all three back to back ({@code GuiContainer.mouseReleased:617-625}); a caller that
     * sends only the per-slot packet leaves the server in {@code dragEvent == 1}, which is not a
     * partial split but a cursor that will not move again. Asserted for several slot counts
     * because the degenerate cases -- zero slots and one slot -- are where a builder that forgets
     * the markers produces an empty or a two-packet sequence.
     */
    @Test
    public void aDragSplitIsBracketedAndComplete() {
        for (int slotCount = 0; slotCount <= 6; slotCount++) {
            int[] slots = new int[slotCount];
            for (int i = 0; i < slotCount; i++) {
                slots[i] = 3 + i;
            }
            int[][] packets = SlotClickMode.dragSplitPacket(slots, 0);
            assertEquals("slotCount=" + slotCount + " needs two markers plus one packet per slot",
                    slotCount + 2, packets.length);

            assertEquals("the bracket opens with no slot", SlotClickMode.NO_SLOT, packets[0][0]);
            assertEquals("the bracket opens with action 0", 0,
                    SlotClickMode.dragSplitPacket(0, 0), packets[0][1]);
            assertEquals(SlotClickMode.DRAG_SPLIT, packets[0][2]);

            int last = packets.length - 1;
            assertEquals("the bracket closes with no slot", SlotClickMode.NO_SLOT, packets[last][0]);
            assertEquals("the bracket closes with action 2",
                    SlotClickMode.dragSplitPacket(2, 0), packets[last][1]);
            assertEquals(SlotClickMode.DRAG_SPLIT, packets[last][2]);

            for (int i = 0; i < slotCount; i++) {
                assertEquals("every named slot must appear exactly once, in order",
                        slots[i], packets[i + 1][0]);
                assertEquals("a touched slot carries action 1",
                        SlotClickMode.dragSplitPacket(1, 0), packets[i + 1][1]);
                assertEquals("every packet in a drag is mode 5",
                        SlotClickMode.DRAG_SPLIT, packets[i + 1][2]);
            }
        }
    }

    /**
     * The marker packets are distinguishable from each other and from the slot packets.
     *
     * <p>If the opening and closing markers were byte-identical, a truncated sequence would be
     * indistinguishable from a complete one and the caller would have no way to notice it dropped
     * the last packet. This is the difference between "the drag bracket is a convention" and "the
     * drag bracket is checkable".
     */
    @Test
    public void theThreeDragKindsAreDistinguishable() {
        int[][] packets = SlotClickMode.dragSplitPacket(new int[] { 7 }, 0);
        int[][] byRow = { packets[0], packets[1], packets[2] };
        java.util.Set<Integer> buttons = new java.util.HashSet<>();
        for (int[] row : byRow) {
            buttons.add(row[1]);
        }
        assertEquals("open, touched and close must be three different buttons",
                byRow.length, buttons.size());
    }

    /**
     * The even split is a floor division, and the remainder is not lost -- it stays on the cursor.
     *
     * <p>{@code Container.computeStackSize:742-744} uses
     * {@code MathHelper.floor_float(stackSize / slotCount)}, so a 64-item stack over 5 slots puts
     * 12 in each and keeps 4. The conservation property is what a caller needs: the per-slot
     * figure and the remainder must always add back up to the original, or a split destroys or
     * invents items. Checked across the whole small domain rather than at a chosen example,
     * because the failures live at the uneven cases.
     */
    @Test
    public void anEvenSplitConservesEveryItem() {
        for (int stackSize = 0; stackSize <= 200; stackSize++) {
            for (int slotCount = 1; slotCount <= 17; slotCount++) {
                int each = SlotClickMode.evenSplitEach(stackSize, slotCount);
                int remainder = SlotClickMode.evenSplitRemainder(stackSize, slotCount);
                assertTrue("stackSize=" + stackSize + " slotCount=" + slotCount,
                        each >= 0 && remainder >= 0);
                assertEquals("stackSize=" + stackSize + " slotCount=" + slotCount
                                + " must not create or destroy items",
                        stackSize, each * slotCount + remainder);
                assertTrue("the remainder must be smaller than the divisor",
                        remainder < slotCount);
            }
        }
    }

    /**
     * A zero-slot split is answered, not divided by.
     *
     * <p>{@code evenSplitEach} divides by {@code slotCount}, and vanilla would produce a NaN that
     * {@code MathHelper.floor_float} turns into 0 -- but only because the caller already checked
     * the drag set was non-empty ({@code GuiContainer.mouseReleased:617}). A caller asking this
     * method directly has not been through that check, so the method has to answer rather than
     * propagate a division by zero.
     */
    @Test
    public void aSplitOverNoSlotsIsAnsweredNotCrashed() {
        assertEquals(0, SlotClickMode.evenSplitEach(64, 0));
        assertEquals(0, SlotClickMode.evenSplitEach(64, -3));
        assertEquals(0, SlotClickMode.evenSplitEach(0, 5));
        assertEquals(0, SlotClickMode.evenSplitEach(-7, 5));
        // Nothing placed anywhere means everything is still held.
        assertEquals(64, SlotClickMode.evenSplitRemainder(64, 0));
        assertEquals(7, SlotClickMode.evenSplitRemainder(7, 0));
        assertEquals(0, SlotClickMode.evenSplitRemainder(0, 5));
    }

    /**
     * A null slot array is the same question as an empty one.
     *
     * <p>{@code dragSplitPacket} takes the caller's array, and a caller assembling slots from a
     * container read can legitimately hand over null when it found none. Producing the two marker
     * packets alone is the honest answer: it is a complete, well-formed sequence that moves
     * nothing.
     */
    @Test
    public void aNullSlotArrayIsTreatedAsEmpty() {
        assertArrayEquals(SlotClickMode.dragSplitPacket(new int[0], 0),
                SlotClickMode.dragSplitPacket(null, 0));
    }

    /**
     * The drag mode travels in the packet and is not the click mode.
     *
     * <p>{@code Container.computeStackSize:742-752} switches on the drag mode to choose the
     * division: 0 divides evenly, 1 puts one down, 2 puts a whole stack (creative only). All
     * three ride inside mode {@link SlotClickMode#DRAG_SPLIT}, which is why a caller that builds
     * the packets without carrying the drag mode silently gets the even split.
     */
    @Test
    public void theDragModeRidesInsideTheDragSplitPacket() {
        for (int dragMode = 0; dragMode <= 2; dragMode++) {
            int[][] packets = SlotClickMode.dragSplitPacket(new int[] { 5 }, dragMode);
            for (int[] packet : packets) {
                assertEquals("every packet is mode 5 regardless of the drag mode",
                        SlotClickMode.DRAG_SPLIT, packet[2]);
                assertEquals("the drag mode must survive into the button byte",
                        dragMode, (packet[1] >> 2) & 3);
            }
        }
    }
}
