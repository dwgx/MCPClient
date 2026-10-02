package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import org.junit.Test;

/**
 * {@code world_view} and {@code scan_surroundings} must agree about the time of day.
 *
 * <p>They did not, because the bucketing existed twice: {@link WorldViewCapture#timeBucket}
 * normalised the modulus ({@code ((t % 24000) + 24000) % 24000}) and {@code WorldScanner}'s copy took
 * {@code t % 24000} raw. Java's remainder keeps the sign of the dividend, so at a world time of
 * {@code -6000} the two tools answered "night" and "sunrise" about the same instant -- and nothing
 * on either wire said which to believe. A negative world time is not hypothetical: {@code /time set}
 * and a restored save both reach it, and the game itself keeps ticking the counter.
 *
 * <p>The scanner's bucket is driven through its own private method against a world carrying the
 * time, rather than by re-implementing the arithmetic here: a test that re-derived the answer would
 * agree with whatever the production copy did, which is the shape this repo keeps catching.
 */
public final class BothObservationToolsBucketTimeTheSameWayTest {

    /** Every bucket boundary, one day either side of zero. */
    @Test
    public void theBucketBoundariesAreWhereTheLegendSaysTheyAre() {
        assertEquals("sunrise", WorldViewCapture.timeBucket(0L));
        assertEquals("day", WorldViewCapture.timeBucket(1000L));
        assertEquals("noon-afternoon", WorldViewCapture.timeBucket(6000L));
        assertEquals("sunset", WorldViewCapture.timeBucket(12000L));
        assertEquals("night", WorldViewCapture.timeBucket(13000L));
        assertEquals("sunrise", WorldViewCapture.timeBucket(23000L));
        assertEquals("and the cycle repeats a day later",
                WorldViewCapture.timeBucket(6000L), WorldViewCapture.timeBucket(6000L + 24000L));
    }

    /**
     * The regression: a negative instant must bucket the same in both tools.
     *
     * <p>Collected rather than failing on the first, so one run names every instant the two copies
     * disagree about.
     */
    @Test
    public void aNegativeWorldTimeIsBucketedTheSameWayByBothTools() throws Exception {
        StringBuilder disagreed = new StringBuilder();
        for (long t : new long[] {-1L, -1000L, -6000L, -12000L, -23000L, -23999L, -24000L, -24001L,
                -123456L}) {
            String here = WorldViewCapture.timeBucket(t);
            String there = scannerBucket(t);
            if (!here.equals(there)) {
                disagreed.append(t).append(": world_view=").append(here)
                        .append(" scan_surroundings=").append(there).append("; ");
            }
        }
        assertEquals("the two observation tools must not contradict each other about the same "
                + "instant -- one normalised the modulus and the other did not: " + disagreed,
                "", disagreed.toString());
    }

    /**
     * The shared answer, pinned to the value rather than to agreement with itself.
     *
     * <p>{@code -6000} is 18000 ticks into the day, which is the middle of the night. The old
     * scanner copy called it "sunrise", the one answer a model might act on by walking outside.
     */
    @Test
    public void theSharedAnswerForANegativeInstantIsTheNormalisedOne() throws Exception {
        assertEquals("night", scannerBucket(-6000L));
        assertEquals("night", WorldViewCapture.timeBucket(-6000L));
    }

    /** A time a whole day before the same instant is the same instant. */
    @Test
    public void aDayOfNegativeTimeIsTheSameInstant() throws Exception {
        assertEquals(WorldViewCapture.timeBucket(6000L), scannerBucket(6000L - 24000L));
    }

    // ===== the scanner's own bucket, driven =====

    /** The scanner's private bucket, fed a world whose clock reads {@code worldTime}. */
    private static String scannerBucket(long worldTime) throws Exception {
        Method m = WorldScanner.class.getDeclaredMethod("timeOfDay", WorldClient.class);
        m.setAccessible(true);
        return (String) m.invoke(null, worldAt(worldTime));
    }

    /**
     * The smallest world {@code getWorldTime} needs: vanilla's own chain is
     * {@code WorldClient.getWorldTime() -> WorldInfo.getWorldTime()}, so a blank client carrying a
     * real {@code WorldInfo} is enough -- and it keeps this test off the world constructor, which
     * has nothing to do with the clock.
     */
    private static WorldClient worldAt(long worldTime) throws Exception {
        WorldInfo info = new WorldInfo(new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false,
                false, WorldType.DEFAULT), "MpServer");
        info.setWorldTime(worldTime);
        Object world = blank(WorldClient.class);
        set(world, "worldInfo", info);
        return (WorldClient) world;
    }

    private static Object blank(Class<?> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), type);
    }

    /** Walks up the hierarchy: {@code worldInfo} is declared on {@code World}, not the client. */
    private static void set(Object target, String field, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException inherited) {
                // declared further up; keep walking
            }
        }
        throw new NoSuchFieldException(field + " on " + target.getClass().getName());
    }
}
