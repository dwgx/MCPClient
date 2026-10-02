package net.marcloud.mcp.core.drivers.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

/**
 * One {@link ItemStack} read the way an agent needs to choose: the REGISTRY name
 * ({@code minecraft:diamond_sword}, which is what every other tool in this
 * codebase compares against), the human display name, the count, the metadata
 * ("damage" in the GUI tooltip) and a capped summary of the stack's NBT.
 *
 * <p><b>Why not just the numeric id.</b> {@code Item.getIdFromItem} is a runtime
 * save-format number that depends on registration order; {@link Item#itemRegistry}
 * is the stable identity. {@code SlotView} in {@code ToolRegistry} already went
 * this way for its confirmation compare, and a slot read that only offered the
 * number left the agent unable to match a slot against what the rest of the tool
 * surface says an item is.
 *
 * <p><b>Fail-loud-but-degrade.</b> {@link #of} never throws: an unregistered item,
 * a null tag or a nameless stack yields {@link #EMPTY} plus an empty field rather
 * than an exception out of the middle of a snapshot, and {@link #describe} is the
 * single place that records WHY a field is missing.
 *
 * @param registryName  {@code minecraft:foo}, or "" when the item is not in the registry
 * @param displayName   the localised name the GUI would draw, "" when unreadable
 * @param count         stack size, 0 when empty
 * @param damage        the metadata value (dye colour, potion variant, tool durability)
 * @param maxStackSize  the item's stack limit, 0 when unreadable
 * @param enchantments  display-name-per-line of the stored enchantments, never null
 * @param nbtKeys       the stack tag's top-level keys, sorted, never null
 * @param nbt           a capped {@code NBTTagCompound.toString()}, "" when the stack has no tag
 */
