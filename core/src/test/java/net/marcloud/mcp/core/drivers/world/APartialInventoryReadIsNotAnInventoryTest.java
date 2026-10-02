package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.GameAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import org.junit.After;
import org.junit.Test;

/**
 * The same defect shape {@code SelfEffectsSeparateUnreadFromNoneTest} covers, one section over: a
 * read that fails PARTWAY through handing back what it had collected.
 *
 * <p>{@code WorldViewCapture.inventory()} accumulated slots inside one try, so a throw anywhere in
 * the loop returned the slots read so far. {@link WorldViewDiff} compares slot INDEX sets, so every
 * slot after the failure point reported {@code cleared} -- and a model reads {@code cleared} as
 * "this slot is empty". It would conclude it had just lost the pickaxe it is holding and plan
 * around a kit it never lost. The sibling {@code effectsOrNull}'s own javadoc already states the
 * rule this file pins: "a HALF list is the dangerous case rather than the safe one".
 *
 * <p>Two halves, both driven rather than asserted from prose. The capture half drives the seam
 * {@code inventoryOrNull} with a read that throws after three slots, which is the shape of a getter
 * failing mid-array; the differ half drives the two views that failure produces and asks what a
 * caller would receive.
 *
 * <p>The end-to-end case uses the smallest client the capture accepts -- a blank
 * {@code EntityPlayerSP} whose {@code inventory} field was never initialised, so reading it is the
 * failure. That is the same husk technique {@code ToolDescriptionsMatchTheirBoundsTest} uses for the
 * send path, and it is what makes the difference between "a failed read is null" and "a failed read
 * is an empty kit" observable without a live game.
 */
public final class APartialInventoryReadIsNotAnInventoryTest {

    /** A slot the caller would notice losing, and one it would not. */
    private static InventoryView kit(int selectedSlot, InventoryView.Slot... slots) {
        return new InventoryView(selectedSlot, List.of(slots));
    }

    private static WorldView inventoryOnly(long tick, InventoryView inv) {
        return new WorldView(true, tick, "explore", null, null, List.of(), false, inv, null, null);
    }

    /**
     * An iterable that delivers {@code delivered} empty slots and then throws -- the same shape as
     * the real failure, where the throw lands PARTWAY through the array rather than before it.
     *
     * <p>Bounded so the loop terminates: after the last delivered element the next {@code next()}
     * throws, exactly as a getter on a corrupt stack would.
     */
    private static Iterable<ItemStack> failsAfter(int delivered) {
        return () -> new Iterator<ItemStack>() {
            private int seen;

            @Override
            public boolean hasNext() {
                return true;
            }

            @Override
            public ItemStack next() {
                if (seen++ == delivered) {
                    throw new IllegalStateException("inventory read failed");
                }
                return null;
            }
        };
    }

    // ===== the capture seam: all-or-nothing, like effectsOrNull =====

    /**
     * A read that fails partway must DISCARD what it collected.
     *
     * <p>The dangerous case, and the reason the helper exists. Returning the slots read before the
     * throw would be a kit quietly missing everything after it, and the differ reports exactly those
     * as {@code cleared} -- the false loss this whole change exists to prevent.
     */
    @Test
    public void aReadThatFailsPartwayYieldsNoInventoryRatherThanWhatItCollected() {
        assertNull("a partial list is worse than no list: every slot after the failure point would "
                        + "report as cleared, which reads as 'the model just lost its pickaxe'",
                WorldViewCapture.inventoryOrNull(() -> failsAfter(3), 0));
    }

    @Test
    public void aReadThatFailsOnItsVeryFirstSlotIsAlsoNoInventory() {
        assertNull("nothing collected is still a failed read, not an empty kit",
                WorldViewCapture.inventoryOrNull(() -> failsAfter(0), 0));
    }

    /**
     * The negative half: a successful read must survive as itself, or the fix could be satisfied by
     * answering "could not read" always -- which would be strictly worse than the partial list,
     * because a caller could never see its inventory at all.
     */
    @Test
    public void aReadableInventorySurvivesWithItsSlotIndexesIntact() {
        Bootstrap.register();   // the item registry the stack's name is resolved through
        ItemStack stick = new ItemStack(Items.stick, 7);

        InventoryView inv = WorldViewCapture.inventoryOrNull(() -> Arrays.asList(null, stick, null), 4);

        assertNotNull("a successful read must not be reported as a failure", inv);
        assertEquals("the held slot survives", 4, inv.selectedSlot());
        assertEquals("one non-empty slot", 1, inv.slots().size());
        assertEquals("and it keeps its ARRAY index, which is the key the differ compares -- a source "
                        + "that skipped the empty slots would renumber the kit and report every "
                        + "remaining slot as changed", 1, inv.slots().get(0).index());
        assertEquals("stick", inv.slots().get(0).item());
        assertEquals(7, inv.slots().get(0).count());
    }

