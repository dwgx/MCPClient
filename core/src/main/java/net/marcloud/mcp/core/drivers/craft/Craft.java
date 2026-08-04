package net.marcloud.mcp.core.drivers.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;

/**
 * The craft package's public face: name a thing, get the ways to make it.
 *
 * <p>Exists because there was NO path from an item name to a {@link RecipeView}. The pieces were all
 * here -- {@code RecipeLayoutReader} resolves one {@code IRecipe} into a layout, {@link
 * CraftFeasibility} answers what an inventory is short -- but the reader is package-private and takes
 * an already-chosen recipe, so the caller had to hold vanilla's recipe list and index into it. Tests
 * did exactly that through their own helper. Nothing outside this package could, which is why the
 * whole capability was unreachable from the tool layer despite being finished and tested.
 *
 * <p>Pure and headless: no game thread, no world, no window. Vanilla's recipe table is a static list
 * built during the client's own startup ({@code CraftingManager}'s static instance, forced via
 * {@code Bootstrap.register}), so this works in a plain unit-test JVM -- measured, 373 recipes in
 * under a second. That is worth stating because the first attempt at this work assumed the opposite
 * and stopped without running the experiment.
 *
 * <p>What this deliberately does NOT do is craft anything. Executing a craft needs a live {@link
 * CraftWindow} over the open container, and no implementation of that interface exists outside the
 * tests yet.
 */
public final class Craft {

    private Craft() {
    }

    /**
     * Every way to make {@code name}, nearest-to-vanilla-first, with the unsupported ones named.
     *
     * <p>Order is vanilla's own list order, and that is load-bearing rather than incidental:
     * {@code CraftingManager.findMatchingRecipe} scans the same list and takes the FIRST match, so a
     * lower index wins when two recipes could both accept one grid. Re-sorting here would hand a
     * caller a preference the game does not share.
     *
     * @param name registry name, namespace optional ({@code "stick"} or {@code "minecraft:stick"}),
     *             matching how {@code find_block} accepts either so a name read out of one tool can
     *             be fed straight into another
     */
    public static Result recipesFor(String name) {
        String wanted = strip(name);
        if (wanted.isEmpty()) {
            return new Result(List.of(), List.of("no item name was given"));
        }
        List<RecipeView> made = new ArrayList<>();
        List<String> unsupported = new ArrayList<>();
        List<IRecipe> list = CraftingManager.getInstance().getRecipeList();
        for (int i = 0; i < list.size(); i++) {
            RecipeLayoutReader.Read read = RecipeLayoutReader.read(list.get(i), i);
            if (!wanted.equals(strip(read.output()))) {
                continue;
            }
            // Reported rather than skipped. A recipe this package cannot express is a fact the caller
            // needs: silently dropping it makes "no recipe" and "a recipe we cannot lay out" identical,
            // and the caller would go looking for a different route to something it can in fact make
            // by hand. Same reasoning as the absence-is-load-bearing convention in the world payload.
            if (read.view() == null) {
                unsupported.add("recipe #" + read.index() + " for " + read.output() + ": "
                        + read.unsupported());
            } else {
                made.add(read.view());
            }
        }
        return new Result(List.copyOf(made), List.copyOf(unsupported));
    }

    /**
     * The first recipe for {@code name} the given inventory can actually satisfy, and what is short
     * for the rest.
     *
     * <p>Reports EVERY candidate's shortfall rather than only the best one, because "you cannot make
     * this" is not actionable while "you have the planks but need one more stick" is. A caller
     * choosing between two routes needs both bills.
     */
    public static Plan plan(String name, CraftInventory inv) {
        Result r = recipesFor(name);
        List<Shortfall> blocked = new ArrayList<>();
        for (RecipeView v : r.recipes()) {
            CraftFeasibility.Result f = CraftFeasibility.check(v, inv);
            if (f.satisfied()) {
                return new Plan(v, List.copyOf(blocked), r.unsupported());
            }
            blocked.add(new Shortfall(v, f.missing()));
        }
        return new Plan(null, List.copyOf(blocked), r.unsupported());
    }

    /** Namespace stripped and lowercased, so {@code minecraft:Stick} and {@code stick} agree. */
    private static String strip(String name) {
        if (name == null) {
            return "";
        }
        String s = name.trim().toLowerCase(Locale.ROOT);
        int colon = s.indexOf(':');
        return colon < 0 ? s : s.substring(colon + 1);
    }

    /**
     * Recipes for one output, plus the ones this package could not lay out.
     *
     * @param recipes     usable layouts, in vanilla's own list order
     * @param unsupported one line per recipe that exists but cannot be expressed, with the reason.
     *                    Non-empty here plus an empty {@code recipes} means "the game can make this
     *                    and we cannot tell you how", which is a different answer from "there is no
     *                    recipe" and the caller must be able to tell them apart.
     */
    public record Result(List<RecipeView> recipes, List<String> unsupported) {
    }

    /** One candidate recipe and what the inventory lacks for it. */
    public record Shortfall(RecipeView recipe, List<CraftFeasibility.Missing> missing) {
    }

    /**
     * The outcome of planning: what can be made now, or what every candidate was short.
     *
     * @param craftable the first satisfiable recipe in vanilla's order, or null if none is
     * @param blocked   every candidate tried before that one, each with its own bill of what is short
     */
    public record Plan(RecipeView craftable, List<Shortfall> blocked, List<String> unsupported) {

        /** True when something can be made right now. */
        public boolean canCraft() {
            return craftable != null;
        }
    }
}
