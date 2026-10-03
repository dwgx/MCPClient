package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

import net.marcloud.mcp.core.io.http.Json;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import org.junit.Test;

/**
 * An environment read that FAILED used to reach the model as a quiet {@code false}.
 *
 * <p><b>What was on the wire.</b> {@code WorldViewJson.envMap} emitted {@code raining},
 * {@code thundering}, {@code daytime} and {@code lightAtPlayer} only when at least one of them was
 * readable. Two different states collapsed into that one shape:
 *
 * <ul>
 *   <li>night, clear, light 4 -- and</li>
 *   <li>night, weather reads threw, light read threw.</li>
 * </ul>
 *
 * <p>Both produced {@code {dimension, biome, timeOfDay, worldTime}} and nothing else, so "it is
 * definitely not raining" and "nobody could find out" were the same four keys. Worse, the second
 * state also lost {@code daytime:false} -- the one key that would have said it was night at all --
 * so a caller could not infer even the time of day from the payload.
 *
 * <p><b>What ships now.</b> All four keys always, {@code null} for a read that failed, and the
 * capture itself records the failure rather than defaulting to {@code false}. A {@code null} is not
 * the same fact as {@code false}, so the model can act on the difference.
 *
 * <p><b>Three assertions, three disjoint red sets, reading three different layers.</b> A writer that
 * dropped null-valued keys would leave the first two green; an encoder that kept the keys but
 * refilled them with the old sentinels would leave the first green. Only all three together say the
 * failure survives into the JSON the model actually parses:
 *
 * <ul>
 *   <li>{@link #aNightWithNothingElseReadableStillSaysWhichKeysAreUnknown} -- the map's SHAPE.</li>
 *   <li>{@link #aFailedReadAndAFalseOneAreNoLongerTheSamePayload} -- the VALUE SEMANTICS, driven
 *       through the real capture on a world whose weather reads throw.</li>
 *   <li>{@link #theUnreadEnvironmentReachesTheJsonAsAnExplicitNull} -- the SERIALIZER.</li>
 * </ul>
 *
 * <p>The world is a real {@link WorldClient} allocated without its constructor and carrying a real
 * {@link WorldInfo}, which is the smallest thing that makes {@code getWorldTime()} answer, with the
 * two weather reads overridden to throw. Nothing here re-derives what the capture does: it calls
 * {@code WorldViewCapture.env} itself, on a world shaped the way a client in a half-initialised
 * world actually is.
 */
public final class AnUnreadEnvironmentReadIsNotAQuietNoTest {

    /** Midnight, so the clock-derived {@code daytime} really is false. */
    private static final long MIDNIGHT = 18000L;

    /** A night with every weather and light read readable. */
    private static final EnvView A_CLEAR_NIGHT = new EnvView("Overworld", "Plains", "night",
            MIDNIGHT, Boolean.FALSE, Boolean.FALSE, Boolean.FALSE, 4);

    @Test
    public void aNightWithNothingElseReadableStillSaysWhichKeysAreUnknown() throws Exception {
        EnvView env = captureEnvOnAWorldThatAnswersOnlyTheClock(MIDNIGHT);
        Map<String, Object> m = WorldViewJson.envMap(env);
        assertTrue("the fixture must fail three of the four reads, or it is not the state under "
                + "test: " + m, env.raining() == null && env.thundering() == null
                && env.lightAtPlayer() == null);
        assertEquals("every env key is present on every full view, whatever the reads returned: "
                + m, Set.of("dimension", "biome", "timeOfDay", "worldTime", "raining", "thundering",
                "daytime", "lightAtPlayer"), m.keySet());
        assertNull("a read that failed is null, and null is not the same fact as false: " + m,
                m.get("raining"));
        assertNull("and the light is null too -- the old -1 was a NUMBER the model would do "
                + "arithmetic with: " + m, m.get("lightAtPlayer"));
        assertEquals("the clock did read, so the daylight answer derived from it is a real false "
                + "and must not be swept up with the failed reads: " + m, Boolean.FALSE,
                m.get("daytime"));
    }

