package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemArmor;
import net.minecraft.item.ItemStack;
import net.minecraft.util.DamageSource;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Teeth for {@link ArmourSlots}: that every number it publishes is one vanilla itself produces,
 * computed from the live 1.8.9 classes rather than from a copy of them.
 *
 * <p>The load-bearing half is {@link #everyPieceAgreesWithVanillasOwnSum}, not the arithmetic
 * checks. {@link ArmourSlots#total} is three lines of addition and would stay green if vanilla
 * changed which field it sums; the sum is therefore compared against
 * {@code InventoryPlayer.getTotalArmorValue()} over every combination of the real armour set, so
 * a change in vanilla's own rule shows up as a disagreement instead of being mirrored here.
 * Everything else pins a property that must hold for the answer to be usable, not a literal.
 */
public final class ArmourValueIsVanillasTest {

    private static net.minecraft.item.ItemArmor[] set;

    @BeforeClass
    public static void bootstrap() {
        net.minecraft.init.Bootstrap.register();
        set = new net.minecraft.item.ItemArmor[] {
            net.minecraft.init.Items.leather_helmet,
            net.minecraft.init.Items.leather_chestplate,
            net.minecraft.init.Items.leather_leggings,
            net.minecraft.init.Items.leather_boots,
            net.minecraft.init.Items.iron_helmet,
            net.minecraft.init.Items.iron_chestplate,
            net.minecraft.init.Items.iron_leggings,
            net.minecraft.init.Items.iron_boots,
            net.minecraft.init.Items.diamond_helmet,
            net.minecraft.init.Items.diamond_chestplate,
            net.minecraft.init.Items.diamond_leggings,
            net.minecraft.init.Items.diamond_boots,
            net.minecraft.init.Items.golden_helmet,
            net.minecraft.init.Items.golden_chestplate,
            net.minecraft.init.Items.golden_leggings,
            net.minecraft.init.Items.golden_boots,
        };
    }

    private static InventoryPlayer wearing(ItemArmor... pieces) {
        InventoryPlayer inv = new InventoryPlayer(null);
        for (int i = 0; i < ArmourSlots.COUNT; i++) {
            inv.armorInventory[i] = pieces[i] == null ? null : new ItemStack(pieces[i]);
        }
        return inv;
    }

    /**
     * The transcription must sum the same numbers vanilla sums, for every way the four cells can
     * be filled from the real armour set.
     *
     * <p>Derived, not pinned: the expected value is whatever {@code getTotalArmorValue} returns
     * for the same inventory, so this tracks vanilla rather than restating it. The sweep is the
     * full cross product of the real armour set with an empty cell added, which is what catches a
     * transcription that happens to agree on a full set -- a per-cell mismatch only shows up once
     * two cells carry different values.
     */
    @Test
    public void everyPieceAgreesWithVanillasOwnSum() {
        java.util.List<ItemArmor> options = new java.util.ArrayList<>(java.util.Arrays.asList(set));
        options.add(null); // the empty cell, which contributes nothing
        int[] perCell = new int[ArmourSlots.COUNT];
        int combinations = 0;
        for (ItemArmor a : options) {
            for (ItemArmor b : options) {
                for (ItemArmor c : options) {
                    for (ItemArmor d : options) {
                        InventoryPlayer inv = wearing(a, b, c, d);
                        for (int i = 0; i < ArmourSlots.COUNT; i++) {
                            ItemStack s = inv.armorInventory[i];
                            perCell[i] = s != null && s.getItem() instanceof ItemArmor armour
                                    ? armour.damageReduceAmount : ArmourSlots.NONE;
                        }
                        assertEquals("combination " + combinations + " vanilla="
                                        + inv.getTotalArmorValue(),
                                inv.getTotalArmorValue(), ArmourSlots.total(perCell));
                        combinations++;
                    }
                }
            }
        }
        assertTrue("sweep must actually cover combinations", combinations > 1000);
    }

    /**
     * Each cell must be able to hold a piece of its own armourType, and only that.
     *
     * <p>This is what makes the sweep above meaningful and the container mapping below
     * unambiguous: the four diamond pieces have four distinct armourTypes, so a cell holding the
     * boots is distinguishable from one holding the helmet. Without this the mapping test could
     * pass on a set where every cell held the same item.
     */
    @Test
    public void eachArmourPieceHasItsOwnType() {
        java.util.Set<Integer> types = new java.util.LinkedHashSet<>();
        for (ItemArmor piece : new ItemArmor[] { net.minecraft.init.Items.diamond_helmet,
                net.minecraft.init.Items.diamond_chestplate,
                net.minecraft.init.Items.diamond_leggings,
                net.minecraft.init.Items.diamond_boots }) {
            assertTrue("armourType " + piece.armorType + " must be a real cell",
                    ArmourSlots.isArmourType(piece.armorType));
            assertTrue("the four pieces must occupy four distinct cells",
                    types.add(piece.armorType));
        }
        assertEquals(ArmourSlots.COUNT, types.size());
    }

    /**
     * An empty inventory is 0, and so is any set that omits the armour -- because the empty cells
     * contribute nothing rather than defaulting to some full-set number.
     */
    @Test
    public void anEmptyBodyHasNoArmourValue() {
        InventoryPlayer bare = new InventoryPlayer(null);
        assertEquals(ArmourSlots.NONE, ArmourSlots.total(new int[ArmourSlots.COUNT]));
        assertEquals(bare.getTotalArmorValue(), ArmourSlots.total(new int[ArmourSlots.COUNT]));
        assertEquals(ArmourSlots.NONE, ArmourSlots.total(null));
    }

    /**
     * A non-armour stack in a cell contributes nothing.
     *
     * <p>Vanilla guards on {@code instanceof ItemArmor} ({@code InventoryPlayer:713-717}), so a
     * skull in the armour block -- which vanilla explicitly permits in the helmet cell, per
     * {@code ContainerPlayer:47} -- must not be counted as points. Pinning that is what stops a
     * later change from summing {@code getMaxDamage()} or the stack size instead: a skull has a
     * durability of 0 but is a real occupant, so a transcription that counted "any stack with a
     * damage value" would report a point here and nowhere else.
     */
    @Test
    public void aNonArmourStackIsWorthNoPoints() {
        InventoryPlayer inv = wearing(net.minecraft.init.Items.diamond_helmet, null, null, null);
        int withHelmet = inv.getTotalArmorValue();
        assertTrue("the fixture must start with armour on", withHelmet > ArmourSlots.NONE);

        ItemStack skull = new ItemStack(net.minecraft.init.Items.skull);
        assertTrue("the test item must not be armour", !(skull.getItem() instanceof ItemArmor));
        inv.armorInventory[ArmourSlots.HEAD] = skull;

        // Both the transcription and vanilla must agree that a skull is worth nothing -- and the
        // surviving cells must still be counted, so this also pins that only the replaced cell
        // changed.
        int[] perCell = new int[ArmourSlots.COUNT];
        for (int i = 0; i < ArmourSlots.COUNT; i++) {
            ItemStack s = inv.armorInventory[i];
            perCell[i] = s != null && s.getItem() instanceof ItemArmor armour
                    ? armour.damageReduceAmount : ArmourSlots.NONE;
        }
        assertEquals("a skull in the helmet cell is not armour",
                inv.getTotalArmorValue(), ArmourSlots.total(perCell));
        assertEquals("removing the helmet must actually change the value",
                withHelmet - ((ItemArmor) net.minecraft.init.Items.diamond_helmet).damageReduceAmount,
                inv.getTotalArmorValue());
    }

    /**
     * The reduction is exactly {@code total / 25} of the incoming damage, checked against the
     * full diamond set's real value rather than a remembered number.
     *
     * <p>The boundary that matters is {@link ArmourSlots#unblockableDoesNotReduce}, which is where
     * a well-meaning caller loses a life; this one exists so the arithmetic itself is anchored to
     * something vanilla computed.
     */
    @Test
    public void theReductionIsTheVanillaRatio() {
        InventoryPlayer inv = wearing(net.minecraft.init.Items.diamond_helmet,
                net.minecraft.init.Items.diamond_chestplate,
                net.minecraft.init.Items.diamond_leggings,
                net.minecraft.init.Items.diamond_boots);
        int total = inv.getTotalArmorValue();
        double damage = 10.0D;
        assertEquals(damage * (ArmourSlots.ABSORB_DENOMINATOR - total)
                        / (double) ArmourSlots.ABSORB_DENOMINATOR,
                ArmourSlots.damageAfter(damage, total, false), 1.0E-9D);
        // absorbed and damageAfter must be complements, or a caller wording "armour saves you N"
        // inverts the number it just computed.
        assertEquals(damage - ArmourSlots.damageAfter(damage, total, false),
                ArmourSlots.absorbed(damage, total, false), 1.0E-9D);
        assertEquals("a larger hit must cost more after the same reduction",
                ArmourSlots.damageAfter(20.0D, total, false) > ArmourSlots.damageAfter(damage, total, false),
                true);
    }

    /**
     * Armour does nothing against an unblockable source -- and the set of those is a property of
     * each {@link DamageSource}, not of its name.
     *
     * <p>This is the test that keeps a caller from reading {@code self.armor == 20} and concluding
     * the player is safe. {@code DamageSource.fall} is the one that actually kills people while
     * wearing full diamond, and {@code DamageSource.lava} is the counterexample to the intuition
     * that "fire damage ignores armour": lava contact is blockable, onFire burn is not.
     */
    @Test
    public void unblockableDoesNotReduce() {
        InventoryPlayer inv = wearing(net.minecraft.init.Items.diamond_helmet,
                net.minecraft.init.Items.diamond_chestplate,
                net.minecraft.init.Items.diamond_leggings,
                net.minecraft.init.Items.diamond_boots);
        int total = inv.getTotalArmorValue();
        assertTrue("the fixture must actually be wearing armour", total > ArmourSlots.NONE);
        double damage = 10.0D;

        int blockable = 0;
        int unblockable = 0;
        for (DamageSource source : realStaticSources()) {
            String label = source.getDamageType();
            if (source.isUnblockable()) {
                unblockable++;
                assertUnchangedByArmour(damage, source, label);
            } else {
                blockable++;
                // A blockable source must be genuinely reduced, or the unblockable half above
                // would pass for the wrong reason.
                assertNotEquals(label + " must be reduced by armour", damage,
                        ArmourSlots.damageAfter(damage, total, false), 1.0E-9D);
            }
        }
        assertTrue("the fixture must contain both kinds of source, or this test is vacuous",
                blockable > 0 && unblockable > 0);

        // The two facts a caller actually gets wrong, named rather than left implicit.
        assertTrue("fall bypasses armour even at full diamond",
                DamageSource.fall.isUnblockable());
        assertTrue("lava contact is NOT unblockable, so armour does reduce it",
                !DamageSource.lava.isUnblockable());
        assertTrue("the burn that follows lava IS unblockable",
                DamageSource.onFire.isUnblockable());
    }

    /**
     * Every {@code DamageSource} vanilla declares as a constant, found by reflection.
     *
     * <p>Enumerated rather than hand-listed so the test cannot go stale: a source added or
     * reclassified upstream is picked up here, and a hand-kept list would keep asserting the old
     * classification forever. Only static {@code DamageSource} fields count -- the factory
     * methods ({@code causeMobDamage} and friends) all return unblockable variants and would
     * double-count the same behaviour.
     */
    private static java.util.List<DamageSource> realStaticSources() {
        java.util.List<DamageSource> out = new java.util.ArrayList<>();
        for (java.lang.reflect.Field field : DamageSource.class.getDeclaredFields()) {
            if (field.getType() != DamageSource.class || !java.lang.reflect.Modifier.isStatic(
                    field.getModifiers())) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(null);
                if (value instanceof DamageSource source && source != null) {
                    out.add(source);
                }
            } catch (Throwable ignored) {
                // A field this JVM will not open is simply not part of the sweep.
            }
        }
        assertTrue("must find vanilla's own source constants", out.size() >= 10);
        return out;
    }

    private static void assertUnchangedByArmour(double damage, DamageSource source, String label) {
        if (!source.isUnblockable()) {
            return;
        }
        assertEquals(label + " must bypass armour", damage,
                ArmourSlots.damageAfter(damage, ArmourSlots.ABSORB_DENOMINATOR, true), 1.0E-9D);
        assertEquals(label + " must absorb nothing", 0.0D,
                ArmourSlots.absorbed(damage, ArmourSlots.ABSORB_DENOMINATOR, true), 1.0E-9D);
    }

    /**
     * The container-slot mapping is the inverse of itself and lands on the inventory index
     * vanilla's own arithmetic produces.
     *
     * <p>Expressed as a property rather than a table: for every armour type, the container slot
     * {@link ArmourSlots#containerSlotFor} returns must (a) reverse back to that type through
     * {@link ArmourSlots#armorTypeForContainerSlot} and (b) sit at an index whose
     * {@code getStackInSlot} really returns that piece. The second half is the load-bearing one:
     * it is checked against the real inventory, so a transcription that inverted the wrong
     * direction -- the obvious mistake, since the drawn order looks like head-first -- fails here
     * instead of quietly equipping the wrong limb.
     */
    @Test
    public void theContainerMappingAgreesWithVanillasOwnIndexing() {
        for (int armorType = 0; armorType < ArmourSlots.COUNT; armorType++) {
            // A distinct item per cell, so a mapping error cannot cancel itself out.
            InventoryPlayer inv = wearing(net.minecraft.init.Items.leather_helmet,
                    net.minecraft.init.Items.leather_chestplate,
                    net.minecraft.init.Items.leather_leggings,
                    net.minecraft.init.Items.leather_boots);

            int containerSlot = ArmourSlots.containerSlotFor(armorType);
            assertEquals("container slot must reverse to the same armourType",
                    armorType, ArmourSlots.armorTypeForContainerSlot(containerSlot));

            // ContainerPlayer builds the slot for k at getSizeInventory()-1-k, so the container
            // slot's inventory index is what vanilla's Slot would read.
            int expectedInventoryIndex = inv.getSizeInventory() - 1 - (containerSlot - ArmourSlots.FIRST_CONTAINER_ARMOUR_SLOT);
            assertEquals("container slot " + containerSlot + " must address inventory index "
                            + expectedInventoryIndex,
                    expectedInventoryIndex, ArmourSlots.inventoryIndexFor(
                            ArmourSlots.armorTypeForContainerSlot(containerSlot)));

            // And the real read: the slot's inventory index must resolve to that armour cell.
            ItemStack atSlot = inv.getStackInSlot(expectedInventoryIndex);
            assertNotNull("cell must be occupied", atSlot);
            assertEquals("container slot " + containerSlot + " must show armourType " + armorType,
                    armorType, ((ItemArmor) atSlot.getItem()).armorType);
        }
    }

    /**
     * A slot outside the armour block is reported as "not armour" rather than as piece zero.
     *
     * <p>The caller is normally walking a whole container and wants to know which entries are
     * armour. Returning the helmet for the crafting output -- or throwing on it -- would make
     * every non-armour slot indistinguishable from the first armour slot.
     */
    @Test
    public void nonArmourContainerSlotsAreNotArmour() {
        for (int slot = 0; slot < ArmourSlots.FIRST_CONTAINER_ARMOUR_SLOT; slot++) {
            assertEquals("slot " + slot, -1, ArmourSlots.armorTypeForContainerSlot(slot));
        }
        for (int slot = ArmourSlots.FIRST_CONTAINER_ARMOUR_SLOT + ArmourSlots.COUNT;
                slot < ArmourSlots.FIRST_CONTAINER_ARMOUR_SLOT + ArmourSlots.COUNT + 4; slot++) {
            assertEquals("slot " + slot, -1, ArmourSlots.armorTypeForContainerSlot(slot));
        }
        assertEquals(ArmourSlots.HEAD,
                ArmourSlots.armorTypeForContainerSlot(
                        ArmourSlots.containerSlotFor(ArmourSlots.HEAD)));
        assertEquals(ArmourSlots.BOOTS,
                ArmourSlots.armorTypeForContainerSlot(
                        ArmourSlots.containerSlotFor(ArmourSlots.BOOTS)));
    }

    /** Every piece name is distinct and the list is in armourType order. */
    @Test
    public void everyPieceHasItsOwnName() {
        assertEquals(ArmourSlots.COUNT, ArmourSlots.pieceNames().size());
        assertEquals(ArmourSlots.pieceNames().size(), java.util.Set.copyOf(ArmourSlots.pieceNames()).size());
        assertEquals(ArmourSlots.pieceName(ArmourSlots.HEAD), ArmourSlots.pieceNames().get(ArmourSlots.HEAD));
        assertEquals(ArmourSlots.pieceName(ArmourSlots.BOOTS),
                ArmourSlots.pieceNames().get(ArmourSlots.BOOTS));
    }

    /**
     * An out-of-range armour type is rejected rather than silently mapped to a limb.
     *
     * <p>{@code ContainerPlayer:47} rejects a mismatched armour type outright, so a caller that
     * computed a slot from a bad type must be stopped here too rather than clicking a real slot
     * with the wrong item.
     */
    @Test(expected = IllegalArgumentException.class)
    public void anOutOfRangeArmourTypeIsRejected() {
        ArmourSlots.containerSlotFor(ArmourSlots.COUNT);
    }

    @Test
    public void aNegativeArmourTypeIsRejected() {
        assertTrue(!ArmourSlots.isArmourType(-1));
        assertTrue(!ArmourSlots.isArmourType(ArmourSlots.COUNT));
        for (int i = 0; i < ArmourSlots.COUNT; i++) {
            assertTrue(ArmourSlots.isArmourType(i));
        }
    }

    /** The empty-slot textures vanilla uses are in the same order as the armourTypes. */
    @Test
    public void vanillaEmptySlotTexturesRunHeadFirst() {
        String[] names = ItemArmor.EMPTY_SLOT_NAMES;
        assertEquals(ArmourSlots.COUNT, names.length);
        assertTrue("index 0 must be the helmet slot, not the boots",
                names[ArmourSlots.HEAD].endsWith("_helmet"));
        assertTrue(names[ArmourSlots.BOOTS].endsWith("_boots"));
    }
}
