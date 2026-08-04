package net.marcloud.mcp.core.drivers.craft;

import java.util.List;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraft.item.crafting.IRecipe;

/**
 * Shared harness: a real vanilla crafting grid, filled from a {@link RecipeView}.
 *
 * <p><b>Why the real table and the real matcher.</b> The claims these tests check are claims about
 * vanilla's behaviour, so a hand-written fake recipe would only prove the reader agrees with the
 * test author. {@code net.minecraft.init.Bootstrap.register()} builds the item registry and the
 * 373-entry recipe list in a plain surefire JVM in under a second -- no display, no world, no game
 * thread -- so there is no reason to test against anything less than the real thing.
 *
 * <p>{@link Container} has one abstract method and its {@code onCraftMatrixChanged} walks two empty
 * lists, so an empty subclass is enough to satisfy {@code InventoryCrafting}, which calls back into
 * its event handler on every {@code setInventorySlotContents}. Passing null there NPEs.
 */
final class CraftBench {

    private CraftBench() {
    }

    /**
     * The one place {@code Bootstrap} is triggered. Idempotent, so every caller may invoke it.
     *
     * <p>Extracted from {@link #recipes()} because that javadoc claimed to BE the one place while
     * {@code FakeCraftWindow} called {@code Bootstrap.register()} itself -- two homes and a comment
     * asserting one. A third caller wanting the registries without the recipe list is what surfaced
     * it, and the fix is the same one this repo applies to every duplicated lesson: give it a single
     * home rather than a third copy.
     */
    static void boot() {
        net.minecraft.init.Bootstrap.register();
    }

    /** Every vanilla recipe, with the registries booted. */
    static List<IRecipe> recipes() {
        boot();
        return CraftingManager.getInstance().getRecipeList();
    }

    static final class Bench extends Container {
        public boolean canInteractWith(EntityPlayer playerIn) {
            return true;
        }
    }

    /**
     * Places a view's cells into a {@code side}x{@code side} grid at the top-left anchor.
     *
     * <p>The slot arithmetic is {@code col + row * side} -- the caller-side arithmetic RecipeView's
     * javadoc names, written out here so that if the reader's coordinate convention ever flips, this
     * harness keeps using the documented one and the round-trip fails instead of silently agreeing.
     *
     * @param transpose swap row and col on the way in, to build the layout a naming-led reader would
     *                  have emitted
     */
    static InventoryCrafting grid(RecipeView view, int side, boolean transpose) {
        InventoryCrafting inv = new InventoryCrafting(new Bench(), side, side);
        for (RecipeView.Cell c : view.cells()) {
            int row = transpose ? c.col() : c.row();
            int col = transpose ? c.row() : c.col();
            inv.setInventorySlotContents(col + row * side, stack(c));
        }
        return inv;
    }

    /**
     * One ingredient as a placeable stack.
     *
     * <p>A wildcard cell is placed at metadata 0 rather than at 32767: 32767 is a matcher token on
     * the RECIPE side, and an item stack actually carrying it is a different variant from every real
     * one. Placing it would fail every match and the failure would look like a layout bug.
     */
    static ItemStack stack(RecipeView.Cell c) {
        Item item = Item.getByNameOrId(c.item());
        if (item == null) {
            throw new AssertionError("ingredient not in the item registry: " + c.item());
        }
        return new ItemStack(item, 1, c.anyMeta() ? 0 : c.meta());
    }

    /** The view of the recipe at {@code index}, or null when that recipe is unsupported. */
    static RecipeView view(int index) {
        return RecipeLayoutReader.read(recipes().get(index), index).view();
    }

    /** First readable recipe whose output is exactly this name and metadata. */
    static RecipeView find(String output, int outputMeta) {
        List<IRecipe> list = recipes();
        for (int i = 0; i < list.size(); i++) {
            RecipeView v = RecipeLayoutReader.read(list.get(i), i).view();
            if (v != null && v.output().equals(output) && v.outputMeta() == outputMeta) {
                return v;
            }
        }
        throw new AssertionError("no readable recipe outputs " + output + "/" + outputMeta);
    }

    /** Cell at (row, col), or null. Keeps the assertions readable as a picture of the grid. */
    static RecipeView.Cell at(RecipeView view, int row, int col) {
        for (RecipeView.Cell c : view.cells()) {
            if (c.row() == row && c.col() == col) {
                return c;
            }
        }
        return null;
    }
}
