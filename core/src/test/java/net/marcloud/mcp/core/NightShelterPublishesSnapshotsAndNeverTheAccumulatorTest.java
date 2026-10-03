package net.marcloud.mcp.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.minecraft.block.Block;

import net.marcloud.mcp.core.eval.Enclosure;
import net.marcloud.mcp.core.eval.NightEnclosure;
import net.marcloud.mcp.core.eval.NightShelter;

import org.junit.Test;

/**
 * The shelter accumulator publishes snapshots, and it has no way left to hand out the thing it
 * snapshots.
 *
 * <p><b>The defect this pins.</b> {@code NightShelter} had a {@code public NightEnclosure
 * ledger()}. {@code NightEnclosure} is the accumulator the game thread rewrites in place on every
 * sampled tick, and its counters are ordinary fields with no {@code volatile} on them, so that
 * accessor gave any other thread a mutable object with no happens-before edge to the writer -- and
 * because {@code NightEnclosure.observe} is public, holding the object also meant being able to
 * write the counters the shelter verdict is derived from. Every other accessor on this class is
 * a {@code volatile} scalar; that one was the class's single escape from its own publication
 * rule. The author's own audit named it as the one design flaw knowingly shipped, and three waves
 * left it in place.
 *
 * <p><b>Four tests, four disjoint red sets.</b> Each is stated so that removing the production
 * change, or removing the thing the production change rests on, fails a DIFFERENT one:
 * <ul>
 *   <li>{@link #theDetectorFlagsALeakyAccumulatorAndPassesASealedOne} -- the premise. The scan
 *       this class runs over {@code NightShelter} is exercised against a deliberately leaky
 *       canary first, so the other three cannot pass by having a scan that finds nothing. It stays
 *       green on the old code, which is the point of a premise.</li>
 *   <li>{@link #noPublicAccessorHandsOutTheMutableAccumulator} -- the headline, and the one that
 *       goes red on the old code: the accumulator is not on any public signature.</li>
 *   <li>{@link #everyReadTheAccumulatorOffersIsRepublishedHereWithTheSameType} -- the
 *       information claim. Deleting the accessor is only free if this class still republishes
 *       every read {@code NightEnclosure} exposes; this checks it by reflection instead of
 *       trusting the comment that says so. Delete one {@code NightShelter} accessor and this
 *       goes red while the headline stays green.</li>
 *   <li>{@link #whatIsPublishedEqualsWhatTheAccumulatorWouldHaveHandedOut} -- the behavioural
 *       join, with two independent halves: the production accumulator driven through
 *       {@code NightShelter}, and the instrument driven directly. Stop copying one counter out of
 *       {@code onTick} and this goes red while the other three stay green.</li>
 * </ul>
 *
 * <p><b>Why the window is a window and not the whole night.</b> This join is about which numbers
 * reach the reader, not about how long a night is, and {@code NightShelter.onTick} recomputes
 * the night's boundaries from the production clock on every call. Driving the full night twice
 * here would buy nothing and cost that recomputation thousands of times over.
 */
public final class NightShelterPublishesSnapshotsAndNeverTheAccumulatorTest {

    /** Cell the body stands in for every run below; only the grid's answers vary. */
    private static final int FEET_X = 0;
    private static final int FEET_Y = 64;
    private static final int FEET_Z = 0;

    /**
     * How many night ticks are driven.
     *
     * <p>Long enough that the open run, its first tick and the exposed verdict are all settled
     * well before the window closes, which is all the join needs to see.
     */
    private static final int WINDOW = 240;

    /**
     * The premise, and the reason the other three assertions are worth anything.
     *
     * <p>A scan for "does this class hand out the accumulator" that finds nothing because it is
     * broken is the exact failure this repository keeps cataloguing, so the scan is pointed at a
     * canary that DOES hand it out before it is pointed at {@code NightShelter}. The canary
     * declares the accessor itself rather than being spelled out here, so the predicate under
     * test is the same code in both directions.
     */
    @Test
    public void theDetectorFlagsALeakyAccumulatorAndPassesASealedOne() {
        List<String> flagged = accumulatorsHandedOut(LeakyCanary.class);
        assertEquals("the scan must flag a class that really does hand out the accumulator,"
                + " or it will happily pass NightShelter for the wrong reason",
                Collections.singletonList("ledger:NightEnclosure"), flagged);
        assertEquals("and the same scan must pass a class that does not, or it finds nothing"
                + " anywhere",
                Collections.emptyList(), accumulatorsHandedOut(SealedCanary.class));
    }

    /**
     * The headline: {@link NightEnclosure} is on no public signature of {@link NightShelter}.
     *
     * <p><b>RED ON OLD CODE.</b> Old {@code NightShelter} carried {@code public NightEnclosure
     * ledger()}, which this scan reports as {@code ledger:NightEnclosure}. Measured by putting
     * the accessor back and running the authoritative suite: this test goes red and the other
     * three in this class stay green.
     */
    @Test
    public void noPublicAccessorHandsOutTheMutableAccumulator() {
        List<String> handedOut = accumulatorsHandedOut(NightShelter.class);
        assertEquals("NightShelter published a mutable, game-thread-owned accumulator across"
                + " threads. Every counter here is a volatile scalar and a reader gets a snapshot"
                + " of each; the accumulator's own counters are plain fields, so holding it gives"
                + " a reader neither a current answer nor a happens-before edge, and its public"
                + " observe() lets a reader WRITE the shelter verdict's inputs. If a caller needs"
                + " several counters at once, publish an immutable value -- do not publish the"
                + " accumulator. Handed out by: " + handedOut,
                Collections.emptyList(), handedOut);
    }

