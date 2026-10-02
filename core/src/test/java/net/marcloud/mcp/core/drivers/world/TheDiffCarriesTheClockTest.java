package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.Test;

/**
 * A caller polling {@code mode=diff} has to be able to see the sun go down.
 *
 * <p><b>What was invisible.</b> {@code WorldViewDiff.envDiff} compared {@code timeOfDay} -- the
 * BUCKET -- plus biome and dimension, and nothing else. The bucket is 1,000 ticks wide
 * ({@code WorldViewCapture.timeBucket}), and vanilla's own night begins at world time 13,807
 * (measured against {@code World.calculateSkylightSubtracted}; see
 * {@link DaylightMatchesTheVanillaCurveTest}). So the two ticks either side of dusk, 13,806 and
 * 13,807, are both in the same bucket, and the diff said nothing had changed. A poll every second
 * would have walked the whole of the 1,000-tick bucket in silence, seen the bucket flip at 13,000
 * -- long before the dark -- and then seen nothing at all at the moment the dark actually arrived.
 *
 * <p><b>What it carries now.</b> The raw clock and the daylight answer. The clock moves every
 * tick so no movement is silent, and {@code daytime} flips on the tick vanilla's curve flips,
 * which is the fact a caller has to act on rather than a bucket boundary that is 807 ticks early.
 */
public final class TheDiffCarriesTheClockTest {

    /** The first tick vanilla's own curve calls night, measured -- not the bucket boundary. */
    private static final long DUSK = 13807L;
    private static final long DAWN = 22193L;
    private static final long NOON = 6000L;

    /**
     * A whole-env view at one instant, built so that only the env section differs between calls.
     *
     * <p>The other five sections are the same object every time, so a key that appears in the
     * diff can only have come from the env.
     */
    private static WorldView viewAt(long worldTime, String bucket, boolean daytime) {
        return new WorldView(true, worldTime, "test", SELF, null, List.of(), false, null, null,
                new EnvView("overworld", "plains", bucket, worldTime, false, false, daytime, 15));
    }

    /** A self section that never varies, so every diff key can only have come from the env. */
    private static final SelfView SELF = new SelfView(0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0f, 0f,
            20.0F, 20, 5.0F, 0, 0.0F, 0, 300, "survival", false, false, true, List.of(), 0.0, 0,
            false);

    /**
     * The two ticks either side of dusk, which are in the SAME bucket.
     *
     * <p>This is the regression. Before the change {@code envDiff} compared the bucket and
     * nothing else, so this pair produced an empty env section -- a diff that said the world was
     * identical across the exact tick the sun went down.
     */
    @Test
    public void duskIsVisibleEvenThoughItDoesNotCrossTheHourBucket() {
        // Both instants bucket as "sunset": timeBucket is 'night' only from 13,000 onwards and
        // dusk is 13,807, so these two are the same bucket by construction.
        String before = WorldViewCapture.timeBucket(DUSK - 1);
        String after = WorldViewCapture.timeBucket(DUSK);
        assertEquals("premise: the two ticks either side of dusk are in the same bucket, which is"
                + " why the bucket could not carry this", before, after);

        WorldView prev = viewAt(DUSK - 1, before, true);
        WorldView cur = viewAt(DUSK, after, false);

        Map<String, Object> env = envSectionOf(WorldViewDiff.diff(prev, cur));

        assertTrue("the diff must report the clock moving; it did not, and the env section was "
                + env, env.containsKey("worldTime"));
        assertEquals("and it must report the tick the world is now at, which is the tick vanilla's"
                + " own curve first calls night", DUSK, env.get("worldTime"));
        assertEquals("and it must report the daylight answer flipping to false", Boolean.FALSE,
                env.get("daytime"));
    }

    /** The other transition, because a curve with one edge is not a curve. */
    @Test
    public void dawnIsVisibleTooAndItIsNotTheSameTickAsTheBucket() {
        String before = WorldViewCapture.timeBucket(DAWN - 1);
        String after = WorldViewCapture.timeBucket(DAWN);
        assertEquals("premise: dawn at 22,193 is still in the 'night' bucket, which runs to 23,000",
                before, after);

        Map<String, Object> env = envSectionOf(WorldViewDiff.diff(
                viewAt(DAWN - 1, before, false), viewAt(DAWN, after, true)));

        assertEquals("the diff carries the clock across dawn", DAWN, env.get("worldTime"));
        assertEquals("and the daylight answer flipping back to true", Boolean.TRUE,
                env.get("daytime"));
    }

    /**
     * The clock is reported even when nothing else about the world moved.
     *
     * <p>The strongest form of the property, and the one a bucket comparison could never have: a
     * full day passes with the bucket, the biome, the dimension, the weather and the light all
     * unchanged at both ends, and the diff still says the world moved -- because it did.
     */
    @Test
    public void aDayPassingWithNothingElseChangingIsStillADiff() {
        Map<String, Object> env = envSectionOf(WorldViewDiff.diff(
                viewAt(NOON, WorldViewCapture.timeBucket(NOON), true),
                viewAt(NOON + 1, WorldViewCapture.timeBucket(NOON + 1), true)));

        assertEquals("two adjacent ticks differ only in the clock, and that is enough to report",
                NOON + 1, env.get("worldTime"));
        assertFalse("and the daylight answer has not flipped, so it must NOT be reported -- a"
                + " section that emits a key on every poll is a section nobody reads",
                env.containsKey("daytime"));
        assertFalse("nor may the bucket, which did not change", env.containsKey("timeOfDay"));
    }

    /**
     * A diff with no baseline still ships the whole env, clock included.
     *
     * <p>The {@code a == null} branch returns {@code WorldViewJson.envMap(b)} wholesale, so this
     * is a claim about that map rather than about {@code envDiff} -- but it is the first thing a
     * caller sees, and a first poll that omits the clock teaches them the key is optional.
     */
    @Test
    public void aFirstPollWithNoBaselineCarriesTheClock() {
        Map<String, Object> out = WorldViewDiff.diff(null,
                viewAt(NOON, WorldViewCapture.timeBucket(NOON), true));
        assertEquals("a diff with no previous view is a full projection", "full", out.get("mode"));
        @SuppressWarnings("unchecked")
        Map<String, Object> env = (Map<String, Object>) out.get("env");
        assertEquals("and the env in it carries the clock", NOON, env.get("worldTime"));
        assertEquals("and the daylight answer", Boolean.TRUE, env.get("daytime"));
    }

    /**
     * An unsampled env says so, and does not smuggle a stale clock out with it.
     *
     * <p>The mirror of the tests above. A diff that reported a clock change while the env was
     * never sampled would be asserting a fact about a section nobody looked at.
     */
    @Test
    public void anUnsampledEnvIsNotAClockReading() {
        WorldView prev = viewAt(NOON, WorldViewCapture.timeBucket(NOON), true);
        WorldView cur = new WorldView(true, NOON + 1, "test", SELF, null, List.of(), false, null,
                null, null);

        Map<String, Object> env = envSectionOf(WorldViewDiff.diff(prev, cur));
        assertEquals("an env that was not sampled reports itself as unsampled, and says nothing"
                + " about the clock", Map.of("unsampled", true), env);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envSectionOf(Map<String, Object> diff) {
        Object env = diff.get("env");
        assertTrue("the diff carried no env section at all, so the assertions below would be"
                + " vacuous; the diff was " + diff, env instanceof Map);
        return (Map<String, Object>) env;
    }
}
