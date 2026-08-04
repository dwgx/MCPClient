package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.marcloud.mcp.core.drivers.world.InventoryView;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.NBTTagCompound;
import org.junit.Test;

/**
 * The feasibility check: not whether the player can craft it, but what exactly is short.
 *
 * <p><b>Why the shortfall is the tested part.</b> A boolean "no" is a dead end for a model -- it
 * cannot tell two more sticks from a diamond it has never seen, and the two cost thirty seconds and an
 * expedition respectively. So the assertions here are about the CONTENT of a negative answer: the
 * ingredient is named, the shortfall arithmetic holds, and the entries arrive in the recipe's own
 * ingredient order so the report reads like the layout.
 *
 * <p><b>Why synthetic views.</b> {@link CraftFeasibility} takes values, and the case its allocation
 * order exists to handle -- one recipe wanting both a wildcard and an exact variant of the same item
 * -- does not occur anywhere in vanilla's table (measured: zero such recipes). A table-driven test
 * therefore cannot reach the argument that motivates the code. The real-table round trip lives in
 * {@link EveryEmittedLayoutIsAcceptedByVanillaTest}; this file is where the allocator is cornered.
 */
public class CraftFeasibilityNamesWhatIsShortTest {

    /** A view with a demand list built by hand, so the allocator can be given orders vanilla never gives. */
    private static RecipeView demanding(RecipeView.Ingredient... demand) {
        return new RecipeView(0, "test_output", 0, 1, false, 1, 1, List.of(), List.of(demand));
    }

    private static RecipeView.Ingredient exact(String item, int meta, int count) {
        return new RecipeView.Ingredient(item, meta, false, count);
    }

    private static RecipeView.Ingredient wildcard(String item, int count) {
        return new RecipeView.Ingredient(item, 0, true, count);
    }

