package net.marcloud.mcp.core.drivers.craft;

import java.util.ArrayList;
import java.util.List;

/**
 * Can the player pay for this recipe, and if not, what exactly is short.
 *
 * <p><b>Why the answer is not a boolean.</b> A model told only "false" has learned nothing it can
 * act on: it cannot tell "you need two more sticks" from "you need a diamond you have never seen".
 * The first is thirty seconds of crafting, the second is an expedition. Every unsatisfied answer
 * therefore NAMES the ingredient and says how many are short, which is the difference between a
 * model that goes and gets the missing item and one that retries the same failing craft.
 *
 * <p><b>What satisfaction means here.</b> Item and metadata only, matching what vanilla's own
 * matchers compare -- {@code ShapedRecipes.checkMatch} and {@code ShapelessRecipes.matches} both
 * test {@code getItem()} then {@code getMetadata()}, and neither looks at NBT or at {@code
 * stackSize}. So an enchanted or renamed item pays for a plain ingredient (verified: a fishing rod
 * carrying a tag compound is still accepted by the carrot-on-a-stick recipe), while a WORN tool does
 * not, because {@code getMetadata()} returns the damage value and damage 3 is not metadata 0.
 *
 * <p>Pure values in, pure values out: no game thread, no world, no window.
 */
public final class CraftFeasibility {

    private CraftFeasibility() {
    }

    /**
     * One ingredient the inventory cannot pay for.
     *
     * @param available how many of the held items could be spent on THIS ingredient after the
     *                  exact-variant ingredients of the same recipe took their share -- not the raw
     *                  total held, because reporting the raw total would let two ingredients each
     *                  claim the same stack and produce a report that says nothing is short while
     *                  the craft still fails
     * @param shortBy   {@code need - available}, always at least 1 for an entry that exists
     */
    public record Missing(String item, int meta, boolean anyMeta, int available, int need,
                          int shortBy) {
    }

    /**
     * @param missing empty exactly when {@code satisfied} is true, and in the recipe's own ingredient
     *                order so the report reads in the same order as the layout
     */
    public record Result(boolean satisfied, List<Missing> missing) {
    }

    /**
     * Checks one craft's worth of demand against what is held.
     *
     * <p><b>Exact variants are allocated before wildcards</b>, and the order is the whole
     * correctness argument. A wildcard ingredient can be paid with any variant, an exact one only
     * with its own, so spending a stack of oak planks on a wildcard square first can leave an exact
     * "oak planks" square unpayable while a spruce stack sat unspent. Serving the pickier ingredient
     * first cannot make the tolerant one unpayable, so this order never invents a shortfall the
     * inventory could have covered. The rejected alternative was one pass in ingredient order, which
     * is cheaper and reports phantom shortfalls on exactly the recipes that mix a wildcard and an
     * exact variant of one item.
     *
     * @param view the recipe to pay for; {@code null} is refused rather than treated as free
     */
    public static Result check(RecipeView view, CraftInventory inv) {
        if (view == null) {
            throw new IllegalArgumentException("no recipe to check");
        }
        List<CraftInventory.Held> pool = pool(inv);
        // Two passes over one mutable pool. Indices are kept aligned with view.demand() so the
        // report can be emitted in the recipe's ingredient order rather than allocation order.
        int[] paid = new int[view.demand().size()];
        for (int pass = 0; pass < 2; pass++) {
            boolean wildcardPass = pass == 1;
            for (int i = 0; i < view.demand().size(); i++) {
                RecipeView.Ingredient want = view.demand().get(i);
                if (want.anyMeta() != wildcardPass) {
                    continue;
                }
                paid[i] = take(pool, want);
            }
        }
        List<Missing> missing = new ArrayList<>();
        for (int i = 0; i < view.demand().size(); i++) {
            RecipeView.Ingredient want = view.demand().get(i);
            if (paid[i] < want.count()) {
                missing.add(new Missing(want.item(), want.meta(), want.anyMeta(), paid[i],
                        want.count(), want.count() - paid[i]));
            }
        }
        return new Result(missing.isEmpty(), List.copyOf(missing));
    }

    /**
     * Spends what it can on one ingredient and returns how much it paid.
     *
     * <p>Mutates the pool: a stack spent here is gone for every LATER INGREDIENT, and that -- not
     * repetition within one ingredient -- is what the deduction buys. This paragraph previously
     * claimed that without it "a recipe wanting four planks would be reported satisfiable by one plank
     * counted four times", which is false: the loop below visits each stack once and is bounded by
     * {@code paid < want.count()}, so one plank pays 1 either way. Deleting the deduction breaks only
     * the cross-ingredient case -- two squares of a recipe both spending the same single plank and the
     * report saying nothing is short. Kept as a correction because the wrong reason had already been
     * copied into a test, which then asserted a property the deduction does not provide.
     */
    private static int take(List<CraftInventory.Held> pool, RecipeView.Ingredient want) {
        int paid = 0;
        for (int i = 0; i < pool.size() && paid < want.count(); i++) {
            CraftInventory.Held held = pool.get(i);
            if (!held.item().equals(want.item())) {
                continue;
            }
            if (!want.anyMeta() && held.meta() != want.meta()) {
                continue;
            }
            int spend = Math.min(held.count(), want.count() - paid);
            paid += spend;
            pool.set(i, new CraftInventory.Held(held.item(), held.meta(), held.count() - spend));
        }
        return paid;
    }

    /** A spendable copy: nulls and empty stacks dropped, so {@link #take} needs no guards. */
    private static List<CraftInventory.Held> pool(CraftInventory inv) {
        List<CraftInventory.Held> out = new ArrayList<>();
        if (inv != null && inv.items() != null) {
            for (CraftInventory.Held h : inv.items()) {
                if (h != null && h.item() != null && h.count() > 0) {
                    out.add(h);
                }
            }
        }
        return out;
    }
}
