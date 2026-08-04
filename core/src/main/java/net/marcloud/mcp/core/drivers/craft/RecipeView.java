package net.marcloud.mcp.core.drivers.craft;

import java.util.List;

/**
 * One recipe, flattened to values an LLM can read and act on.
 *
 * <p><b>Why reference-free.</b> Everything here is ints, Strings and Lists, the same discipline
 * {@link net.marcloud.mcp.core.drivers.world.InventoryView} follows. A recipe's real ingredients are
 * live {@code ItemStack} objects owned by the vanilla recipe table -- the very objects the game
 * matches against. Handing one of those out would let a caller mutate the recipe table itself
 * ({@code ItemStack} is mutable: {@code stackSize} and the damage value are both writable fields),
 * and a mutated table is a corruption that survives until the client restarts and shows up as
 * recipes that silently stop working. Copying to values at the boundary makes that impossible.
 *
 * <p><b>Coordinates.</b> {@code cells} carry (row, col) with (0,0) at the top-left of the recipe's
 * own bounding box, not of the crafting grid. Deliberately NOT a slot index: the slot number for a
 * cell depends on the width of the container it is being placed into -- 2 for the player's inventory
 * grid, 3 for a crafting table -- so {@code row * containerWidth + col} is the caller's arithmetic to
 * do once it knows which window is open. Emitting a slot index here would have baked in an
 * assumption about a window this class cannot see.
 *
 * <p><b>Anchoring.</b> The layout is the recipe's natural (unmirrored) orientation, anchored at the
 * top-left of the grid. Vanilla's matcher slides the pattern over every offset and also accepts the
 * horizontal mirror, so the top-left anchor is one of several placements it would accept -- it is
 * chosen because it is the only one that is always in bounds.
 *
 * @param index       position in the vanilla recipe list. Vanilla resolves a filled grid by scanning
 *                    that list in order and taking the first match, so a lower index wins over a
 *                    higher one when two recipes could both accept the same grid. Valid only for the
 *                    lifetime of the client -- it is a list position, not an id.
 * @param output      output item registry name, namespace stripped ("stick", not "minecraft:stick")
 * @param outputMeta  output metadata, which distinguishes variants that share one name: every
 *                    {@code stone_slab} recipe is called "stone_slab" and only the meta says which
 * @param outputCount how many the single craft yields
 * @param shapeless   true when position does not matter, in which case {@code cells} is one valid
 *                    arrangement rather than the required one
 * @param width       width of the recipe's bounding box, 1..3
 * @param height      height of the recipe's bounding box, 1..3
 * @param cells       occupied cells only; empty cells of the bounding box are absent, not null
 *                    entries, because "leave this square empty" is a non-instruction
 * @param demand      one entry per distinct ingredient with the total count a single craft consumes,
 *                    which is what a feasibility check needs and what {@code cells} does not
 *                    directly say
 */
public record RecipeView(int index, String output, int outputMeta, int outputCount,
                         boolean shapeless, int width, int height, List<Cell> cells,
                         List<Ingredient> demand) {

    /**
     * One ingredient to place, and where.
     *
     * @param item    ingredient item registry name, namespace stripped
     * @param meta    required metadata, meaningful only when {@code anyMeta} is false
     * @param anyMeta true when the recipe accepts any variant of the item. Vanilla writes this as
     *                the magic metadata 32767; it is surfaced as a boolean because a caller that
     *                treated 32767 as a literal metadata to place would put nothing craftable in the
     *                square.
     */
    public record Cell(int row, int col, String item, int meta, boolean anyMeta) {
    }

    /**
     * Total demand for one distinct ingredient across a single craft.
     *
     * @param count how many squares of the layout call for this ingredient
     */
    public record Ingredient(String item, int meta, boolean anyMeta, int count) {
    }

    /**
     * Whether this recipe needs a crafting table rather than the player's own 2x2 grid.
     *
     * <p>Worth stating up front: the alternative is the model filling four squares, finding no
     * result, and having no way to tell a missing ingredient from a grid that is simply too small.
     */
    public boolean requiresTable() {
        return width > 2 || height > 2;
    }
}
