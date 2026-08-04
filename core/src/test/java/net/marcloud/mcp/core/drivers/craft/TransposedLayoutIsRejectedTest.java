package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import org.junit.Test;

/**
 * Teeth for the trap that a passing test cannot see: a row/column transposition.
 *
 * <p><b>The trap.</b> {@code InventoryCrafting.getStackInRowAndColumn(int row, int column)} is
 * MISNAMED. Its body is {@code getStackInSlot(row + column * inventoryWidth)} and it bounds-checks
 * its first argument against {@code inventoryWidth}, so the first argument is the COLUMN and the
 * second is the ROW. {@code ShapedRecipes.checkMatch} is written against the behaviour rather than
 * the names -- it passes its x loop first and indexes {@code recipeItems[k + l * recipeWidth]} with k
 * from that same x loop. Any reader that trusts the parameter names instead transposes every recipe
 * it emits, compiles clean, and reads plausibly.
 *
 * <p><b>Why a symmetric recipe cannot catch it.</b> A crafting table is four planks in a 2x2 square:
 * its transpose is itself, so it matches either way and proves nothing. The recipes below are
 * asymmetric under transposition, so a swap changes the answer.
 *
 * <p><b>What a mistake would have cost.</b> Nothing throws. The model receives a layout that looks
 * authoritative, places real materials into the wrong squares, gets no output, and has nothing in the
 * report to hint at why -- so it retries, and spends the materials again.
 */
public class TransposedLayoutIsRejectedTest {

    /**
     * The trap itself, asserted directly against the frozen client rather than described in a
     * comment: if a future reader "fixes" the misnaming, this fails first and explains why.
     */
    @Test
    public void vanillaGetStackInRowAndColumnTakesColumnFirstDespiteItsParameterNames() {
        CraftBench.recipes();
        // 3 wide, 1 tall on purpose: a square grid would hide a transposition here too.
        InventoryCrafting inv = new InventoryCrafting(new CraftBench.Bench(), 3, 3);
        ItemStack marker = new ItemStack(Item.getByNameOrId("stick"), 1, 0);
        inv.setInventorySlotContents(2, marker); // row 0, col 2

        // Named (row=0, column=2) this would be the same square. It is not: it is row 2, col 0.
        assertNull("first argument is the column, so (0,2) is row 2 col 0 and holds nothing",
                inv.getStackInRowAndColumn(0, 2));
        assertEquals("(col=2, row=0) is the square that was filled", marker,
                inv.getStackInRowAndColumn(2, 0));
    }

    /**
     * A bucket is 3x2 and asymmetric: iron at (0,0) and (0,2) with one at (1,1). Transposed it
     * becomes 2x3, so both the shape and the cell contents change.
     */
    @Test
    public void anAsymmetricRecipeMatchesItsEmittedLayoutAndRejectsTheTranspose() {
        RecipeView bucket = CraftBench.find("bucket", 0);
        assertEquals(3, bucket.width());
        assertEquals(2, bucket.height());
        assertNotNull("iron at the top-left", CraftBench.at(bucket, 0, 0));
        assertNotNull("iron at the top-right, three columns wide", CraftBench.at(bucket, 0, 2));
        assertNotNull("iron below the middle", CraftBench.at(bucket, 1, 1));
        assertNull("the top middle is empty, which is what makes this a V", CraftBench.at(bucket, 0, 1));

        IRecipe recipe = CraftBench.recipes().get(bucket.index());
        assertTrue("vanilla accepts the emitted layout",
                recipe.matches(CraftBench.grid(bucket, 3, false), null));
        assertFalse("vanilla rejects the transpose, so the emitted orientation is not a coin flip",
                recipe.matches(CraftBench.grid(bucket, 3, true), null));
    }

    /**
     * A stick is 1 wide and 2 tall. Transposed it is two planks side by side, which no 1x2 pattern
     * can cover at any offset -- the cheapest possible statement of the same trap.
     */
    @Test
    public void aVerticalTwoCellRecipeRejectsTheHorizontalPlacement() {
        RecipeView stick = CraftBench.find("stick", 0);
        assertEquals("one column", 1, stick.width());
        assertEquals("two rows", 2, stick.height());
        assertEquals(0, CraftBench.at(stick, 1, 0).col());

        IRecipe recipe = CraftBench.recipes().get(stick.index());
        assertTrue(recipe.matches(CraftBench.grid(stick, 3, false), null));
        assertFalse("two planks side by side are not a stick",
                recipe.matches(CraftBench.grid(stick, 3, true), null));
    }

