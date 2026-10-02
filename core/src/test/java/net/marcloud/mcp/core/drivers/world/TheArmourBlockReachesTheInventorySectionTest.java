package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Teeth for the wiring: that the armour block a client can read actually REACHES a caller, in the
 * full projection and in the diff.
 *
 * <p>{@link ArmourValueIsVanillasTest} proves the arithmetic is vanilla's. This proves the fact is
 * reachable, which is the half that silently rots: a correct class nothing calls answers no
 * question, and an inventory section that quietly omits four of the player's forty cells looks
 * exactly like a player wearing nothing. The load-bearing case is the last one -- a swap must
 * surface as two named pieces, not as two index deltas nobody can act on.
 */
public final class TheArmourBlockReachesTheInventorySectionTest {

    @BeforeClass
    public static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
    }

    /**
     * A real {@link InventoryPlayer} read through the capture's own seam.
     *
     * <p>Driven from a live inventory object rather than a hand-built {@link InventoryView} so the
     * test exercises the array-to-slot conversion, which is where an index bug would hide. The
     * seam is package-private precisely so a test can reach it without a game.
     */
    private static InventoryView capture(InventoryPlayer inv) {
        return WorldViewCapture.inventoryOrNull(
                () -> Arrays.asList(inv.mainInventory), inv.currentItem,
                () -> Arrays.asList(inv.armorInventory));
    }

    private static InventoryPlayer wearing() {
        InventoryPlayer inv = new InventoryPlayer(null);
        inv.armorInventory[ArmourSlots.HEAD] = new ItemStack(net.minecraft.init.Items.diamond_helmet);
        inv.armorInventory[ArmourSlots.BOOTS] = new ItemStack(net.minecraft.init.Items.leather_boots);
        inv.mainInventory[0] = new ItemStack(net.minecraft.init.Items.diamond_pickaxe);
        return inv;
    }

    /** The four worn pieces must arrive, labelled with vanilla's own 36..39 indices. */
    @Test
    public void theFourPiecesArriveWithVanillasIndices() {
        InventoryView view = capture(wearing());
        assertNotNull(view);
        assertNotNull("the armour block must be captured, not omitted", view.armour());
        assertEquals("all four cells are published, occupied or not",
                ArmourSlots.COUNT, view.armour().size());

        for (int armorType = 0; armorType < ArmourSlots.COUNT; armorType++) {
            InventoryView.Slot s = view.armourAt(armorType);
            assertNotNull("cell " + armorType + " must be present even when empty", s);
            assertEquals("cell " + armorType + " must carry vanilla's inventory index",
                    ArmourSlots.inventoryIndexFor(armorType), s.index());
        }
        // And the occupied ones must be the right pieces, in the right cells.
        // The published name is the REGISTRY name with the domain stripped
        // (WorldViewCapture.slot:386-390), not the mapped field name: `Items.helmetDiamond` is a
        // Java identifier and never appears in anything an agent reads.
        assertEquals("diamond_helmet", view.armourAt(ArmourSlots.HEAD).item());
        assertEquals("leather_boots", view.armourAt(ArmourSlots.BOOTS).item());
        assertNull("an empty cell is a null item, not a missing row",
                view.armourAt(ArmourSlots.CHEST).item());
    }

    /**
     * The main inventory's indices must stay inside it -- adding the armour block must not push a
     * main-inventory slot to 36 or beyond.
     *
     * <p>Stated as an index bound rather than a list length, because the main-inventory list is
     * deliberately SPARSE: {@code inventoryOrNull} skips empty cells while preserving
     * {@code index}, so its length is the number of occupied slots and asserting a width of 36
     * would be asserting the wrong thing. What must hold is that no main-inventory row claims an
     * index at or past {@link ArmourSlots#MAIN_SLOTS}, because that is the exact collision that
     * would make a caller read a helmet as a hotbar slot.
     */
    @Test
    public void theMainInventoryIsNotWidenedByTheArmourBlock() {
        InventoryView view = capture(wearing());
        assertEquals("the one occupied hotbar slot must be present", 1, view.slots().size());
        for (InventoryView.Slot s : view.slots()) {
            assertTrue("main-inventory index " + s.index() + " must stay below "
                            + ArmourSlots.MAIN_SLOTS,
                    s.index() >= 0 && s.index() < ArmourSlots.MAIN_SLOTS);
        }
        assertEquals("the hotbar item must still be at index 0", 0, view.slots().get(0).index());
        // And the two index spaces must not overlap in either direction.
        for (InventoryView.Slot s : view.armour()) {
            assertTrue("armour index " + s.index() + " must be past the main inventory",
                    s.index() >= ArmourSlots.MAIN_SLOTS);
        }
    }

    /** The full projection publishes the block, and each row names the piece and its container slot. */
    @Test
    @SuppressWarnings("unchecked")
    public void theFullProjectionPublishesEveryPieceAndItsContainerSlot() {
        Map<String, Object> map = WorldViewJson.invMap(capture(wearing()));
        assertTrue("the projection must carry an armour key", map.containsKey("armor"));
        List<Object> rows = (List<Object>) map.get("armor");
        assertEquals(ArmourSlots.COUNT, rows.size());

        for (int armorType = 0; armorType < ArmourSlots.COUNT; armorType++) {
            Map<String, Object> row = (Map<String, Object>) rows.get(armorType);
            assertEquals("row " + armorType + " must name its piece",
                    ArmourSlots.pieceName(armorType), row.get("piece"));
            assertEquals("row " + armorType + " must carry the container slot that equips it",
                    ArmourSlots.containerSlotFor(armorType), row.get("containerSlot"));
            // The load-bearing direction: the helmet's container slot must NOT be the first of
            // the block. That inversion is what puts a helmet on the feet.
            if (armorType == ArmourSlots.HEAD) {
                assertFalse("the helmet must not live in the first drawn armour slot",
                        ArmourSlots.FIRST_CONTAINER_ARMOUR_SLOT
                                == (Integer) row.get("containerSlot"));
            }
        }
    }

    /**
     * An unread armour block is ABSENT from the projection, not an empty list.
     *
     * <p>The same three-state rule air and effects already follow. A player who is definitely not
     * wearing a helmet and a capture that could not read the armour block must not produce the
     * same JSON, because the first means "take this off" and the second means "I do not know".
     */
    @Test
    public void anUnreadArmourBlockIsAbsentRatherThanEmpty() {
        InventoryPlayer inv = wearing();
        InventoryView read = WorldViewCapture.inventoryOrNull(
                () -> Arrays.asList(inv.mainInventory), inv.currentItem, null);
        assertNotNull(read);
        assertNull("no armour supplier means no armour block", read.armour());
        assertFalse(WorldViewJson.invMap(read).containsKey("armor"));

        // And a capture that failed outright still returns null for the whole inventory rather
        // than a half-built one.
        InventoryView failed = WorldViewCapture.inventoryOrNull(() -> {
            throw new IllegalStateException("simulated read failure");
        }, 0, () -> Arrays.asList(inv.armorInventory));
        assertNull(failed);
    }

    /**
     * A swap must surface as the two named pieces, not as two index deltas.
     *
     * <p>This is what the diff is for. Helmet off and boots on is one act, and the caller needs
     * "head: null, boots: iron_boots" to reason about it; "index 36 changed" and "index 39
     * changed" describe the same event in a form that has to be re-derived against the same
     * inverted table {@link ArmourSlots} exists to publish.
     */
    @Test
    @SuppressWarnings("unchecked")
    public void aSwapSurfacesAsNamedPieces() {
        InventoryPlayer before = wearing();
        InventoryPlayer after = wearing();
        after.armorInventory[ArmourSlots.HEAD] = null;
        after.armorInventory[ArmourSlots.BOOTS] = new ItemStack(net.minecraft.init.Items.iron_boots);

        Map<String, Object> diff = WorldViewDiff.diff(
                worldViewWith(capture(before)), worldViewWith(capture(after)));
        Map<String, Object> inventory = (Map<String, Object>) diff.get("inventory");
        assertNotNull("the inventory section must report the swap", inventory);
        List<Object> now = (List<Object>) inventory.get("armorNow");
        assertNotNull("the swap must publish the whole block, named", now);
        assertEquals(ArmourSlots.COUNT, now.size());

        Map<String, Object> head = (Map<String, Object>) now.get(ArmourSlots.HEAD);
        Map<String, Object> boots = (Map<String, Object>) now.get(ArmourSlots.BOOTS);
        assertEquals("helmet", head.get("piece"));
        assertNull("the helmet must be reported as gone", head.get("item"));
        assertEquals("boots", boots.get("piece"));
        assertEquals("iron_boots", boots.get("item"));
    }

    /** Unchanged armour is silence, or the diff would fire on every poll of every player. */
    @Test
    @SuppressWarnings("unchecked")
    public void unchangedArmourIsSilence() {
        InventoryView view = capture(wearing());
        Map<String, Object> diff = WorldViewDiff.diff(worldViewWith(view), worldViewWith(view));
        Map<String, Object> inventory = (Map<String, Object>) diff.get("inventory");
        if (inventory != null) {
            assertFalse("an unchanged armour block must not report",
                    inventory.containsKey("armorNow"));
        }
    }

    /**
     * Every cell's reported container slot must be the one vanilla's own indexing reaches.
     *
     * <p>The end-to-end check: the number the projection hands a caller is fed straight back into
     * {@code InventoryPlayer.getStackInSlot} and must return that same piece. This is what turns
     * {@link ArmourSlots}'s table from a claim into a checked fact at the boundary a model would
     * actually use.
     */
    @Test
    public void everyReportedContainerSlotReadsBackItsOwnPiece() {
        InventoryPlayer inv = wearing();
        InventoryView view = capture(inv);
        for (int armorType = 0; armorType < ArmourSlots.COUNT; armorType++) {
            int containerSlot = view.containerSlotFor(armorType);
            // ContainerPlayer:32-52 builds the slot for k at getSizeInventory()-1-k.
            int inventoryIndex = inv.getSizeInventory() - 1
                    - (containerSlot - ArmourSlots.FIRST_CONTAINER_ARMOUR_SLOT);
            ItemStack read = inv.getStackInSlot(inventoryIndex);
            ItemStack expected = inv.armorInventory[armorType];
            if (expected == null) {
                assertNull("piece " + ArmourSlots.pieceName(armorType) + " must read as empty",
                        read);
            } else {
                assertNotNull(read);
                assertEquals("container slot " + containerSlot + " must hold the "
                                + ArmourSlots.pieceName(armorType),
                        expected.getItem(), read.getItem());
            }
        }
    }

    /** An out-of-range piece has no container slot and no cell, rather than silently mapping to 0. */
    @Test
    public void anUnknownPieceIsAnsweredNotGuessed() {
        InventoryView view = capture(wearing());
        assertEquals(-1, view.containerSlotFor(ArmourSlots.COUNT));
        assertEquals(-1, view.containerSlotFor(-1));
        assertNull(view.armourAt(ArmourSlots.COUNT));
        assertNull(view.armourAt(-1));
    }

    /** The armour list is a snapshot, so a caller holding it is unaffected by later mutation. */
    @Test
    public void theArmourBlockIsASnapshot() {
        InventoryPlayer inv = wearing();
        InventoryView view = capture(inv);
        List<InventoryView.Slot> held = new ArrayList<>(view.armour());
        inv.armorInventory[ArmourSlots.HEAD] = null;
        assertEquals("the captured block must not change under the caller",
                ArmourSlots.COUNT, held.size());
    }

    private static WorldView worldViewWith(InventoryView inv) {
        return new WorldView(true, 1L, "explore", null, null, new ArrayList<>(), false, inv, null, null);
    }
}
