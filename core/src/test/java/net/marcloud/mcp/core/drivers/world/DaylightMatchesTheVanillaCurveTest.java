package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.Locale;

import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.world.WorldProviderSurface;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import net.minecraft.util.MovementInput;
import org.junit.Test;

/**
 * The transcribed curve must BE the vanilla curve, checked against the vanilla code and not
 * against a report.
 *
 * <p><b>Why this test asserts against {@code World.calculateSkylightSubtracted} rather than
 * against numbers.</b> The design pass that produced {@link Daylight} estimated dusk 13806, dawn
 * 22194, noon 6000 and sunrise 0, and then warned about its own numbers because
 * {@code MathHelper.cos} ({@code MathHelper.java:38-41}) is a 65536-entry lookup table rather than
 * a function, so the exact tick is a property of the table. The warning was correct and the
 * estimates were wrong. Measured here, by driving the real method across all 24,000 ticks:
 * <b>dusk is 13807 and dawn is 22193</b> -- dusk one tick LATER than reported, dawn one tick
 * EARLIER. A test written against 13806/22194 would have been red for the right reason and then
 * "fixed" by editing the constants, which is how a transcription quietly stops being one.
 *
 * <p><b>So every boundary below is measured in the same run that asserts it.</b> The constants are
 * written in as the values this tree produces, and the assertions that matter are the ones that
 * compare {@link Daylight} against the world method tick by tick. If vanilla's curve changes, the
 * per-tick comparison goes red and names the tick; it does not go red because a report is stale.
 *
 * <p><b>The frozen-clock control.</b> The last test pins the property that makes the rest of this
 * file mean anything: with the daylight cycle off, a clock that starts in the morning never
 * reaches night no matter how long the world runs. Without that control, "the curve is right" and
 * "the clock never moves" are indistinguishable -- a substrate whose clock is stuck at 0 agrees
 * with the curve at every instant it is ever asked about.
 */
public final class DaylightMatchesTheVanillaCurveTest {

    /**
     * The boundaries this tree measures, from the run below.
     *
     * <p>{@code DUSK} is the first tick whose skylight subtraction is 4 or more, i.e. the first
     * tick vanilla's own {@code isDaytime} would answer {@code false}. {@code DAWN} is the first
     * tick after that which is back below 4.
     */
    private static final int DUSK = 13807;
    private static final int DAWN = 22193;
    /** {@code World.setWorldTime(6000L)} -- vanilla's own "noon" ({@code WorldServer.java:790}). */
    private static final int NOON = 6000;
    private static final int MIDNIGHT = 18000;
    /** One full day, as {@code WorldProvider.calculateCelestialAngle} counts it. */
    private static final int DAY = 24000;

    /**
     * The whole curve, tick by tick, against the real method.
     *
     * <p>This is the assertion that matters and the one that cannot be satisfied by a report. All
     * 24,000 ticks of {@code worldTime} are pushed through vanilla's
     * {@code World.calculateSkylightSubtracted} and through {@link Daylight#skylightSubtracted},
     * and the results must be equal at every single one. A transcription that dropped the
     * {@code MathHelper} lookup for {@code Math.cos}, or moved a clamp, or dropped a term, differs
     * somewhere in here and the message names the tick.
     */
    @Test
    public void everyTickOfTheDayMatchesVanillasOwnCalculation() throws Exception {
        WorldClient vanilla = vanillaWorld();
        WorldInfo info = infoOf(vanilla);
        int firstDisagreement = -1;
        String detail = "";
        for (int t = 0; t < DAY; t++) {
            info.setWorldTime(t);
            int theirs = vanilla.calculateSkylightSubtracted(1.0F);
            int ours = Daylight.skylightSubtracted(t, 1.0F);
            if (theirs != ours) {
                firstDisagreement = t;
                detail = String.format(Locale.ROOT,
                        "at worldTime %d vanilla computes %d and Daylight computes %d", t, theirs,
                        ours);
                break;
            }
        }
        assertEquals("Daylight's transcription of World.calculateSkylightSubtracted (World.java:1403-1413)"
                + " disagrees with the real method somewhere in the day -- and it disagreed at "
                + firstDisagreement + ": " + detail
                + ". The likely cause is MathHelper.cos: it is a 65536-entry lookup table"
                + " (MathHelper.java:38-41), so replacing it with Math.cos moves the boundary"
                + " by a tick while looking identical in review.", -1, firstDisagreement);
    }

