package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The environment section must report weather, because weather moves the spawn gate.
 *
 * <p>{@code EnvView} carried dimension, biome, time-of-day and the raw world time. It did not carry
 * rain, thunder, or the light at the player's own cell. That omission is not cosmetic:
 * {@code EntityMob.isValidLightLevel} ({@code EntityMob.java:145-165}) re-reads the cell with
 * {@code skylightSubtracted} forced to 10 while the world thunders, which turns a midday reading of
 * 15 into 5 and opens a spawn that a clear day closes. An agent that was told "noon" and nothing
 * about the storm would plan against the wrong afternoon.
 *
 * <p>And the day/night bucket is not the same fact as the light. {@code World.isDaytime} is
 * {@code skylightSubtracted < 4} ({@code World.java:862-865}) -- a threshold on the factor, not on
 * the clock -- so a caller that derives daylight from the hour bucket is deriving it from something
 * that is not what the game uses.
 *
 * <p>The rules are transcribed from the vendored source and compared directly.
 */
public final class AnEnvironmentReportsTheWeatherThatMovesTheSpawnGateTest {

    /**
     * {@code World.java:3551-3562}.
     *
     * <p>Both use the 1.0f multiplier, i.e. the raw strength: thunder is above 0.9 and rain above
     * 0.2. These are separate thresholds, so "raining" and "thundering" are different facts and a
     * caller told only one of them cannot derive the other.
     */
    static boolean thundering(float thunderStrength) {
        return thunderStrength > 0.9F;
    }

    static boolean raining(float rainStrength) {
        return rainStrength > 0.2F;
    }

    /** {@code World.isDaytime}, {@code World.java:862-865}. */
    static boolean daytime(int skylightSubtracted) {
        return skylightSubtracted < 4;
    }

    @Test
    public void rainAndThunderAreSeparateThresholds() {
        assertTrue("a full-strength storm thunders", thundering(1.0F));
        assertTrue("a light shower rains without thundering", raining(0.5F) && !thundering(0.5F));
        assertTrue("a drizzle below 0.2 is not rain at all", !raining(0.1F));
        assertTrue("and the threshold is strict: exactly 0.9 is not thunder",
                !thundering(0.9F));
    }

    @Test
    public void daylightIsAThresholdOnTheFactorNotOnTheHour() {
        assertTrue("noon reads 0", daytime(0));
        assertTrue("4 is already night by the game's own rule", !daytime(4));
        assertTrue("and 11, a deep night, is not", !daytime(11));
    }

    @Test
    public void theProductionRulesAgreeWithTheVanillaOnes() {
        for (int t = 0; t <= 100; t++) {
            float f = t / 100.0F;
            assertEquals("thunder at " + f, thundering(f),
                    EnvironmentWeather.isThundering(f));
            assertEquals("rain at " + f, raining(f), EnvironmentWeather.isRaining(f));
        }
        for (int s = 0; s <= 15; s++) {
            assertEquals("daytime at skylightSubtracted=" + s, daytime(s),
                    EnvironmentWeather.isDaytime(s));
        }
    }

    @Test
    public void aStormIsTheCaseThatChangesAnAnswer() {
        int midday = 0;
        assertTrue("precondition: a clear noon is day", EnvironmentWeather.isDaytime(midday));
        int underStorm = BlockInspector.skylightAmountFor(midday, true);
        assertTrue("and under thunder the spawn gate reads the sky factor as 10, so the light AT a "
                        + "clear-noon cell falls to 5 and a mob can spawn where a clear day could "
                        + "not: " + BlockInspector.lightAtPosition(15, 0, underStorm),
                BlockInspector.lightAtPosition(15, 0, underStorm) <= 7);
        assertTrue("while the clear-noon reading is 15 and safe",
                BlockInspector.lightAtPosition(15, 0, midday) > 7);
    }
}