    /**
     * The whole table at once, which is what makes the claim more than three examples: EVERY shaped
     * recipe whose layout is asymmetric under transposition rejects its transpose.
     *
     * <p>Shapeless recipes are separated rather than skipped silently, because for them the transpose
     * matching is CORRECT -- {@code ShapelessRecipes.matches} sweeps the whole grid and ignores
     * position. Folding them in would have made the assertion unfalsifiable in the one direction it
     * exists to test. They are then asserted in their own direction: every one of them MUST accept its
     * transpose, so a packing that dropped a cell instead of moving it fails here rather than being
     * counted as a shapeless recipe behaving normally.
     */
    @Test
    public void everyAsymmetricShapedRecipeInTheTableRejectsItsTranspose() {
        List<IRecipe> list = CraftBench.recipes();
        int checked = 0;
        int shapelessAccepted = 0;
        int symmetricShaped = 0;
        int symmetricShapeless = 0;
        for (int i = 0; i < list.size(); i++) {
            IRecipe recipe = list.get(i);
            RecipeView v = RecipeLayoutReader.read(recipe, i).view();
            if (v == null) {
                continue;
            }
            if (transposeInvariant(v)) {
                if (v.shapeless()) {
                    symmetricShapeless++;
                } else {
                    symmetricShaped++;
                }
                continue;
            }
            boolean transposeMatches = recipe.matches(CraftBench.grid(v, 3, true), null);
            if (recipe.getClass() == ShapelessRecipes.class) {
                assertTrue("shapeless idx=" + i + " (" + v.output() + ") ignores position, so its"
                        + " transpose must still match; a rejection would mean the packing lost a"
                        + " cell rather than moving it", transposeMatches);
                shapelessAccepted++;
                continue;
            }
            assertEquals("only ShapedRecipes should be left here", ShapedRecipes.class,
                    recipe.getClass());
            checked++;
            assertFalse("recipe " + i + " (" + v.output() + "/" + v.outputMeta() + ", " + v.width()
                    + "x" + v.height() + ") accepts its own transpose, so its emitted orientation"
                    + " carries no information", transposeMatches);
        }
        // CORRECTION. This assertion was written as `checked > 200` with a comment claiming 268
        // asymmetric shaped recipes had been measured. It failed at exactly 200 -- and 268 turned out
        // to be 307 (readable shaped) minus 39 (asymmetric SHAPELESS), one bucket's count subtracted
        // from another's. The number was arithmetic wearing the word "measured", and the floor it
        // produced sat one recipe above the truth. The kept lesson: a count no test derives is a
        // count nobody checked.
        //
        // Pinned as a partition instead. Each side sums to a bucket total that
        // EveryEmittedLayoutIsAcceptedByVanillaTest independently ties to list.size(), so a reader
        // that stopped emitting layouts moves recipes out of `checked` and breaks the sum -- it cannot
        // pass by checking nothing, which is what the floor was reaching for.
        assertEquals("asymmetric + symmetric must account for every readable shaped recipe",
                307, checked + symmetricShaped);
        assertEquals("...and likewise for shapeless", 58, shapelessAccepted + symmetricShapeless);
        assertEquals("asymmetric shaped recipes, all of which reject their transpose above",
                200, checked);
        assertEquals("shaped recipes whose layout is its own transpose, so they prove nothing",
                107, symmetricShaped);
        assertEquals("asymmetric shapeless recipes, all of which accept their transpose above",
                39, shapelessAccepted);
        assertEquals(19, symmetricShapeless);
    }

    /** True when swapping row and col leaves the cell set identical, so the transpose proves nothing. */
    private static boolean transposeInvariant(RecipeView v) {
        for (RecipeView.Cell c : v.cells()) {
            RecipeView.Cell mirror = CraftBench.at(v, c.col(), c.row());
            if (mirror == null || !mirror.item().equals(c.item()) || mirror.meta() != c.meta()
                    || mirror.anyMeta() != c.anyMeta()) {
                return false;
            }
        }
        return true;
    }
}