    /**
     * The measured boundaries, asserted as the transitions rather than as four literals.
     *
     * <p>Each assertion names a FACT about the curve and states the tick it happens at, with the
     * tick read from vanilla's own method in the same run. If a future change moves dusk by one
     * tick, this goes red with the new number in the message and the reader can decide whether
     * that is correct, which is the decision a hardcoded expectation would have taken away.
     */
    @Test
    public void theDuskAndDawnTransitionsAreTheOnesVanillaProduces() throws Exception {
        WorldClient vanilla = vanillaWorld();
        WorldInfo info = infoOf(vanilla);

        int firstNight = -1;
        int firstDayAgain = -1;
        for (int t = 0; t < DAY; t++) {
            info.setWorldTime(t);
            if (EnvironmentWeather.isDaytime(vanilla.calculateSkylightSubtracted(1.0F))) {
                if (firstNight >= 0 && firstDayAgain < 0) {
                    firstDayAgain = t;
                    break;
                }
            } else if (firstNight < 0) {
                firstNight = t;
            }
        }

        assertEquals("the first tick vanilla's own isDaytime calls night, measured against"
                + " World.calculateSkylightSubtracted", DUSK, firstNight);
        assertEquals("the tick vanilla's own isDaytime calls day again, measured against"
                + " World.calculateSkylightSubtracted", DAWN, firstDayAgain);
        // And the two agree with the helper, which is what makes the helper's answer usable at all.
        assertEquals("Daylight and vanilla must call the same tick night", firstNight, firstNightTick());
        assertEquals("Daylight and vanilla must call the same tick dawn", firstDayAgain,
                firstDayTick());
    }

    /**
     * Noon is the top of the curve and sunrise is the bottom of the dark half.
     *
     * <p>Two different claims and both are worth pinning, because "noon" is the one instant a
     * player can check against a clock on the wall. At {@code worldTime 6000} vanilla's own
     * {@code celestialAngle} is 0.0 -- the sun at its apex -- and the skylight subtraction is 0,
     * the minimum the curve ever reaches. At {@code worldTime 0} the curve is also 0 and
     * {@code isDaytime} is true, which is what makes 0 the sunrise a client world is constructed
     * at.
     */
    @Test
    public void noonIsTheTopOfTheCurveAndSunriseIsDaylight() throws Exception {
        WorldClient vanilla = vanillaWorld();
        WorldInfo info = infoOf(vanilla);

        info.setWorldTime(NOON);
        assertEquals("at worldTime 6000 the sun is at its apex and nothing is subtracted from"
                + " skylight", 0, vanilla.calculateSkylightSubtracted(1.0F));
        assertEquals("so the same tick reads 0.0 from celestialAngle", 0.0F,
                vanilla.getCelestialAngle(1.0F), 0.0F);
        assertEquals("and Daylight agrees with both", 0, Daylight.skylightSubtracted(NOON, 1.0F));
        assertTrue("noon is daytime by the one rule there is", Daylight.isDaytime(NOON));

        info.setWorldTime(0L);
        assertEquals("a client world is constructed at worldTime 0, so sunrise must be the"
                + " curve's own starting point and must read as day", 0,
                vanilla.calculateSkylightSubtracted(1.0F));
        assertTrue("sunrise is daylight", Daylight.isDaytime(0L));
        assertEquals("and the celestial angle there is the one vanilla itself reports",
                vanilla.getCelestialAngle(1.0F), Daylight.celestialAngle(0L, 1.0F), 0.0F);
    }

