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
     * The guard for {@link #IDS}. Held only by the miss path and by the readers, both of which run
     * orders of magnitude less often than {@code ExtendedBlockStorage.get} itself.
     */
    private static final Object LOCK = new Object();

    /** Every decode that reached the air fallback, whether or not its id had been seen before. */
    private static final LongAdder FALLBACK_READS = new LongAdder();

    /** Distinct unresolvable state ids, ascending, capped at {@link #MAX_TRACKED_IDS}. */
    private static final TreeSet<Integer> IDS = new TreeSet<Integer>();

    /** Distinct ids that arrived after {@link #IDS} was full. A count of a count, never a lie. */
    private static final AtomicInteger DROPPED_IDS = new AtomicInteger();

    /** How many ids {@link #idsTruncated()} will print beyond the display cap in {@link #summarize()}. */
    private static final int DISPLAYED_IDS = 8;

    private UnknownBlockStates() {
    }

    /**
     * Record one decode that produced the air fallback. Called from the fallback and nowhere else.
     *
     * @param stateId the id that was stored, which the registry could not resolve
     */
    public static void record(int stateId) {
        FALLBACK_READS.increment();

        synchronized (LOCK) {
            if (IDS.size() < MAX_TRACKED_IDS) {
                IDS.add(Integer.valueOf(stateId));
            }
            else if (IDS.add(Integer.valueOf(stateId))) {
                // A distinct id we cannot keep. The read total already counted it; this says the
                // id list is short by exactly this many.
                DROPPED_IDS.incrementAndGet();
            }
        }
    }

    /**
     * How many block-state reads this client has turned into air because the id was unknown.
     *
     * <p>A READ count, not a cell count -- see the class javadoc. {@code 0} means every read so far
     * resolved, and says nothing about the cells nobody has read.
     */
    public static long fallbackReads() {
        return FALLBACK_READS.sum();
    }

    /**
     * The distinct state ids this client has failed to resolve, ascending.
     *
     * <p>This is the actionable half: it names what the server sent that this tree does not have.
     * Empty means every id read so far resolved. Check {@link #idsTruncated()} before treating a
     * non-empty list as the whole story.
     */
    public static int[] unknownIds() {
        synchronized (LOCK) {
            int[] out = new int[IDS.size()];
            int i = 0;

            for (Integer id : IDS) {
                out[i++] = id.intValue();
            }

            return out;
        }
    }

    /** How many distinct unresolvable ids {@link #unknownIds()} is holding. */
    public static int distinctUnknownIds() {
        synchronized (LOCK) {
            return IDS.size();
        }
    }

    /** True when at least one distinct id arrived after {@link #IDS} was full and was not kept. */
    public static boolean idsTruncated() {
        return DROPPED_IDS.get() > 0;
    }

    /** How many distinct ids are missing from {@link #unknownIds()} because the set was full. */
    public static int droppedIds() {
        return DROPPED_IDS.get();
    }

    /**
     * A one-line summary for a diagnostic surface. Cheap and constant when there is nothing to
     * report, because the caller renders it every frame.
     *
     * <p>The id list is capped at {@link #DISPLAYED_IDS} entries with the remainder counted, so the
     * string cannot grow with the size of the lie.
     */
    public static String summarize() {
        long reads = fallbackReads();

        if (reads == 0L) {
            return "Unknown block ids: none";
        }

        int[] ids = unknownIds();
        StringBuilder sb = new StringBuilder(64);
        sb.append("Unknown block ids: ").append(reads).append(" reads, ").append(ids.length)
                .append(" ids [");

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

        if (idsTruncated()) {
            sb.append(" (list truncated: ").append(droppedIds()).append(" more ids not kept)");
        }

        return sb.toString();
    }

    /**
     * The ids as a list of strings, for a JSON-shaped surface. Exposed so a reader does not have to
     * reimplement the copy, and so the sorted order is the same order every surface reports.
     */
    public static List<String> unknownIdNames() {
        int[] ids = unknownIds();

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