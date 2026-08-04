package net.marcloud.mcp.core.drivers.craft;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.marcloud.mcp.core.drivers.world.InventoryView;

/**
 * A reference-free snapshot of what the player is carrying, as the feasibility check consumes it.
 *
 * <p><b>Why a separate type from {@link InventoryView}.</b> The check takes the smallest input that
 * answers the question -- item, variant, count -- so it can be exercised with three-line synthetic
 * inventories in a headless test. Slot indices and durability matter to the clicking half and are
 * noise here. {@link #from} adapts the real snapshot, so the live path and the tested path are the
 * same code with the same rules rather than two implementations that can drift apart.
 */
public record CraftInventory(List<Held> items) {

    /**
     * One quantity of one item variant.
     *
     * <p>The canonical constructor normalises {@code item} because the two sides of the comparison
     * are produced by different code: an ingredient name comes from
     * {@code RecipeLayoutReader.itemName}, which strips the namespace AND lowercases, while the live
     * snapshot next door strips the namespace only. Normalising here rather than trusting callers is
     * what makes a hand-built {@code Held("minecraft:Stick", 0, 1)} satisfy an ingredient named
     * "stick" -- unnormalised it would have been reported as a missing ingredient the player was in
     * fact holding, and the model would have gone mining for something already in its pockets.
     *
     * @param item  item registry name; namespace stripped and lowercased on the way in
     * @param meta  the variant, as {@code getItemDamage} reports it. For a damageable tool this is
     *              wear rather than a variant, which is exactly why a worn tool fails to satisfy an
     *              ingredient that names meta 0 -- see {@link CraftFeasibility}. Verified against
     *              vanilla: a fishing rod at damage 3 is refused by the carrot-on-a-stick recipe's
     *              own {@code matches}.
     * @param count how many are held
     */
    public record Held(String item, int meta, int count) {

        public Held(String item, int meta, int count) {
            this.item = normalise(item);
            this.meta = meta;
            this.count = count;
        }

        private static String normalise(String raw) {
            if (raw == null) {
                return null;
            }
            String s = raw.toLowerCase(Locale.ROOT);
            int colon = s.indexOf(':');
            return colon < 0 ? s : s.substring(colon + 1);
        }
    }

    /** Convenience for the common shape of a test or a caller building a query by hand. */
    public static CraftInventory of(Held... held) {
        return new CraftInventory(List.of(held));
    }

    /**
     * Adapts a live inventory snapshot.
     *
     * <p>Slots are taken as they come, one {@code Held} each; the check aggregates. Two stacks of the
     * same item are therefore additive without this method needing to know it, which keeps the
     * pooling rule in one place.
     */
    public static CraftInventory from(InventoryView inv) {
        List<Held> out = new ArrayList<>();
        if (inv != null && inv.slots() != null) {
            for (InventoryView.Slot s : inv.slots()) {
                if (s == null || s.item() == null || s.count() <= 0) {
                    continue;
                }
                out.add(new Held(s.item(), s.damage(), s.count()));
            }
        }
        return new CraftInventory(List.copyOf(out));
    }
}
