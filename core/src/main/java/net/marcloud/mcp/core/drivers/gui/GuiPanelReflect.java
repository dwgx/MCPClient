package net.marcloud.mcp.core.drivers.gui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;

/**
 * Reads the NON-interactive state of the open panel — the half {@link GuiReflect}
 * (which answers "what can I click") does not: a chest's real title, a villager's
 * trades and their real costs, an anvil's result, an enchantment table's three
 * offers and lapis price, a furnace's three slots and progress, a brewing stand's
 * bottles.
 *
 * <p><b>Generic first, overlays second.</b> Every {@link Container} is first asked
 * the one question that works for all of them: which {@link IInventory}s does it
 * hold, what are their real display names, how big are they and what is in them.
 * That block alone already answers "a chest's title and contents", "a furnace's
 * three slots", "a brewing stand's bottles" and "a dispenser's items" for a panel
 * nobody wrote a case for. The per-kind overlays then add only what is genuinely
 * NOT an inventory: trade lists, enchantment offers, progress counters, costs.
 *
 * <p><b>Dispatch is by container class, never by screen class.</b> The semantics
 * live in the container (that is where the trades, the offers and the costs are),
 * and reading them off the container means the same state is reported whether the
 * panel is {@code GuiChest} or a server's own subclass of it.
 *
 * <p><b>Fail-loud-but-degrade, exactly like {@link GuiReflect}.</b> Every read is
 * isolated: a field that a mappings change renamed costs that one fact and appends
 * a note to {@code unreadable}, and the rest of the panel still reads. Nothing here
 * throws.
 *
 * <p><b>Threading.</b> Reads live game state; the caller must be on the game thread
 * ({@link GuiSnapshotService} marshals).
 */
public final class GuiPanelReflect {

    private GuiPanelReflect() {
    }

    /**
     * Read the panel state of {@code container} (which belongs to {@code screen}).
     *
     * @param screen     the open screen, used only for context in unreadable notes
     * @param container  the screen's container, or null when the screen has none
     * @param unreadable sink for "why is this fact missing" notes; may be null
     */
    public static GuiPanelState read(GuiScreen screen, Container container,
                                    List<String> unreadable) {
        if (container == null) {
            return new GuiPanelState("screen", "", Map.of(), orEmpty(unreadable));
        }

        Map<String, Object> facts = new LinkedHashMap<>();
        List<Map<String, Object>> inventories = scanInventories(container, unreadable);
        facts.put(GuiPanelState.GENERIC_INVENTORIES, inventories);

        String kind = kindOf(container);
        String title = null;

        // instanceof, not a class-name switch: a server's own ContainerChest or a
        // modded ContainerFurnace still IS the panel the agent is looking at, and a
        // renamed class must not downgrade it to the generic block.
        if (container instanceof net.minecraft.inventory.ContainerChest) {
            chest(container, inventories, facts, unreadable);
        } else if (container instanceof net.minecraft.inventory.ContainerMerchant) {
            title = merchant(container, facts, unreadable);
        } else if (container instanceof net.minecraft.inventory.ContainerRepair) {
            anvil(container, facts, unreadable);
        } else if (container instanceof net.minecraft.inventory.ContainerEnchantment) {
            enchantment(container, facts, unreadable);
        } else if (container instanceof net.minecraft.inventory.ContainerFurnace) {
            furnace(container, facts, unreadable);
        } else if (container instanceof net.minecraft.inventory.ContainerBrewingStand) {
            brewing(container, facts, unreadable);
        } else if (container instanceof net.minecraft.inventory.ContainerBeacon) {
            beacon(container, facts, unreadable);
        } else if (container instanceof net.minecraft.inventory.ContainerHorseInventory) {
            horse(container, facts, unreadable);
        } else if (container instanceof net.minecraft.inventory.ContainerHopper) {
            hopper(container, facts, unreadable);
        } else if (container instanceof net.minecraft.inventory.ContainerDispenser) {
            facts.put("dispenserMode",
                    intField(container, "dispenserMode", "dispenserMode", unreadable));
        } else if (container instanceof net.minecraft.inventory.ContainerWorkbench) {
            workbench(facts);
        } else {
            // Unknown container: the generic block IS the answer. Say so, so the agent
            // knows it is reading a real inventory dump rather than a gap.
            facts.put(GuiPanelState.RECOGNISED, false);
        }

        if (title == null) {
            title = titleFrom(inventories);
        }
        return new GuiPanelState(kind, title, facts, orEmpty(unreadable));
    }

