package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Teeth for {@link Craft}, the public face that was missing.
 *
 * <p>Everything below runs against vanilla's REAL recipe table, which initialises headlessly -- the
 * measurement that the first attempt at this package never made, and whose absence was the only
 * reason it stopped. So these assert concrete recipes rather than shapes: a test that only checked
 * "some list came back non-empty" would pass against a lookup that returned the wrong item entirely.
 */
public class CraftNamesRecipesAndShortfallsTest {

    @BeforeClass
    public static void bootVanilla() {
        // Touching the list is what forces CraftingManager's static instance, and with it
        // Bootstrap's block/item registration. Explicit here so a failure to boot reads as a setup
        // problem rather than as "there is no stick recipe".
        assertFalse("vanilla's recipe table must initialise headlessly",
                CraftBench.recipes().isEmpty());
    }

    @Test
    public void aStickHasARecipeAndItsOutputIsAStick() {
        Craft.Result r = Craft.recipesFor("stick");
        assertFalse("vanilla has a stick recipe", r.recipes().isEmpty());
        for (RecipeView v : r.recipes()) {
            assertEquals("every recipe returned for a name must produce THAT item; a lookup that "
                    + "matched loosely would hand the caller a bill of materials for something else",
                    "stick", v.output());
        }
    }

    @Test
    public void theNamespaceIsOptionalJustAsFindBlockAccepts() {
        assertEquals("a name copied out of another tool's output must work unchanged, whichever form "
                        + "that tool used",
                Craft.recipesFor("stick").recipes().size(),
                Craft.recipesFor("minecraft:stick").recipes().size());
        assertEquals("and case must not decide it either",
                Craft.recipesFor("stick").recipes().size(),
                Craft.recipesFor("Minecraft:STICK").recipes().size());
    }

    @Test
    public void anUnknownNameIsAnEmptyResultRatherThanAnError() {
        Craft.Result r = Craft.recipesFor("definitely_not_an_item");
        assertTrue("no recipes", r.recipes().isEmpty());
        assertTrue("and nothing unsupported either -- there is simply no such output",
                r.unsupported().isEmpty());
    }

    @Test
    public void anEmptyNameSaysSoRatherThanScanningTheWholeTable() {
        Craft.Result r = Craft.recipesFor("   ");
        assertTrue(r.recipes().isEmpty());
        assertFalse("an empty name is a caller mistake and must be named, not answered with a "
                + "silent empty list that reads as 'no such recipe'", r.unsupported().isEmpty());
    }

    @Test
    public void recipeOrderIsVanillasOwnBecauseVanillaTakesTheFirstMatch() {
        Craft.Result r = Craft.recipesFor("stick");
        List<RecipeView> vs = r.recipes();
        for (int i = 1; i < vs.size(); i++) {
            assertTrue("indices must ascend: vanilla's findMatchingRecipe scans its list in order "
                            + "and takes the FIRST match, so re-sorting would hand the caller a "
                            + "preference the game does not share",
                    vs.get(i - 1).index() < vs.get(i).index());
        }
    }

    /**
     * The planning half: a full inventory crafts, and a short one says what is missing BY NAME.
     *
     * <p>Asserting the name and the count, not just {@code canCraft() == false}. A boolean is the one
     * answer a caller cannot act on -- it cannot go and fetch "false" -- and reporting the shortfall
     * is the part of {@link CraftFeasibility} that took the work.
     */
    @Test
    public void aShortInventoryNamesWhatIsMissingRatherThanJustFailing() {
        Craft.Plan empty = Craft.plan("stick", CraftInventory.of());
        assertFalse("nothing in hand, nothing craftable", empty.canCraft());
        assertNull(empty.craftable());
        assertFalse("and every candidate must carry its own bill", empty.blocked().isEmpty());

        boolean namesAnIngredient = false;
        for (Craft.Shortfall s : empty.blocked()) {
            for (CraftFeasibility.Missing m : s.missing()) {
                assertTrue("a shortfall must state how many are needed", m.need() > 0);
                assertEquals("and how many are held, which for an empty inventory is none",
                        0, m.available());
                if (m.item() != null && !m.item().isEmpty()) {
                    namesAnIngredient = true;
                }
            }
        }
        assertTrue("at least one shortfall must NAME the ingredient; a caller that cannot read the "
                + "name has to guess what to go and get", namesAnIngredient);
    }

    @Test
    public void anInventoryHoldingTheIngredientsCraftsAndNamesTheRecipe() {
        // Planks are the stick recipe's input. Two of them, which is what the shaped recipe needs.
        Craft.Plan plan = Craft.plan("stick",
                CraftInventory.of(new CraftInventory.Held("planks", 0, 8)));
        assertTrue("holding planks must make a stick craftable: " + describe(plan),
                plan.canCraft());
        assertNotNull(plan.craftable());
        assertEquals("and the chosen recipe must be the one for the thing asked for",
                "stick", plan.craftable().output());
    }

    /**
     * A recipe the reader cannot lay out must be REPORTED, not dropped.
     *
     * <p>Vanilla has several dynamic recipes with no fixed grid and, for most, a null output --
     * armour dyeing, map and book cloning, repair, banners, fireworks. Silently skipping them makes
     * "there is no recipe" and "there is a recipe we cannot express" the same answer, and a caller
     * would go hunting for another route to something it could in fact make by hand.
     */
    @Test
    public void aRecipeThatCannotBeLaidOutIsNamedRatherThanSkipped() {
        int namedSomewhere = 0;
        for (String name : List.of("leather_helmet", "written_book", "map", "banner",
                "fireworks", "shield")) {
            namedSomewhere += Craft.recipesFor(name).unsupported().size();
        }
        assertTrue("at least one of vanilla's dynamic recipes must surface as unsupported with a "
                        + "reason; zero here means they are being dropped on the floor",
                namedSomewhere > 0);
    }

    private static String describe(Craft.Plan plan) {
        StringBuilder sb = new StringBuilder("craftable=" + plan.canCraft());
        for (Craft.Shortfall s : plan.blocked()) {
            for (CraftFeasibility.Missing m : s.missing()) {
                sb.append("; short ").append(m.item()).append(' ')
                        .append(m.available()).append('/').append(m.need());
            }
        }
        return sb.toString();
    }
}
