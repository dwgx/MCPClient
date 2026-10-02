package net.marcloud.mcp.core.drivers.world;

import net.minecraft.util.MathHelper;

/**
 * The celestial state of a world, computed from its clock.
 *
 * <p><b>Why this exists: the production day/night read was frozen.</b> {@code World.isDaytime}
 * ({@code World.java:866-869}) is {@code this.skylightSubtracted < 4}, and
 * {@code skylightSubtracted} has exactly two writers in the whole vendored tree:
 * {@code calculateInitialSkylight} ({@code World.java:2454-2461}), which the {@code WorldClient}
 * constructor calls ONCE ({@code WorldClient.java:59}), and the setter
 * ({@code World.java:3824-3827}). The only caller of that setter on a clock path is
 * {@code WorldServer.tick} ({@code WorldServer.java:197-202}) -- a SERVER method.
 * {@code WorldClient.tick} ({@code WorldClient.java:66-94}) calls {@code super.tick()} and then
 * advances {@code worldTime} and {@code totalWorldTime}; it never recomputes
 * {@code skylightSubtracted}. So on a multiplayer client the field holds whatever the constructor
 * computed, forever, while the clock it was derived from keeps running.
 *
 * <p>Measured on this tree, driving the real {@code World.calculateSkylightSubtracted}: a client
 * world constructed at {@code worldTime == 0} holds {@code skylightSubtracted == 0} and therefore
 * {@code isDaytime() == true} for its entire life. At the real midnight tick ({@code worldTime ==
 * 18000}) vanilla's own curve says {@code 11}, so the honest answer there is "night". The one
 * production read of "is it night" answered "no" at midnight, and nothing in the system could say
 * otherwise. That is the defect this class exists to remove, and it is a CRITICAL for the Owner's
 * north star: a weak model plus a system prompt has to survive the first night, and the prompt is
 * assembled from facts the client reports.
 *
 * <p><b>No {@code World} parameter, by construction.</b> Every method here is arithmetic on a
 * {@code long}. There is no field to cache into and no seam through which a world read could
 * enter, so this class is structurally incapable of becoming a second frozen read -- and
 * {@code TheClockCostsTheWalkNoWorldReadTest.theDaylightHelperContainsNoWorldCallAtAll} proves
 * that from the compiled bytecode rather
 * than from this sentence. The alternative (a helper taking the world and calling
 * {@code w.isDaytime()}) would have been a one-line fix that reproduced the defect exactly.
 *
 * <p><b>The threshold is not restated here.</b> {@link #isDaytime(long)} delegates to
 * {@link EnvironmentWeather#isDaytime(int)}, which already owns {@code skylightSubtracted < 4}
 * ({@code World.java:866-869}). A second copy of that comparison would be a second rule, and two
 * rules about when night begins is one more than a reader can check.
 *
 * <p><b>Where the numbers come from, and what the transcriptions are NOT.</b>
 * {@link #celestialAngle} is {@code WorldProvider.calculateCelestialAngle}
 * ({@code WorldProvider.java:115-133}) with the dead {@code f + (f - f) / 3} dropped (it is
 * {@code f + 0}, and keeping a line that cannot change a value is a line a reader has to verify).
 * {@link #skylightSubtracted} is {@code World.calculateSkylightSubtracted}
 * ({@code World.java:1403-1413}) and it keeps {@link MathHelper#cos}, because
 * {@code MathHelper.cos} is a 65536-entry lookup table ({@code MathHelper.java:38-41}) and
 * substituting {@code Math.cos} moves the dusk tick by one. {@link #isNight} is the same predicate
 * as {@link #isDaytime} with the sense flipped, and {@link #moonPhase} is
 * {@code WorldProvider.getMoonPhase} ({@code WorldProvider.java:135-138}).
 *
 * <p><b>The one thing this does not model, stated rather than approximated.</b> Vanilla's
 * {@code calculateSkylightSubtracted} also multiplies by the rain and thunder strengths
 * ({@code World.java:1409-1410}), so in a storm vanilla subtracts MORE light than this class does
 * and its {@code isDaytime} can flip while this one still says day. The weather is a separate
 * observation ({@link EnvView#raining()}, {@link EnvView#thundering()}) and a caller that wants
 * vanilla's exact storm-time answer has both facts in hand. This class answers the clock-only
 * question, and says so rather than pretending the storm is not there.
 */
public final class Daylight {

    /**
     * {@code WorldProvider.calculateCelestialAngle} ({@code WorldProvider.java:115-133}).
     *
     * <p>Kept faithful including the {@code < 0} / {@code > 1} wrap, which is what makes a
     * negative world time land in the right place in the day rather than in a wrapped-arithmetic
     * accident. {@code calculateCelestialAngle(0L, 1.0F)} is 0.8535997 (measured against the real
     * method) and the sun is at its apex at {@code calculateCelestialAngle(6000L, 1.0F) == 0.0F}.
     */
    public static float celestialAngle(long worldTime, float partialTicks) {
        int i = (int) (worldTime % 24000L);
        float f = ((float) i + partialTicks) / 24000.0F - 0.25F;

        if (f < 0.0F) {
            ++f;
        }

        if (f > 1.0F) {
            --f;
        }

        f = 1.0F - (float) ((Math.cos((double) f * Math.PI) + 1.0D) / 2.0D);
        return f;
    }

    /**
     * {@code World.calculateSkylightSubtracted} ({@code World.java:1403-1413}) with the rain and
     * thunder terms removed, which is the clear-sky case. Vanilla's own range is 0 (noon, full
     * light) to 11 (midnight).
     *
     * <p>The weather terms are omitted deliberately and the class javadoc says why; what matters
     * here is that the {@link MathHelper#cos} lookup is vanilla's, so the tick at which this
     * returns 4 is the tick at which vanilla's returns 4. Measured on this tree, driving the real
     * method: the first value {@code >= 4} is at {@code worldTime == 13807} and the first value
     * back below it is at {@code worldTime == 22193}.
     */
    public static int skylightSubtracted(long worldTime, float partialTicks) {
        float f = celestialAngle(worldTime, partialTicks);
        float f1 = 1.0F - (MathHelper.cos(f * (float) Math.PI * 2.0F) * 2.0F + 0.5F);
        f1 = MathHelper.clamp_float(f1, 0.0F, 1.0F);
        f1 = 1.0F - f1;
        f1 = 1.0F - f1;
        return (int) (f1 * 11.0F);
    }

    /**
     * {@code World.isDaytime} ({@code World.java:866-869}), computed from the clock.
     *
     * <p>Delegates to {@link EnvironmentWeather#isDaytime(int)} rather than restating the
     * comparison, so there is exactly one rule about where night begins.
     */
    public static boolean isDaytime(long worldTime) {
        return EnvironmentWeather.isDaytime(skylightSubtracted(worldTime, 1.0F));
    }

    /** The same predicate with the sense flipped, for callers asking the question they have. */
    public static boolean isNight(long worldTime) {
        return !isDaytime(worldTime);
    }

    /** {@code WorldProvider.getMoonPhase} ({@code WorldProvider.java:135-138}). */
    public static int moonPhase(long worldTime) {
        return (int) (worldTime / 24000L % 8L + 8L) % 8;
    }

    private Daylight() {
    }
}
