package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import org.junit.Test;

/**
 * The defect was not that a helper was missing. It was that the PRODUCTION read was frozen, and
 * only a test that drives that read can say so.
 *
 * <p><b>Why a test of {@link Daylight} in isolation would have proved nothing.</b> Before this
 * change, {@code WorldViewCapture.env} answered "is it day" with {@code w.isDaytime()}
 * ({@code WorldViewCapture.java:492}), and {@code World.isDaytime} is
 * {@code skylightSubtracted < 4} ({@code World.java:866-869}) against a field that on a client is
 * written exactly once -- by {@code calculateInitialSkylight()} in the {@code WorldClient}
 * constructor ({@code WorldClient.java:59}) -- and never again. A perfect new helper that nothing
 * called would have left the world view claiming noon at midnight, and every test of the helper
 * would have stayed green. So this test builds the world the way a client is built, moves its
 * clock the way the server moves it, and asks {@code WorldViewCapture} itself.
 *
 * <p><b>The world is built to be exactly as wrong as a real client.</b> {@code calculateInitialSkylight}
 * is run on it once, at world time 0, which is what the {@code WorldClient} constructor does; the
 * {@code skylightSubtracted} field is then left alone for the rest of the test, because on a
 * client nothing ever touches it again. That is not a shortcut, it is the setup: a test that
 * recomputed the field would be testing a world that does not exist.
 *
 * <p><b>Red before, green after, and the message says which.</b> The assertion is on
 * {@link EnvView#daytime()} -- the value that reaches the observation wire -- and the failure
 * message prints both answers, so the reader sees the frozen one and the correct one side by side.
 */
public final class TheProductionDayNightReadIsNotFrozenTest {

    /** {@code World.setWorldTime(6000L)} -- vanilla's own "noon" ({@code WorldServer.java:790}). */
    private static final long NOON = 6000L;
    /** The deepest point of the curve, where vanilla subtracts the full eleven. */
    private static final long MIDNIGHT = 18000L;

    /**
     * The production observation, asked at midnight, on a world whose clock says midnight.
     *
     * <p>This is the whole slice in one assertion. The world is a real {@code WorldClient} with a
     * real {@code WorldInfo} and a real provider, given its construction-time skylight and then
     * moved to {@code worldTime 18000}. {@link WorldViewCapture#env} is the private method the
     * {@code world_view} tool calls, driven directly rather than through the tool, because the
     * tool needs a live client and the value under test is a single line inside it.
     */
    @Test
    public void theWorldViewReportsNightWhenTheClockSaysMidnight() throws Exception {
        WorldClient world = clientWorldAtConstructionTime();
        boolean frozenRead = midnightWhereTheFieldNeverMoves(world);

        EnvView env = captureEnv(world);
        assertFalse("the world view reported DAY at worldTime " + world.getWorldTime() + ", where"
                        + " vanilla's own curve subtracts 11 of 15 and the correct answer is night."
                        + " World.isDaytime() said " + frozenRead + " on this very world and clock,"
                        + " so the observation wire is still carrying the frozen field: the read is"
                        + " World.isDaytime() and it has to become Daylight.isDaytime(worldTime)",
                env.daytime());
    }

    /**
     * The same read at noon, so the fix is not "always report night".
     *
     * <p>A one-sided fix -- a predicate that returns false unconditionally -- satisfies the test
     * above. This one does not, and it is the reason the two are in the same file rather than one
     * being enough.
     */
    @Test
    public void theWorldViewStillReportsDayWhenTheClockSaysNoon() throws Exception {
        WorldClient world = clientWorldAtConstructionTime();
        setWorldTime(world, NOON);

        EnvView env = captureEnv(world);
        assertTrue("the world view reported NIGHT at worldTime " + NOON + ", which is the sun at its"
                + " apex with nothing subtracted from skylight. A predicate that answers false"
                + " everywhere would pass the midnight test and fail here, and would be a new"
                + " frozen read wearing the old one's name", env.daytime());
    }

    /**
     * The read MOVES when the clock moves, which is the property the frozen one lacked.
     *
     * <p>Two instants on one world, one object, no reconstruction between them. The frozen field
     * cannot do this: it holds whatever the constructor computed, so every sample of it on this
     * world is the same value. Sampling across a full day and counting the transitions is
     * therefore a test of the READ rather than of the arithmetic.
     *
     * <p>Two transitions, not four. A day is 24,000 ticks and the sweep is 0..23,999, so it
     * contains dusk and dawn and does not contain the wrap past 24,000 -- a third crossing would
     * need one more sample than the day has. The daytime count below is the stronger half
     * anyway: 15,614 of 24,000 is the exact complement of the 8,386-tick night, so a read that
     * flipped at the WRONG tick fails on the count even when it flips the right number of times.
     */
    @Test
    public void theReadMovesTwiceInADayBecauseTheClockDoes() throws Exception {
        WorldClient world = clientWorldAtConstructionTime();
        int transitions = 0;
        Boolean previous = null;
        int daytimeTicks = 0;
        for (int t = 0; t < 24000; t++) {
            setWorldTime(world, t);
            boolean day = captureEnv(world).daytime();
            if (previous != null && day != previous) {
                transitions++;
            }
            if (day) {
                daytimeTicks++;
            }
            previous = day;
        }
        assertEquals("the production read changed twice in a full day -- once at dusk and once at"
                + " dawn. It changed " + transitions + " times, so on this world the observation"
                + " is not tracking the clock, and a frozen read changes it zero times.", 2,
                transitions);
        assertEquals("and it read day for 15,614 of the 24,000 ticks, which is the complement of"
                + " the 8,386-tick night the real curve produces. This is the half that catches a"
                + " read flipping at the WRONG tick: the transition count alone would not",
                24000 - 8386, daytimeTicks);
    }

