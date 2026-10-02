package net.marcloud.mcp.core.drivers.world;

import java.util.List;

/**
 * Vanilla's four armour cells and the arithmetic that turns them into a number.
 *
 * <p><b>Why the mapping is the hard part.</b> There are THREE index spaces for the same four
 * pieces and they do not agree, which is exactly the silent misfire
 * {@code transfer_item}'s own doc warns about ({@code ToolRegistry}: "in window 0, container
 * slot 0 is the crafting OUTPUT and hotbar 0 is container slot 36"). An agent that reasons
 * "armour slot 0 is the helmet, so click container slot 5" clicks the right-looking box and puts
 * the helmet where the boots go:
 *
 * <ul>
 *   <li><b>{@code armorInventory[i]}</b> -- {@code InventoryPlayer.armorInventory:27}, the four
 *       cells themselves. Index is {@code ItemArmor.armorType}: 0 helmet, 1 chestplate, 2
 *       leggings, 3 boots.</li>
 *   <li><b>{@code InventoryPlayer} index 36+i</b> -- what {@code getStackInSlot:639-650} /
 *       {@code setInventorySlotContents:539-550} accept, because both subtract
 *       {@code mainInventory.length} (36) once the index is past the main array. So 36 is
 *       {@code armorInventory[0]} and 39 is {@code armorInventory[3]}.</li>
 *   <li><b>container slot 5+i</b> -- what a C0E {@code windowClick} addresses in window 0.
 *       {@code ContainerPlayer:32-52} adds the four armour slots with
 *       {@code getSizeInventory() - 1 - k}, i.e. 39, 38, 37, 36 for k=0..3, in that
 *       <b>descending</b> order. So container slot 5 is InventoryPlayer index 39, which is
 *       {@code armorInventory[3]} -- the BOOTS -- while the slot's own empty texture
 *       ({@code ItemArmor.EMPTY_SLOT_NAMES}, indexed by the same k) draws the helmet.</li>
 * </ul>
 *
 * <p>That inversion is not a bug in this project and not a typo in vanilla; it is the shape of
 * vanilla's own code, and it is reproduced here rather than tidied up. A caller that "fixes" it
 * by assuming index order gets a screen that looks right and a body wearing the wrong piece.
 * {@link #containerSlotFor} is the one place that inversion is allowed to appear.
 *
 * <p><b>What is checkable and what is not.</b> {@code armorItemInSlot:702} and
 * {@code getTotalArmorValue:710-723} are plain client-side array reads -- the client holds the
 * real stacks, so "am I wearing a helmet" and "my armour value went up" are both facts the
 * client can answer by itself. {@link #damageAfter} is vanilla's own reduction formula
 * ({@code EntityLivingBase.applyArmorCalculations:1213-1224}), which is fixed and published, so
 * the cost of a hit is answerable too. The hit itself is not: {@code applyArmorCalculations}
 * runs inside {@code attackEntityFrom}, which is guarded by {@code !worldObj.isRemote}. So this
 * class answers "what will this hit cost me wearing this", never "this hit landed".
 */
public final class ArmourSlots {

    /** {@code ItemArmor.armorType} for the helmet. Also {@code armorInventory[0]}. */
    public static final int HEAD = 0;

    /** {@code armorType} for the chestplate. */
    public static final int CHEST = 1;

    /** {@code armorType} for the leggings. */
    public static final int LEGS = 2;

    /** {@code armorType} for the boots. */
    public static final int BOOTS = 3;

    /** {@code armorInventory.length}: four pieces, no more. */
    public static final int COUNT = 4;

    /**
     * {@code InventoryPlayer.mainInventory.length} -- the point where a vanilla inventory index
     * stops meaning "hotbar slot" and starts meaning "armour cell".
     */
    public static final int MAIN_SLOTS = 36;

    /**
     * First armour slot of window 0's container. {@code ContainerPlayer} adds the crafting
     * output, then the 2x2 matrix, then these four, so 5 is the first of the armour block.
     */
    public static final int FIRST_CONTAINER_ARMOUR_SLOT = 5;

    /** No armour, and the only negative this returns. */
    public static final int NONE = 0;

