package net.marcloud.mcp.core.drivers.world;

import java.util.List;

/**
 * PHASE W.5 — per-slot inventory using the item REGISTRY name (not displayName),
 * addressable by slot index. {@code maxDamage} null for non-damageable items.
 *
 * <p><b>The two lists are different index spaces and conflating them is a silent misfire.</b>
 * {@code slots} is {@code InventoryPlayer.mainInventory} -- hotbar 0-8, then rows. {@code armour}
 * is {@code InventoryPlayer.armorInventory}, the four pieces, whose vanilla index is 36..39 and
 * whose {@code containerSlot} is the <b>reversed</b> window-0 block {@link ArmourSlots}
 * transcribes. They are separate fields rather than one list with a flag because every consumer
 * of the main inventory -- crafting, transfer, the diff -- assumes it is 36 wide, and quietly
 * appending four more would make "the player has 40 items" true in a way no caller means.
 *
 * @param armour the four worn pieces in {@code armourType} order (head, chest, legs, boots), or
 *               {@code null} when the read failed. {@code null} means unreadable, NOT empty:
 *               an empty armour block is four slots with a null {@code item}, which is a real
 *               state and the common one.
 */
public record InventoryView(int selectedSlot, List<Slot> slots, List<Slot> armour) {

    /** The pre-armour shape: no armour block was captured. */
    public InventoryView(int selectedSlot, List<Slot> slots) {
        this(selectedSlot, slots, null);
    }

    /**
     * The window-0 container slot that equips {@code armorType}, or {@code -1} when there is no
     * such piece.
     *
     * <p>Deliberately a method on the view and not on {@link Slot}: a {@code Slot}'s {@code index}
     * means "main-inventory row" in {@code slots} and "36 + armourType" in {@code armour}, so
     * deriving a container slot from it inside {@code Slot} would read main-inventory index 5 --
     * an ordinary chest-row cell -- as the helmet. The two lists are distinguished here, where
     * that distinction is actually known.
     *
     * <p>The result is {@link ArmourSlots#containerSlotFor}'s, which is not {@code 5 + armorType}:
     * vanilla builds the armour block in descending inventory order. A caller reading the drawn
     * order instead puts the helmet on the feet.
     */
    public int containerSlotFor(int armorType) {
        if (!ArmourSlots.isArmourType(armorType)) {
            return -1;
        }
        return ArmourSlots.containerSlotFor(armorType);
    }

    /** The worn piece at {@code armorType}, or {@code null} when that cell is empty or unread. */
    public Slot armourAt(int armorType) {
        if (armour == null || !ArmourSlots.isArmourType(armorType)) {
            return null;
        }
        return armorType < armour.size() ? armour.get(armorType) : null;
    }

    public record Slot(int index, String item, int count, int damage, Integer maxDamage) {
    }
}
