package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import org.junit.Test;

/**
 * The whole table, round-tripped through vanilla's own matcher: place what the reader says to place
 * and vanilla must hand back what the reader promised.
 *
 * <p><b>Why this is the load-bearing test of the package.</b> Every other claim about a layout is a
 * claim about what vanilla will do with it, and vanilla is right here in the JVM -- the recipe table
 * loads in under a second in a plain surefire run, no display and no world. So there is no reason to
 * assert against a hand-written expectation of a layout when the real matcher can be asked. A reader
 * that transposed every grid, or emitted the wrong metadata, or lost a cell, cannot pass this: the
 * emitted grid would stop matching the recipe it came from.
 *
 * <p><b>What each of the three properties rules out.</b> Self-match rules out a layout vanilla would
 * refuse. Identity-of-winner rules out a layout vanilla accepts but resolves to a DIFFERENT recipe,
 * which would hand the model an item it did not ask for while every field looked healthy. And the 2x2
 * replacement rules out {@code requiresTable()} being wrong in the direction that matters -- claiming
 * the player's own grid is enough when it is not.
 */
public class EveryEmittedLayoutIsAcceptedByVanillaTest {

    /**
     * Counts derived from the frozen table, asserted as an identity rather than as constants.
     *
     * <p>Every recipe lands in exactly one bucket and the buckets sum to the list length, so a reader
     * that quietly stopped emitting a whole category cannot leave the sum intact. The individual
     * numbers are also pinned, because {@code client/} is frozen vanilla: unlike a test count, this
     * table cannot drift without someone editing the recipe list, and if they do, the failure naming
     * the new number is the correct outcome rather than a nuisance.
     */
    @Test
    public void everyRecipeLandsInExactlyOneBucketAndTheBucketsSumToTheTable() {
        List<IRecipe> list = CraftBench.recipes();
        int shaped = 0;
        int shapeless = 0;
        int unreadable = 0;
        for (int i = 0; i < list.size(); i++) {
            RecipeLayoutReader.Read read = RecipeLayoutReader.read(list.get(i), i);
            if (!read.ok()) {
                unreadable++;
                continue;
            }
            if (read.view().shapeless()) {
                shapeless++;
            } else {
                shaped++;
            }
        }
        assertEquals("the buckets must account for every entry, or a lost category is invisible",
                list.size(), shaped + shapeless + unreadable);
        assertEquals("vanilla 1.8.9 recipe table size", 373, list.size());
        assertEquals("readable shaped", 307, shaped);
        assertEquals("readable shapeless", 58, shapeless);
        assertEquals("unreadable, all of them dynamic; see UnreadableRecipesAreNamedNotGuessedTest",
                8, unreadable);
    }

