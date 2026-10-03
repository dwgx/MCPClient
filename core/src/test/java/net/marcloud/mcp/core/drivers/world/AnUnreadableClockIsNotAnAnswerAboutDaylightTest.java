package net.marcloud.mcp.core.drivers.world;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.io.http.Json;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.util.BlockPos;
import net.minecraft.world.EnumDifficulty;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.storage.WorldInfo;
import org.junit.Test;

/**
 * A clock that would not say what time it was used to be reported as sunrise, in broad daylight.
 *
 * <p><b>The defect.</b> {@code WorldViewCapture.env} boxes four readings and swallows every
 * exception, so a failed read survives as "no answer" -- and then it read the clock into a
 * primitive {@code long time = 0L} and derived the bucket and the daylight flag from it. So the
 * failed read did not stop at the clock: it answered three more questions. {@code Daylight
 * .isDaytime(0L)} is TRUE ({@code skylightSubtracted(0, 1.0F)} is 0, and the rule is
 * {@code skylightSubtracted < 4}), so a world that refused to say what time it was was reported as
 * a world in daylight -- and unlike a wrong {@code raining=false}, which the other three keys
 * contradict, nothing in that payload disagrees with it.
 *
 * <p><b>Why 0 cannot be the stand-in for "no answer".</b> Not because it would be ugly, but
 * because it is a real reading. {@code WorldInfo.populateFromWorldSettings} assigns seed, game
 * type, map features, hardcore, terrain type, generator options and allowCommands, and NOT
 * {@code worldTime}, so the field keeps its default and a client that has just joined reads
 * {@code 0} ({@code SimWorld} records the same fact, for the same reason). So "the clock did not
 * answer" and "it is sunrise" were the same wire text, and a failed read was indistinguishable
 * from a real one to every consumer including {@link WorldViewDiff}.
 *
 * <p><b>How the fixture is built, which is the reason this file exists at all.</b> The three
 * assertions in {@code AnUnreadEnvironmentReadIsNotAQuietNoTest} all hand the capture a real
 * {@link WorldInfo}, because that is the smallest thing that makes {@code getWorldTime()} answer --
 * and so every environment fixture in this suite until now produced a READABLE clock. Nothing ever
 * put a world in front of {@code env} that could not say what time it was, which is why the shape
 * survived a fix that boxed the four weather readings right beside it.
 *
 * <p>So this file's fixture is not a mock and not a seam: {@code World.getWorldTime()} is
 * {@code this.worldInfo.getWorldTime()}, and it is the ONLY read in {@code env} that touches
 * {@code worldInfo}. An allocated {@link WorldClient} with no {@code WorldInfo} on it therefore
 * fails the clock and nothing else -- vanilla's own {@link NullPointerException}, on the real
 * production path, with no production interface opened up for the test. The second fixture refuses
 * the clock by override instead, and produces a byte-identical {@link EnvView}; that is what makes
 * the first one a statement about the CATCH rather than about one exception type.
 *
 * <p>Every fixture here is a world whose weather reads ANSWER ({@code isRaining()} and
 * {@code isThundering()} are field reads, so a blank allocation answers false rather than
 * throwing). That is checked, not assumed -- it is what stops "the null is the clock's" from being
 * indistinguishable from "the whole world refused to answer", which is the reading a guard that
 * blanks everything would produce and the reason this file's assertions are not satisfied by one.
 */
public final class AnUnreadableClockIsNotAnAnswerAboutDaylightTest {

    /**
     * Tick zero, and the control for every assertion here.
     *
     * <p>It is the value a client world is CONSTRUCTED with, which is what makes it a real answer
     * rather than an available sentinel: a world genuinely at sunrise and a world that would not say
     * what time it was must be told apart, and this test exists to hold them apart.
     */
    private static final long A_REAL_SUNRISE = 0L;

    /** The tick {@code WorldViewDiff.envDiff} starts from, and the tick it recovers at. */
    private static final long MIDDAY = 6000L;
    private static final long MIDDAY_PLUS_ONE = 6001L;

    /** A self section that never varies, so a diff key can only have come from the env. */
    private static final SelfView SELF = new SelfView(0.0, 64.0, 0.0, 0.0, 0.0, 0.0, 0f, 0f,
            20.0F, 20, 5.0F, 0, 0.0F, 0, 300, "survival", false, false, true, List.of(), 0.0, 0,
            false);

    // ===== the clock that would not answer =================================================