    /**
     * The frozen field, measured, so the disagreement is between two numbers and not a claim.
     *
     * <p>Not the assertion under test -- the two above are. This one exists so the failure message
     * above can quote a measured value rather than a line number, and so a reader who wants to
     * check the premise can see it hold: the clock moved, the field did not, and the two reads
     * disagree at midnight.
     */
    @Test
    public void theFieldTheOldReadUsedIsFrozenWhileTheClockIsNot() throws Exception {
        WorldClient world = clientWorldAtConstructionTime();
        int atConstruction = world.getSkylightSubtracted();
        assertEquals("a client world computes its skylight subtraction once, at construction",
                0, atConstruction);

        setWorldTime(world, MIDNIGHT);
        assertEquals("the clock moved to midnight", MIDNIGHT, world.getWorldTime());
        assertEquals("and the field did not, because World.tick() never recomputes it and the"
                + " only clock-path writer is WorldServer.tick, which a client does not run",
                atConstruction, world.getSkylightSubtracted());
        assertTrue("so the OLD read says day at midnight", world.isDaytime());
        assertFalse("and the clock-derived read says night on the same world and the same clock",
                Daylight.isDaytime(world.getWorldTime()));
    }

    // ===== the world, and the one line under test =====

    /**
     * {@code WorldViewCapture.env} itself, driven directly.
     *
     * <p>Reflected rather than reimplemented on purpose. The value under test is one line inside
     * this method; a test that computed the same thing itself would agree with whatever that line
     * did, which is the exact shape of test this repo keeps having to catch.
     */
    private static EnvView captureEnv(WorldClient world) throws Exception {
        Method env = WorldViewCapture.class.getDeclaredMethod("env", WorldClient.class,
                BlockPos.class);
        env.setAccessible(true);
        return (EnvView) env.invoke(null, world, new BlockPos(0, 64, 0));
    }

    /**
     * A world carrying the skylight subtraction a real client is constructed with, and nothing
     * after that.
     *
     * <p>{@code calculateInitialSkylight()} is the constructor's own call
     * ({@code WorldClient.java:59}), and it is run here once, at world time 0. The field is then
     * left alone for the whole test -- which is the point, not an omission.
     */
    private static WorldClient clientWorldAtConstructionTime() throws Exception {
        WorldClient world = allocate(WorldClient.class);
        set(world, "worldInfo", new WorldInfo(new WorldSettings(0L,
                WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT), "MpServer"));
        set(world, "provider", allocate(WorldProviderSurface.class));
        set(world, "prevRainingStrength", 0.0F);
        set(world, "rainingStrength", 0.0F);
        set(world, "prevThunderingStrength", 0.0F);
        set(world, "thunderingStrength", 0.0F);
        world.calculateInitialSkylight();
        return world;
    }

    /**
     * Where the clock is at the deepest point of the curve, on a world whose field is frozen.
     *
     * @return what {@code World.isDaytime()} -- the read being replaced -- answers there.
     */
    private static boolean midnightWhereTheFieldNeverMoves(WorldClient world) throws Exception {
        setWorldTime(world, MIDNIGHT);
        return world.isDaytime();
    }

    private static void setWorldTime(WorldClient world, long t) throws Exception {
        infoOf(world).setWorldTime(t);
    }

    private static WorldInfo infoOf(WorldClient w) throws Exception {
        for (Class<?> c = w.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField("worldInfo");
                f.setAccessible(true);
                return (WorldInfo) f.get(w);
            } catch (NoSuchFieldException declaredHigher) {
                // worldInfo is declared on World, not on WorldClient; keep walking.
            }
        }
        throw new NoSuchFieldException("worldInfo on " + w.getClass().getName());
    }

    @SuppressWarnings("unchecked")
    private static <T> T allocate(Class<T> type) throws Exception {
        Class<?> unsafeType = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeType.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        return (T) unsafeType.getMethod("allocateInstance", Class.class)
                .invoke(theUnsafe.get(null), type);
    }

    private static void set(Object target, String field, Object value) throws Exception {
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException declaredHigher) {
                // keep walking
            }
        }
        throw new NoSuchFieldException(field + " on " + target.getClass().getName());
    }
}