    // ===== kind / title =====

    /**
     * The panel's kind, as a word an agent can key on. The vanilla names are not
     * those words ({@code ContainerRepair} is the ANVIL, {@code ContainerMerchant}
     * is the VILLAGER), so the known families are mapped explicitly and everything
     * else falls back to the class name with its prefix stripped — a stable word
     * either way, never a blank.
     */
    static String kindOf(Container container) {
        return switch (familyOf(container)) {
            case CHEST -> "chest";
            case MERCHANT -> "merchant";
            case ANVIL -> "anvil";
            case ENCHANTMENT -> "enchantment";
            case FURNACE -> "furnace";
            case BREWING -> "brewing";
            case BEACON -> "beacon";
            case HORSE -> "horse";
            case HOPPER -> "hopper";
            case DISPENSER -> "dispenser";
            case WORKBENCH -> "workbench";
            case PLAYER -> "player";
            case UNKNOWN -> {
                String s = container.getClass().getSimpleName();
                yield s.startsWith("Container") && s.length() > 9
                        ? s.substring(9).toLowerCase(java.util.Locale.ROOT) : "container";
            }
        };
    }

    /** The vanilla container family, decided by type so subclasses land in the right one. */
    enum Family {
        CHEST, MERCHANT, ANVIL, ENCHANTMENT, FURNACE, BREWING, BEACON, HORSE, HOPPER,
        DISPENSER, WORKBENCH, PLAYER, UNKNOWN
    }

    static Family familyOf(Container c) {
        if (c instanceof net.minecraft.inventory.ContainerChest) {
            return Family.CHEST;
        }
        if (c instanceof net.minecraft.inventory.ContainerMerchant) {
            return Family.MERCHANT;
        }
        if (c instanceof net.minecraft.inventory.ContainerRepair) {
            return Family.ANVIL;
        }
        if (c instanceof net.minecraft.inventory.ContainerEnchantment) {
            return Family.ENCHANTMENT;
        }
        if (c instanceof net.minecraft.inventory.ContainerFurnace) {
            return Family.FURNACE;
        }
        if (c instanceof net.minecraft.inventory.ContainerBrewingStand) {
            return Family.BREWING;
        }
        if (c instanceof net.minecraft.inventory.ContainerBeacon) {
            return Family.BEACON;
        }
        if (c instanceof net.minecraft.inventory.ContainerHorseInventory) {
            return Family.HORSE;
        }
        if (c instanceof net.minecraft.inventory.ContainerHopper) {
            return Family.HOPPER;
        }
        if (c instanceof net.minecraft.inventory.ContainerDispenser) {
            return Family.DISPENSER;
        }
        if (c instanceof net.minecraft.inventory.ContainerWorkbench) {
            return Family.WORKBENCH;
        }
        // The creative inventory's container (GuiContainerCreative.ContainerCreative) is
        // package-private, so it is deliberately NOT special-cased: it holds only
        // InventoryBasic fields, which the generic block already reads in full.
        if (c instanceof net.minecraft.inventory.ContainerPlayer) {
            return Family.PLAYER;
        }
        return Family.UNKNOWN;
    }


    // ===== kind / title =====



    /**
     * The title the panel draws: the first held inventory that is NOT the player's
     * own. Vanilla draws exactly this string ({@code GuiChest:38} draws
     * {@code lowerChestInventory.getDisplayName().getUnformattedText()}), so
     * reporting it verbatim is reporting what is on screen, not a paraphrase.
     */
    static String titleFrom(List<Map<String, Object>> inventories) {
        for (Map<String, Object> e : inventories) {
            if (!"InventoryPlayer".equals(e.get("type"))) {
                Object n = e.get("name");
                return n == null ? "" : n.toString();
            }
        }
        return "";
    }

    // ===== generic: every IInventory the container holds =====