    /**
     * Midnight is where the curve bottoms out, and it is the tick the frozen read got wrong.
     *
     * <p>This is the defect stated as a number. Vanilla's own curve at {@code worldTime 18000} is
     * 11 -- the maximum subtraction, full dark -- and a client world constructed at sunrise holds
     * 0 in its {@code skylightSubtracted} field for its entire life, so the production read
     * answered "day" here. Both facts are measured in this test rather than asserted from the
     * defect report, so the claim is checkable.
     */
    @Test
    public void midnightIsTheBottomOfTheCurveAndTheFrozenFieldSaysDayThere() throws Exception {
        WorldClient vanilla = vanillaWorld();
        WorldInfo info = infoOf(vanilla);

        info.setWorldTime(MIDNIGHT);
        int atMidnight = vanilla.calculateSkylightSubtracted(1.0F);
        assertEquals("midnight subtracts the full eleven, which is the maximum of the curve", 11,
                atMidnight);
        assertTrue("so Daylight calls it night", Daylight.isNight(MIDNIGHT));
        assertFalse("and not day", Daylight.isDaytime(MIDNIGHT));

        // The frozen half. A world that has only ever had calculateInitialSkylight() run on it --
        // which is exactly what a WorldClient gets, once, at worldTime 0.
        WorldClient fresh = vanillaWorld();
        fresh.calculateInitialSkylight();
        assertEquals("a freshly constructed client world holds no subtracted light", 0,
                fresh.getSkylightSubtracted());
        assertTrue("so World.isDaytime() -- the read this slice replaced -- says DAY at"
                + " construction", fresh.isDaytime());

        // Move its clock to midnight. Nothing recomputes the field, because on a client nothing
        // ever does: World.tick() does not call setSkylightSubtracted, and the only clock-path
        // writer is WorldServer.tick, which a client does not run.
        infoOf(fresh).setWorldTime(MIDNIGHT);
        assertEquals("the clock did move", MIDNIGHT, fresh.getWorldTime());
        assertEquals("and the field did not, which is the whole defect", 0,
                fresh.getSkylightSubtracted());
        assertTrue("so the old read is still claiming day at midnight", fresh.isDaytime());
        assertFalse("while the SAME clock read through Daylight calls it night -- which is the"
                + " disagreement this slice exists to remove, stated on one world and one clock",
                Daylight.isDaytime(fresh.getWorldTime()));
    }

    /** The moon phase is transcribed too, and a day is a whole number of phases. */
    @Test
    public void theMoonPhaseIsTranscribedAndTheCurveRepeatsEveryDay() {
        assertEquals("day 0 is phase 0", 0, Daylight.moonPhase(0L));
        assertEquals("day 1 is phase 1", 1, Daylight.moonPhase(24000L));
        assertEquals("day 8 wraps back to phase 0", 0, Daylight.moonPhase(8L * 24000L));
        assertEquals("a negative world time still lands in range rather than throwing", 1,
                Daylight.moonPhase(-7L * 24000L));

        // The curve is a function of worldTime mod 24000, so a day later is the same instant.
        for (int t : new int[] {0, 1, NOON, DUSK - 1, DUSK, MIDNIGHT, DAWN, DAY - 1}) {
            assertEquals("worldTime " + t + " and one day later are the same instant of the curve",
                    Daylight.isDaytime(t), Daylight.isDaytime(t + DAY));
            assertEquals("and they subtract the same light", Daylight.skylightSubtracted(t, 1.0F),
                    Daylight.skylightSubtracted(t + DAY, 1.0F));
        }
    }