public record GuiStack(
        String registryName,
        String displayName,
        int count,
        int damage,
        int maxStackSize,
        List<String> enchantments,
        List<String> nbtKeys,
        String nbt) {

    /** Hard cap on the serialised NBT blob; a full tag dump of a shulker box is kilobytes. */
    public static final int NBT_LIMIT = 240;

    /** How deep the enchantment list is walked before it is cut off. */
    private static final int ENCHANT_LIMIT = 16;

    /** The absent stack. Not null, so a caller never has to null-check before serialising. */
    public static final GuiStack EMPTY = new GuiStack("", "", 0, 0, 0, List.of(), List.of(), "");

    public GuiStack {
        registryName = registryName == null ? "" : registryName;
        displayName = displayName == null ? "" : displayName;
        enchantments = enchantments == null ? List.of() : List.copyOf(enchantments);
        nbtKeys = nbtKeys == null ? List.of() : List.copyOf(nbtKeys);
        nbt = nbt == null ? "" : nbt;
    }

    /** True when this describes no item at all (a null stack, or an item the registry dropped). */
    public boolean empty() {
        return registryName.isEmpty() && count <= 0;
    }

    /**
     * Read a live {@link ItemStack}. Null in, {@link #EMPTY} out. Every sub-read is
     * isolated: a stack whose item is not registered still yields its count and
     * metadata, because a nameless stack with the right numbers is more useful than
     * nothing and {@code unreadable} will say why the name is blank.
     *
     * @param unreadable sink for "why is this field blank" notes; may be null
     */
    public static GuiStack of(ItemStack stack, List<String> unreadable) {
        if (stack == null) {
            return EMPTY;
        }
        int count = stack.stackSize;
        int damage = stack.getItemDamage();

        String name = "";
        int max = 0;
        try {
            Item item = stack.getItem();
            if (item != null) {
                // Item.itemRegistry, not Block.blockRegistry: a slot holds items, and this is
                // the same identity SlotView (ToolRegistry) already compares against.
                Object reg = Item.itemRegistry.getNameForObject(item);
                name = reg == null ? "" : reg.toString();
                max = item.getItemStackLimit();
            } else if (unreadable != null) {
                unreadable.add("itemStack(null item):not in the registry");
            }
        } catch (Throwable t) {
            note(unreadable, "itemStack.registry:" + t.getClass().getSimpleName());
        }

        String display = "";
        try {
            String d = stack.getDisplayName();
            display = d == null ? "" : d;
        } catch (Throwable t) {
            note(unreadable, "itemStack.getDisplayName:" + t.getClass().getSimpleName());
        }

        List<String> ench = List.of();
        List<String> keys = List.of();
        String nbt = "";
        try {
            NBTTagCompound tag = stack.getTagCompound();
            if (tag != null) {
                keys = tag.getKeySet().stream().map(String::valueOf).sorted().toList();
                ench = readEnchantments(tag, unreadable);
                nbt = cap(tag.toString());
            }
        } catch (Throwable t) {
            note(unreadable, "itemStack.nbt:" + t.getClass().getSimpleName());
        }

        return new GuiStack(name, display, count, damage, max, ench, keys, nbt);
    }

    /** Null-tolerant convenience for call sites that do not keep an unreadable sink. */
    public static GuiStack of(ItemStack stack) {
        return of(stack, null);
    }

    /**
     * The stack's stored enchantments as "{level} name" lines, read from the vanilla
     * "ench" list rather than by guessing tag shapes. A malformed entry costs that
     * entry, not the whole read.
     */
    private static List<String> readEnchantments(NBTTagCompound tag, List<String> unreadable) {
        try {
            net.minecraft.nbt.NBTTagList raw = tag.getTagList("ench", 10);
            if (raw == null || raw.tagCount() == 0) {
                return List.of();
            }
            java.util.ArrayList<String> out = new java.util.ArrayList<>();
            for (int i = 0; i < raw.tagCount() && out.size() < ENCHANT_LIMIT; i++) {
                if (!(raw.get(i) instanceof NBTTagCompound e)) {
                    continue;
                }
                int id = e.getShort("id");
                int lvl = e.getShort("lvl");
                net.minecraft.enchantment.Enchantment ench =
                        net.minecraft.enchantment.Enchantment.getEnchantmentById(id);
                // getTranslatedName is the line the GUI itself draws ("Sharpness V").
                out.add(ench == null ? "enchantment#" + id : ench.getTranslatedName(lvl));
            }
            return out;
        } catch (Throwable t) {
            note(unreadable, "itemStack.enchantments:" + t.getClass().getSimpleName());
            return List.of();
        }
    }

    private static String cap(String s) {
        return s.length() <= NBT_LIMIT ? s : s.substring(0, NBT_LIMIT) + "...(" + s.length() + " chars)";
    }

    private static void note(List<String> sink, String what) {
        if (sink != null) {
            sink.add(what);
        }
    }

    /** Ordered map view for JSON emission; {@code toMap} is null for an absent stack. */
    public Map<String, Object> toMap() {
        if (empty()) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("item", registryName);
        m.put("name", displayName);
        m.put("count", count);
        m.put("damage", damage);
        m.put("maxStack", maxStackSize);
        if (!enchantments.isEmpty()) {
            m.put("enchantments", enchantments);
        }
        if (!nbtKeys.isEmpty()) {
            m.put("nbtKeys", nbtKeys);
        }
        if (!nbt.isEmpty()) {
            m.put("nbt", nbt);
        }
        return m;
    }

    /** Fold every field into a hash, so two reads can be compared for EQUALITY of contents. */
    @Override
    public boolean equals(Object o) {
        return o instanceof GuiStack g && g.registryName.equals(registryName)
                && g.displayName.equals(displayName) && g.count == count && g.damage == damage
                && g.maxStackSize == maxStackSize && g.enchantments.equals(enchantments)
                && g.nbt.equals(nbt);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(registryName, displayName, count, damage, maxStackSize,
                enchantments, nbt);
    }

    @Override
    public String toString() {
        return empty() ? "-" : registryName + " x" + count + (damage != 0 ? "@" + damage : "");
    }
}
