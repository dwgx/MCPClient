package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;

/**
 * Finding a block must not require reading the whole neighbourhood.
 *
 * <p><b>The cost this removes.</b> Answering "where is the nearest iron ore" today means calling
 * {@code world_view} and scanning its columns: measured live, that is 34,101 characters at radius 8
 * and 138,152 at radius 16 -- roughly 34.5k tokens for one look. Neither existing path can do
 * better. {@link LocalGrid}'s {@code blockCounts} says a block type is present but not where
 * ({@code LocalGrid.java} counts surfaces only), and {@link WorldScanner} builds a name-to-count map
 * and discards every position it visited. So the model pays for the entire grid to learn one
 * coordinate, and pays again on the next question.
 *
 * <p><b>Why a search rather than a wider grid.</b> The two questions have different shapes. "Describe
 * my surroundings" wants breadth at low resolution; "where is the nearest X" wants one coordinate
 * and nothing else. Serving the second from the first is what makes it expensive.
 *
 * <p>These are pure-logic assertions over the result record, which is what can be tested without a
 * live world. The search itself reads live chunk state and is verified against a real client.
 */
public class BlockFinderReturnsCoordinatesTest {

    private static Method method(String name, Class<?>... params) {
        try {
            return BlockFinder.class.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    @Test
    public void thereIsAFinderThatReturnsPositions() {
        assertNotNull("BlockFinder.Hit must exist and carry a position: a finder that returns only "
                + "counts would leave the caller doing exactly what it does today, which is paying "
                + "for the whole grid to locate one block.", hitAccessor("x"));
        assertNotNull(hitAccessor("y"));
        assertNotNull(hitAccessor("z"));
        assertNotNull("a hit must say WHAT was found, since a query can name several types",
            hitAccessor("block"));
        assertNotNull("and how far away it is, so the caller can decide without recomputing",
            hitAccessor("dist"));
    }

    private static Method hitAccessor(String name) {
        for (Method m : BlockFinder.Hit.class.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0) {
                return m;
            }
        }
        return null;
    }

    /** A world as a map from offset to block name, so the search itself can be driven. */
    private static BlockFinder.Sampler world(java.util.Map<String, String> blocks) {
        return (dx, dy, dz) -> blocks.get(dx + "," + dy + "," + dz);
    }

    @Test
    public void theSearchItselfReturnsTheNearestHit() {
        // Added after review found that NO test drove find(): every assertion went through
        // matches() and nearestFirst(), which the search did not call. The shell walk, the early
        // exit and the clamps had zero coverage, so deleting the namespace strip inside the real
        // path would have made find_block answer "no match" for every qualified name with the
        // suite still green.
        var hits = BlockFinder.search(world(java.util.Map.of(
                "8,0,0", "minecraft:iron_ore",
                "2,0,0", "minecraft:iron_ore")),
                100, 64, 200, "iron_ore", 16, 4);
        assertEquals("both ores found", 2, hits.size());
        assertEquals("nearest first", 2.0, hits.get(0).dist(), 0.001);
        assertEquals("and coordinates are absolute, not offsets", 102, hits.get(0).x());
        assertEquals(64, hits.get(0).y());
        assertEquals(200, hits.get(0).z());
        assertEquals("the name reaches the caller stripped, matching what the grid emits",
            "iron_ore", hits.get(0).block());
    }

    @Test
    public void theSearchStopsEarlyWithoutMissingANearerHit() {
        // The subtle one: a hit on the FACE of an outer shell is nearer than one at the CORNER of
        // an inner shell, so an exit that fired on "enough hits found" would return the wrong
        // nearest. Corner of shell 2 is 2.83 away; face of shell 3 is 3.0 -- but corner of shell 2
        // at (2,2,2) is 3.46, which is FARTHER than the face hit at (3,0,0).
        var hits = BlockFinder.search(world(java.util.Map.of(
                "2,2,2", "stone",     // corner of shell 2, dist 3.46
                "3,0,0", "stone")),   // face of shell 3, dist 3.0 -- genuinely nearer
                0, 0, 0, "stone", 16, 1);
        assertEquals("exactly one hit when limited to one", 1, hits.size());
        assertEquals("and it must be the face of the outer shell, which is nearer than the corner "
                + "of the inner one -- stopping as soon as a shell yielded enough hits would "
                + "return 3.46 here", 3.0, hits.get(0).dist(), 0.001);
    }

    @Test
    public void theSearchAppliesItsOwnClamps() {
        // radius and limit arrive from a tool call, so they are attacker-shaped input.
        // Spread over three axes: a single line tops out at MAX_RADIUS hits, so the radius clamp
        // would fire before the limit clamp and MAX_LIMIT would never be exercised. The first
        // version of this test did exactly that and asserted 64 while getting 32.
        var many = new java.util.HashMap<String, String>();
        for (int i = 1; i <= 30; i++) {
            many.put(i + ",0,0", "stone");
            many.put("0," + i + ",0", "stone");
            many.put("0,0," + i, "stone");
        }
        assertEquals("limit must be capped at MAX_LIMIT even when asked for more",
            BlockFinder.MAX_LIMIT, BlockFinder.search(world(many), 0, 0, 0, "stone", 32, 999).size());
        assertTrue("a negative limit must still return something rather than throwing",
            BlockFinder.search(world(many), 0, 0, 0, "stone", 32, -5).size() == 1);
        assertTrue("a huge radius must not reach past MAX_RADIUS",
            BlockFinder.search(world(java.util.Map.of("40,0,0", "stone")),
                    0, 0, 0, "stone", 9999, 4).isEmpty());
    }

    @Test
    public void theSearchDropsAirEvenWhenAsked() {
        var hits = BlockFinder.search(world(java.util.Map.of("1,0,0", "minecraft:air")),
                0, 0, 0, "air", 8, 4);
        assertTrue("air must not be findable through the real search path either", hits.isEmpty());
    }

    @Test
    public void hitsComeBackNearestFirst() {
        // Nearest-first is the whole point: a caller asking for one ore wants THE one it can reach,
        // and a caller asking for five wants them in the order it would walk them.
        List<BlockFinder.Hit> hits = List.of(
                new BlockFinder.Hit("iron_ore", 10, 64, 0, 10.0),
                new BlockFinder.Hit("iron_ore", 2, 64, 0, 2.0),
                new BlockFinder.Hit("iron_ore", 5, 64, 0, 5.0));
        List<BlockFinder.Hit> sorted = BlockFinder.nearestFirst(hits, 10);
        assertEquals("nearest must come first", 2.0, sorted.get(0).dist(), 0.001);
        assertEquals(5.0, sorted.get(1).dist(), 0.001);
        assertEquals(10.0, sorted.get(2).dist(), 0.001);
    }

    @Test
    public void theLimitTruncatesAfterSortingNotBefore() {
        // Truncating first would return an arbitrary subset and call it the nearest, which is the
        // kind of quiet wrongness that reads as a working feature.
        List<BlockFinder.Hit> hits = List.of(
                new BlockFinder.Hit("coal_ore", 30, 64, 0, 30.0),
                new BlockFinder.Hit("coal_ore", 20, 64, 0, 20.0),
                new BlockFinder.Hit("coal_ore", 1, 64, 0, 1.0));
        List<BlockFinder.Hit> top = BlockFinder.nearestFirst(hits, 1);
        assertEquals("only one hit when limited to one", 1, top.size());
        assertEquals("and it must be the actual nearest, not whichever was visited first",
            1.0, top.get(0).dist(), 0.001);
    }

    @Test
    public void aNameMatchesWithOrWithoutTheNamespace() {
        // The grid strips namespaces (LocalGrid and WorldViewCapture both do), so a caller reading
        // "iron_ore" out of a world_view must be able to feed it straight back in. Accepting the
        // qualified form too costs nothing and avoids a class of silent no-match.
        assertTrue(BlockFinder.matches("iron_ore", "iron_ore"));
        assertTrue(BlockFinder.matches("minecraft:iron_ore", "iron_ore"));
        assertTrue("matching should not be case-sensitive: the model types what it read",
            BlockFinder.matches("Iron_Ore", "iron_ore"));
    }

    @Test
    public void askingForAirFindsNothingRatherThanTheWholeVolume() {
        // The first version of this asserted matches("air", "iron_ore") == false, which is true for
        // a reason that has nothing to do with air: "air" simply is not in the type list. Removing
        // the air filter entirely left it green. The real failure mode is a query FOR air, which
        // would match most of the search volume and return the cost this class exists to avoid.
        assertTrue("air must be dropped from the query itself, not merely absent from it",
            !BlockFinder.matches("air", "air"));
        assertTrue("and dropped even when mixed with a real type",
            !BlockFinder.matches("air", "iron_ore,air"));
        assertTrue("while the real type in that same query still matches",
            BlockFinder.matches("iron_ore", "iron_ore,air"));
        assertTrue("qualified air is dropped too, since the namespace is stripped first",
            !BlockFinder.matches("minecraft:air", "minecraft:air"));
    }

    @Test
    public void anEmptyQueryFindsNothingRatherThanEverything() {
        // Defaulting to "all blocks" would emit the entire volume -- the exact cost this exists to
        // avoid, reached by accident.
        assertTrue("an empty type list must match nothing",
            !BlockFinder.matches("stone", ""));
    }
}
