package net.marcloud.mcp.core.drivers.craft;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.ShapedRecipes;
import net.minecraft.item.crafting.ShapelessRecipes;

/**
 * The ONLY place that reflects into vanilla's private recipe layout fields.
 *
 * <p><b>Why reflection is unavoidable.</b> A recipe's shape lives in
 * {@code ShapedRecipes.recipeWidth/recipeHeight/recipeItems} and {@code ShapelessRecipes.recipeItems},
 * all four {@code private final} with no getter. The public surface of {@link IRecipe} is
 * {@code getRecipeOutput}, {@code getRecipeSize}, {@code getCraftingResult},
 * {@code getRemainingItems} and {@code matches(InventoryCrafting, World)} -- it can confirm that a
 * grid you already built is correct, but it cannot tell you what to build. The rejected alternative
 * was brute force: fill a grid with candidate items and ask {@code matches}, which is
 * (items x metas)^9 and needs the answer to pose the question. Since {@code client/} is frozen
 * vanilla, adding a getter is not available either.
 *
 * <p><b>Why it fails closed.</b> If a field name ever changes, the honest outcome is "I cannot read
 * this recipe". The tempting alternative -- default to a 3x3 and carry on -- would emit a layout that
 * looks authoritative and is wrong, and the model would place items into the wrong squares, spend
 * real materials, and get no output with nothing in the report hinting at why. Every read is
 * therefore all-or-nothing: an unreadable recipe becomes an {@code unsupported} candidate carrying
 * the reason, never a guessed one.
 *
 * <p>Pure: no game thread, no world, no window. The recipe table is a static list built during the
 * client's own startup.
 */
final class RecipeLayoutReader {

    private RecipeLayoutReader() {
    }

    /** Vanilla's "any variant of this item will do" metadata. */
    static final int ANY_META = 32767;

    /** Largest grid a player can ever open, so the widest recipe that can be placed. */
    static final int MAX_GRID = 3;

