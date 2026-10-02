package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * In mode=diff an ABSENT key means UNCHANGED, so a section that was never sampled must say so.
 *
 * <p>Three of the six did not: {@code envDiff} returned an empty map whenever either side was null,
 * the grid was emitted only when the current view had one, and the target was emitted as a bare null
 * -- which the full projection already uses for "not sampled", but which no diff-mode reader can
 * distinguish from silence. Entities, effects and air had carried an explicit not-sampled token for
 * several rounds; these three had not.
 *
 * <p>What silence costs is not cosmetic. A caller polling with {@code sections} that omit {@code env}
 * would be told nothing changed while it walked through a Nether portal -- the dimension is in that
 * section -- and one that omitted {@code grid} would never hear that lava had arrived in the next
 * column.
 *
 * <p>The rule is derived from {@link WorldViewCapture#SECTIONS} rather than hand-listed, so a seventh
 * section added to the vocabulary fails here instead of shipping silent. Each case omits exactly one
 * section from an otherwise fully sampled view, which is what makes the diff's answer about that
 * section rather than about the fixture.
 */
public final class EveryDiffSectionSaysWhenItWasNotSampledTest {

    private static final SelfView SELF = new SelfView(10, 64, 0, 0, 0, 0, 0f, 0f, 20f, 20, 5f,
            3, 0.5f, 0, 300, "SURVIVAL", false, false, true, List.of(), 0.0, 0, false);

    private static final LocalGrid GRID = new LocalGrid(1, "surface", 10, 64, 0,
            List.of(new LocalGrid.Column(0, 0, 0, "stone", "air", "air", List.of(), 0,
                    LocalGrid.WALK_CLEAR)),
            Map.of("stone", 1));

    private static final EntityView ZOMBIE = new EntityView(7, "Zombie", 5, 64, 0, 5.0, 20, "Zombie");

    private static final InventoryView INV = new InventoryView(0,
            List.of(new InventoryView.Slot(0, "diamond_pickaxe", 1, 12, 1561)));

    private static final TargetView TARGET =
            new TargetView("block", "stone", 11, 64, -3, "NORTH", null, null, null, 3.0);

    private static final EnvView ENV = new EnvView("Overworld", "Plains", "day", 6000L);

    /**
     * One snapshot with every section sampled except {@code omitted}, which is unsampled when it is
     * named. The sections are the same values every time, so nothing but the omission can move the
     * diff.
     */
    private static WorldView view(long tick, String omitted) {
        return new WorldView(true, tick, "explore",
                omitted("self", omitted) ? null : SELF,
                omitted("grid", omitted) ? null : GRID,
                omitted("entities", omitted) ? null : List.of(ZOMBIE),
                false,
                omitted("inventory", omitted) ? null : INV,
                omitted("target", omitted) ? null : TARGET,
                omitted("env", omitted) ? null : ENV);
    }

    private static boolean omitted(String section, String omitted) {
        return section.equals(omitted);
    }

    /**
     * The rule, once per section of the tool's own vocabulary.
     *
     * <p>Collected rather than failing on the first, so the message names every section that went
     * silent in one run -- the same reason the self-field sweep in
     * {@code DiffLeftMeansUnsampledNotGoneTest} collects its violations.
     */
    @Test
    public void aSectionThatWasNotSampledSaysSoRatherThanGoingQuiet() {
        List<String> silent = new ArrayList<>();
        for (String section : WorldViewCapture.SECTIONS) {
            Map<String, Object> d = WorldViewDiff.diff(view(1L, null), view(2L, section));
            Object got = d.get(section);
            if (got == null || !Boolean.TRUE.equals(asMap(got).get("unsampled"))) {
                silent.add(section + " -> " + got);
            }
        }
        assertTrue("in diff mode a missing key means 'unchanged', so a section that was not sampled "
                + "must answer {'unsampled':true} instead of staying silent -- otherwise the caller "
                + "is told the last known state still holds: " + silent, silent.isEmpty());
    }

    /**
     * The other half, and without it the rule above could be satisfied by emitting
     * {@code unsampled} unconditionally -- which would turn every quiet poll into a report that the
     * observation failed.
     */
    @Test
    public void aSectionThatWasSampledAndDidNotChangeStaysOffTheWire() {
        Map<String, Object> d = WorldViewDiff.diff(view(1L, null), view(2L, null));
        for (String section : WorldViewCapture.SECTIONS) {
            assertFalse("nothing about '" + section + "' moved, so the token saver still applies: " + d,
                    d.containsKey(section));
        }
        assertEquals("and the diff is still a diff", "diff", d.get("mode"));
    }

    /**
     * A section unsampled on BOTH polls still says so.
     *
     * <p>Deliberately unlike air, which compares by value and therefore reports only the transition.
     * The differ is stateless: it cannot know which poll was the first to omit the section, so the
     * alternative to repeating is silence -- and silence would assert that the grid the caller last
     * saw still holds. Same call the entities section already made.
     */
    @Test
    public void aSectionUnsampledOnBothPollsStillSaysSo() {
        Map<String, Object> d = WorldViewDiff.diff(view(1L, "grid"), view(2L, "grid"));
        assertEquals("two polls that learned nothing about the grid must not read as 'the terrain "
                + "did not move'", Boolean.TRUE, asMap(d.get("grid")).get("unsampled"));
    }

    /**
     * A section sampled AFTER an unsampled baseline ships its whole current state under {@code now}.
     *
     * <p>The third state of the same three-state split, and the one the old envDiff got wrong in the
     * opposite direction: with no baseline there is nothing to have changed FROM, so diffing against
     * nothing would have reported every field as a fresh change. {@code selfDiff} and
     * {@code inventoryDiff} already use {@code now} for exactly this.
     */
    @Test
    public void aSectionSampledAfterAnUnsampledBaselineShipsItsCurrentState() {
        Map<String, Object> env = asMap(WorldViewDiff.diff(view(1L, "env"), view(2L, null)).get("env"));
        assertNotNull("env must not stay silent here either", env);
        assertNotNull("the whole current env, under the key the other sections use for 'no "
                + "baseline'", env.get("now"));
        assertFalse("and not dressed up as a change", env.containsKey("dimension"));
    }

    /** The unsampled token must be the SAME one entities has always used, so a caller branches once. */
    @Test
    public void everySectionUsesTheTokenTheDescriptionTeaches() {
        for (String section : WorldViewCapture.SECTIONS) {
            Map<String, Object> d = WorldViewDiff.diff(view(1L, null), view(2L, section));
            Object got = d.get(section);
            assertNotNull("'" + section + "' must say something at all when it was not sampled: " + d,
                    got);
            assertTrue("'" + section + "' must be marked unsampled under the documented key: " + d,
                    asMap(got).containsKey("unsampled"));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return (Map<String, Object>) o;
    }
}