    /**
     * Vanilla's armour denominator ({@code EntityLivingBase.applyArmorCalculations:1219-1222}),
     * not a tuning constant of this project: it is the literal {@code 25} in
     * {@code int i = 25 - this.getTotalArmorValue();}.
     */
    public static final int ABSORB_DENOMINATOR = 25;

    /**
     * A damage source vanilla refuses to reduce.
     *
     * <p>{@code applyArmorCalculations} opens with {@code if (!source.isUnblockable())}, so an
     * unblockable source skips the whole reduction. That includes {@code fall}, {@code onFire}
     * and {@code lava} -- all {@code isUnblockable()} -- so wearing full diamond protects against
     * a zombie and not against the three things most likely to be killing you. Callers that pass
     * {@code unblockable = false} for {@code DamageSource.fall} produce a confidently wrong
     * number, which is why this is a parameter and not an assumption.
     */
    private ArmourSlots() {
    }

    /** Whether {@code armorType} names one of the four armour cells. */
    public static boolean isArmourType(int armorType) {
        return armorType >= HEAD && armorType < COUNT;
    }

    /**
     * The {@code InventoryPlayer} index a piece occupies, i.e. the number a
     * {@code getStackInSlot}/{@code setInventorySlotContents} call takes.
     *
     * <p>{@code 36 + armorType}, from the two {@code index -= mainInventory.length} lines at
     * {@code InventoryPlayer:543-547} and {@code :641-647}.
     */
    public static int inventoryIndexFor(int armorType) {
        requireArmourType(armorType);
        return MAIN_SLOTS + armorType;
    }

    /**
     * The window-0 container slot that addresses a piece.
     *
     * <p><b>This is where the inversion lives.</b> {@code ContainerPlayer} creates the armour
     * slot for {@code k} at {@code getSizeInventory() - 1 - k}, and those four land at container
     * indices 5, 6, 7, 8 in that order -- so container slot {@code 5+k} is inventory index
     * {@code 39-k}, which is {@code armorInventory[3-k]}. The helmet ({@code armorType} 0) is
     * therefore at container slot <b>8</b>, and the boots at container slot <b>5</b>, even though
     * slot 5 is the one that draws the helmet's empty texture.
     *
     * <p>An agent clicking "container slot 5 to equip a helmet" is putting it on the feet, and
     * the only thing that reveals it is {@code getTotalArmorValue} not moving the way the caller
     * expected. Deriving this from vanilla's own expression rather than from the screen's visual
     * order is the entire reason this method exists.
     */
    public static int containerSlotFor(int armorType) {
        requireArmourType(armorType);
        return FIRST_CONTAINER_ARMOUR_SLOT + (COUNT - 1 - armorType);
    }

    /**
     * The {@code armorType} a window-0 container slot holds, or {@code -1} if that slot is not
     * one of the four armour slots.
     *
     * <p>The inverse of {@link #containerSlotFor}, and deliberately returns {@code -1} rather
     * than throwing for a non-armour slot: the caller is usually walking a whole container and
     * wants to know which entries are armour, not to be interrupted by the other 40.
     */
    public static int armorTypeForContainerSlot(int containerSlot) {
        int offset = containerSlot - FIRST_CONTAINER_ARMOUR_SLOT;
        if (offset < 0 || offset >= COUNT) {
            return -1;
        }
        return COUNT - 1 - offset;
    }

    /**
     * {@code ItemArmor.damageReduceAmount} summed over the worn pieces, which is what vanilla's
     * {@code getTotalArmorValue:710-723} returns.
     *
     * <p>One value per armour cell, in {@code armorType} order; {@link #NONE} for an empty cell.
     * The sum is a caller convenience -- the same sum vanilla performs -- and it is NOT the
     * damage reduction, which is {@link #damageAfter}'s job and is not this number scaled.
     *
     * <p>Vanilla adds unconditionally, so a nonsense input summing above
     * {@link #ABSORB_DENOMINATOR} would make {@link #damageAfter} return more damage than went
     * in. Vanilla's own armour set tops out at 20 so that cannot happen in game, but the caller
     * is the one supplying the numbers and is told the answer honestly either way.
     *
     * @param damageReduceAmountPerCell {@code ItemArmor.damageReduceAmount} for
     *                                  {@code armorInventory[0..3]}, in that order
     */
    public static int total(int[] damageReduceAmountPerCell) {
        if (damageReduceAmountPerCell == null || damageReduceAmountPerCell.length != COUNT) {
            return NONE;
        }
        int sum = 0;
        for (int points : damageReduceAmountPerCell) {
            sum += Math.max(NONE, points);
        }
        return sum;
    }