    /**
     * The capture's own answers, on a world that will not say what time it is.
     *
     * <p>The first assertion in each test is the fixture check, and it is load-bearing: the weather
     * reads must still ANSWER, so that a null {@code daytime} below can only have come from the
     * clock. A world that refused everything would make the same {@code daytime} null for a
     * completely different reason, and the fix this file pins would then be pinning nothing.
     */
    @Test
    public void aClockThatWouldNotSayLeavesEveryAnswerItHadFedWithoutOne() throws Exception {
        for (WorldClient world : List.of(worldWithoutAWorldInfo(), worldThatRefusesTheClock())) {
            EnvView env = capture(world);
            String what = "world " + world.getClass().getSimpleName() + ": " + env;

            assertEquals("the fixture must fail ONLY the clock: isRaining() and isThundering() are "
                    + "field reads, so a blank world answers false rather than throwing, and if they "
                    + "were null this assertion below would prove nothing about daylight: " + what,
                    Boolean.FALSE, env.raining());
            assertEquals("same for thunder, which is what lightAtPlayer is gated on: " + what,
                    Boolean.FALSE, env.thundering());

            assertNull("a clock that did not answer must not answer whether it is daylight either. "
                    + "isDaytime(0L) is TRUE, so the unwrapped derivation reported a confident "
                    + "afternoon off a read that never happened: " + what, env.daytime());
            assertNull("and the clock itself is absent rather than 0, which is a real sunrise a "
                    + "just-joined world genuinely reports: " + what, env.worldTime());
            assertEquals("and the bucket is the token the legend already reserves for a failed read "
                    + "-- the five bucket names never spell it, so it cannot collide with a real "
                    + "answer: " + what, "unknown", env.timeOfDay());
        }
    }

    /**
     * The non-vacuity half, and the reason this file is not one assertion with a fixture.
     *
     * <p>A test that only proves the new code fires would also pass a guard that refuses to answer
     * everything. This one cannot: the SAME method, on the SAME allocated world, with the same
     * unreadable light reads and the same absent provider, must still return TRUE for a world that
     * really is at tick zero. Blanking the daylight answer unconditionally fails here.
     */
    @Test
    public void aWorldGenuinelyAtTickZeroStillReportsDaylight() throws Exception {
        assertTrue("the premise of this file: isDaytime(0L) is TRUE, which is the whole reason 0 is "
                + "not an available sentinel for a failed clock read. If this ever goes false the "
                + "defect this file closes was harmless and every argument here needs redoing",
                Daylight.isDaytime(A_REAL_SUNRISE));

        EnvView env = capture(worldAt(A_REAL_SUNRISE));
        assertEquals("a world that really is at sunrise must still be told it is daylight: a fix "
                + "that nulled the answer unconditionally would pass the test above and be useless",
                Boolean.TRUE, env.daytime());
        assertEquals("and it must carry the real clock rather than an absence", Long.valueOf(
                A_REAL_SUNRISE), env.worldTime());
        assertEquals("and its bucket", "sunrise", env.timeOfDay());
    }

    /**
     * The wire, and the distinguishability claim as a comparison rather than as a shape.
     *
     * <p>Before the change these two documents were identical, which is the defect: the model could
     * not tell a world nobody could give a clock to from a world at dawn.
     */
    @Test
    public void theUnreadableClockReachesTheJsonAsAbsenceRatherThanAsASunrise() throws Exception {
        String heard = Json.write(WorldViewJson.envMap(capture(worldWithoutAWorldInfo())));
        String told = Json.write(WorldViewJson.envMap(capture(worldAt(A_REAL_SUNRISE))));

        assertTrue("the daylight key must be on the wire with a null value: " + heard,
                heard.contains("\"daytime\":null"));
        assertTrue("and the clock: " + heard, heard.contains("\"worldTime\":null"));
        assertTrue("and the bucket as the reserved token: " + heard,
                heard.contains("\"timeOfDay\":\"unknown\""));
        assertFalse("and it must NOT carry the daylight that a read that never happened produced: "
                + heard, heard.contains("\"daytime\":true"));
        assertFalse("nor a worldTime of 0 -- the first three ticks of any world look exactly like "
                + "this, and a model told 'the clock is at dawn' on the strength of a read that "
                + "failed will act on it: " + heard, heard.contains("\"worldTime\":0"));
        assertTrue("while the world that really is at sunrise must keep both answers: " + told,
                told.contains("\"daytime\":true") && told.contains("\"worldTime\":0"));
        assertNotEquals("the two documents must differ, or the agent cannot tell a world nobody "
                + "could give a clock to from a world at dawn: " + heard + " vs " + told,
                heard, told);
    }