    // Resolved once. A null here means the field is gone, which every read below turns into an
    // unsupported candidate rather than a guess.
    private static final Field SHAPED_WIDTH = field(ShapedRecipes.class, "recipeWidth");
    private static final Field SHAPED_HEIGHT = field(ShapedRecipes.class, "recipeHeight");
    private static final Field SHAPED_ITEMS = field(ShapedRecipes.class, "recipeItems");
    private static final Field SHAPELESS_ITEMS = field(ShapelessRecipes.class, "recipeItems");

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getDeclaredField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            // Swallowed deliberately: the absence is the signal, and it is reported per-recipe at
            // read time with the field name in the reason. Throwing from a static initialiser would
            // instead take out the whole class and every recipe with it.
            return null;
        }
    }

    /**
     * Reads one recipe, or explains why it cannot be read.
     *
     * <p>Only the exact classes {@code ShapedRecipes} and {@code ShapelessRecipes} are read. A
     * subclass is refused even though its fields would be readable, because a subclass overrides
     * {@code matches} or {@code getCraftingResult} to add conditions the layout does not express --
     * {@code RecipesMapExtending} extends {@code ShapedRecipes} with a readable paper-around-map
     * shape, but also demands the map's NBT scale be below the maximum and computes its output count
     * at craft time (its declared output stackSize is 0). Emitting its layout would promise a craft
     * that fails on a fully-zoomed map for reasons nothing in the layout mentions.
     */
    static Read read(IRecipe recipe, int index) {
        if (recipe == null) {
            return Read.unsupported(index, null, 0, "null recipe entry");
        }
        Class<?> cls = recipe.getClass();
        ItemStack out = recipe.getRecipeOutput();

        // A dynamic recipe computes its output from its inputs, so there is no fixed output to
        // resolve a name against. Reported, never skipped: 7 of vanilla's recipes are in this state
        // (armour dyeing, book and map cloning, item repair, banner patterns, fireworks) and a
        // caller told "no recipe" for a firework would conclude fireworks are uncraftable.
        if (out == null) {
            return Read.unsupported(index, null, 0,
                    "dynamic recipe with no fixed output (" + cls.getSimpleName() + ")");
        }
        String name = itemName(out.getItem());
        if (name == null) {
            return Read.unsupported(index, null, out.getMetadata(),
                    "output item is not in the item registry");
        }
        if (cls != ShapedRecipes.class && cls != ShapelessRecipes.class) {
            return Read.unsupported(index, name, out.getMetadata(),
                    "dynamic recipe subclass " + cls.getSimpleName()
                            + " adds conditions the layout cannot express");
        }
        // Guarded separately from the subclass check because the two say different things: a
        // stackSize of 0 means the count is decided at craft time, which makes "craft one and get
        // this many" unanswerable even when the shape reads cleanly.
        if (out.stackSize <= 0) {
            return Read.unsupported(index, name, out.getMetadata(),
                    "output count is decided at craft time (declared stackSize "
                            + out.stackSize + ")");
        }
        return cls == ShapedRecipes.class
                ? readShaped(recipe, index, name, out, SHAPED_WIDTH, SHAPED_HEIGHT, SHAPED_ITEMS)
                : readShapeless(recipe, index, name, out, SHAPELESS_ITEMS);
    }

    /**
     * Shaped read, with the fields passed in so a test can prove the fail-closed path fires.
     *
     * <p>The fields are arguments rather than read from the statics because the guard is the whole
     * point of this class: with no way to simulate a missing field, "fails closed" would be an
     * untested claim, and the branch that reports it could be broken without any test noticing.
     */
    static Read readShaped(IRecipe recipe, int index, String name, ItemStack out,
                           Field widthField, Field heightField, Field itemsField) {
        if (widthField == null || heightField == null || itemsField == null) {
            return Read.unsupported(index, name, out.getMetadata(),
                    "ShapedRecipes layout fields are not readable"
                            + " (recipeWidth/recipeHeight/recipeItems)");
        }
        int width;
        int height;
        ItemStack[] items;
        try {
            width = (int) widthField.get(recipe);
            height = (int) heightField.get(recipe);
            items = (ItemStack[]) itemsField.get(recipe);
        } catch (Throwable t) {
            return Read.unsupported(index, name, out.getMetadata(),
                    "ShapedRecipes layout unreadable: " + t.getClass().getSimpleName());
        }
        // A shape wider or taller than the grid cannot be placed at all, and a short array would
        // mean the indices below are lying about which square each ingredient belongs to.
        if (width < 1 || height < 1 || width > MAX_GRID || height > MAX_GRID) {
            return Read.unsupported(index, name, out.getMetadata(),
                    "shape " + width + "x" + height + " does not fit a " + MAX_GRID + "x" + MAX_GRID
                            + " grid");
        }
        if (items == null || items.length < width * height) {
            return Read.unsupported(index, name, out.getMetadata(),
                    "layout array holds " + (items == null ? "null" : items.length)
                            + " entries for a " + width + "x" + height + " shape");
        }

        List<RecipeView.Cell> cells = new ArrayList<>();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                // Vanilla stores the pattern in reading order, col + row * width. Do NOT reach for
                // the parameter names of InventoryCrafting.getStackInRowAndColumn(row, column) to
                // check this: its body indexes row + column * inventoryWidth, so its first argument
                // is really the COLUMN. ShapedRecipes.checkMatch relies on that, calling it with its
                // x loop first, and indexes recipeItems as k + l * recipeWidth with k from the same
                // x loop. So the array is col-major-free: col varies fastest. Naming-led code would
                // transpose every grid and still compile.
                ItemStack ing = items[col + row * width];
                if (ing == null) {
                    continue;
                }
                String ingName = itemName(ing.getItem());
                if (ingName == null) {
                    return Read.unsupported(index, name, out.getMetadata(),
                            "ingredient at row " + row + " col " + col + " is not in the registry");
                }
                int meta = ing.getMetadata();
                cells.add(new RecipeView.Cell(row, col, ingName, meta == ANY_META ? 0 : meta,
                        meta == ANY_META));
            }
        }
        return Read.of(view(index, name, out, false, width, height, cells));
    }

    /**
     * Shapeless read. Position is irrelevant to matching, so the layout is synthesised.
     *
     * <p>Packed into the smallest square that holds the ingredients rather than always a 3x3, so a
     * 4-ingredient shapeless recipe stays craftable in the player's own 2x2 grid instead of being
     * reported as needing a table it does not need.
     */
    static Read readShapeless(IRecipe recipe, int index, String name, ItemStack out,
                              Field itemsField) {
        if (itemsField == null) {
            return Read.unsupported(index, name, out.getMetadata(),
                    "ShapelessRecipes layout field is not readable (recipeItems)");
        }
        List<?> raw;
        try {
            raw = (List<?>) itemsField.get(recipe);
        } catch (Throwable t) {
            return Read.unsupported(index, name, out.getMetadata(),
                    "ShapelessRecipes layout unreadable: " + t.getClass().getSimpleName());
        }
        if (raw == null || raw.isEmpty()) {
            return Read.unsupported(index, name, out.getMetadata(), "shapeless recipe has no inputs");
        }
        if (raw.size() > MAX_GRID * MAX_GRID) {
            return Read.unsupported(index, name, out.getMetadata(),
                    "shapeless recipe needs " + raw.size() + " inputs, more than the "
                            + (MAX_GRID * MAX_GRID) + " squares a grid has");
        }
        int side = raw.size() <= 1 ? 1 : (raw.size() <= 4 ? 2 : MAX_GRID);

        List<RecipeView.Cell> cells = new ArrayList<>();
        int at = 0;
        for (Object o : raw) {
            if (!(o instanceof ItemStack ing)) {
                return Read.unsupported(index, name, out.getMetadata(),
                        "shapeless input " + at + " is not an ItemStack");
            }
            String ingName = itemName(ing.getItem());
            if (ingName == null) {
                return Read.unsupported(index, name, out.getMetadata(),
                        "shapeless input " + at + " is not in the registry");
            }
            int meta = ing.getMetadata();
            cells.add(new RecipeView.Cell(at / side, at % side, ingName,
                    meta == ANY_META ? 0 : meta, meta == ANY_META));
            at++;
        }
        int height = (raw.size() + side - 1) / side;
        return Read.of(view(index, name, out, true, side, height, cells));
    }

    private static RecipeView view(int index, String name, ItemStack out, boolean shapeless,
                                   int width, int height, List<RecipeView.Cell> cells) {
        return new RecipeView(index, name, out.getMetadata(), out.stackSize, shapeless, width, height,
                List.copyOf(cells), demand(cells));
    }

    /**
     * Collapses the layout into per-ingredient totals.
     *
     * <p>Keyed on item AND meta AND the wildcard flag, all three: two squares wanting different
     * variants of one item are different demands, and a wildcard square is a different demand from an
     * exact one even at the same nominal meta, because only one of them can be paid for with any
     * variant on hand.
     */
    private static List<RecipeView.Ingredient> demand(List<RecipeView.Cell> cells) {
        Map<String, RecipeView.Ingredient> byKey = new LinkedHashMap<>();
        for (RecipeView.Cell c : cells) {
            String key = c.item() + "/" + (c.anyMeta() ? "*" : String.valueOf(c.meta()));
            RecipeView.Ingredient prev = byKey.get(key);
            byKey.put(key, prev == null
                    ? new RecipeView.Ingredient(c.item(), c.meta(), c.anyMeta(), 1)
                    : new RecipeView.Ingredient(prev.item(), prev.meta(), prev.anyMeta(),
                            prev.count() + 1));
        }
        return List.copyOf(byKey.values());
    }

    /** Registry name with the namespace stripped, matching what {@code world_view} reports. */
    static String itemName(Item item) {
        if (item == null) {
            return null;
        }
        try {
            Object rl = Item.itemRegistry.getNameForObject(item);
            if (rl == null) {
                return null;
            }
            String s = rl.toString().toLowerCase(Locale.ROOT);
            int colon = s.indexOf(':');
            return colon < 0 ? s : s.substring(colon + 1);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Either a readable recipe or the reason it is not.
     *
     * @param view   the layout, null when unsupported
     * @param output output name when known even though the layout is not -- a caller searching for
     *               "map" must be told that one of its recipes is dynamic, which needs the name
     */
    record Read(RecipeView view, int index, String output, int outputMeta, String unsupported) {

        static Read of(RecipeView v) {
            return new Read(v, v.index(), v.output(), v.outputMeta(), null);
        }

        static Read unsupported(int index, String output, int outputMeta, String reason) {
            return new Read(null, index, output, outputMeta, reason);
        }

        boolean ok() {
            return view != null;
        }
    }
}