    /**
     * The information claim behind the deletion: nothing readable was thrown away with it.
     *
     * <p>Every public instance read on {@link NightEnclosure} -- the ones with no parameters and
     * a non-void return -- must have a public method of the SAME NAME and the SAME RETURN TYPE on
     * {@link NightShelter}. That is what makes the deletion free rather than lossy, and it is
     * checked here by reflection because a comment asserting it is not evidence of it.
     */
    @Test
    public void everyReadTheAccumulatorOffersIsRepublishedHereWithTheSameType() {
        List<String> reads = instanceReads(NightEnclosure.class);
        assertTrue("the join is empty, so it joins nothing. NightEnclosure must expose the reads"
                + " this class republishes; found only " + reads, reads.size() > 1);

        List<String> unrepublished = new ArrayList<>();
        for (String read : reads) {
            if (!republishedOn(NightShelter.class, read)) {
                unrepublished.add(read);
            }
        }
        assertEquals("NightShelter stopped republishing a read NightEnclosure offers, and the"
                + " accumulator is no longer reachable to ask it for one, so that state is now"
                + " unreachable rather than merely unpublished. Unrepublished: " + unrepublished,
                Collections.emptyList(), unrepublished);
    }

    /**
     * The behavioural join, and the only one here that runs the production accumulator.
     *
     * <p>Two independent halves are fed one identical sequence of night ticks over one identical
     * grid: the {@link NightShelter} under test, and a {@link NightEnclosure} driven by hand. Every
     * accessor {@link NightShelter} republishes must equal the answer the instrument itself gives
     * at the same tick -- so the numbers a reader sees are provably the numbers {@code ledger()}
     * used to carry, and one dropped copy in {@code onTick} is caught here and nowhere else.
     *
     * <p>Runs the grid both ways, sealed and open, because the two halves disagree on every field
     * that matters: an all-open night is exposed from its first tick with a non-zero longest run
     * and a known first-open clock, and an all-sealed night is sheltered throughout with no open
     * tick at all. A join checked against only one of them would pass a counter that was being
     * copied from the wrong place.
     */
    @Test
    public void whatIsPublishedEqualsWhatTheAccumulatorWouldHaveHandedOut() {
        assertPublishesWhatTheInstrumentHolds(false);
        assertPublishesWhatTheInstrumentHolds(true);
    }

    /** Drive one night window both ways and demand the published numbers match the instrument. */
    private static void assertPublishesWhatTheInstrumentHolds(boolean sealed) {
        String which = sealed ? "a sealed body" : "an open body";
        NightEnclosure instrument = new NightEnclosure();
        Clock clock = new Clock();
        NightShelter.Body theBody = body(sealed, clock);
        NightShelter accumulator = new NightShelter();
        // The supplier hands back the SAME body every tick, the way the live wiring does. A
        // supplier that minted a fresh body per call would also mint a fresh world identity, and
        // identity is this accumulator's reset trigger -- so the ledger would be thrown away
        // every tick and the join below would be comparing two instruments that each remember
        // one sample.
        accumulator.reading(() -> theBody);

        long firstNightTick = NightEnclosure.duskTick();
        for (long t = firstNightTick; t < firstNightTick + WINDOW; t++) {
            instrument.observe(grid(sealed), FEET_X, FEET_Y, FEET_Z, t);
            clock.now = t;
            accumulator.onTick();
        }

        assertEquals(which + ": the window has to have been sampled at all, or the rest of this"
                + " join compares two unmeasured instruments",
                WINDOW, instrument.samples());
        assertEquals(which + ": samples", instrument.samples(), accumulator.samples());
        assertEquals(which + ": openSamples", instrument.openSamples(), accumulator.openSamples());
        assertEquals(which + ": enclosedSamples", instrument.enclosedSamples(),
                accumulator.enclosedSamples());
        assertEquals(which + ": longestUnreachableTicks", instrument.longestUnreachableTicks(),
                accumulator.longestUnreachableTicks());
        assertEquals(which + ": firstNightTick", instrument.firstNightTick(),
                accumulator.firstNightTick());
        assertEquals(which + ": lastNightTick", instrument.lastNightTick(),
                accumulator.lastNightTick());
        assertEquals(which + ": firstOpenTick", instrument.firstOpenTick(),
                accumulator.firstOpenTick());
        assertEquals(which + ": sheltered", instrument.sheltered(), accumulator.sheltered());
        assertEquals(which + ": measured", instrument.measured(), accumulator.measured());
        assertEquals(which + ": periodTicks", instrument.periodTicks(), accumulator.periodTicks());
        // Added with NightEnclosure.lastSampleOpen(): a read the instrument offers has to be
        // joined here too, or this test's own claim -- "one dropped copy in onTick is caught here
        // and nowhere else" -- stops covering it. The sealed/open halves are what make it a join
        // rather than an identity: a sealed body is never exposed and an open one is exposed from
        // its first sample, so a copy that was wired to the wrong field fails one of the two.
        assertEquals(which + ": lastSampleOpen", instrument.lastSampleOpen(),
                accumulator.lastSampleOpen());
        assertEquals(which + ": exposedNow", instrument.lastSampleOpen(),
                accumulator.exposedNow());

        // And the two halves really do disagree, so this is a join and not an identity: a sealed
        // body is sheltered throughout with no open tick, an open body is exposed from the first.
        if (sealed) {
            assertEquals(which + ": a sealed body is exposed nowhere", 0,
                    accumulator.openSamples());
            assertTrue(which + ": and is sheltered throughout", accumulator.sheltered());
            assertEquals(which + ": with no first open tick", -1L,
                    accumulator.firstOpenTick());
        } else {
            assertEquals(which + ": an open body is exposed on every tick it was sampled",
                    WINDOW, accumulator.openSamples());
            assertTrue(which + ": and is therefore not sheltered", !accumulator.sheltered());
            assertEquals(which + ": and its longest unbroken run is the whole window",
                    (long) WINDOW, accumulator.longestUnreachableTicks());
            assertEquals(which + ": beginning at the first tick it was sampled", firstNightTick,
                    accumulator.firstOpenTick());
        }
    }