    private static List<Map<String, Object>> scanInventories(Container container,
                                                              List<String> unreadable) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Class<?> c = container.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (!IInventory.class.isAssignableFrom(f.getType())) {
                    continue;
                }
                IInventory inv;
                try {
                    f.setAccessible(true);
                    inv = (IInventory) f.get(container);
                } catch (Throwable t) {
                    note(unreadable, "inventory." + f.getName() + ":"
                            + t.getClass().getSimpleName());
                    continue;
                }
                if (inv == null) {
                    note(unreadable, "inventory." + f.getName() + ":null");
                    continue;
                }
                out.add(describe(f.getName(), inv, unreadable));
            }
        }
        return out;
    }

    private static Map<String, Object> describe(String fieldName, IInventory inv,
                                                List<String> unreadable) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("field", fieldName);
        m.put("type", inv.getClass().getSimpleName());
        String name = "";
        try {
            name = String.valueOf(inv.getDisplayName().getUnformattedText());
        } catch (Throwable t) {
            note(unreadable, "inventory." + fieldName + ".name:"
                    + t.getClass().getSimpleName());
        }
        m.put("name", name);
        int size = 0;
        try {
            size = inv.getSizeInventory();
        } catch (Throwable t) {
            note(unreadable, "inventory." + fieldName + ".size:"
                    + t.getClass().getSimpleName());
        }
        m.put("size", size);
        List<Object> contents = new ArrayList<>(Math.max(0, size));
        for (int i = 0; i < size; i++) {
            ItemStack s;
            try {
                s = inv.getStackInSlot(i);
            } catch (Throwable t) {
                note(unreadable, "inventory." + fieldName + "[" + i + "]:"
                        + t.getClass().getSimpleName());
                contents.add(null);
                continue;
            }
            contents.add(GuiStack.of(s, unreadable).toMap());
        }
        m.put("slots", contents);
        return m;
    }

    /** The first held inventory that is not the player's own — the panel's own storage. */
    private static IInventory panelInventory(Container c, String preferred, List<String> unreadable) {
        IInventory named = inventoryField(c, preferred, unreadable);
        if (named != null) {
            return named;
        }
        for (Class<?> k = c.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) {
                if (!IInventory.class.isAssignableFrom(f.getType())
                        || "InventoryPlayer".equals(f.getType().getSimpleName())) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    IInventory inv = (IInventory) f.get(c);
                    if (inv != null) {
                        return inv;
                    }
                } catch (Throwable ignored) {
                    // scanInventories already recorded the failure for this field
                }
            }
        }
        return null;
    }

    // ===== overlays =====
    //
    // Package-visible rather than private: each overlay is a pure function of a
    // container, and a container that cannot be constructed without a live World (the
    // enchantment table's, whose constructor reads playerInv.player.getXPSeed())
    // still has to be reachable headlessly by a test that reproduces its declared
    // vanilla fields. Every overlay reads by FIELD NAME, so a shape-faithful fake
    // exercises the same code a real panel does.
    static void chest(Container c, List<Map<String, Object>> invs,
                              Map<String, Object> facts, List<String> unreadable) {
        // ContainerChest:14 derives numRows from the chest's own size; a single chest
        // is 27/9=3 rows and a double is 54/9=6. Read the field first, fall back to
        // the size so a subclass that drops the field still reports its shape.
        int rows = intField(c, "numRows", "chest.numRows", unreadable);
        if (rows <= 0) {
            for (Map<String, Object> e : invs) {
                if ("InventoryPlayer".equals(e.get("type"))) {
                    continue;
                }
                int size = e.get("size") instanceof Integer n ? n : 0;
                if (size > 0) {
                    rows = size / 9;
                }
                break;
            }
        }
        facts.put("rows", rows);
    }

    /**
     * Villager panel. Returns the merchant's own name as the panel TITLE, because
     * the merchant inventory's display name is the raw key {@code mob.villager}
     * while {@code GuiMerchant:75} draws {@code chatComponent} — the merchant's name
     * — as the heading. Returns null when there is nothing better to say, so the
     * caller falls back to the generic title.
     */
    static String merchant(Container c, Map<String, Object> facts,
                                   List<String> unreadable) {
        String title = null;
        Object merchant = field(c, "theMerchant", "merchant.theMerchant", unreadable);
        if (merchant != null) {
            Object name = invoke(merchant, "getDisplayName", unreadable, "merchant.getDisplayName");
            if (name instanceof net.minecraft.util.IChatComponent comp) {
                try {
                    title = comp.getUnformattedText();
                    facts.put("merchant", title);
                } catch (Throwable t) {
                    note(unreadable, "merchant.name:" + t.getClass().getSimpleName());
                }
            }
        }

        Object inv = field(c, "merchantInventory", "merchant.merchantInventory", unreadable);
        if (inv == null) {
            note(unreadable, "merchant.trades:no merchant inventory");
            facts.put("trades", List.of());
            return title;
        }
        int selected = intField(inv, "currentRecipeIndex", "merchant.currentRecipeIndex",
                unreadable);
        facts.put("selectedIndex", selected);

        // The player is the merchant's customer and getRecipes needs it; a null here
        // is only a headless read, and the fake merchant in a test ignores it.
        Object player = field(inv, "thePlayer", "merchant.thePlayer", unreadable);
        Object recipes = merchant == null ? null
                : invoke(merchant, "getRecipes", unreadable, "merchant.getRecipes", player);
        if (!(recipes instanceof List<?> list)) {
            facts.put("trades", List.of());
            return title;
        }
        List<Object> trades = new ArrayList<>(list.size());
        int idx = 0;
        for (Object o : list) {
            trades.add(trade(o, idx++ == selected, unreadable));
        }
        facts.put("trades", trades);
        return title;
    }


    /**
     * One trade, with the REAL costs: what the villager wants (one or two stacks)
     * and what it gives, plus how many uses are left. {@code uses}/{@code maxUses}
     * are vanilla's own fields ({@code MerchantRecipe:89,94}) — a disabled trade is
     * one that has run out, which is the difference between "affordable" and "shown
     * but dead".
     */
    private static Map<String, Object> trade(Object recipe, boolean selected,
                                             List<String> unreadable) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (recipe == null) {
            return m;
        }
        m.put("selected", selected);
        m.put("cost", stack(invoke(recipe, "getItemToBuy", unreadable, "trade.getItemToBuy")));
        Object second = invoke(recipe, "getSecondItemToBuy", unreadable, "trade.getSecondItemToBuy");
        // 1.8.9 has no ItemStack.isEmpty(): an absent stack is null and a spent one
        // has stackSize 0.
        if (second instanceof ItemStack s && s.stackSize > 0) {
            m.put("cost2", GuiStack.of(s, unreadable).toMap());
        }
        m.put("gives", stack(invoke(recipe, "getItemToSell", unreadable, "trade.getItemToSell")));
        Object uses = invoke(recipe, "getToolUses", unreadable, "trade.getToolUses");
        Object max = invoke(recipe, "getMaxTradeUses", unreadable, "trade.getMaxTradeUses");
        if (uses instanceof Number u) {
            m.put("uses", u.intValue());
        }
        if (max instanceof Number u) {
            m.put("maxUses", u.intValue());
        }
        Object disabled = invoke(recipe, "isRecipeDisabled", unreadable, "trade.isRecipeDisabled");
        if (disabled instanceof Boolean b) {
            m.put("exhausted", b);
        }
        return m;
    }

    static void anvil(Container c, Map<String, Object> facts, List<String> unreadable) {
        facts.put("primary", slotStack(c, 0, unreadable));
        facts.put("secondary", slotStack(c, 1, unreadable));
        facts.put("result", slotStack(c, 2, unreadable));
        // ContainerRepair:36 maximumCost is the XP bill; :38 materialCost is the lapis
        // bill. Both are what the GUI prints next to the result.
        facts.put("xpCost", intField(c, "maximumCost", "anvil.maximumCost", unreadable));
        facts.put("lapisCost", intField(c, "materialCost", "anvil.materialCost", unreadable));
        String name = stringField(c, "repairedItemName", "anvil.repairedItemName", unreadable);
        if (name != null && !name.isEmpty()) {
            facts.put("rename", name);
        }
    }

    static void enchantment(Container c, Map<String, Object> facts,
                                    List<String> unreadable) {
        IInventory table = panelInventory(c, "tableInventory", unreadable);
        facts.put("item", stack(safeSlot(table, 0, unreadable)));
        facts.put("lapisInSlot", stack(safeSlot(table, 1, unreadable)));

        Object levels = field(c, "enchantLevels", "enchantment.enchantLevels", unreadable);
        Object ids = field(c, "enchantmentIds", "enchantment.enchantmentIds", unreadable);
        if (!(levels instanceof int[] lv) || lv.length < 3) {
            facts.put("offers", List.of());
            return;
        }
        int[] enc = ids instanceof int[] a ? a : new int[0];
        List<Object> offers = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            Map<String, Object> o = new LinkedHashMap<>();
            o.put("index", i);
            o.put("level", lv[i]);
            // ContainerEnchantment:247 — offer i costs i+1 lapis and i+1 levels.
            o.put("lapisCost", i + 1);
            o.put("xpCost", i + 1);
            if (i < enc.length) {
                // ContainerEnchantment:221 packs effectId | level<<8.
                int packed = enc[i];
                int effectId = packed & 0xFF;
                int enchLevel = packed >> 8;
                net.minecraft.enchantment.Enchantment e =
                        net.minecraft.enchantment.Enchantment.getEnchantmentById(effectId);
                if (e != null) {
                    o.put("enchantment", e.getTranslatedName(enchLevel));
                }
            }
            offers.add(o);
        }
        facts.put("offers", offers);
    }

    static void furnace(Container c, Map<String, Object> facts,
                                List<String> unreadable) {
        IInventory tile = panelInventory(c, "tileFurnace", unreadable);
        if (tile == null) {
            return;
        }
        facts.put("input", stack(safeSlot(tile, 0, unreadable)));
        facts.put("fuel", stack(safeSlot(tile, 1, unreadable)));
        facts.put("output", stack(safeSlot(tile, 2, unreadable)));
        // GuiFurnace:58-73 reads these same four fields off the tile inventory:
        // 0 total burn time, 1 burn time remaining, 2 cook time, 3 total cook time.
        int burnTotal = invField(tile, 0, unreadable);
        int burn = invField(tile, 1, unreadable);
        int cook = invField(tile, 2, unreadable);
        int cookTotal = invField(tile, 3, unreadable);
        facts.put("burnTime", burn);
        facts.put("burnTotal", burnTotal);
        facts.put("lit", burn > 0);
        facts.put("cookTime", cook);
        facts.put("cookTotal", cookTotal);
    }

    static void brewing(Container c, Map<String, Object> facts,
                                List<String> unreadable) {
        IInventory tile = panelInventory(c, "tileBrewingStand", unreadable);
        if (tile == null) {
            return;
        }
        // ContainerBrewingStand:20-23 — tile slots 0..2 are the bottles, slot 3 the ingredient.
        List<Object> bottles = new ArrayList<>(3);
        for (int i = 0; i < 3; i++) {
            bottles.add(stack(safeSlot(tile, i, unreadable)));
        }
        facts.put("bottles", bottles);
        facts.put("ingredient", stack(safeSlot(tile, 3, unreadable)));
        int brewTime = invField(tile, 0, unreadable);
        facts.put("brewTime", brewTime);
        // GuiBrewingStand:48 divides the countdown by a constant 400.
        facts.put("brewTotal", 400);
        facts.put("brewing", brewTime > 0);
    }

    static void beacon(Container c, Map<String, Object> facts, List<String> unreadable) {
        facts.put("payment", slotStack(c, 0, unreadable));
        IInventory tile = panelInventory(c, "tileBeacon", unreadable);
        if (tile != null) {
            facts.put("beaconEffects", stack(safeSlot(tile, 0, unreadable)));
            facts.put("beaconTier", stack(safeSlot(tile, 1, unreadable)));
        }
    }

    static void horse(Container c, Map<String, Object> facts, List<String> unreadable) {
        IInventory horse = panelInventory(c, "horseInventory", unreadable);
        if (horse != null) {
            facts.put("saddleSlot", stack(safeSlot(horse, 0, unreadable)));
            facts.put("armorSlot", stack(safeSlot(horse, 1, unreadable)));
        }
        facts.put("horseSaddleSlot", slotStack(c, 0, unreadable));
    }

    static void hopper(Container c, Map<String, Object> facts, List<String> unreadable) {
        IInventory hopper = panelInventory(c, "hopperInventory", unreadable);
        if (hopper != null) {
            for (int i = 0; i < hopper.getSizeInventory(); i++) {
                facts.put("item" + i, stack(safeSlot(hopper, i, unreadable)));
            }
        }
    }

    static void workbench(Map<String, Object> facts) {
        // Grid coordinates (0..8) are what a plan needs, so publish the shape here
        // instead of making the caller reverse the container's numbering.
        facts.put("gridWidth", 3);
        facts.put("gridHeight", 3);
    }

    // ===== primitives =====

    private static Object safeSlot(IInventory inv, int i, List<String> unreadable) {
        try {
            return inv.getStackInSlot(i);
        } catch (Throwable t) {
            note(unreadable, "inventory." + inv.getClass().getSimpleName() + "[" + i + "]:"
                    + t.getClass().getSimpleName());
            return null;
        }
    }

    private static Map<String, Object> stack(Object itemStack) {
        return GuiStack.of((ItemStack) itemStack).toMap();
    }

    /** One container slot's contents, read through the container's own numbering. */
    private static Map<String, Object> slotStack(Container c, int slotId, List<String> unreadable) {
        try {
            if (slotId < 0 || slotId >= c.inventorySlots.size()) {
                return null;
            }
            return stack(c.getSlot(slotId).getStack());
        } catch (Throwable t) {
            note(unreadable, "slot[" + slotId + "]:" + t.getClass().getSimpleName());
            return null;
        }
    }

    private static int invField(IInventory inv, int id, List<String> unreadable) {
        try {
            return inv.getField(id);
        } catch (Throwable t) {
            note(unreadable, "inventory." + inv.getClass().getSimpleName() + ".field(" + id + "):"
                    + t.getClass().getSimpleName());
            return 0;
        }
    }

    private static IInventory inventoryField(Object target, String name, List<String> unreadable) {
        Object v = field(target, name, name, unreadable);
        return v instanceof IInventory inv ? inv : null;
    }

    /** Read a field by name, walking up the hierarchy; null (with a note) on any failure. */
    private static Object field(Object target, String name, String label,
                                List<String> unreadable) {
        for (Class<?> c = target.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // walk up
            } catch (Throwable t) {
                note(unreadable, label + ":" + t.getClass().getSimpleName());
                return null;
            }
        }
        note(unreadable, label + ":absent");
        return null;
    }

    private static int intField(Object target, String name, String label,
                                List<String> unreadable) {
        Object v = field(target, name, label, unreadable);
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static String stringField(Object target, String name, String label,
                                      List<String> unreadable) {
        Object v = field(target, name, label, unreadable);
        return v instanceof String s ? s : null;
    }

    /**
     * Call a vanilla getter found by name and argument count. Used only for public
     * getters, so a missing method is a mappings drift worth reporting rather than
     * a silent null. Every same-name overload in the hierarchy is tried, because an
     * interface method and its implementation are both declared and only one of them
     * is the real body.
     */
    private static Object invoke(Object target, String method, List<String> unreadable,
                                 String label, Object... args) {
        boolean anyFound = false;
        for (Class<?> c = target.getClass(); c != null && c != Object.class;
                c = c.getSuperclass()) {
            for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(method) || m.getParameterCount() != args.length) {
                    continue;
                }
                anyFound = true;
                try {
                    m.setAccessible(true);
                    return m.invoke(target, args);
                } catch (Throwable ignored) {
                    // A bridge/synthetic overload that throws: try the next candidate
                    // and only report if none of them worked.
                }
            }
        }
        note(unreadable, label + (anyFound ? ":failed" : ":absent"));
        return null;
    }

    private static void note(List<String> sink, String what) {
        if (sink != null) {
            sink.add(what);
        }
    }

    private static List<String> orEmpty(List<String> l) {
        return l == null ? List.of() : l;
    }
}
