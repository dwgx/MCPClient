package net.marcloud.mcp.board.link;

import net.marcloud.mcp.board.Board;
import net.marcloud.mcp.board.Backplane;

/**
 * Board's side of the neutral cross-subsystem port. It is the handle mcp-core
 * (or any other peer) picks up off the {@link Backplane} to discover a live
 * Board <em>without importing a single Board class</em>: the finder looks the
 * port up by the string key {@link #KEY} (or by {@code Object}) and drives it
 * purely by reflection on the method names below.
 *
 * <p>This is the mirror image of {@link McpLink}: McpLink reflects <em>into</em>
 * mcp-core, BoardPort exposes Board <em>outward</em> so the coupling stays zero
 * in both directions. Every method returns a plain JDK type ({@code String},
 * {@code boolean}, {@code Object}) so a reflective caller never has to load a
 * Board type to read the answer.
 *
 * <p>Not part of the frozen skeleton — this lives in {@code board.link} and is a
 * seam, not a骨架 signature. It may grow new capability accessors over time.
 */
public final class BoardPort {

    /** The Backplane key core looks Board up by (peer-visible, stable). */
    public static final String KEY = "board.port";

    /** The stable id of this subsystem. */
    public String id() {
        return "board";
    }

    /** {@code true} if the Board framework has been started. */
    public boolean isStarted() {
        return Board.isStarted();
    }

    /**
     * The Board event bus ({@code net.marcloud.mcp.board.Trace}) as an opaque
     * {@code Object} — a reflective peer can subscribe/publish without a
     * compile-time Board dependency.
     */
    public Object trace() {
        return Board.trace();
    }

    /**
     * The default feature matrix ({@code net.marcloud.mcp.board.Matrix}) as an
     * opaque {@code Object}.
     */
    public Object features() {
        return Board.features();
    }

    /**
     * Start the Board framework if it is not already up. Idempotent (delegates to
     * {@link Board#init()}). Returns {@code true} once Board is running.
     */
    public boolean start() {
        Board.init();
        return Board.isStarted();
    }
}