    /**
     * The direction {@link #aClockThatWouldNotSayLeavesEveryAnswerItHadFedWithoutOne} cannot see,
     * and the one that decides between the two encodings.
     *
     * <p>{@code worldTime} is on the wire because it moves every tick, so that any clock movement is
     * visible ({@code WorldViewDiff.envDiff} says so). That makes it the one key a failed read must
     * not quietly move: reporting 0 there tells a polling caller the world's clock went BACK six
     * thousand ticks, and the next poll reports 6001, so a counter that advances one tick per tick
     * invents a reset and a recovery around a read that simply failed.
     *
     * <p><b>Both directions are pinned, and one is not enough.</b> {@code 6000 -> null} alone would
     * go red under "no answer" and under "null treated as the value 0" alike -- and the second of
     * those is what not boxing {@code worldTime} actually does. {@code null -> 6001} is what
     * separates them: a null treated as a value either reports 0 again or goes quiet about the
     * clock coming back, and only a real absence reports where the world actually is.
     */
    @Test
    public void theDiffCarriesAnUnreadableClockAsItsOwnEventAndThenRecovers() throws Exception {
        WorldView readable = viewAt(1L, capture(worldAt(MIDDAY)));
        WorldView unreadable = viewAt(2L, capture(worldWithoutAWorldInfo()));
        WorldView recovered = viewAt(3L, capture(worldAt(MIDDAY_PLUS_ONE)));

        Map<String, Object> lost = envOf(WorldViewDiff.diff(readable, unreadable));
        assertTrue("the clock key exists so that any clock movement is visible, so a clock that "
                + "stopped answering must move it: " + lost, lost.containsKey("worldTime"));
        assertNull("and it must carry the absence rather than a 0. A 0 here reads as a world whose "
                + "clock jumped back " + MIDDAY + " ticks, which is an event that did not happen: "
                + lost, lost.get("worldTime"));
        assertNull("the daylight answer went with the reading it was derived from: " + lost,
                lost.get("daytime"));
        assertEquals("and the bucket is the reserved token rather than a fabricated hour: " + lost,
                "unknown", lost.get("timeOfDay"));

        Map<String, Object> back = envOf(WorldViewDiff.diff(unreadable, recovered));
        assertEquals("when the clock answers again the diff must report where the world ACTUALLY "
                + "is. This direction is what separates 'no answer' from 'a value': an absence read "
                + "as the number 0 either reports 0 here or says nothing at all about the clock "
                + "coming back, and either way the caller never learns the day moved on: " + back,
                MIDDAY_PLUS_ONE, back.get("worldTime"));
    }

    // ===== the worlds =====================================================================

    /**
     * The real capture, driven on a world shaped the way a half-initialised client is.
     *
     * <p>{@code World.getWorldTime()} is {@code this.worldInfo.getWorldTime()} and it is the only
     * read in {@code env} that goes through {@code worldInfo}, so a world with none fails the clock
     * and nothing else. What reaches {@code WorldViewCapture.env} is the real article.
     */
    private static EnvView capture(WorldClient world) throws Exception {
        Method env = WorldViewCapture.class.getDeclaredMethod("env", WorldClient.class,
                BlockPos.class);
        env.setAccessible(true);
        return (EnvView) env.invoke(null, world, new BlockPos(0, 64, 0));
    }

    /**
     * A client that was never given its {@link WorldInfo}, so its clock throws inside vanilla.
     *
     * <p>The smallest fixture that states the defect: no override, no mock, and no production
     * interface widened for the sake of being testable.
     */
    private static WorldClient worldWithoutAWorldInfo() throws Exception {
        return (WorldClient) blank(WorldClient.class);
    }

    /**
     * A client that refuses the clock outright.
     *
     * <p>The second fixture, and the one that makes the first mean something: this world is the
     * first plus an override, and {@link #aClockThatWouldNotSayLeavesEveryAnswerItHadFedWithoutOne}
     * asserts both produce the same answers. A fix keyed on the particular
     * {@link NullPointerException} above rather than on the catch that follows it passes the first
     * fixture alone.
     */
    private static WorldClient worldThatRefusesTheClock() throws Exception {
        return (WorldClient) blank(RefusesTheClock.class);
    }

    /** A client carrying a real {@link WorldInfo} at one tick -- the control for every assertion. */
    private static WorldClient worldAt(long worldTime) throws Exception {
        WorldInfo info = new WorldInfo(new WorldSettings(0L, WorldSettings.GameType.SURVIVAL, false,
                false, WorldType.DEFAULT), "MpServer");
        info.setWorldTime(worldTime);
        WorldClient world = (WorldClient) blank(WorldClient.class);
        set(world, "worldInfo", info);
        return world;
    }

    /**
     * A client whose clock throws something that is not a null dereference.
     *
     * <p>The constructor never runs -- {@link #blank} allocates without invoking one -- so the nulls
     * it would hand to {@code super} are never dereferenced.
     */
    private static final class RefusesTheClock extends WorldClient {

        private RefusesTheClock() {
            super(null, null, 0, EnumDifficulty.PEACEFUL, null);
        }

        @Override
        public long getWorldTime() {
            throw new UnsupportedOperationException("this world will not say what time it is");
        }
    }

    /** A whole view whose env section came out of a real capture, for the diff. */
    private static WorldView viewAt(long tickId, EnvView env) {
        return new WorldView(true, tickId, "test", SELF, null, List.of(), false, null, null, env);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> envOf(Map<String, Object> diff) {
        assertNotNull("the diff must carry an env section to be saying anything about the clock: "
                + diff, diff.get("env"));
        return (Map<String, Object>) diff.get("env");
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