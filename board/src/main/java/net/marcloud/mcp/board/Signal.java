package net.marcloud.mcp.board;

/**
 * Base class for everything that travels on a {@link Trace} — a signal on the
 * board's copper. A signal is a snapshot of something that happened (or is
 * about to happen) in the game: a tick elapsed, a key was pressed, a screen is
 * opening. Subscribers observe (and, for a {@link Cancellable}, veto) it.
 *
 * <p>This is intentionally minimal: a monotonic timestamp for ordering/latency
 * and nothing else. Concrete signals add their own immutable payload. Keep
 * subscribers cheap — {@link Trace#publish} delivers synchronously on the
 * publishing (game) thread.
 *
 * <p>FROZEN framework contract (design doc 06 §7): do not change these
 * signatures; new signals only subclass.
 */
public abstract class Signal {

    private final long timestampNanos = System.nanoTime();

    /** Monotonic time the signal was created (for ordering/latency). */
    public final long timestampNanos() {
        return timestampNanos;
    }

    /**
     * A signal that can be vetoed by a subscriber. Fires around a game action so
     * a {@code PRE} phase can cancel it and a {@code POST} phase can observe the
     * result. Mirrors the classic event {@code state + cancel()} shape without
     * pulling in any external event library.
     */
    public abstract static class Cancellable extends Signal {

        /** Which side of the guarded action this signal represents. */
        public enum State {
            /** Before the action — subscribers may {@link #cancel()} it. */
            PRE,
            /** After the action ran — observation only. */
            POST
        }

        private final State state;
        private boolean cancelled;

        /** A cancellable signal in the {@link State#PRE} phase. */
        protected Cancellable() {
            this(State.PRE);
        }

        /** A cancellable signal in the given phase. */
        protected Cancellable(State state) {
            this.state = state == null ? State.PRE : state;
        }

        /** The phase this signal represents. */
        public final State state() {
            return state;
        }

        /** {@code true} once a subscriber has vetoed the guarded action. */
        public final boolean isCancelled() {
            return cancelled;
        }

        /** Veto the guarded action. Meaningful in {@link State#PRE}. */
        public final void cancel() {
            this.cancelled = true;
        }

        /** Set the cancelled flag explicitly (e.g. to un-cancel). */
        public final void setCancelled(boolean cancelled) {
            this.cancelled = cancelled;
        }
    }
}
