package net.minecraft.world.chunk.storage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * What this client could not resolve, counted and named instead of absorbed.
 *
 * <p><b>The defect this exists for.</b> {@code ExtendedBlockStorage.get} is the single point where
 * a stored block-state id becomes a block, and it answers
 * {@code Blocks.air.getDefaultState()} for any id
 * {@link net.minecraft.block.Block#BLOCK_STATE_IDS} does not hold:
 *
 * <pre>
 *     IBlockState iblockstate = (IBlockState)Block.BLOCK_STATE_IDS.getByValue(this.data[...]);
 *     return iblockstate != null ? iblockstate : Blocks.air.getDefaultState();
 * </pre>
 *
 * The air answer is vanilla's and it stays: a 1.8.9 client on a newer server legitimately receives
 * block ids it has never heard of, and refusing them would break the connection. That is a
 * compatibility decision, not a defect, and it is not this class's to revisit.
 *
 * <p>What was wrong was not the answer but that <b>nothing said so</b>. The cell reads as air, so
 * every consumer of the client's world -- the renderer, the walk planner, the eval -- is told the
 * cell was looked at and found empty. The server disagrees and nothing raises, because nothing
 * failed. This class turns that silence into two facts a caller can act on.
 *
 * <p><b>Two events, two censuses, and never one merged number.</b> A block-state id can arrive
 * unresolvable by two routes, and the two are not the same incident:
 *
 * <ul>
 *   <li><b>A fallback read</b> ({@link #record}, called from {@code ExtendedBlockStorage.get}). A
 *       stored id was read and became {@code Blocks.air}. The client continues on a value; every
 *       consumer keeps working and every one of them is now wrong about that cell.</li>
 *   <li><b>A null arrival</b> ({@link #recordNullArrival}, called from the two block-change packet
 *       decoders). The id was read off the wire and no value was manufactured at all -- the
 *       caller is handed {@code null}. That does not propagate the way air does: the first
 *       consumer to dereference it raises, which drops the whole update.</li>
 * </ul>
 *
 * <p>The counts are therefore kept apart, deliberately, and so are their id lists. A reader asking
 * "how much work is this client doing on a lie" ({@link #fallbackReads()}) and a reader asking
 * "how many updates did this client throw away" ({@link #nullArrivals()}) are asking different
 * questions about different failures, and one number that answered both would answer neither. For
 * the same reason {@link #unknownIds()} and {@link #nullArrivalIds()} are separate lists even
 * though the ids themselves are the same actionable fact (a registration this tree is missing):
 * pairing a count with an id list that a different event contributed to is the exact shape of a
 * number that looks like evidence and is not.
 *
 * <p><b>The two sites are not split further.</b> {@code S23PacketBlockChange} and
 * {@code S22PacketMultiBlockChange} both produce "a decode returned null", which is one event, and
 * the site is already visible in the packet journal. They differ in blast radius -- the multi
 * change aborts its remaining entries too -- but that difference is a property of the CALLER's
 * loop, not a second kind of registry miss, and inventing a counter for it would be counting the
 * caller twice.
 *
 * <p><b>What the two numbers mean, and why they are not interchangeable.</b>
 * {@link #fallbackReads()} counts READS, not cells. A single unknown cell walked by the renderer is
 * read many times a second, so the read count says how much work the client is doing on a lie; it
 * does not say how much of the world is a lie. For that, ask {@link #unknownIds()} -- the distinct
 * ids that arrived unresolvable. Both are needed and neither derives from the other: a million
 * reads of two ids and a thousand reads of a thousand ids are the same world and very different
 * diagnoses.
 *
 * <p><b>Absent is not zero.</b> {@link #fallbackReads()} being {@code 0} means "every block-state
 * read this client has performed resolved to a state it knows". It does NOT mean "every cell in
 * every chunk resolves" -- nothing here has read the cells that have not been read yet, and an
 * unvisited cell is exactly as capable of holding an unknown id as a visited one. A caller that
 * wants a statement about coverage must ask its own coverage question; this class answers only
 * "of what was read, how much was not understood". The belief layer makes the same split, and for
 * the same reason: {@code LocalGrid} already refuses a verdict on an unloaded column rather than
 * judging the air {@code getBlockState} manufactures for it.
 *
 * <p><b>The id list is bounded and says so.</b> Distinct ids are kept in a fixed-capacity set. Once
 * it is full, further distinct ids are still COUNTED (the read total is unconditional) but not
 * KEPT, and {@link #idsTruncated()} reports {@code true}. A silently short list would be the exact
 * shape this project exists to stop -- a number that looks complete and is not -- so the truncation
 * is a first-class part of the answer rather than a detail of the implementation.
 *
 * <p>Threading: {@code ExtendedBlockStorage.get} runs on the client thread and on the render
 * thread, so the counters are lock-free and the id set is guarded. The read total uses
 * {@link LongAdder} because it is written from more than one thread; the write path also happens
 * only when a lookup misses, which is rare by construction -- if it were common, the client would
 * be spending its frame budget on air.
 */
public final class UnknownBlockStates {

    /**
     * Distinct ids kept for the reader to act on. Chosen to sit above every id this tree can
     * produce (a 1.8.9 wire id is {@code blockId << 4 | meta}, so at most 256 << 4) while staying
     * small enough that the set cannot become a memory sink.
     */
    public static final int MAX_TRACKED_IDS = 256;

    /**
     * The air-fallback census: {@link #record}'s half. See the class javadoc for why the two
     * halves are separate objects rather than two counters on one.
     */
    private static final IdCensus FALLBACK = new IdCensus();

    /** The null-arrival census: {@link #recordNullArrival}'s half. */
    private static final IdCensus NULL_ARRIVAL = new IdCensus();

    /** How many ids a {@code summarize()} line will print beyond the display cap. */
    private static final int DISPLAYED_IDS = 8;

    private UnknownBlockStates() {
    }

    /**
     * One bounded, thread-safe census: a count that is always complete, a distinct-id list that
     * says so when it is not, and a summary line that renders both without pairing them wrongly.
     *
     * <p>Threading: the count is a {@link LongAdder} because {@code ExtendedBlockStorage.get} runs
     * on the client thread and on the render thread. The id set is guarded, and held only by the
     * miss path and by the readers, both of which run orders of magnitude less often than the
     * read itself -- if they did not, the client would be spending its frame budget on air.
     */
    private static final class IdCensus {

        /** Every decode that reached this census, whether or not its id had been seen before. */
        private final LongAdder events = new LongAdder();

        /** Distinct unresolvable state ids, ascending, capped at {@link #MAX_TRACKED_IDS}. */
        private final TreeSet<Integer> ids = new TreeSet<Integer>();

        /** Distinct ids that arrived after {@link #ids} was full. A count of a count, never a lie. */
        private final AtomicInteger dropped = new AtomicInteger();

        private final Object lock = new Object();

        private void record(int stateId) {
            this.events.increment();

            synchronized (this.lock) {
                if (this.ids.size() < MAX_TRACKED_IDS) {
                    this.ids.add(Integer.valueOf(stateId));
                }
                else if (this.ids.add(Integer.valueOf(stateId))) {
                    // A distinct id we cannot keep. The event total already counted it; this says
                    // the id list is short by exactly this many.
                    this.dropped.incrementAndGet();
                }
            }
        }

        private long count() {
            return this.events.sum();
        }

        private int[] ids() {
            synchronized (this.lock) {
                int[] out = new int[this.ids.size()];
                int i = 0;

                for (Integer id : this.ids) {
                    out[i++] = id.intValue();
                }

                return out;
            }
        }

        private boolean truncated() {
            return this.dropped.get() > 0;
        }

        private int dropped() {
            return this.dropped.get();
        }
    }

    /**
     * Record one decode that produced the air fallback. Called from the fallback and nowhere else.
     *
     * @param stateId the id that was stored, which the registry could not resolve
     */
    public static void record(int stateId) {
        FALLBACK.record(stateId);
    }

    /**
     * Record one decode that produced {@code null} instead of a state.
     *
     * <p>Called from the two block-change packet decoders and nowhere else. The behaviour of those
     * decoders is deliberately unchanged -- this counts the event, it does not decide what the
     * event should have been. See the class javadoc and
     * {@code S23PacketBlockChange#readPacketData} for why the null stays for now.
     *
     * @param stateId the id that came off the wire, which the registry could not resolve
     */
    public static void recordNullArrival(int stateId) {
        NULL_ARRIVAL.record(stateId);
    }

    /**
     * How many block-state reads this client has turned into air because the id was unknown.
     *
     * <p>A READ count, not a cell count -- see the class javadoc. {@code 0} means every read so far
     * resolved, and says nothing about the cells nobody has read. It does NOT include null
     * arrivals: ask {@link #nullArrivals()} for those.
     */
    public static long fallbackReads() {
        return FALLBACK.count();
    }

    /**
     * How many block-state decodes produced {@code null} because the registry did not hold the id.
     *
     * <p>An ARRIVAL count, not a read count, and it is a strictly worse number than
     * {@link #fallbackReads()} at the same value: a fallback read leaves a wrong value in place,
     * while a null arrival drops the update entirely. {@code 0} means no such decode has happened.
     */
    public static long nullArrivals() {
        return NULL_ARRIVAL.count();
    }

    /**
     * The distinct state ids this client has failed to resolve, ascending.
     *
     * <p>This is the actionable half of the fallback census: it names what the server sent that
 * this tree does not have. Empty means every id read so far resolved. Check
 * {@link #idsTruncated()} before treating a non-empty list as the whole story. It does NOT
     include null arrivals -- see {@link #nullArrivalIds()}.
     */
    public static int[] unknownIds() {
        return FALLBACK.ids();
    }

    /**
     * The distinct state ids that arrived off the wire and produced {@code null}, ascending.
     *
     * <p>The same actionable fact as {@link #unknownIds()} and a different list on purpose: the
     * count that goes with this one is {@link #nullArrivals()}, and pairing a null-arrival count
     * with a list that fallback reads contributed to would be a number describing two events as
 * one.
     */
    public static int[] nullArrivalIds() {
        return NULL_ARRIVAL.ids();
    }

    /** How many distinct unresolvable ids {@link #unknownIds()} is holding. */
    public static int distinctUnknownIds() {
        return FALLBACK.ids().length;
    }

    /** How many distinct unresolvable ids {@link #nullArrivalIds()} is holding. */
    public static int distinctNullArrivalIds() {
        return NULL_ARRIVAL.ids().length;
    }

    /** True when at least one distinct id arrived after the fallback id set was full. */
    public static boolean idsTruncated() {
        return FALLBACK.truncated();
    }

    /** How many distinct ids are missing from {@link #unknownIds()} because the set was full. */
    public static int droppedIds() {
        return FALLBACK.dropped();
    }

    /** True when at least one distinct id arrived after the null-arrival id set was full. */
    public static boolean nullArrivalIdsTruncated() {
        return NULL_ARRIVAL.truncated();
    }

    /** How many distinct ids are missing from {@link #nullArrivalIds()} because the set was full. */
    public static int droppedNullArrivalIds() {
        return NULL_ARRIVAL.dropped();
    }

    /**
     * A one-line summary of the air-fallback census, for a diagnostic surface. Cheap and constant
     * when there is nothing to report, because the caller renders it every frame.
     *
     * <p>The id list is capped at {@link #DISPLAYED_IDS} entries with the remainder counted, so the
     * string cannot grow with the size of the lie.
     */
    public static String summarize() {
        return render("Unknown block ids", FALLBACK, "reads");
    }

    /**
     * A one-line summary of the null-arrival census, for the same diagnostic surface.
     *
     * <p>A separate line from {@link #summarize()} on purpose. One F3 reader has to be able to tell
     * "the client is rendering air it does not believe" from "the client threw updates away", and
     * a single line carrying both counts would make the reader do that arithmetic, which is how
     * one of them gets misread as the other.
     */
    public static String summarizeNullArrivals() {
        return render("Unknown block ids off the wire", NULL_ARRIVAL, "arrivals");
    }

    /**
     * The shared renderer for both lines. Kept in one place so the two summaries cannot drift into
     * disagreeing about what "none" means or about how a truncated list is announced.
     */
    private static String render(String label, IdCensus census, String unit) {
        long events = census.count();

        if (events == 0L) {
            return label + ": none";
        }

        int[] ids = census.ids();
        StringBuilder sb = new StringBuilder(64);
        sb.append(label).append(": ").append(events).append(' ').append(unit).append(", ")
                .append(ids.length).append(" ids [");

        int shown = Math.min(ids.length, DISPLAYED_IDS);

        for (int i = 0; i < shown; ++i) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append(ids[i]);
        }

        if (ids.length > shown) {
            sb.append(", +").append(ids.length - shown).append(" more");
        }

        sb.append(']');

        if (census.truncated()) {
            sb.append(" (list truncated: ").append(census.dropped()).append(" more ids not kept)");
        }

        return sb.toString();
    }

    /**
     * The fallback ids as a list of strings, for a JSON-shaped surface. Exposed so a reader does
     * not have to reimplement the copy, and so the sorted order is the same order every surface
     * reports.
     */
    public static List<String> unknownIdNames() {
        return asNames(unknownIds());
    }

    /** {@link #unknownIdNames()} for the null-arrival census. */
    public static List<String> nullArrivalIdNames() {
        return asNames(nullArrivalIds());
    }

    private static List<String> asNames(int[] ids) {
        if (ids.length == 0) {
            return Collections.emptyList();
        }

        List<String> out = new ArrayList<String>(ids.length);

        for (int id : ids) {
            out.add(String.valueOf(id));
        }

        return out;
    }
}