    /**
     * Whether {@code owner} has any public method returning the mutable accumulator, named for a
     * failure message.
     *
     * <p>{@code getMethods()} and {@code Object}'s own methods are excluded: the claim is about
     * what this class publishes, not about what every class inherits.
     */
    private static List<String> accumulatorsHandedOut(Class<?> owner) {
        List<String> handedOut = new ArrayList<>();
        for (Method m : owner.getMethods()) {
            if (m.getDeclaringClass() == Object.class || m.isSynthetic()) {
                continue;
            }
            if (m.getReturnType() == NightEnclosure.class) {
                handedOut.add(m.getName() + ":" + m.getReturnType().getSimpleName());
            }
        }
        return handedOut;
    }

    /** Every public instance read on {@code type}, as {@code name:ReturnType}. */
    private static List<String> instanceReads(Class<?> type) {
        List<String> reads = new ArrayList<>();
        for (Method m : type.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.isSynthetic()
                    || m.getParameterCount() != 0 || m.getReturnType() == void.class
                    || m.getDeclaringClass() == Object.class) {
                continue;
            }
            reads.add(m.getName() + ":" + m.getReturnType().getSimpleName());
        }
        return reads;
    }

    /** Whether {@code owner} carries a public method named and typed as {@code nameAndType}. */
    private static boolean republishedOn(Class<?> owner, String nameAndType) {
        int split = nameAndType.indexOf(':');
        String name = nameAndType.substring(0, split);
        String type = nameAndType.substring(split + 1);
        for (Method m : owner.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == 0
                    && m.getReturnType().getSimpleName().equals(type)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A grid that answers both enclosure halves directly.
     *
     * <p>Both {@code solidAt} and {@code skyClosedAt} are overridden, so {@link Enclosure} never
     * asks {@code at} for a block and this test needs no block registry -- which also means the
     * join below cannot be affected by anything about which blocks exist. Returning null from
     * {@code at} is legal under {@code CellGrid}'s own contract ("or null for air") and is never
     * consulted.
     */
    private static Enclosure.CellGrid grid(boolean sealed) {
        return new Enclosure.CellGrid() {
            @Override
            public Block at(int bx, int by, int bz) {
                return null;
            }

            @Override
            public boolean solidAt(int bx, int by, int bz) {
                return sealed;
            }

            @Override
            public boolean skyClosedAt(int bx, int headY, int bz) {
                return sealed;
            }
        };
    }

    /** The one body this test measures, over a grid whose answers are fixed at construction. */
    private static NightShelter.Body body(boolean sealed, Clock clock) {
        Object world = new Object();
        Enclosure.CellGrid cells = grid(sealed);
        return new NightShelter.Body() {
            @Override
            public Object world() {
                return world;
            }

            @Override
            public long worldTime() {
                return clock.now;
            }

            @Override
            public Enclosure.CellGrid grid() {
                return cells;
            }

            @Override
            public int feetX() {
                return FEET_X;
            }

            @Override
            public int feetY() {
                return FEET_Y;
            }

            @Override
            public int feetZ() {
                return FEET_Z;
            }
        };
    }

    /** The world clock, stepped by the driving loop so the body reports the tick under test. */
    private static final class Clock {
        private long now;
    }

    /** A class that really does hand the accumulator out; the scan has to catch this one. */
    private static final class LeakyCanary {
        @SuppressWarnings("unused")
        public NightEnclosure ledger() {
            return null;
        }
    }

    /** A class that hands out scalars only; the scan has to pass this one. */
    private static final class SealedCanary {
        @SuppressWarnings("unused")
        public long samples() {
            return 0L;
        }
    }
}