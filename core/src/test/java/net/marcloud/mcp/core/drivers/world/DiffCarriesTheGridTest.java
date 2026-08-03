package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * {@code mode=diff} must be able to report that the terrain changed.
 *
 * <p><b>Why this is not a nicety.</b> The diff carried self, entities, inventory, target and env,
 * and no grid at all -- so the {@code drop} and {@code walk} hazard terms were structurally
 * unreachable in the mode a caller uses for continuous observation. Lava flowing into the next
 * column, or a floor mined out from under a planned path, could not be learned by polling.
 *
 * <p>Worse than merely missing: absence is load-bearing in the full encoding, where a missing
 * {@code walk} means CLEAR. A diff that omitted the grid therefore read as "everything walkable,
 * nothing changed" rather than "not sampled" -- the same quiet wrongness the drop terms were added
 * to remove, one layer up.
 */
public class DiffCarriesTheGridTest {

    private static LocalGrid.Column col(int dx, int dz, String surface, Integer drop, int walk) {
        return new LocalGrid.Column(dx, dz, 0, surface, "air", "air", List.of(), drop, walk);
    }

    private static WorldView viewWith(long tick, LocalGrid grid) {
        return new WorldView(true, tick, "explore", null, grid, List.of(), null, null, null);
    }

    private static LocalGrid grid(LocalGrid.Column... cols) {
        return new LocalGrid(1, "column", 0, 64, 0, List.of(cols), Map.of());
    }

    @Test
    public void aChangedGridShowsUpInTheDiff() {
        LocalGrid before = grid(col(0, 0, "stone", 0, 1));
        // Lava arrives in the same column: vanilla's verdict for it is -2.
        LocalGrid after = grid(col(0, 0, "lava", 0, -2));

        Map<String, Object> d = WorldViewDiff.diff(viewWith(1, before), viewWith(2, after));
        assertTrue("a diff must carry the grid when the terrain changed, or a caller polling this "
                + "mode can never learn that lava arrived or that a floor was mined away",
            d.containsKey("grid"));
    }

    @Test
    public void anUnchangedGridCostsNothing() {
        // The reason it can be emitted whole: when nothing moved, the key is simply absent.
        LocalGrid same = grid(col(0, 0, "stone", 0, 1));
        Map<String, Object> d = WorldViewDiff.diff(viewWith(1, same), viewWith(2, grid(
                col(0, 0, "stone", 0, 1))));
        assertFalse("an unchanged grid must not be re-sent", d.containsKey("grid"));
    }

    @Test
    public void aNewDropIsVisibleThroughTheDiff() {
        // The case that matters for a follower: the floor it was going to step on is gone.
        LocalGrid before = grid(col(2, 0, "stone", 0, 1));
        LocalGrid after = grid(col(2, 0, "air", null, 1));

        Map<String, Object> d = WorldViewDiff.diff(viewWith(1, before), viewWith(2, after));
        assertTrue("the grid must be present", d.containsKey("grid"));
        @SuppressWarnings("unchecked")
        Map<String, Object> g = (Map<String, Object>) d.get("grid");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cols = (List<Map<String, Object>>) g.get("columns");
        assertEquals("one column", 1, cols.size());
        assertEquals("a null dropDepth must reach the wire as \"deep\", not vanish -- vanishing "
                + "would read as drop 0, i.e. flat ground, which is the opposite of a void",
            "deep", cols.get(0).get("drop"));
    }
}
