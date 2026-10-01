package net.marcloud.mcp.board;

/**
 * The board itself — the one stable static entry point to the client-feature
 * framework, holding the framework singletons: the {@link Trace} event bus and a
 * default feature {@link Matrix}. Mirrors the "Global facade" pattern: code
 * reaches the framework through {@code Board.trace()} / {@code Board.features()}
 * without threading instances around.
 *
 * <p>Board is a PEER to mcp-core with zero hard dependency: it never imports a
 * core class. Cross-subsystem discovery goes through {@link Backplane} by
 * reflection — present, use it; absent, run standalone.
 *
 * <p>FROZEN framework contract (design doc 06 §7).
 */
public final class Board {

    /** Reflective name of mcp-core's entry class, looked up via {@link Backplane}. */
    public static final String MCP_CORE_CLASS = "net.marcloud.mcp.core.McpCore";

    private static final Trace TRACE = new Trace();
    private static final Matrix<Chip> FEATURES = new Matrix<Chip>();

    private static volatile boolean started;

    private Board() {
    }

    /** The framework event bus (copper traces). Always non-null. */
    public static Trace trace() {
        return TRACE;
    }

    /** The default feature matrix (where {@link Chip}s are soldered). Always non-null. */
    public static Matrix<Chip> features() {
        return FEATURES;
    }

    /** {@code true} once {@link #init()} has run and not been {@link #shutdown()}. */
    public static boolean isStarted() {
        return started;
    }

    /**
     * Start the framework. Idempotent — a second call while started is a no-op.
     * Registers the Board as a service on the {@link Backplane} so mcp-core (if
     * present) can discover it by reflection.
     */
    public static synchronized void init() {
        if (started) {
            return;
        }
        started = true;
        // Publish the outward-facing BoardPort so a peer (mcp-core, present or
        // arriving later) discovers a live Board by the neutral key and drives it
        // reflectively (id/isStarted/trace/features/start) — NOT a raw Trace, which
        // has none of those methods. Registered under both BoardPort.KEY and the
        // bare "board" alias, mirroring how McpLink probes "mcp.port" then "mcp".
        net.marcloud.mcp.board.link.BoardPort port =
                net.marcloud.mcp.board.link.McpLink.publishBoardPort();
        Backplane.register("board", port);

        // PHASE E.3 (ADR-0003): install the built-in chip roster so a fresh board is
        // useful out of the box. Additive to this frozen (§L2) facade — no signature
        // change. Honors the mcp.board.officialChips opt-out and is idempotent.
        net.marcloud.mcp.board.chips.OfficialChips.install(FEATURES, TRACE);

        // ChipBridge (2026-07-17, owner-authorized): publish the live roster (read) + a
        // toggle-by-id command (write) so the zero-core dwm launcher can enumerate and drive
        // real chips reflectively. Additive registration only — hard-scoped to the public
        // frozen Chip API; the write is marshalled to the game thread inside the port.
        net.marcloud.mcp.board.chips.ChipBridgePort.publish(FEATURES);
    }

    /**
     * Stop the framework: unload every feature and clear the event bus.
     * Idempotent — a call while not started is a no-op.
     *
     * <p><b>This empties the feature matrix, not just disables it</b> — deliberately, and
     * because the previous version did not, a documented {@code shutdown()} + {@link #init()}
     * restart came up with a dead board: {@code shutdown()} only called
     * {@code disableAll()}, so every chip id was still present when
     * {@code OfficialChips.install} ran again, and its {@code addAndEnable} early-returns 0
     * for an id that is already there <i>without re-enabling it</i>. The result was five
     * chips listed and every one of them off, with the two "enabled out of the box" ones
     * unsubscribed from the tick bus — while the launcher still showed a roster.
     *
     * <p>{@link Matrix#clear()} is also the right verb on its own terms: it fires
     * {@link Chip#onUnload()} for each chip, which {@code disableAll()} never did, so a chip
     * holding a resource was told to let it go.
     *
     * <p><b>The bus is cleared separately, and both lines are load-bearing.</b> An earlier
     * revision of this fix replaced the old two-line body with {@code FEATURES.clear()} alone,
     * on the reasoning that clearing the matrix was the whole job. It is not: the matrix only
     * knows the chips <i>in</i> it, and {@link Trace} is a process-wide bus that anything can
     * subscribe to. Dropping {@code TRACE.clear()} left every non-chip subscriber live through
     * a shutdown — still {@code isActive()}, still receiving ticks, still there after a restart,
     * holding whatever it closed over. That is the same class of bug as the one being fixed
     * (a restart that silently does not restore a clean board), one level down, and the only
     * reason it is not worse is that the roster chips happen to be in the matrix.
     */
    public static synchronized void shutdown() {
        if (!started) {
            return;
        }
        FEATURES.clear();
        TRACE.clear();
        Backplane.unregister(net.marcloud.mcp.board.link.BoardPort.KEY);
        Backplane.unregister("board");
        started = false;
    }
}