    /**
     * Half-hearts of damage a hit costs after armour, in vanilla's own arithmetic.
     *
     * <p>{@code EntityLivingBase.applyArmorCalculations:1213-1224} verbatim:
     * {@code int i = 25 - this.getTotalArmorValue(); float f = damage * (float)i;
     * {@code damage = f / 25.0F;} -- so no table is consulted at all.
     *
     * <p>The reduction is therefore exactly {@code total / 25}, i.e. the familiar "4% per armour
     * point" rule is a restatement of this line rather than a rival to it -- worth naming because
     * a caller who applies 4% <em>twice</em> (once here, once from memory) halves the wrong side.
     * A value above {@link #ABSORB_DENOMINATOR} would make the returned figure exceed the
     * incoming one; vanilla's own armour tops out at 20 so it cannot happen in game, but the
     * caller supplies the number and is answered honestly either way.
     *
     * <p>An {@code unblockable} source returns {@code damage} untouched, which is the whole
     * content of vanilla's leading {@code if (!source.isUnblockable())}. Which sources those
     * are is a property of each constant, not a guess from its name: {@code fall},
     * {@code onFire}, {@code drown}, {@code inWall}, {@code starve}, {@code outOfWorld},
     * {@code generic}, {@code magic} and {@code wither} all carry
     * {@code setDamageBypassesArmor()}. Two that sound like they should be in that list are
     * not: {@code lava} and {@code inFire} are {@code setFireDamage()} only, so armour DOES
     * reduce them, and {@code cactus} and {@code anvil} are plain sources. Read
     * {@code DamageSource.isUnblockable} rather than inferring it -- a player on 20 armour still
     * takes full damage from a fall, which is the one that most often kills them.
     *
     * <p>Half-hearts, matching {@link FallDamage}'s unit, because the caller already has a
     * half-heart health figure ({@code SelfView.health} is a half-heart count) and mixing the two
     * is a factor-of-two the model cannot see.
     *
     * @param damage           incoming damage in half-hearts
     * @param totalArmourValue the sum {@link #total} computes
     * @param unblockable      whether the damage source is one vanilla refuses to reduce
     */
    public static double damageAfter(double damage, int totalArmourValue, boolean unblockable) {
        if (unblockable) {
            return damage;
        }
        int divisor = ABSORB_DENOMINATOR - totalArmourValue;
        return damage * (double) divisor / (double) ABSORB_DENOMINATOR;
    }

    /**
     * Half-hearts {@link #damageAfter} removes.
     *
     * <p>Named for what it is: the reduction, so a caller can say "this hit costs 4 instead of
     * 10" without doing the subtraction itself and getting it backwards.
     */
    public static double absorbed(double damage, int totalArmourValue, boolean unblockable) {
        return damage - damageAfter(damage, totalArmourValue, unblockable);
    }

    /** Human-facing piece name, for the inventory projection and for messages. */
    public static String pieceName(int armorType) {
        return switch (armorType) {
            case HEAD -> "helmet";
            case CHEST -> "chestplate";
            case LEGS -> "leggings";
            case BOOTS -> "boots";
            default -> "unknown";
        };
    }

    /**
     * The four piece names in {@code armorType} order, which is also {@code armorInventory}
     * order and also the order {@code ItemArmor.EMPTY_SLOT_NAMES} uses.
     */
    public static List<String> pieceNames() {
        return List.of(pieceName(HEAD), pieceName(CHEST), pieceName(LEGS), pieceName(BOOTS));
    }

    private static void requireArmourType(int armorType) {
        if (!isArmourType(armorType)) {
            throw new IllegalArgumentException(
                    "armorType must be 0..3 (ItemArmor.armorType), got " + armorType);
        }
    }
}
