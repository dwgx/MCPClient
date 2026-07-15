package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * A single block changed in the world (inbound {@code S23PacketBlockChange}). The
 * mcp-core→board bridge republishes it onto the {@link net.marcloud.mcp.board.Trace}
 * so chips can react to world edits (e.g. a nearby-block watcher).
 *
 * <p><b>Tier-2, honestly typed.</b> The bridge sees only a reference-free summary
 * of the packet — never the live packet — but the PHASE-P {@code S23} summarizer
 * ALREADY emits the position and block-state in a structured form
 * ({@code "blockChange at=x,y,z state=<...>"}), so the bridge parses the real
 * integer coordinates and the state string out of that summary. No placeholder,
 * no opaque field: {@link #x()}/{@link #y()}/{@link #z()} are the true block
 * coordinates and {@link #state()} is the server's block-state description.
 *
 * <p>Note the coupling this typing rests on: the coordinate fields are recovered
 * by parsing the S23 summarizer's output format. If that summarizer's format
 * changes, the bridge's parse (not this value type) is what must track it — this
 * class stays a plain, honest value holder.
 *
 * <p>Immutable; not cancellable — the edit already happened. Mirrors
 * {@link KeySignal}'s shape (immutable payload + accessors).
 */
public final class BlockChangeSignal extends Signal {

    private final int x;
    private final int y;
    private final int z;
    private final String state;

    /**
     * @param x     block X coordinate
     * @param y     block Y coordinate
     * @param z     block Z coordinate
     * @param state the server's block-state description (never {@code null};
     *              coerced to {@code ""} when absent)
     */
    public BlockChangeSignal(int x, int y, int z, String state) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.state = state == null ? "" : state;
    }

    /** Block X coordinate. */
    public int x() {
        return x;
    }

    /** Block Y coordinate. */
    public int y() {
        return y;
    }

    /** Block Z coordinate. */
    public int z() {
        return z;
    }

    /** The server's block-state description. Never {@code null}. */
    public String state() {
        return state;
    }

    @Override
    public String toString() {
        return "BlockChangeSignal{x=" + x + ", y=" + y + ", z=" + z
                + ", state=\"" + state + "\"}";
    }
}