    /**
     * Fill the grid exactly as the layout instructs, and the recipe it came from accepts it.
     *
     * <p>Asserted per recipe with the index and output in the message, because a bare count of
     * failures would say the reader is broken without saying on what -- and the shape of the failing
     * set (all of them, only the 1xN ones, only the wildcards) is the whole diagnosis.
     */
    @Test
    public void vanillaAcceptsEveryLayoutTheReaderEmits() {
        List<IRecipe> list = CraftBench.recipes();
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            IRecipe recipe = list.get(i);
            RecipeView v = RecipeLayoutReader.read(recipe, i).view();
            if (v == null) {
                continue;
            }
            checked++;
            assertTrue("recipe " + i + " (" + v.output() + "/" + v.outputMeta() + ", " + v.width()
                    + "x" + v.height() + (v.shapeless() ? " shapeless" : " shaped")
                    + ") does not accept the layout the reader emitted for it",
                    recipe.matches(CraftBench.grid(v, 3, false), null));
        }
        assertEquals("every readable recipe must have been round-tripped", 365, checked);
    }

    /**
     * The grid resolves to THIS recipe, not merely to some recipe.
     *
     * <p><b>Why identity and not just the output.</b> Vanilla resolves a filled grid by scanning the
     * list and taking the first match, which {@link RecipeView} documents as the reason a lower index
     * wins. Comparing only the output item would pass if an earlier recipe with the same output stole
     * the grid, and the interesting failure is exactly that. Comparing the index makes the claim the
     * document actually makes.
     *
     * <p>O(table^2) on purpose -- 373 x 365 matches run in well under a second, and the cheaper
     * version of this test is the one that cannot see a tiebreak.
     */
    @Test
    public void theFirstRecipeMatchingAnEmittedLayoutIsTheOneItCameFrom() {
        List<IRecipe> list = CraftBench.recipes();
        List<String> stolen = new ArrayList<>();
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            RecipeView v = RecipeLayoutReader.read(list.get(i), i).view();
            if (v == null) {
                continue;
            }
            checked++;
            InventoryCrafting grid = CraftBench.grid(v, 3, false);
            for (int j = 0; j < i; j++) {
                if (list.get(j).matches(grid, null)) {
                    stolen.add("idx=" + i + " (" + v.output() + "/" + v.outputMeta()
                            + ") is resolved by earlier idx=" + j);
                    break;
                }
            }
        }
        assertEquals("no emitted layout may be claimed by an earlier recipe: " + stolen,
                List.of(), stolen);
        assertEquals(365, checked);
    }

    /**
     * What the model is promised is what {@code findMatchingRecipe} produces: name, variant and count.
     *
     * <p>The count is the part a layout test alone would miss. {@code outputCount} comes from the
     * declared output stack, and a model planning "craft 8 sticks" acts on it -- a wrong count is a
     * plan that runs out of materials halfway with nothing in the report explaining why.
     */
    @Test
    public void theResolvedOutputIsExactlyWhatTheViewPromised() {
        List<IRecipe> list = CraftBench.recipes();
        int checked = 0;
        for (int i = 0; i < list.size(); i++) {
            RecipeView v = RecipeLayoutReader.read(list.get(i), i).view();
            if (v == null) {
                continue;
            }
            checked++;
            ItemStack won = CraftingManager.getInstance()
                    .findMatchingRecipe(CraftBench.grid(v, 3, false), null);
            assertNotNull("idx=" + i + " promised " + v.output() + " but the grid resolves to nothing",
                    won);
            String where = "idx=" + i + " " + v.output() + "/" + v.outputMeta() + "x"
                    + v.outputCount();
            assertEquals(where + ": output item", v.output(),
                    RecipeLayoutReader.itemName(won.getItem()));
            assertEquals(where + ": output variant", v.outputMeta(), won.getMetadata());
            assertEquals(where + ": output count", v.outputCount(), won.stackSize);
        }
        assertEquals(365, checked);
    }

    /**
     * A recipe that says it does not need a table is craftable in the player's own 2x2 grid.
     *
     * <p>This is the direction that costs something. {@code requiresTable()} reads {@code width > 2
     * || height > 2}, and if that were wrong the model would fill its 2x2, get no result, and have no
     * way to tell "missing ingredient" from "grid too small" -- the exact confusion
     * {@link RecipeView#requiresTable()} exists to prevent. Placing the same layout into a real 2x2
     * {@code InventoryCrafting} and asking vanilla settles it.
     */
    @Test
    public void everyRecipeThatDeniesNeedingATableFitsThePlayerTwoByTwoGrid() {
        List<IRecipe> list = CraftBench.recipes();
        int fitsTwo = 0;
        for (int i = 0; i < list.size(); i++) {
            IRecipe recipe = list.get(i);
            RecipeView v = RecipeLayoutReader.read(recipe, i).view();
            if (v == null || v.requiresTable()) {
                continue;
            }
            fitsTwo++;
            assertTrue("idx=" + i + " (" + v.output() + ", " + v.width() + "x" + v.height()
                    + ") says it needs no table, but vanilla refuses it in a 2x2 grid",
                    recipe.matches(CraftBench.grid(v, 2, false), null));
        }
        assertEquals("recipes craftable without a table", 139, fitsTwo);
        assertTrue("a table-free recipe must exist for this test to mean anything", fitsTwo > 0);
    }

    /**
     * The reader only ever emits the two exact classes it claims to read.
     *
     * <p>Cheap, and it pins the fail-closed rule from the other side: {@code RecipeLayoutReader}
     * refuses subclasses because they add conditions the layout cannot express, so a view whose recipe
     * is a subclass would mean that refusal stopped working.
     */
    @Test
    public void onlyTheTwoExactRecipeClassesEverProduceAView() {
        List<IRecipe> list = CraftBench.recipes();
        for (int i = 0; i < list.size(); i++) {
            IRecipe recipe = list.get(i);
            RecipeView v = RecipeLayoutReader.read(recipe, i).view();
            if (v == null) {
                continue;
            }
            Class<?> cls = recipe.getClass();
            assertTrue("idx=" + i + " produced a view from " + cls.getSimpleName(),
                    cls == ShapedRecipes.class || cls == ShapelessRecipes.class);
            assertEquals("idx=" + i + ": the shapeless flag must follow the class",
                    cls == ShapelessRecipes.class, v.shapeless());
        }
    }
}
