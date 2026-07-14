package net.marcloud.mcp.core.ke;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The single global game clock — the one authoritative source of tick time for
 * the whole kernel (PHASE T spine). Every observation the MCP layer records (a
 * packet in/out, a disconnect, a hook fire, a GUI action) can be stamped with the
 * {@code tickId} that was current when it happened, so a caller can reconstruct
 * the ordered timeline of a session without a second, divergent clock.
 *
 * <p><b>One clock, one counter.</b> Before this, the only tick number lived in
 * {@code TickBridge.tickCounter} buried in the seam layer. This promotes it to a
 * kernel-level singleton so packet/event/action code can all read the SAME
 * tickId. There must never be a second tick counter (roadmap PHASE T rule).
 *
 * <p><b>Phases (FORKS-T1 default = A).</b> A tick has an entry phase; a POST phase
 * is reserved for a future observation point after the game's world logic runs.
 * We ship the entry phase only ({@link Phase#START}); {@link Phase#POST_WORLD}
 * exists in the enum so downstream code can already switch on it, but the seam
 * only advances the clock once per {@code runTick} entry today. If a later Phase
 * finds the movement-apply point and the observation point diverge, wire a real
 * POST_WORLD advance (see roadmap §FORKS-T1-B for the three-phase variant).
 *
 * <p><b>Threading.</b> {@link #advance()} is called on the game thread (from the
 * {@code Minecraft.runTick} seam). {@link #lastCompletedTick()} and
 * {@link #tickId()} are read from any thread (e.g. a Netty worker stamping an
 * inbound packet), so the counter is an {@link AtomicLong} and the snapshot
 * fields are volatile. A cross-thread reader gets the last value the game thread
 * published — the correct "which tick was current when this off-thread event
 * arrived" answer (see {@link #lastCompletedTick()}).
 */
public final class GameClock {

    /** Where in a game tick an observation sits. Default build advances only at {@link #START}. */
    public enum Phase {
        /** The game-tick entry point ({@code Minecraft.runTick} enter). The one phase shipped. */
        START,
        /** Reserved: after the game's world/logic tick ran. Not advanced by the default seam. */
        POST_WORLD
    }

    /** The process-wide clock. One instance; the seam advances it, everyone reads it. */
    public static final GameClock INSTANCE = new GameClock();

    /** Monotonic tick counter. 0 before the first tick; first tick becomes 1. */
    private final AtomicLong tick = new AtomicLong(0L);

    /** nanoTime of the most recent {@link #advance()} (volatile: game thread writes, others read). */
    private volatile long lastTickMonoNs = System.nanoTime();

    /** Phase of the most recent advance. */
    private volatile Phase lastPhase = Phase.START;

    /** Public singleton via {@link #INSTANCE}; still constructable for isolated tests. */
    public GameClock() {
    }

    /**
     * Advance the clock one tick at {@link Phase#START} and return the new tickId.
     * Called once per {@code Minecraft.runTick} entry on the game thread.
     */
    public long advance() {
        return advance(Phase.START);
    }

    /**
     * Advance the clock to {@code phase}. Today the seam only calls this with
     * {@link Phase#START} (one advance per tick); a {@link Phase#POST_WORLD} call
     * would record a same-tick second phase WITHOUT bumping the tick number — so
     * the tickId still counts whole ticks. Returns the current tickId.
     */
    public long advance(Phase phase) {
        long id;
        if (phase == Phase.POST_WORLD) {
            // Same tick, later phase: update the phase/time snapshot, don't bump.
            id = tick.get();
        } else {
            id = tick.incrementAndGet();
        }
        lastTickMonoNs = System.nanoTime();
        lastPhase = phase == null ? Phase.START : phase;
        return id;
    }

    /** The current tickId (monotonic; 0 before the first tick). Readable from any thread. */
    public long tickId() {
        return tick.get();
    }

    /**
     * The tickId to attribute an OFF-thread observation to — the last tick the
     * game thread completed. Identical to {@link #tickId()} here (the counter is
     * already "last advanced"), named separately because that is the correct
     * semantic for a Netty-thread packet: "the game was on tick N when this
     * arrived". Kept distinct so a future PRE/POST split can refine it without
     * changing callers.
     */
    public long lastCompletedTick() {
        return tick.get();
    }

    /** nanoTime captured at the most recent advance. */
    public long lastTickMonoNs() {
        return lastTickMonoNs;
    }

    /** Phase of the most recent advance. */
    public Phase lastPhase() {
        return lastPhase;
    }

    /** Reset to the pre-first-tick state. For tests only. */
    public void reset() {
        tick.set(0L);
        lastTickMonoNs = System.nanoTime();
        lastPhase = Phase.START;
    }
}
