package net.marcloud.mcp.core.drivers.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.IMerchant;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.init.Items;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.ContainerBrewingStand;
import net.minecraft.inventory.ContainerChest;
import net.minecraft.inventory.ContainerFurnace;
import net.minecraft.inventory.ContainerMerchant;
import net.minecraft.inventory.ContainerRepair;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.InventoryBasic;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IChatComponent;
import net.minecraft.village.MerchantRecipe;
import net.minecraft.village.MerchantRecipeList;

import org.junit.Test;

/**
 * Headless tests for the READ side of the panel surface: what a person reads off a
 * panel to decide where to click.
 *
 * <p>Every container here is the REAL vanilla class, built with a null
 * {@link EntityPlayer} and a null world — which is exactly how the real panels build
 * their slots ({@code SlotMerchantResult} and {@code SlotFurnaceOutput} only store
 * the player), so the reads are the production ones and not a re-implementation.
 * The screen is synthetic ({@link PanelScreen}) because every real
 * {@code GuiChest}/{@code GuiMerchant} constructor reaches for
 * {@code Minecraft.getMinecraft()}.
 *
 * <p>The one exception is {@link FakeEnchantTable}: {@code ContainerEnchantment}'s
 * constructor reads {@code playerInv.player.getXPSeed()}, so it cannot be built
 * without a live World. That fake reproduces its declared vanilla field set
 * ({@code tableInventory}, {@code enchantLevels}, {@code enchantmentIds}) verbatim
 * from the source, and the overlay reads those by name, so the same production code
 * runs.
 */
public class GuiPanelStateTest {

    /** The item registry Items resolves against; without it every ItemStack is empty. */
    @org.junit.BeforeClass
    public static void bootstrapRegistry() {
        net.minecraft.init.Bootstrap.register();
    }

    /** A synthetic screen over a real vanilla container; initGui() is never called. */
    private static final class PanelScreen extends GuiContainer {
        PanelScreen(Container c) {
            super(c);
        }

        @Override
        protected void drawGuiContainerBackgroundLayer(float partialTicks, int mouseX, int mouseY) {
        }

        void place(int left, int top) {
            this.guiLeft = left;
            this.guiTop = top;
        }
    }

    /** The vanilla enchantment table container's declared fields, nothing invented. */
    private static final class FakeEnchantTable extends Container {
        public IInventory tableInventory = new InventoryBasic("Enchant", true, 2);
        public int[] enchantLevels = new int[3];
        public int[] enchantmentIds = new int[] {-1, -1, -1};

        @Override
        public boolean canInteractWith(EntityPlayer playerIn) {
            return true;
        }
    }

    /**
     * InventoryBasic's getField/setField are vanilla no-ops (InventoryBasic:260-267);
     * the real TileEntityFurnace / TileEntityBrewingStand do store progress there, and
     * that is exactly what the panel reader asks for. So the fixture stores.
     */
    private static final class ProgressInventory extends InventoryBasic {
        private final int[] fields = new int[4];

        ProgressInventory(String title, int slots) {
            super(title, true, slots);
        }

        @Override
        public int getField(int id) {
            return id >= 0 && id < fields.length ? fields[id] : 0;
        }

        @Override
        public void setField(int id, int value) {
            if (id >= 0 && id < fields.length) {
                fields[id] = value;
            }
        }
    }

    /** A merchant with a fixed trade list; getRecipes ignores the player it is handed. */
    private static final class FakeMerchant implements IMerchant {
        private MerchantRecipeList recipes = new MerchantRecipeList();
        EntityPlayer customer;

        @Override
        public void setCustomer(EntityPlayer p) {
            this.customer = p;
        }

        @Override
        public EntityPlayer getCustomer() {
            return customer;
        }

        @Override
        public MerchantRecipeList getRecipes(EntityPlayer p) {
            return recipes;
        }

        @Override
        public void setRecipes(MerchantRecipeList list) {
            this.recipes = list;
        }

