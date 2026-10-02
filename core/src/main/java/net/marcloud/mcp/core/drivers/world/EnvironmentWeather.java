package net.marcloud.mcp.core.drivers.world;

/**
 * The weather facts, separated from the world so they are reachable by a test.
 *
 * <p>{@code World.isRaining} and {@code World.isThundering} ({@code World.java:3551-3562}) both
 * call their strength getter with a 1.0f multiplier and compare against different constants --
 * 0.9 for thunder, 0.2 for rain. So they are two independent facts with two independent
 * thresholds: a shower rains without thundering, and neither can be derived from the other.
 *
 * <p>They are separated from {@code World} here for the same reason {@link BlockInspector}'s
 * thunder rule is: a rule that can only be exercised by standing in a storm is a rule nobody
 * checks, and a mutation that deleted either threshold ran green.
 *
 * <p>Daylight is here too, and it is deliberately NOT the time-of-day bucket.
 * {@code World.isDaytime} ({@code World.java:862-865}) is {@code skylightSubtracted < 4}, a
 * threshold on the daylight factor rather than on the clock. Two things can therefore read "day"
 * by this rule at a time the hour bucket says otherwise, and a caller deriving daylight from the
 * hour is deriving it from a different fact than the game uses.
 */
public final class EnvironmentWeather {

    /** {@code World.isThundering}: {@code getThunderStrength(1.0F) > 0.9}. */
    public static boolean isThundering(float thunderStrength) {
        return thunderStrength > 0.9F;
    }

    /** {@code World.isRaining}: {@code getRainStrength(1.0F) > 0.2}. */
    public static boolean isRaining(float rainStrength) {
        return rainStrength > 0.2F;
    }

    /** {@code World.isDaytime}: {@code skylightSubtracted < 4}. */
    public static boolean isDaytime(int skylightSubtracted) {
        return skylightSubtracted < 4;
    }

    private EnvironmentWeather() {
    }
}