    /**
     * The control: a clock that is not advancing cannot produce a night.
     *
     * <p>Without this, every assertion above is satisfiable by a substrate whose clock is stuck.
     * "The curve is right" and "the clock never moves" are the same observation to a test that
     * only ever asks about the curve, and a night is a SEQUENCE -- something that happened over
     * time -- not a state the curve can be in. So this drives the real substrate for 24,000
     * ticks, which is a full day, and asks whether night ever arrived.
     */
    @Test
    public void withTheDaylightCycleOnTheClockReachesNightAndWithItOffItNeverDoes() {
        net.marcloud.mcp.core.eval.SimWorld running = new net.marcloud.mcp.core.eval.SimWorld()
                .plain(64, "stone", -2, 2, -2, 2).standOn(0, 64, 0);
        MovementInput input = new MovementInput();
        running.atWorldTime(0L);

        // A full day, sampled every tick, rather than stopping at the first night. The whole day
        // is the evidence: it shows the night ARRIVED (and arrived at the measured tick) and that
        // the clock covered a complete cycle while it did.
        long reachedNightAt = -1L;
        long lastNightAt = -1L;
        int nightTicks = 0;
        for (int i = 0; i < DAY; i++) {
            if (running.isNight()) {
                if (reachedNightAt < 0) {
                    reachedNightAt = running.worldTime();
                }
                lastNightAt = running.worldTime();
                nightTicks++;
            }
            running.tick(input);
        }
        assertEquals("a clock advancing one tick per tick reaches night exactly where vanilla's"
                + " own curve says it does", DUSK, reachedNightAt);
        assertEquals("and the night ended one tick before dawn, which is the tick before the curve"
                + " crosses back under 4", DAWN - 1L, lastNightAt);
        assertEquals("so a full day holds exactly as many night ticks as the curve says it does,"
                        + " which is 22193 - 13807", DAWN - DUSK, nightTicks);
        assertEquals("and the clock covered the whole day while it did", DAY, running.worldTime());

        // The control. Same world, same number of ticks, cycle off.
        net.marcloud.mcp.core.eval.SimWorld frozen = new net.marcloud.mcp.core.eval.SimWorld()
                .plain(64, "stone", -2, 2, -2, 2).standOn(0, 64, 0).doDaylightCycle(false);
        frozen.atWorldTime(0L);
        for (int i = 0; i < DAY; i++) {
            assertFalse("a pinned clock with the cycle off must never reach night, and it did at"
                    + " tick " + i + " (worldTime " + frozen.worldTime() + ")", frozen.isNight());
            frozen.tick(input);
        }
        assertEquals("and a full day of ticks later it has not moved at all", 0L,
                frozen.worldTime());
    }

    /** The first tick at or after 0 that {@link Daylight} calls night. */
    private static int firstNightTick() {
        for (int t = 0; t < DAY; t++) {
            if (Daylight.isNight(t)) {
                return t;
            }
        }
        throw new AssertionError("Daylight never reports night in a whole day, which is not a"
                + " curve this test knows how to fail on");
    }

    /** The first tick at or after dusk that {@link Daylight} calls day again. */
    private static int firstDayTick() {
        for (int t = firstNightTick(); t < DAY + DAY; t++) {
            if (Daylight.isDaytime(t)) {
                return t;
            }
        }
        throw new AssertionError("Daylight never reports day again, which is not a curve this test"
                + " knows how to fail on");
    }

    // ===== the vanilla world, built as small as the clock allows =====

    /**
     * A world with a real {@code WorldInfo} and a real {@code WorldProvider}, and nothing else.
     *
     * <p>Underscore-allocated on purpose: {@code World}'s constructor loads chunks and finds a
     * spawn point, none of which the clock depends on, and the chain that matters is only
     * {@code getCelestialAngle -> provider.calculateCelestialAngle(worldInfo.getWorldTime())}.
     * The rain and thunder fields are pinned to 0, which is the clear-sky case that
     * {@code Daylight.skylightSubtracted} transcribes.
     */
    private static WorldClient vanillaWorld() throws Exception {
        WorldClient world = allocate(WorldClient.class);
        set(world, "worldInfo", new WorldInfo(new WorldSettings(0L,
                WorldSettings.GameType.SURVIVAL, false, false, WorldType.DEFAULT), "MpServer"));
        set(world, "provider", allocate(WorldProviderSurface.class));
        set(world, "prevRainingStrength", 0.0F);
        set(world, "rainingStrength", 0.0F);
        set(world, "prevThunderingStrength", 0.0F);
        set(world, "thunderingStrength", 0.0F);
        return world;
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
