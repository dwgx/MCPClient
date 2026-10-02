package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * {@code scan_surroundings} answers HOW MUCH, and {@code world_view} cannot.
 *
 * <p>Found by a design review, then confirmed against the source: {@code scan_surroundings}'s
 * description used to call itself a compact heartbeat superseded by {@code world_view}, and told
 * the caller to prefer {@code world_view} for detailed decisions. That is not cosmetic — it points
 * the model at the wrong tool for a whole class of question.
 *
 * <p>The two count over different sets. {@link WorldScanner#census} walks the whole sampled cube
 * (three nested loops over dx, dy and dz), so it answers "how much dirt is near me".
 * {@link LocalGrid}'s {@code blockCounts} is a PER-COLUMN histogram and therefore surface-only:
 * dirt under a floor, ore under a roof, or anything below the surface is simply absent from it, and
 * {@code world_view}'s own text warns its histogram is surface-only. So routing a quantity question
 * to {@code world_view} produces a confidently wrong "there is none here".
 *
 * <p>This pins the behavioural difference with arithmetic, not prose — a test asserting the
 * description contains a word would break on rewording and prove nothing about the census.
 */
public final class ScanSurroundingsCensusesTheCubeNotTheSurfaceTest {

    /** A slab of dirt reaching the bottom of the cube — entirely below any surface. */
    private static final class SlabBelowSurface implements WorldScanner.Sampler {
        private final int yAtOrBelow;

        SlabBelowSurface(int yAtOrBelow) {
            this.yAtOrBelow = yAtOrBelow;
        }

        @Override
        public String at(int dx, int dy, int dz) {
            // The WIRE spelling, which is what production actually produces: LocalGrid.wireName
            // strips the namespace, so the census sees "air" and never "minecraft:air". My first
            // version of this sampler returned the namespaced form and the test went red on
            // {minecraft:air=125} -- correctly reporting that countable() excludes the bare "air".
            // That was my sampler lying about the contract, not a defect in the guard.
            return dy <= yAtOrBelow ? "dirt" : "air";
        }
    }

    @Test
    public void theCensusCountsBlocksThatAreNotOnTheSurface() {
        // r = 4, so dy spans -4..4: five of the nine rows are dirt, over a 9x9 footprint.
        Map<String, Integer> counts = WorldScanner.census(new SlabBelowSurface(0), 4);

        assertEquals("a slab reaching the bottom of a 9x9x9 cube must be counted, and it is "
                + "entirely below the surface; got " + counts,
                Integer.valueOf(5 * 9 * 9), counts.get("dirt"));
    }

    @Test
    public void airIsNotTalliedAsABlockType() {
        Map<String, Integer> counts =
                WorldScanner.census(new SlabBelowSurface(Integer.MIN_VALUE), 2);

        assertFalse("air is not a block type a caller asked about: " + counts,
                counts.containsKey("air"));
        assertTrue("an all-air volume must census to nothing at all: " + counts, counts.isEmpty());
    }
}