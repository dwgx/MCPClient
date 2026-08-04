package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;
import org.junit.Test;

/**
 * The fail-closed half: what the reader refuses, and that a refusal still says something useful.
 *
 * <p><b>Why refusing loudly matters more than refusing.</b> The tempting behaviour for an unreadable
 * recipe is to assume a 3x3 and carry on, which emits a layout that looks authoritative and is wrong;
 * the model then spends real materials on the wrong squares and gets nothing, with no field in the
 * report hinting at why. So every refusal here is checked for two things: that it refuses, and that it
 * still carries the output NAME when the name was knowable. A caller searching for "map" has to be
 * told that one of the map recipes is dynamic -- told "no recipe", it would conclude maps are
 * uncraftable.
 *
 * <p>The synthetic cases exist because the real table cannot reach some branches. Vanilla has no
 * exact-{@code ShapedRecipes} with a zero output count and no recipe with an unreadable layout field,
 * so those guards would be untested claims -- and an untested guard in a fail-closed design is the
 * one that is broken when it is finally needed.
 */
public class UnreadableRecipesAreNamedNotGuessedTest {

    /** A subclass with a perfectly readable shape, which must still be refused for being a subclass. */
    private static final class SneakySubclass extends ShapedRecipes {
        SneakySubclass(ItemStack out) {
            super(1, 1, new ItemStack[]{new ItemStack(Items.stick)}, out);
        }
    }