    @Test
    public void anInventoryThatCoversEveryIngredientIsSatisfiedWithAnEmptyMissingList() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(exact("planks", 0, 4)),
                CraftInventory.of(new CraftInventory.Held("planks", 0, 4)));

        assertTrue(r.satisfied());
        assertEquals("satisfied and a non-empty missing list would contradict each other",
                List.of(), r.missing());
    }

    /**
     * The shortfall names the item, the variant, what was available and what was needed.
     *
     * <p>All four, because each answers a different question the model has to act on: which item to go
     * and get, which variant of it, whether it has some already, and how many more.
     */
    @Test
    public void aShortfallNamesTheIngredientAndTheExactAmountMissing() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(exact("diamond", 0, 3)),
                CraftInventory.of(new CraftInventory.Held("diamond", 0, 1)));

        assertFalse(r.satisfied());
        assertEquals(List.of(new CraftFeasibility.Missing("diamond", 0, false, 1, 3, 2)),
                r.missing());
    }

    /**
     * One plank does not cover a four-plank recipe, and the report says it is three short.
     *
     * <p>NOT a test of the pool deduction, though it was written believing it was, copying that reason
     * from {@code take}'s javadoc -- which was itself wrong. Deleting the deduction leaves this test
     * green: the allocator visits each stack once per ingredient, so one plank pays 1 whether or not
     * anything is deducted. Found by injecting exactly that defect and watching this pass. The
     * deduction is cornered by
     * {@link #twoSquaresOfOneRecipeCannotBothSpendTheSameSinglePlank()} instead; what this test
     * actually pins is the shortfall arithmetic on a partially payable ingredient.
     */
    @Test
    public void oneHeldItemCannotPayForFourSquaresOfTheSameIngredient() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(exact("planks", 0, 4)),
                CraftInventory.of(new CraftInventory.Held("planks", 0, 1)));

        assertFalse("a single plank must not cover a four-plank recipe", r.satisfied());
        assertEquals(1, r.missing().size());
        assertEquals(3, r.missing().get(0).shortBy());
        assertEquals(1, r.missing().get(0).available());
    }

    /**
     * The pool deduction, cornered: two squares of one recipe cannot both be paid by one plank.
     *
     * <p>A wildcard square and an exact oak square, and a single oak plank held. Correct: the exact
     * square takes it, the wildcard square finds an empty pool, and the report is short by one.
     * Without the deduction both squares spend the same plank and the answer is "satisfied, nothing
     * missing" -- the model opens a table, places one plank in two squares it does not have the
     * material for, and gets nothing, with no field in the report hinting at why.
     *
     * <p>Reaching this needs two demand entries for ONE item, which only a mixed wildcard-and-exact
     * recipe produces -- {@code demand()} keys on item, meta and the wildcard flag, so two identical
     * exact ingredients collapse into a single entry of count 2 and cannot compete with each other.
     * That is why the earlier same-ingredient test could not see the deduction at all.
     */
    @Test
    public void twoSquaresOfOneRecipeCannotBothSpendTheSameSinglePlank() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(wildcard("planks", 1), exact("planks", 0, 1)),
                CraftInventory.of(new CraftInventory.Held("planks", 0, 1)));

        assertFalse("one plank cannot fill two squares", r.satisfied());
        assertEquals("the wildcard square is the one left unpaid, since the exact one is served first",
                1, r.missing().size());
        assertTrue("and it is the wildcard that is reported short", r.missing().get(0).anyMeta());
        assertEquals("with nothing available to it, the oak having been spent",
                0, r.missing().get(0).available());
    }

    /** Two stacks of one item add up: the pooling rule lives in the check, not in the adapter. */
    @Test
    public void twoStacksOfTheSameItemAreSpentTogether() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(exact("planks", 0, 4)),
                CraftInventory.of(new CraftInventory.Held("planks", 0, 3),
                        new CraftInventory.Held("planks", 0, 1)));

        assertTrue("three plus one is four", r.satisfied());
    }

    /**
     * The case the whole two-pass allocator exists for, with the wildcard listed FIRST so that a
     * single pass in ingredient order gets it wrong.
     *
     * <p>Oak planks (meta 0) and spruce planks (meta 5), one of each. The recipe wants one square of
     * any plank and one square of oak specifically. Correct: the exact square takes the oak, the
     * wildcard takes the spruce, and nothing is short. A single pass in list order spends the oak on
     * the wildcard, finds no oak left for the exact square, and reports a shortfall for a recipe the
     * player can in fact afford -- sending the model to chop wood it is already carrying.
     *
     * <p>Vanilla's table contains no recipe of this shape, so this arrangement is unreachable from the
     * real data. It is written by hand precisely because the argument in
     * {@link CraftFeasibility#check} is otherwise unfalsifiable.
     */
    @Test
    public void anExactVariantIsServedBeforeAWildcardEvenWhenTheWildcardIsListedFirst() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(wildcard("planks", 1), exact("planks", 0, 1)),
                CraftInventory.of(new CraftInventory.Held("planks", 0, 1),
                        new CraftInventory.Held("planks", 5, 1)));

        assertTrue("the exact square must take the oak and leave the spruce for the wildcard,"
                + " so nothing is short: " + r.missing(), r.satisfied());
    }

    /**
     * The same demand with only the wildcard-payable stack held is short on the exact one, and the
     * report blames the exact ingredient rather than the wildcard.
     *
     * <p>The pair with the test above is the point: the first shows the allocator does not invent a
     * shortfall, this one shows it still finds a real one, so neither passes by being permissive.
     */
    @Test
    public void aWildcardCannotDisguiseAMissingExactVariant() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(wildcard("planks", 1), exact("planks", 0, 1)),
                CraftInventory.of(new CraftInventory.Held("planks", 5, 2)));

        assertFalse(r.satisfied());
        assertEquals("only the exact square is unpayable", 1, r.missing().size());
        CraftFeasibility.Missing m = r.missing().get(0);
        assertFalse("the blamed ingredient is the exact one, not the wildcard", m.anyMeta());
        assertEquals(0, m.meta());
        assertEquals("no spruce may be counted as available for an oak square", 0, m.available());
        assertEquals(1, m.shortBy());
    }

    /** A wildcard is paid by any variant, which is what distinguishes it from an exact ingredient. */
    @Test
    public void aWildcardIngredientIsPaidByAnyVariant() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(wildcard("planks", 2)),
                CraftInventory.of(new CraftInventory.Held("planks", 3, 1),
                        new CraftInventory.Held("planks", 5, 1)));

        assertTrue("jungle and dark oak together satisfy two wildcard squares", r.satisfied());
    }

    /**
     * A worn tool does not pay for an ingredient that names metadata 0.
     *
     * <p>Not a quirk of this check but of vanilla's own matchers, which compare {@code getMetadata()}
     * and see the damage value there. Asserted here because the report has to agree with what the
     * crafting grid will do -- claiming a damaged rod is spendable would produce a craft that vanilla
     * silently refuses.
     */
    @Test
    public void aDamagedToolDoesNotPayForAnIngredientNamingMetadataZero() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(exact("fishing_rod", 0, 1)),
                CraftInventory.of(new CraftInventory.Held("fishing_rod", 3, 1)));

        assertFalse("damage 3 is not metadata 0", r.satisfied());
        assertEquals(0, r.missing().get(0).available());
    }

    /** The missing list follows the recipe's ingredient order, so the report reads like the layout. */
    @Test
    public void missingEntriesArriveInTheRecipesOwnIngredientOrder() {
        CraftFeasibility.Result r = CraftFeasibility.check(
                demanding(exact("diamond", 0, 1), exact("stick", 0, 2), exact("gold_ingot", 0, 1)),
                CraftInventory.of(new CraftInventory.Held("stick", 0, 1)));

        assertEquals(List.of("diamond", "stick", "gold_ingot"),
                r.missing().stream().map(CraftFeasibility.Missing::item).toList());
        assertEquals("the partially paid ingredient reports what it did get",
                1, r.missing().get(1).available());
    }

    /** An empty inventory is short by the full demand, not satisfied by having nothing to contradict. */
    @Test
    public void anEmptyInventoryIsShortByTheWholeDemand() {
        CraftFeasibility.Result r = CraftFeasibility.check(demanding(exact("stick", 0, 2)),
                CraftInventory.of());

        assertFalse(r.satisfied());
        assertEquals(2, r.missing().get(0).shortBy());
    }

    /** Null and empty stacks in the pool are dropped rather than counted or crashed on. */
    @Test
    public void nullAndEmptyHeldEntriesAreIgnoredRatherThanCounted() {
        CraftFeasibility.Result r = CraftFeasibility.check(demanding(exact("stick", 0, 1)),
                new CraftInventory(java.util.Arrays.asList(null,
                        new CraftInventory.Held("stick", 0, 0),
                        new CraftInventory.Held(null, 0, 5),
                        new CraftInventory.Held("stick", 0, 1))));

        assertTrue("the one real stick pays, and nothing above it throws", r.satisfied());
    }

    /**
     * A null recipe is refused loudly rather than treated as free.
     *
     * <p>{@code read()} hands back a null view for every unsupported recipe, so this is the value a
     * caller that forgot to check {@code ok()} would arrive with -- and "satisfied, nothing missing"
     * would be the worst possible answer to give it.
     */
    @Test
    public void aNullRecipeIsRefusedRatherThanTreatedAsCostingNothing() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> CraftFeasibility.check(null, CraftInventory.of()));
        assertTrue(e.getMessage().contains("no recipe"));
    }

    /**
     * A namespaced, mixed-case held item satisfies an ingredient named the way the reader names them.
     *
     * <p>The two sides are produced by different code -- {@code RecipeLayoutReader.itemName} strips the
     * namespace and lowercases, the live inventory snapshot strips the namespace only -- so without
     * normalisation this reports a missing ingredient the player is holding, and the model goes mining
     * for something already in its pockets.
     */
    @Test
    public void aNamespacedOrMixedCaseHeldItemStillPaysForAPlainlyNamedIngredient() {
        CraftFeasibility.Result r = CraftFeasibility.check(demanding(exact("stick", 0, 1)),
                CraftInventory.of(new CraftInventory.Held("minecraft:Stick", 0, 1)));

        assertTrue("minecraft:Stick and stick are the same item", r.satisfied());
        assertEquals("stick", new CraftInventory.Held("minecraft:Stick", 0, 1).item());
    }

    /**
     * The live-snapshot adapter carries item, damage-as-variant and count, and drops what cannot be
     * spent.
     *
     * <p>Adapting rather than reimplementing is what keeps the tested path and the live path the same
     * code; this test pins the three fields it depends on, including that {@code damage} is what
     * becomes {@code meta} -- the field whose meaning the feasibility rule turns on.
     */
    @Test
    public void theLiveInventoryAdapterKeepsItemDamageAndCountAndDropsUnspendableSlots() {
        InventoryView live = new InventoryView(0, java.util.Arrays.asList(
                new InventoryView.Slot(0, "minecraft:planks", 3, 5, null),
                new InventoryView.Slot(1, null, 4, 0, null),
                new InventoryView.Slot(2, "stick", 0, 0, null),
                null,
                new InventoryView.Slot(4, "fishing_rod", 1, 7, 64)));

        CraftInventory inv = CraftInventory.from(live);

        assertEquals("empty, null-item and null slots are not spendable",
                List.of(new CraftInventory.Held("planks", 5, 3),
                        new CraftInventory.Held("fishing_rod", 7, 1)),
                inv.items());
        assertTrue("the adapted planks pay a wildcard plank square",
                CraftFeasibility.check(demanding(wildcard("planks", 3)), inv).satisfied());
        assertFalse("and the rod's damage is carried through as its variant",
                CraftFeasibility.check(demanding(exact("fishing_rod", 0, 1)), inv).satisfied());
    }

    /** A null snapshot adapts to an empty inventory rather than throwing at the boundary. */
    @Test
    public void aNullLiveSnapshotAdaptsToAnEmptyInventory() {
        assertEquals(List.of(), CraftInventory.from(null).items());
        assertEquals(List.of(), CraftInventory.from(new InventoryView(0, null)).items());
    }

    /**
     * The premise of the whole item-and-metadata rule, asserted against vanilla instead of asserted
     * about it: an enchanted or renamed item pays for a plain ingredient, and a WORN one does not.
     *
     * <p>{@link CraftFeasibility} compares item and metadata only, and cites vanilla's own matchers as
     * the reason -- neither {@code ShapedRecipes.checkMatch} nor {@code ShapelessRecipes.matches} looks
     * at NBT, while both read {@code getMetadata()}, which for a damageable tool returns its wear. Both
     * halves matter and in opposite directions: if vanilla did reject a tagged item, the check would
     * call a craft affordable that the grid then refuses; if vanilla ignored damage, the check would
     * send the model mining for a rod it is holding.
     *
     * <p>Lives here rather than in the reader's tests because it is this file's premise, and it was the
     * one measurement in the deleted exploration probes that no regression test had taken over.
     */
    @Test
    public void vanillaIgnoresNbtButNotDamageWhichIsWhyThisCheckComparesItemAndMetaOnly() {
        RecipeView rod = CraftBench.find("carrot_on_a_stick", 0);
        IRecipe recipe = CraftBench.recipes().get(rod.index());
        RecipeView.Cell rodCell = rod.cells().stream()
                .filter(c -> "fishing_rod".equals(c.item())).findFirst().orElseThrow();
        int slot = rodCell.col() + rodCell.row() * 3;

        InventoryCrafting tagged = CraftBench.grid(rod, 3, false);
        ItemStack enchantedLooking = new ItemStack(Item.getByNameOrId("fishing_rod"), 1, 0);
        NBTTagCompound tag = new NBTTagCompound();
        tag.setString("display", "a named rod");
        enchantedLooking.setTagCompound(tag);
        tagged.setInventorySlotContents(slot, enchantedLooking);
        assertTrue("vanilla ignores NBT, so a named or enchanted rod still crafts",
                recipe.matches(tagged, null));

        InventoryCrafting worn = CraftBench.grid(rod, 3, false);
        worn.setInventorySlotContents(slot, new ItemStack(Item.getByNameOrId("fishing_rod"), 1, 3));
        assertFalse("vanilla reads damage through getMetadata, so a worn rod does not",
                recipe.matches(worn, null));

        assertTrue("and this check agrees with vanilla on the tagged rod, which carries no meta",
                CraftFeasibility.check(demanding(exact("fishing_rod", 0, 1)),
                        CraftInventory.of(new CraftInventory.Held("fishing_rod", 0, 1))).satisfied());
    }
}