    @Test
    public void anInventoryThatIsEntirelyEmptyIsAnEmptyListAndNotNull() {
        InventoryView inv = WorldViewCapture.inventoryOrNull(() -> Arrays.asList(null, null), 2);
        assertNotNull("most of a real inventory is empty slots; that is a successful read of "
                + "nothing, not a failure", inv);
        assertTrue(inv.slots().isEmpty());
        assertEquals("and the held slot is still reported", 2, inv.selectedSlot());
    }

    // ===== the view: a failed read is not an empty kit =====

    /**
     * The consequence at the top of the seam: a player whose inventory cannot be read must reach the
     * view as null, not as {@code InventoryView(0, [])}.
     *
     * <p>{@code InventoryView(0, [])} is a POSITIVE claim -- "you are holding nothing and your 36
     * slots are empty" -- and a model acts on it by crafting a replacement for a tool it still has.
     */
    @Test
    public void anUnreadableInventoryReachesTheViewAsNullRatherThanAsAnEmptyKit() throws Exception {
        installBlankClient();
        WorldView v = WorldViewCapture.capture(new GameAccess(), ObserveProfile.SPARSE, 1,
                List.of("inventory"));

        assertTrue("the view itself is still a real one -- the player is in a world", v.present());
        assertNull("an inventory that could not be read must be null. The old code answered an empty "
                        + "kit, which is the same token as 'I checked and you hold nothing'",
                v.inventory());
    }

    // ===== the differ: no cleared list for a section that was not read =====

    @Test
    public void anUnreadInventoryIsNotReportedAsAClearedList() {
        WorldView before = inventoryOnly(1L, kit(0,
                new InventoryView.Slot(0, "diamond_pickaxe", 1, 12, 1561),
                new InventoryView.Slot(1, "torch", 8, 0, null)));
        // What a failed read produces now: no reading at all.
        WorldView after = inventoryOnly(2L, null);

        Map<String, Object> d = WorldViewDiff.diff(before, after);
        Map<String, Object> inv = asMap(d.get("inventory"));
        assertNotNull("silence is not available: in diff mode a missing key means 'unchanged', so "
                + "the caller would be told its kit still holds the pickaxe it can no longer see",
                inv);
        assertEquals("the honest statement is that this poll has no reading",
                Boolean.TRUE, inv.get("unsampled"));
        assertFalse("THE REGRESSION: the slots after a mid-loop failure used to come back as "
                + "'cleared', which a model reads as 'these slots are empty'", inv.containsKey("cleared"));
    }

    /** And the normal path is untouched: a slot that really was emptied is still reported. */
    @Test
    public void aGenuinelyEmptiedSlotStillReportsCleared() {
        WorldView before = inventoryOnly(1L, kit(0,
                new InventoryView.Slot(0, "diamond_pickaxe", 1, 12, 1561),
                new InventoryView.Slot(2, "bread", 1, 0, null)));
        WorldView after = inventoryOnly(2L, kit(0,
                new InventoryView.Slot(0, "diamond_pickaxe", 1, 12, 1561)));

        Map<String, Object> inv = asMap(WorldViewDiff.diff(before, after).get("inventory"));
        assertEquals("the eaten bread must still be visible, or a fix that suppresses 'cleared' "
                + "outright would hide every real consumption", List.of(2), inv.get("cleared"));
    }

    // ===== the smallest client the capture accepts =====

    private boolean huskInstalled;

    /**
     * A {@code Minecraft} whose player and world exist but whose fields were never initialised --
     * {@code EntityPlayerSP.inventory} is null, so the inventory read fails exactly the way a getter
     * on an unreadable stack does. Unsafe allocation is what keeps the field initialisers from
     * running; a constructed player would have a real (and readable) {@code InventoryPlayer}.
     */
    private void installBlankClient() throws Exception {
        Object mc = blank(Minecraft.class);
        set(mc, "thePlayer", blank(EntityPlayerSP.class));
        set(mc, "theWorld", blank(WorldClient.class));
        setStatic(Minecraft.class, "theMinecraft", mc);
        huskInstalled = true;
    }

    /** The singleton is process-wide; leaving it installed would leak into every later test. */
    @After
    public void restoreMinecraft() throws Exception {
        if (huskInstalled) {
            setStatic(Minecraft.class, "theMinecraft", null);
            huskInstalled = false;
        }
    }

    private static Object blank(Class<?> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), type);
    }

    /** Walks up the hierarchy: the husk fields are vanilla's and may be inherited or final. */
    private static void set(Object target, String field, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException inherited) {
                // declared further up; keep walking
            }
        }
        throw new NoSuchFieldException(field + " on " + target.getClass().getName());
    }

    private static void setStatic(Class<?> owner, String field, Object value) throws Exception {
        Field f = owner.getDeclaredField(field);
        f.setAccessible(true);
        f.set(null, value);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