    /**
     * Exactly eight recipes are unreadable, and every one of them is a dynamic recipe class.
     *
     * <p>The reasons are asserted as a set keyed by class name, not as a count: a count would still
     * pass if a readable recipe became unreadable while a dynamic one silently became readable, which
     * is the failure with the highest cost -- a guessed layout for a recipe whose extra conditions the
     * layout cannot express.
     */
    @Test
    public void theOnlyUnreadableRecipesAreTheDynamicOnes() {
        List<IRecipe> list = CraftBench.recipes();
        Map<String, String> refused = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            RecipeLayoutReader.Read read = RecipeLayoutReader.read(list.get(i), i);
            if (read.ok()) {
                continue;
            }
            refused.put(list.get(i).getClass().getSimpleName() + "@" + i, read.unsupported());
            assertEquals("a refusal must keep the index it was asked about", i, read.index());
            assertNull("a refusal must not also carry a view", read.view());
        }
        assertEquals("the eight dynamic recipes, by class and list position",
                List.of("RecipesArmorDyes@0", "RecipeFireworks@1", "RecipeAddPattern@2",
                        "RecipeBookCloning@70", "RecipesMapCloning@71", "RecipesMapExtending@72",
                        "RecipeRepairItem@216", "RecipeDuplicatePattern@280"),
                List.copyOf(refused.keySet()));
        for (Map.Entry<String, String> e : refused.entrySet()) {
            assertNotNull(e.getKey() + " must carry a reason", e.getValue());
            assertFalse(e.getKey() + " must carry a non-empty reason", e.getValue().isBlank());
        }
    }

    /**
     * Seven of the eight have no fixed output at all, so their reason cannot name an item -- and the
     * one that can name one does.
     *
     * <p>{@code RecipesMapExtending} is the interesting entry: it extends {@code ShapedRecipes} with a
     * readable paper-around-map shape, so it is refused for adding conditions rather than for being
     * unreadable, and its output name survives the refusal.
     */
    @Test
    public void aRefusalCarriesTheOutputNameWheneverTheNameIsKnowable() {
        List<IRecipe> list = CraftBench.recipes();
        List<String> named = new ArrayList<>();
        int nameless = 0;
        for (int i = 0; i < list.size(); i++) {
            RecipeLayoutReader.Read read = RecipeLayoutReader.read(list.get(i), i);
            if (read.ok()) {
                continue;
            }
            if (read.output() == null) {
                nameless++;
                assertNull("a recipe with no fixed output has no metadata to report either",
                        list.get(i).getRecipeOutput());
                assertTrue("idx=" + i + " must say the output is dynamic, not merely 'unsupported': "
                        + read.unsupported(), read.unsupported().contains("no fixed output"));
            } else {
                named.add(read.output() + "@" + i);
            }
        }
        assertEquals("the dynamic recipes with no fixed output", 7, nameless);
        assertEquals("the one refusal that can still name what it makes", List.of("map@72"), named);
    }

    /**
     * A subclass is refused even though its own fields read cleanly, and the reason says so.
     *
     * <p>Asserted against a synthetic subclass rather than against {@code RecipesMapExtending}, so the
     * claim is about the rule and not about one recipe: a future vanilla subclass with a readable
     * shape must be refused too.
     */
    @Test
    public void aSubclassWithAReadableShapeIsStillRefused() {
        CraftBench.recipes();
        SneakySubclass sneaky = new SneakySubclass(new ItemStack(Items.stick, 1));
        RecipeLayoutReader.Read read = RecipeLayoutReader.read(sneaky, 900);

        assertFalse("a readable shape is not a licence to emit a subclass layout", read.ok());
        assertEquals("stick", read.output());
        assertTrue("the reason must name the class, so a caller can see which one: "
                + read.unsupported(), read.unsupported().contains("SneakySubclass"));
        assertTrue("the reason must say what is missing, not just that it is a subclass: "
                + read.unsupported(), read.unsupported().contains("conditions"));
    }

    /**
     * An output count decided at craft time is refused separately from the subclass rule.
     *
     * <p>Unreachable from the real table -- vanilla's only zero-count recipe is also a subclass, so
     * the subclass check fires first and this branch never runs. The synthetic case is the only way to
     * prove the guard works, and the reason is asserted to name the count so it cannot be confused
     * with the subclass refusal.
     */
    @Test
    public void anExactRecipeWhoseOutputCountIsZeroIsRefusedForThatReason() {
        CraftBench.recipes();
        ShapedRecipes zero = new ShapedRecipes(1, 1, new ItemStack[]{new ItemStack(Items.stick)},
                new ItemStack(Items.stick, 0));
        RecipeLayoutReader.Read read = RecipeLayoutReader.read(zero, 901);

        assertFalse("a recipe that cannot say how many it yields is not readable", read.ok());
        assertEquals("stick", read.output());
        assertTrue("the reason must say the count is the problem: " + read.unsupported(),
                read.unsupported().contains("craft time"));
        assertTrue("and must quote the declared count: " + read.unsupported(),
                read.unsupported().contains("stackSize 0"));
    }

    /**
     * A shape too big for a 3x3 grid is refused with its own dimensions in the reason.
     *
     * <p>Vanilla has no such recipe, and a caller shown a 4x1 layout would place four ingredients in a
     * row that no grid has.
     */
    @Test
    public void aShapeThatCannotFitTheGridIsRefusedWithItsDimensions() {
        CraftBench.recipes();
        ItemStack[] four = new ItemStack[4];
        for (int i = 0; i < 4; i++) {
            four[i] = new ItemStack(Items.stick);
        }
        RecipeLayoutReader.Read read = RecipeLayoutReader.read(
                new ShapedRecipes(4, 1, four, new ItemStack(Items.bowl, 1)), 902);

        assertFalse(read.ok());
        assertTrue("the reason must quote the shape that does not fit: " + read.unsupported(),
                read.unsupported().contains("4x1"));
    }

    /**
     * A layout array shorter than its declared shape is refused rather than indexed past its end.
     *
     * <p>Without the guard the read would throw {@code ArrayIndexOutOfBounds} out of a method whose
     * contract is to return a reason -- and the caller, which is a tool handler, would turn an
     * unreadable recipe into a failed request instead of a report.
     */
    @Test
    public void aShortLayoutArrayIsRefusedRatherThanIndexedPastItsEnd() {
        CraftBench.recipes();
        RecipeLayoutReader.Read read = RecipeLayoutReader.read(
                new ShapedRecipes(2, 2, new ItemStack[]{new ItemStack(Items.stick)},
                        new ItemStack(Items.bowl, 1)), 903);

        assertFalse(read.ok());
        assertTrue("the reason must quote both the array length and the shape: " + read.unsupported(),
                read.unsupported().contains("1 entries") && read.unsupported().contains("2x2"));
    }

    /**
     * When the private field cannot be resolved at all, the read reports it and names the fields.
     *
     * <p>This is the branch the whole class exists for, and the reason
     * {@code readShaped}/{@code readShapeless} take their fields as arguments: a static that resolved
     * successfully cannot be un-resolved, so with the fields hard-coded "fails closed on a renamed
     * field" would be a claim no test could reach. Passing null is the simulation.
     */
    @Test
    public void anUnresolvableLayoutFieldFailsClosedAndNamesTheFields() {
        CraftBench.recipes();
        ItemStack out = new ItemStack(Items.bowl, 1);
        ShapedRecipes shaped = new ShapedRecipes(1, 1, new ItemStack[]{new ItemStack(Items.stick)},
                out);

        RecipeLayoutReader.Read noWidth = RecipeLayoutReader.readShaped(shaped, 904, "bowl", out,
                null, null, null);
        assertFalse("a missing field must never become a guessed shape", noWidth.ok());
        assertTrue("the reason must name the fields a future reader has to look for: "
                + noWidth.unsupported(), noWidth.unsupported().contains("recipeWidth")
                && noWidth.unsupported().contains("recipeHeight")
                && noWidth.unsupported().contains("recipeItems"));

        ShapelessRecipes shapelessRecipe = new ShapelessRecipes(out,
                List.of(new ItemStack(Items.stick)));
        RecipeLayoutReader.Read noItems = RecipeLayoutReader.readShapeless(shapelessRecipe, 905,
                "bowl", out, null);
        assertFalse(noItems.ok());
        assertTrue("the shapeless reason must name its own field: " + noItems.unsupported(),
                noItems.unsupported().contains("recipeItems"));
    }

    /**
     * A shapeless recipe wanting more inputs than a grid has squares is refused with both numbers.
     *
     * <p>Ten inputs cannot be placed in nine squares, and the packing arithmetic would otherwise
     * silently drop the tenth -- emitting a layout that is short one ingredient and matches nothing.
     */
    @Test
    public void aShapelessRecipeWantingMoreInputsThanTheGridHasIsRefused() {
        CraftBench.recipes();
        List<ItemStack> ten = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ten.add(new ItemStack(Items.stick));
        }
        RecipeLayoutReader.Read read = RecipeLayoutReader.read(
                new ShapelessRecipes(new ItemStack(Items.bowl, 1), ten), 906);

        assertFalse(read.ok());
        assertTrue("the reason must quote the demand and the capacity: " + read.unsupported(),
                read.unsupported().contains("10 inputs") && read.unsupported().contains("9 squares"));
    }

    /** A null entry in the table is a reason, not a crash: the reader is called in a loop over it. */
    @Test
    public void aNullRecipeEntryIsARefusalRatherThanAnException() {
        RecipeLayoutReader.Read read = RecipeLayoutReader.read(null, 907);

        assertFalse(read.ok());
        assertEquals(907, read.index());
        assertNull(read.output());
        assertTrue(read.unsupported().contains("null recipe"));
    }
}
