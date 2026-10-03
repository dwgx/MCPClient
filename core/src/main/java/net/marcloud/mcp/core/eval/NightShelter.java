package net.marcloud.mcp.core.eval;

import java.util.function.Consumer;
import java.util.function.Supplier;

import net.marcloud.mcp.core.drivers.world.Daylight;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * The shelter counter, running.
 *
 * <p><b>What this is.</b> {@link NightEnclosure} knows how to fold a night of enclosure verdicts
 * into a ledger, and until now the only thing that ever fed it was an eval task. This is the
 * missing half: a driver that feeds it from the tick seam, so the ledger accumulates whether or
 * not anybody is watching. That is the difference between an instrument and a surface. A model
 * that polls {@code night_shelter} at tick 400 of a night and at tick 4,000 sees a number move.
 * A model that polls once, at dawn, sees a constant -- and {@code NightEnclosure}'s own javadoc
 * is the argument against that ({@code :10-26}): a roof built at 4am is standing when the sun
 * comes up.
 *
 * <p><b>Counter, not verdict, and why the distinction is load-bearing.</b>
 * {@code NightEnclosure.sheltered()} is {@code measured() && openSamples == 0}, and
 * {@code openSamples} is monotone non-decreasing. So the verdict is already false the instant the
 * first unsheltered sample lands and carries no information about the future from that moment
 * on. What it destroys is the part a model can still act on: {@code longestUnreachableTicks()} and
 * {@code firstOpenTick()} both keep moving while the night is happening, and "exposed for 200
 * ticks with 4,000 night ticks left" is a different situation from "exposed for 200 ticks with 40
 * left". A boolean cannot tell those apart; the counter can. So the counter is the state and the
 * verdict is a projection of it -- and the projection is still available, because at dawn
 * {@code openSamples == 0} IS the final answer, because nothing after dawn can change it. One
 * shape, read early for the gradient and at dawn for the verdict.
 *
 * <p><b>Driven by the tick, and that is a decision rather than a detail.</b> Two facts force it.
 * An observation cannot complete faster than one game tick: {@code GameBridge.onGameThread} is
 * {@code mc.addScheduledTask} plus {@code future.get}, and {@code KeGameDispatcher.awaitOrCancel}
 * DELIBERATELY does not cancel on timeout ({@code :99-113}), because cancelling a queued
 * {@code FutureTask} kills the client -- observed live three times. So if the accumulator were
 * poll-driven, the poll rate would be the sample rate, and the sample rate would be a decision
 * nobody wrote down. A tick subscriber samples at 20 Hz because the game ticks at 20 Hz.
 *
 * <p><b>What one sample costs, as a number rather than an adjective.</b> One
 * {@link Enclosure#at} call reads EIGHT cells -- the eight horizontal neighbours of the two cells
 * a standing body occupies -- and asks the sky question of the chunk height map instead of walking
 * the column. Eight {@code isBlockLoaded} + eight {@code getBlockState} per NIGHT tick, and
 * ZERO during the 15,614 day ticks, because {@link NightEnclosure#observe} gates on
 * {@link Daylight#isNight(long)} before it touches the grid at all. Per night that is
 * {@code 8 x nightTicks()} -- 67,088 block reads on this tree -- spread over 6m59s, which is about
 * 1.9 {@code scan_surroundings} calls at the default radius 16 (35,937 reads each). The number is
 * here because a cost that is only described is a cost nobody can decide about; {@code
 * TheShippedShelterCounterCostsEightReadsASampleAndNothingADayTickTest} measures it rather than
 * repeating it.
 *
 * <p><b>It does not read the world through the controller seam.</b> The zero-world-read gates pin
 * {@code ActActuator.blockAt} at exactly 25 reads for a walk, and the obvious way to ask "is the
 * body enclosed" from inside a controller is that method. This class is not a controller: it
 * subscribes to the kernel's own {@link TickEvent} and reads {@code WorldClient} on the game
 * thread, the same way {@code WorldViewCapture} does. Those are different seams and the difference
 * is asserted, not asserted-about.
 *
 * <p><b>Why the ledger resets when the world changes.</b> A counter that silently spanned two
 * nights would report {@code openSamples == 0} for a window twice as long as any window the rest
 * of this package reports, and would call two bodies one. Identity of the {@link World} object is
 * the reset trigger -- not a heuristic, not a timer -- so a dimension change or a fresh single-
 * player world starts a ledger rather than corrupting one.
 *
 * <p><b>How this class publishes itself, and the one way it used not to.</b> Every field a reader
 * can see is written by {@link #onTick} on the game thread and may be read from any thread, so
 * every one of them is {@code volatile} and every accessor hands back a copy of a scalar. The
 * published state of this class is therefore a SET OF SNAPSHOTS, and it had exactly one escape
 * from that rule: a {@code public NightEnclosure ledger()} that returned the accumulator itself.
 * That accessor is gone, and the reason it had to go is not that a stale read is embarrassing.
 * {@link NightEnclosure}'s counters are ordinary fields with no {@code volatile} on them, so a
 * reader off the game thread had no happens-before edge to the writer at all; and because
 * {@code NightEnclosure.observe} is public, holding the object also meant being able to WRITE
 * the counters {@link #sheltered()} is derived from.
 *
 * <p>Removing it removed no readable state: every read {@code NightEnclosure} offers is
 * republished here field for field, which
 * {@code NightShelterPublishesSnapshotsAndNeverTheAccumulatorTest} checks by reflection rather
 * than taking on trust. What that accessor could never have given anyone -- several counters read
 * mutually consistently -- is a real gap with a different owner: {@code ShelterTools} fills one
 * payload by calling these accessors one at a time, so a payload assembled across a tick
 * boundary can mix two ticks' counters. That is a property of the per-field snapshot design
 * above, it predates the accessor, and closing it means publishing one immutable value instead of
 * one volatile per counter -- a change to the tool layer, not a deletion.
 */
public final class NightShelter {

    /** How the accumulator gets at a body. GAME THREAD ONLY, like {@code WorldViewCapture}. */
    public interface Body {

        /**
         * The world this body is in, or null when the client is not in one. Identity is the reset
         * trigger: a change of object means a change of world, not a change of cell.
         */
        Object world();

        /** The world clock, in ticks. */
        long worldTime();

        /** The cell lookup for this world. */
        Enclosure.CellGrid grid();

        /** The body's feet cell X, or 0 when there is no body to measure. */
        int feetX();

        int feetY();

        int feetZ();
    }

    /**
     * One tick's worth of accounting, handed to the tick subscriber.
     *
     * <p>A consumer rather than a hard {@code bus.subscribe} so a caller can drive the
     * accumulator by hand -- which is how the negative control drives it, and driving it by hand
     * is not a test convenience: it is the only way to put the SAME world in front of the SAME
     * code twice and change one block between the runs.
     */
    public void onTick() {
        ticksDelivered = true;
        Body body = source == null ? null : source.get();
        if (body == null || body.world() == null) {
            // No body, no sample, and NOT a reset: the client can be mid-world-load for a tick or
            // two, and throwing the ledger away on that would be a far worse lie than a gap. The
            // early return is also why this is not where `lastTickSeen` is set: a tick with no
            // world behind it has been delivered but not measured, and those are different facts.
            return;
        }
        Object w = body.world();
        if (ledger == null || !w.equals(world)) {
            ledger = new NightEnclosure(periodTicks);
            world = w;
        }
        long now = body.worldTime();
        lastClock = now;
        sampled = ledger.observe(body.grid(), body.feetX(), body.feetY(), body.feetZ(), now);
        // The verdict of the most recent sample, READ from the instrument rather than derived from
        // the counter moving. Deriving it was wrong at every window boundary: this was
        // `ledger.openSamples() > openBefore` with openBefore read before observe, and observe
        // resets the window at each dusk and on a clock moved backwards, so at the first tick of
        // a night the counter went (last night's total) -> 1 and a body that really was exposed was
        // reported safe. Measured: night 1 ends at openSamples=100, night 2's first tick samples
        // openSamples=1 and exposedNow answered false. The instrument now stores the verdict at
        // the point that took the sample, which is the only position that knew it, and the
        // derivation cannot disagree with the running total because there is no derivation left.
        exposedNow = sampled && ledger.lastSampleOpen();
        lastSampleOpen = ledger.lastSampleOpen();
        lastTickSeen = true;
        samples = ledger.samples();
        openSamples = ledger.openSamples();
        enclosedSamples = ledger.enclosedSamples();
        longestOpen = ledger.longestUnreachableTicks();
        firstOpenTick = ledger.firstOpenTick();
        firstNightTick = ledger.firstNightTick();
        lastNightTick = ledger.lastNightTick();
        sheltered = ledger.sheltered();
        intoNight = NightEnclosure.ticksIntoNight(now);
    }

    /** Where the body comes from; null means "nothing to measure", which is a legal state. */
    private Supplier<? extends Body> source;

    /**
     * Game-thread owned: written by {@link #onTick} and never published directly.
     *
     * <p><b>There is deliberately no getter for this field, and the reason is the field's
     * mutability rather than its visibility.</b> There was one -- {@code public NightEnclosure
     * ledger()} -- and it is the one design flaw this class shipped.
     * {@link NightEnclosure} is rewritten in place on every sampled tick and its counters are
     * plain fields rather than {@code volatile} ones, so handing the object to another thread
     * gave that thread neither a current answer nor a happens-before edge to the game thread; and
     * because {@code NightEnclosure.observe} is public, it also gave that thread a way to write
     * the counters {@link #sheltered()} is derived from. Package-private would not have been
     * enough: everything else in this package is driven from the same game thread, so the
     * hazard would have survived with a fence around it, and {@code @Deprecated} would have left
     * it live behind a warning. Deleting the accessor lost no readable state, because every read
     * {@code NightEnclosure} offers is republished below as a {@code volatile} scalar -- an
     * invariant {@code NightShelterPublishesSnapshotsAndNeverTheAccumulatorTest} holds by
     * reflection rather than by this comment's word.
     */
    private NightEnclosure ledger;
    private Object world;
    private final int periodTicks;

    /** The world clock of the most recent tick, or 0 before the first one. */
    private volatile long lastClock;
    private volatile boolean lastTickSeen;

    /**
     * Marks that a tick actually ARRIVED, before anything else in this method can decide there is
     * nothing to look at.
     *
     * <p>Separate from {@link #lastTickSeen} because {@link #fact()} has to name a cause and the
     * two causes are not interchangeable: a seam that never delivered a tick is dead wiring
     * (no {@code -javaagent}, {@code -Dmcp.core.tick=false}, an accumulator that was never
     * attached), and a seam that is delivering ticks the client has no world for is a client
     * sitting on the title screen. They look identical from {@code lastTickSeen} alone, and
     * attributing the second to the first is what this flag exists to stop.
     *
     * <p>Written first in {@link #onTick()} on purpose, before the supplier is consulted: a tick
     * that reached this class has been delivered whatever the body turns out to be, so a
     * throwing supplier must not turn it into "the seam is silent".
     */
    private volatile boolean ticksDelivered;

    private volatile boolean sampled;
    private volatile int samples;
    private volatile int openSamples;
    private volatile int enclosedSamples;
    private volatile long longestOpen;
    private volatile long firstOpenTick = -1L;
    private volatile long firstNightTick = -1L;
    private volatile long lastNightTick = -1L;
    private volatile boolean sheltered;
    private volatile int intoNight;
    private volatile boolean exposedNow;
    private volatile boolean lastSampleOpen;

    /**
     * Whether the MOST RECENT sample found the body reachable — the one field here that is about
     * the present rather than the window.
     *
     * <p>It exists because a running total cannot answer "is it happening NOW", and a model
     * deciding what to do in the next few seconds needs that rather than a total. It is read from
     * {@link NightEnclosure#lastSampleOpen()} — the verdict the sample point wrote — rather than
     * derived here from the counter's movement, because a window reset moves that counter without
     * anything having been observed, and the subtraction then reported a body standing in the open
     * as safe on the first tick of every night.
     *
     * <p>Gated on {@code sampled}: when this tick took no sample there is nothing to have seen, so
     * "not exposed" here means "not looked at", which is what {@link #sampledLastTick()} is for.
     * At a sampling period above one this is therefore the last SAMPLE's answer and not this
     * tick's, which is the same distinction {@link NightEnclosure#lastSampleOpen()} documents.
     */
    public boolean exposedNow() {
        return lastTickSeen && exposedNow;
    }

    /**
     * {@link NightEnclosure#lastSampleOpen()}, republished.
     *
     * <p>Not the same read as {@link #exposedNow()}: this is the last sample's verdict whether or
     * not the most recent tick was itself a sample, so at a sampling period above one the two
     * differ on every tick the accumulator skipped. {@code NightShelterPublishesSnapshotsAndNever
     * TheAccumulatorTest} requires every read the instrument offers to be republished here, and
     * this one is not a duplicate of {@code exposedNow} in any case — it is the ungated reading.
     */
    public boolean lastSampleOpen() {
        return lastTickSeen && lastSampleOpen;
    }

    /** The default: one sample per tick. See {@link NightEnclosure#DEFAULT_PERIOD_TICKS}. */
    public NightShelter() {
        this(NightEnclosure.DEFAULT_PERIOD_TICKS);
    }

    /**
     * @param periodTicks ticks between samples; 1 means every night tick, and anything larger can
     *                    miss a shorter exposure. The default is 1 because the north-star claim is
     *                    "enclosed THROUGHOUT", and a body that stood in the open for three ticks in
     *                    the middle of a night is not sheltered.
     */
    public NightShelter(int periodTicks) {
        if (periodTicks < 1) {
            throw new IllegalArgumentException("sampling period must be at least one tick, got "
                    + periodTicks);
        }
        this.periodTicks = periodTicks;
    }

    /** Point the accumulator at a body source. Null is legal and means "measures nothing". */
    public NightShelter reading(Supplier<? extends Body> source) {
        this.source = source;
        return this;
    }

    /**
     * Drive this accumulator from the kernel's tick seam, so the sample rate is the game's and not
     * a model's polling rate.
     *
     * <p>Returns the handler so a caller can {@link EventBus#unsubscribe} exactly what it
     * subscribed, the same discipline {@code ActTickLoop.detach} follows.
     */
    public Consumer<TickEvent> attach(EventBus bus) {
        Consumer<TickEvent> handler = e -> onTick();
        bus.subscribe(TickEvent.class, handler);
        return handler;
    }

    /** Whether a sample was taken on the last tick. */
    public boolean sampledLastTick() {
        return lastTickSeen && sampled;
    }

    public int samples() {
        return lastTickSeen ? samples : 0;
    }

    public int openSamples() {
        return lastTickSeen ? openSamples : 0;
    }

    public int enclosedSamples() {
        return lastTickSeen ? enclosedSamples : 0;
    }

    /**
     * The longest unbroken run of unsheltered samples, in ticks -- multiplied by the sampling
     * period, so a caller who coarsened the instrument is not silently reporting an undercount.
     */
    public long longestUnreachableTicks() {
        return lastTickSeen ? longestOpen : 0L;
    }

    /** The clock tick of the first unsheltered sample, or -1 when the body was enclosed throughout. */
    public long firstOpenTick() {
        return lastTickSeen ? firstOpenTick : -1L;
    }

    public long firstNightTick() {
        return lastTickSeen ? firstNightTick : -1L;
    }

    public long lastNightTick() {
        return lastTickSeen ? lastNightTick : -1L;
    }

    /**
     * The world clock of the most recent tick, or 0 before the first one.
     *
     * <p>Published next to the counters rather than folded into them: a model comparing
     * {@code worldTime} against {@code firstOpenTick} is asking "how long ago was that", and
     * {@link #lastNightTick()} is the ledger's own night boundary, not the clock.
     */
    public long worldTime() {
        return lastTickSeen ? lastClock : 0L;
    }

    /** Whether the measured window is enclosed THROUGHOUT. False also covers "not measured". */
    public boolean sheltered() {
        return lastTickSeen && sheltered;
    }

    /**
     * Whether the window has been measured at all.
     *
     * <p>Separate from {@link #sheltered()} on purpose, and the separation is the whole point of
     * this class existing on a surface: a model that reads only {@code sheltered} cannot tell a
     * body that was safe from a body that was never looked at, and those are opposite decisions.
     */
    public boolean measured() {
        return lastTickSeen && samples > 0;
    }

    /**
     * Whether this instrument has EVER completed a measurement -- the one bit that separates a
     * live ledger from an empty one, and the reason {@link #measured()} cannot be the only one.
     *
     * <p>{@link #measured()} asks a stricter question ("has a NIGHT tick been sampled"), so it is
     * false in daylight and false before the counter has ever come up, and those two need
     * opposite readings: "not tonight yet" is a clock fact, "not ever" is a dead-instrument fact.
     * Nothing else on the payload separates them -- {@link #worldTime()} and {@link #intoNight()}
     * are both 0 in daylight and both 0 here, and {@code samples < intoNight} is {@code 0 < 0},
     * false. So {@code measured} false with this true is an ordinary daylight row, and
     * {@code measured} false with this false is a tool that has never observed anything.
     *
     * <p>A LATCH, not a level: set at {@link #onTick}'s first tick that had a world to measure, and
     * never cleared. No state makes it go back to false once true, because a world change starts
     * a new ledger rather than un-starting this one.
     *
     * <p><b>Why it is not named {@code ticking}.</b> The tick seam is running in exactly the state
     * this reports false -- a client on the title screen ticks and has no body -- so a name about
     * the LOOP is false in the one state where a model most needs the bit, and a model reading
     * {@code ticking:false} would conclude the game is hung (state B) or that the loop is fine
     * (state C). Neither conclusion tells it the payload is dead. This name is about the ledger.
     */
    public boolean measuredOnce() {
        return lastTickSeen;
    }

    /** How far into the current night the clock is, in ticks. 0 outside night, or before any tick. */
    public int intoNight() {
        return lastTickSeen ? intoNight : 0;
    }

    public int periodTicks() {
        return periodTicks;
    }

    /**
     * One sentence for a surface, carrying the window, the resolution and the live verdict
     * together -- because a verdict without its window is the thing this whole slice exists to
     * stop being quotable.
     */
    public String fact() {
        if (!lastTickSeen) {
            // Two causes, one old sentence, and the merge was itself the defect: `ticksDelivered`
            // is what tells them apart, and until this split the sentence blamed the SEAM in the
            // state where the seam is demonstrably running.
            if (!ticksDelivered) {
                // Verbatim the sentence this branch shipped, because for THIS cause it was right:
                // no tick has arrived, so the seam is the honest thing to name. Only the branch
                // below was lying, and only it changes.
                return "shelter: NOT MEASURED -- the tick seam has not delivered a tick yet, so this"
                        + " says nothing about shelter. A client that never ticked never looked";
            }
            return "shelter: NOT MEASURED -- ticks ARE arriving but none of them could be measured:"
                    + " there is no world to stand in yet (title screen, still loading, or just"
                    + " left one), so every number below is a placeholder rather than a reading."
                    + " A client ticking in a world it has not loaded has ticked without ever"
                    + " looking";
        }
        if (!measured()) {
            return "shelter: NOT MEASURED -- no night tick was sampled (clock " + lastClock
                    + ", " + intoNight + " tick(s) into the night). A world with the daylight cycle"
                    + " off never reaches night, and a window measured over zero ticks must not"
                    + " report a shelter";
        }
        return String.format(java.util.Locale.ROOT,
                "shelter: %s -- %d of %d night tick(s) so far at %d tick(s) per sample, clock %d..%d"
                        + " (%d tick(s) into this night); %d enclosed, %d unsheltered, longest"
                        + " unbroken stretch out in the open %d tick(s)%s",
                sheltered ? "ENCLOSED THROUGHOUT so far" : "EXPOSED at some point so far",
                samples, NightEnclosure.nightTicks(), periodTicks, firstNightTick, lastNightTick,
                intoNight, enclosedSamples, openSamples, longestOpen,
                openSamples == 0 ? "" : " beginning at clock " + firstOpenTick);
    }

    @Override
    public String toString() {
        return fact();
    }
}