    /**
     * The distinguishability claim, stated as a comparison rather than as a shape.
     *
     * <p>Before the change these two payloads compared EQUAL, which is the whole defect: the model
     * could not tell a night nobody could read from a night it had been told was clear.
     */
    @Test
    public void aFailedReadAndAFalseOneAreNoLongerTheSamePayload() throws Exception {
        Map<String, Object> heardNothing =
                WorldViewJson.envMap(captureEnvOnAWorldThatAnswersOnlyTheClock(MIDNIGHT));
        Map<String, Object> toldNothing = WorldViewJson.envMap(A_CLEAR_NIGHT);
        assertNotEquals("a night whose weather and light could not be read must not serialise to "
                + "the same map as a night the world explicitly reported as clear -- if it does, "
                + "the model is being told 'it is not raining' by a read that never happened: "
                + heardNothing + " vs " + toldNothing, heardNothing, toldNothing);
        assertEquals("and they must disagree on the light at least, since that is the key the old "
                + "-1 sentinel swallowed along with the rest: " + toldNothing, 4,
                toldNothing.get("lightAtPlayer"));
        assertNull("while the unread night says so instead of carrying a number: " + heardNothing,
                heardNothing.get("lightAtPlayer"));
    }

    /** The last layer: the null has to survive {@link Json#write} as a null, not as a dropped key. */
    @Test
    public void theUnreadEnvironmentReachesTheJsonAsAnExplicitNull() throws Exception {
        String heard = Json.write(WorldViewJson.envMap(
                captureEnvOnAWorldThatAnswersOnlyTheClock(MIDNIGHT)));
        String told = Json.write(WorldViewJson.envMap(A_CLEAR_NIGHT));
        assertTrue("the unread key must be on the wire with a null value, not omitted: " + heard,
                heard.contains("\"raining\":null"));
        assertTrue("and the light too: " + heard, heard.contains("\"lightAtPlayer\":null"));
        assertTrue("the readable night must not carry a null where it has an answer: " + told,
                told.contains("\"lightAtPlayer\":4"));
        assertNotEquals("the two documents must differ, or the agent cannot tell a night nobody "
                + "read from a night the world reported clear: " + heard + " vs " + told,
                heard, told);
    }

    // ===== the world =========================================================================

    /**
     * The real capture, driven on a world that answers the clock and refuses everything else.
     *
     * <p>{@code getWorldTime()} goes through {@code worldInfo}, so a client carrying a real
     * {@link WorldInfo} answers it. The two weather reads are overridden to throw; the light reads
     * need no help because they walk a chunk provider the allocation left null. What reaches
     * {@code WorldViewCapture.env} is therefore the real article: a world that would not say.
     */
    private static EnvView captureEnvOnAWorldThatAnswersOnlyTheClock(long worldTime) throws Exception {
        WorldInfo info = new WorldInfo(new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false,
                false, WorldType.DEFAULT), "MpServer");
        info.setWorldTime(worldTime);
        WorldClient world = (WorldClient) blank(SilentWeatherWorld.class);
        set(world, "worldInfo", info);
        Method env = WorldViewCapture.class.getDeclaredMethod("env", WorldClient.class,
                BlockPos.class);
        env.setAccessible(true);
        return (EnvView) env.invoke(null, world, new BlockPos(0, 64, 0));
    }

    /**
     * A client whose weather reads throw.
     *
     * <p>Needed because {@code World.isRaining} is {@code rainingStrength > 0.2F} -- a pure field
     * read that answers {@code false} on an uninitialised instance instead of failing. A plain blank
     * client therefore reports a clear sky for a read that never touched the world, which is this
     * file's defect reproduced by its own fixture.
     *
     * <p>The constructor never runs -- {@link #blank} allocates without invoking one -- so the nulls
     * it hands to {@code super} are never dereferenced.
     */
    private static final class SilentWeatherWorld extends WorldClient {

        private SilentWeatherWorld() {
            super(null, null, 0, EnumDifficulty.PEACEFUL, null);
        }

        @Override
        public boolean isRaining() {
            throw new UnsupportedOperationException("this world will not say");
        }

        @Override
        public boolean isThundering() {
            throw new UnsupportedOperationException("this world will not say");
        }
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