        @Override
        public void useRecipe(MerchantRecipe recipe) {
        }

        @Override
        public void verifySellingItem(ItemStack stack) {
        }

        @Override
        public IChatComponent getDisplayName() {
            return new net.minecraft.util.ChatComponentText("Librarian");
        }
    }

    // ---- fixtures -----------------------------------------------------------

    private static InventoryPlayer playerInv() {
        return new InventoryPlayer(null);
    }

    private static GuiPanelState read(Container c, List<String> unreadable) {
        return GuiPanelReflect.read(new PanelScreen(c), c, unreadable);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> invNamed(GuiPanelState panel, String field) {
        List<Object> invs = (List<Object>) panel.fact(GuiPanelState.GENERIC_INVENTORIES);
        for (Object o : invs) {
            Map<String, Object> m = (Map<String, Object>) o;
            if (field.equals(m.get("field"))) {
                return m;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stackAt(Map<String, Object> inv, int index) {
        List<Object> slots = (List<Object>) inv.get("slots");
        return (Map<String, Object>) slots.get(index);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> factMap(GuiPanelState panel, String key) {
        return (Map<String, Object>) panel.fact(key);
    }

    // ---- tests --------------------------------------------------------------

    /**
     * The chest case the brief names: the panel's REAL title (the chest's own
     * display name, which is the string GuiChest:38 draws) and its real contents,
     * read off the vanilla ContainerChest.
     */
    @Test
    public void chestReadsItsRealTitleRowsAndContents() {
        InventoryBasic chest = new InventoryBasic("Loot Chest", true, 27);
        chest.setInventorySlotContents(0, new ItemStack(Items.diamond, 12));
        chest.setInventorySlotContents(1, new ItemStack(Items.coal, 64));
        ContainerChest c = new ContainerChest(playerInv(), chest, null);
        List<String> unreadable = new ArrayList<>();

        GuiPanelState panel = read(c, unreadable);

        assertTrue("no drift expected: " + unreadable, unreadable.isEmpty());
        assertEquals("chest", panel.kind());
        assertEquals("Loot Chest", panel.title());
        assertEquals(3, panel.fact("rows"));

        Map<String, Object> inv = invNamed(panel, "lowerChestInventory");
        assertNotNull("the chest's own inventory must be reported: "
                + panel.fact(GuiPanelState.GENERIC_INVENTORIES), inv);
        assertEquals("InventoryBasic", inv.get("type"));
        assertEquals(27, inv.get("size"));
        Map<String, Object> slot0 = stackAt(inv, 0);
        assertEquals("minecraft:diamond", slot0.get("item"));
        assertEquals(12, slot0.get("count"));
        assertEquals("minecraft:coal", stackAt(inv, 1).get("item"));
        assertNull("an empty slot reports null, not a zero stack", stackAt(inv, 2));
    }

    /**
     * The villager case: trades with their REAL costs, how many uses are left, and
     * which one is on screen. Driven through the vanilla ContainerMerchant and a
     * MerchantRecipeList, so the cost stacks are vanilla's own.
     */
    @Test
    public void merchantReadsTradesWithRealCostsUsesAndSelection() {
        FakeMerchant merchant = new FakeMerchant();
        merchant.setRecipes(new MerchantRecipeList());
        merchant.getRecipes(null).add(new MerchantRecipe(
                new ItemStack(Items.emerald, 16), null, new ItemStack(Items.book, 1), 2, 5));
        ContainerMerchant c = new ContainerMerchant(playerInv(), merchant, null);
        List<String> unreadable = new ArrayList<>();

        GuiPanelState panel = read(c, unreadable);

        assertTrue("no drift expected: " + unreadable, unreadable.isEmpty());
        assertEquals("merchant", panel.kind());
        assertEquals("Librarian", panel.title());
        assertEquals("Librarian", panel.fact("merchant"));
        assertEquals(0, panel.fact("selectedIndex"));

        List<?> trades = (List<?>) panel.fact("trades");
        assertEquals(1, trades.size());
        Map<String, Object> t = (Map<String, Object>) trades.get(0);
        assertEquals(Boolean.TRUE, t.get("selected"));
        assertEquals("minecraft:emerald", mapOf(t, "cost").get("item"));
        assertEquals(16, mapOf(t, "cost").get("count"));
        assertNull("a one-stack trade has no second cost", t.get("cost2"));
        assertEquals("minecraft:book", mapOf(t, "gives").get("item"));
        assertEquals(2, t.get("uses"));
        assertEquals(5, t.get("maxUses"));
        assertEquals(Boolean.FALSE, t.get("exhausted"));
    }

    /** The anvil case: the result stack plus the XP bill and the lapis bill. */
    @Test
    public void anvilReadsItsResultAndBothBills() {
        ContainerRepair c = new ContainerRepair(playerInv(), null, null);
        c.getSlot(0).putStack(new ItemStack(Items.diamond_sword));
        c.getSlot(1).putStack(new ItemStack(Items.diamond));
        c.getSlot(2).putStack(new ItemStack(Items.diamond_sword));
        c.maximumCost = 7;
        setPrivateInt(c, "materialCost", 2);
        setPrivateString(c, "repairedItemName", "Excalibur");
        List<String> unreadable = new ArrayList<>();

        GuiPanelState panel = read(c, unreadable);

        assertTrue("no drift expected: " + unreadable, unreadable.isEmpty());
        assertEquals("anvil", panel.kind());
        assertEquals("minecraft:diamond_sword", factMap(panel, "primary").get("item"));
        assertEquals("minecraft:diamond", factMap(panel, "secondary").get("item"));
        assertEquals("minecraft:diamond_sword", factMap(panel, "result").get("item"));
        assertEquals(7, panel.fact("xpCost"));
        assertEquals(2, panel.fact("lapisCost"));
        assertEquals("Excalibur", panel.fact("rename"));
    }

    /**
     * The enchantment-table case: three offers with their levels, the lapis price of
     * each (offer i costs i+1, ContainerEnchantment:247) and the enchantment the
     * server packed into it (ContainerEnchantment:221 packs effectId|level<<8).
     */
    @Test
    public void enchantmentReadsThreeOffersWithLapisPriceAndTheEnchantment() {
        FakeEnchantTable c = new FakeEnchantTable();
        c.tableInventory.setInventorySlotContents(0, new ItemStack(Items.diamond_sword));
        c.tableInventory.setInventorySlotContents(1, new ItemStack(Items.dye, 9, 4));
        c.enchantLevels[0] = 5;
        c.enchantLevels[1] = 0;
        c.enchantLevels[2] = 12;
        // Sharpness is effectId 2 in 1.8.9 (1 is Fire Protection); vanilla packs
        // effectId | level<<8 at ContainerEnchantment:221.
        c.enchantmentIds[0] = 2 | (5 << 8);
        List<String> unreadable = new ArrayList<>();

        Map<String, Object> facts = new HashMap<>();
        GuiPanelReflect.enchantment(c, facts, unreadable);
        GuiPanelState panel = new GuiPanelState("enchantment", "Enchant", facts, unreadable);

        assertTrue("no drift expected: " + unreadable, unreadable.isEmpty());
        assertEquals("minecraft:diamond_sword", factMap(panel, "item").get("item"));
        assertEquals(9, factMap(panel, "lapisInSlot").get("count"));

        List<?> offers = (List<?>) panel.fact("offers");
        assertEquals("an enchantment table always shows three offers", 3, offers.size());
        assertEquals(1, ((Map<?, ?>) offers.get(0)).get("lapisCost"));
        assertEquals(2, ((Map<?, ?>) offers.get(1)).get("lapisCost"));
        assertEquals(3, ((Map<?, ?>) offers.get(2)).get("lapisCost"));
        assertEquals(5, ((Map<?, ?>) offers.get(0)).get("level"));
        assertEquals(0, ((Map<?, ?>) offers.get(1)).get("level"));
        assertEquals(12, ((Map<?, ?>) offers.get(2)).get("level"));
        assertNotNull("a packed enchantment id must decode to a name",
                ((Map<?, ?>) offers.get(0)).get("enchantment"));
        // The decode is effectId | level<<8 (ContainerEnchantment:221) and the name is
        // vanilla's own getTranslatedName(level). Asserted against the vanilla API
        // rather than a literal, because effectId -> which enchantment is a mappings
        // detail; what is being pinned is that BOTH halves decode and are named.
        int packed = c.enchantmentIds[0];
        assertEquals("effectId 2", net.minecraft.enchantment.Enchantment.getEnchantmentById(
                packed & 0xFF).getTranslatedName(packed >> 8),
                ((Map<?, ?>) offers.get(0)).get("enchantment"));
        assertNull("an unpacked offer (-1) has no enchantment to name",
                ((Map<?, ?>) offers.get(1)).get("enchantment"));
    }

    /** The furnace case: its three slots and its burn/cook progress. */
    @Test
    public void furnaceReadsItsThreeSlotsAndProgress() {
        ProgressInventory tile = new ProgressInventory("Furnace", 3);
        ContainerFurnace c = new ContainerFurnace(playerInv(), tile);
        tile.setInventorySlotContents(0, new ItemStack(Items.iron_ingot, 5));
        tile.setInventorySlotContents(1, new ItemStack(Items.coal, 1, 1));
        tile.setInventorySlotContents(2, new ItemStack(Items.iron_ingot, 3));
        tile.setField(0, 200);
        tile.setField(1, 120);
        tile.setField(2, 40);
        tile.setField(3, 200);
        List<String> unreadable = new ArrayList<>();

        GuiPanelState panel = read(c, unreadable);

        assertTrue("no drift expected: " + unreadable, unreadable.isEmpty());
        assertEquals("furnace", panel.kind());
        assertEquals("Furnace", panel.title());
        assertEquals("minecraft:iron_ingot", factMap(panel, "input").get("item"));
        assertEquals("minecraft:coal", factMap(panel, "fuel").get("item"));
        assertEquals("minecraft:iron_ingot", factMap(panel, "output").get("item"));
        assertEquals(120, panel.fact("burnTime"));
        assertEquals(200, panel.fact("burnTotal"));
        assertEquals(Boolean.TRUE, panel.fact("lit"));
        assertEquals(40, panel.fact("cookTime"));
        assertEquals(200, panel.fact("cookTotal"));
    }

    /** The brewing-stand case: three bottles, the ingredient, and the countdown. */
    @Test
    public void brewingStandReadsItsThreeBottlesAndCountdown() {
        ProgressInventory tile = new ProgressInventory("Brewing Stand", 4);
        ContainerBrewingStand c = new ContainerBrewingStand(playerInv(), tile);
        for (int i = 0; i < 3; i++) {
            tile.setInventorySlotContents(i, new ItemStack(Items.potionitem, 1, 16421));
        }
        tile.setInventorySlotContents(3, new ItemStack(Items.blaze_powder, 1));
        tile.setField(0, 300);
        List<String> unreadable = new ArrayList<>();

        GuiPanelState panel = read(c, unreadable);

        assertTrue("no drift expected: " + unreadable, unreadable.isEmpty());
        assertEquals("brewing", panel.kind());
        List<?> bottles = (List<?>) panel.fact("bottles");
        assertEquals(3, bottles.size());
        for (Object o : bottles) {
            assertEquals("minecraft:potion", ((Map<?, ?>) o).get("item"));
            assertEquals(16421, ((Map<?, ?>) o).get("damage"));
        }
        assertEquals("minecraft:blaze_powder", factMap(panel, "ingredient").get("item"));
        assertEquals(300, panel.fact("brewTime"));
        assertEquals(400, panel.fact("brewTotal"));
        assertEquals(Boolean.TRUE, panel.fact("brewing"));
    }

    /**
     * The generalisation the whole thing exists for: a container nobody wrote a case
     * for STILL gets a real dump of every inventory it holds, and says so, so the
     * agent can tell a genuine reading from a gap.
     */
    @Test
    public void unknownContainerStillDumpsItsInventoriesAndSaysItIsUnrecognised() {
        final InventoryBasic crateInv = new InventoryBasic("Supply Crate", true, 2);
        crateInv.setInventorySlotContents(0, new ItemStack(Items.gold_ingot, 3));
        Container odd = new Container() {
            private final IInventory crate = crateInv;

            @Override
            public boolean canInteractWith(EntityPlayer playerIn) {
                return true;
            }
        };
        List<String> unreadable = new ArrayList<>();

        GuiPanelState panel = read(odd, unreadable);

        assertEquals(Boolean.FALSE, panel.fact(GuiPanelState.RECOGNISED));
        assertEquals("Supply Crate", panel.title());
        Map<String, Object> inv = invNamed(panel, "crate");
        assertNotNull(panel.fact(GuiPanelState.GENERIC_INVENTORIES));
        assertNotNull("the unknown container's inventory must still be dumped", inv);
        assertEquals("minecraft:gold_ingot", stackAt(inv, 0).get("item"));
        assertTrue("a panel with content is worth publishing", panel.hasContent());
    }

    /**
     * A panel with nothing to say must not be published: gui_snapshot omits the block
     * entirely rather than shipping an empty object to an agent on every menu.
     */
    @Test
    public void aPanelWithNoContentReportsNone() {
        Container empty = new Container() {
            @Override
            public boolean canInteractWith(EntityPlayer playerIn) {
                return true;
            }
        };
        GuiPanelState panel = GuiPanelReflect.read(new PanelScreen(empty), empty, new ArrayList<>());
        assertFalse("an empty container has no title and no facts to publish",
                panel.hasContent());
        assertFalse(GuiPanelState.NONE.hasContent());
    }

    /**
     * The slot read itself: a snapshot's slot element must carry the item by REGISTRY
     * name, and the panel and the element must agree on it — the same identity
     * world_view and transfer_item use.
     */
    @Test
    public void slotElementAndPanelAgreeOnTheSameRegistryName() {
        InventoryBasic chest = new InventoryBasic("Loot Chest", true, 27);
        chest.setInventorySlotContents(0, new ItemStack(Items.enchanted_book, 1));
        ContainerChest c = new ContainerChest(playerInv(), chest, null);
        PanelScreen screen = new PanelScreen(c);
        screen.width = 427;
        screen.height = 240;
        screen.place(100, 60);

        GuiReflect.Extraction ex = GuiReflect.extract(screen, false);
        GuiElement slot = ex.elements().stream()
                .filter(e -> e.id().equals("s0")).findFirst().orElse(null);
        assertNotNull(slot);
        @SuppressWarnings("unchecked")
        Map<String, Object> item = (Map<String, Object>) slot.attributes().get("item");
        assertEquals("minecraft:enchanted_book", item.get("item"));
        assertEquals("Enchanted Book", item.get("name"));

        GuiPanelState panel = GuiPanelReflect.read(screen, c, ex.unreadable());
        assertEquals("minecraft:enchanted_book",
                stackAt(invNamed(panel, "lowerChestInventory"), 0).get("item"));
    }

    // ---- helpers ------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOf(Map<String, Object> m, String key) {
        return (Map<String, Object>) m.get(key);
    }

    private static void setPrivateInt(Object target, String field, int value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.setInt(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot set " + field, e);
        }
    }

    private static void setPrivateString(Object target, String field, String value) {
        try {
            java.lang.reflect.Field f = target.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("cannot set " + field, e);
        }
    }
}
