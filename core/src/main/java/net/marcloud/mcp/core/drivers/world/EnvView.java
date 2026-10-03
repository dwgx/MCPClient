package net.marcloud.mcp.core.drivers.world;

/**
 * Environment context: dimension, biome, time-of-day bucket, raw world time, and the weather.
 *
 * <p>The weather fields are not decoration. {@code EntityMob.isValidLightLevel} re-reads a cell
 * with {@code skylightSubtracted} forced to 10 while the world thunders, which turns a midday
 * reading of 15 into 5 and opens a mob spawn that a clear day closes. An agent told "noon" and
 * nothing about the storm is planning against the wrong afternoon.
 *
 * <p>{@code lightAtPlayer} is the light AT the player's own cell, which is the quantity the spawn
 * gate compares -- not the hour. It is read as {@code max(storedSky - skylightSubtracted,
 * storedBlock)}, i.e. {@code getLightSubtracted(pos, amount)} with the factor the world actually
 * holds, so it is directly comparable with {@link BlockInspector}'s number for any other cell.
 *
 * <p><b>Every weather read is BOXED, and that is the point.</b> {@code raining}, {@code
 * thundering}, {@code daytime} and {@code lightAtPlayer} are {@link Boolean}/{@link Integer} rather
 * than {@code boolean}/{@code int} because each of the four reads in {@code WorldViewCapture.env}
 * can throw, and a primitive turned a failed read into a VALUE: a world that would not answer
 * {@code isRaining()} was reported as {@code raining=false}, which on the wire is the same text as
 * "it is definitely not raining". The capture isolated each read so one failure could not blank
 * the rest, and then encoded the failure as the absence of information -- a false, or a light of
 * {@code -1}, both of which are numbers a caller can act on. A nullable component makes "the read
 * failed" a state the wire can carry, and {@code null} is that state -- and it now covers FIVE
 * keys, not four: {@code raining}, {@code thundering}, {@code daytime}, {@code lightAtPlayer} and
 * {@code worldTime}. The sixth, {@code timeOfDay}, cannot be null because it is a label the legend
 * already reserves, and answers a failed clock read with {@code "unknown"} for the same reason a
 * dimension does. So no key in this record is left with an in-band default standing in for a read
 * that never happened.
 *
 * <p><b>{@code worldTime} is boxed on the same reasoning, and it is the harder case.</b> Every read
 * in {@code WorldViewCapture.env} can throw, including the clock, and this paragraph used to stop
 * at four of the five -- {@code daytime} was listed as a read when it is arithmetic on the clock,
 * and {@code worldTime} was left a primitive whose default was {@code 0}. A clock that would not
 * answer therefore shipped a sunrise, a {@code daytime:true}, and a {@code worldTime: 0}, all
 * three derived from a read that never happened. See the constructor below for why {@code 0} is
 * not an available sentinel here.
 *
 * <p>Reference-free otherwise: no World reference, no lookup, nothing derived here.
 */
public record EnvView(String dimension, String biome, String timeOfDay, Long worldTime,
                      Boolean raining, Boolean thundering, Boolean daytime,
                      Integer lightAtPlayer) {

    /**
     * The 4-argument shape, for a view captured before weather was reported.
     *
     * <p>{@code null} and not {@code false}: "this view never carried the weather" is a different
     * fact from "the weather was clear", and collapsing the two is what made a read that never
     * happened indistinguishable from a read that came back negative.
     *
     * <p>It is five arguments now, in the sense that matters: {@code worldTime} is boxed too, for
     * the same reason as the four. It was the last primitive left in this record and it was the
     * worst of them, because {@code 0} is not a free sentinel for a clock --
     * {@code WorldInfo.populateFromWorldSettings} assigns every save-level field and not
     * {@code worldTime}, so a client world that has just been joined genuinely reads {@code 0},
     * and {@code 0} is sunrise. A failed clock read therefore shipped the same {@code
     * worldTime: 0} as a world at dawn, and in diff mode it shipped something worse: a jump
     * backwards of however many ticks the real clock had advanced, which
     * {@link WorldViewDiff} reports as a movement of the counter. {@code null} and {@code 0} take
     * different branches in every consumer, which is the only argument for the boxing that
     * matters; see {@code WorldViewCapture.env}.
     */
    public EnvView(String dimension, String biome, String timeOfDay, Long worldTime) {
        this(dimension, biome, timeOfDay, worldTime, null, null, null, null);
    }
}
