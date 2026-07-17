package net.marcloud.mcp.board.chips;

import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Matrix;
import net.marcloud.mcp.board.Trace;

/**
 * The framework's built-in chip roster — the small set of chips the Board enables
 * out of the box so a fresh install is useful without any hand wiring. PHASE E
 * (E.3): {@link net.marcloud.mcp.board.Board#init() Board.init()} is INTENDED to
 * delegate here (one line: {@code OfficialChips.install(FEATURES, TRACE)}), but that
 * edit touches the frozen framework contract (design doc 06 §7) and is DEFERRED
 * pending an ADR + owner sign-off. Until then this class is wired only from tests;
 * Board.init() does not yet call it.
 *
 * <p>Installs two demonstrator chips enabled — {@link ChatLogChip} (observes
 * outgoing chat on the {@link Trace}) and {@link TickCounterChip} (counts ticks) —
 * plus three neutral, reversible, LOCAL-only feature chips added DISABLED (opt-in):
 * {@link FullbrightChip} (local gamma, restored on disable), {@link CoordinatesHudChip}
 * and {@link FpsMeterChip} (pure observers). None sends a packet, writes save data, or
 * changes any server-visible state — nothing that alters gameplay for other players.
 *
 * <p><b>Opt-out:</b> honors the system property {@code mcp.board.officialChips}.
 * Default (unset / any value other than the opt-outs) installs the roster; the
 * values {@code "false"}, {@code "none"}, {@code "off"}, and {@code "0"}
 * (case-insensitive) install NOTHING, leaving a bare board for tests or minimal
 * runs. This mirrors the opt-in/opt-out property style used elsewhere in the
 * kernel (e.g. {@code mcp.core.overlay}).
 *
 * <p>Idempotent-friendly: {@link #install} skips a chip whose {@link Chip#id() id}
 * is already present in the matrix, so a double {@code Board.init()} (or a test
 * that pre-adds a chip) never throws a duplicate-id error.
 */
public final class OfficialChips {

    /** System property gating installation. See the class javadoc. */
    public static final String PROPERTY = "mcp.board.officialChips";

    private OfficialChips() {
    }

    /**
     * Install and enable the official chip roster onto {@code matrix}, wiring
     * trace-consuming chips to {@code trace}. A no-op (installs nothing) when the
     * {@link #PROPERTY} opt-out is set. Returns the number of chips installed.
     *
     * @param matrix the feature matrix to solder chips onto (must not be null)
     * @param trace  the bus trace-consuming chips subscribe on (must not be null)
     * @return the count of chips added by this call (0 when opted out or all
     *         already present)
     */
    public static int install(Matrix<Chip> matrix, Trace trace) {
        if (matrix == null) {
            throw new IllegalArgumentException("matrix must not be null");
        }
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
        if (!enabled()) {
            return 0;
        }
        int installed = 0;
        installed += addAndEnable(matrix, new ChatLogChip(trace));
        installed += addAndEnable(matrix, new TickCounterChip(trace));
        // Neutral, reversible, local-only feature chips so the launcher roster is not bare:
        // fullbright (local gamma, restored on disable), coordinates HUD + FPS meter (pure
        // observers). None sends a packet, writes save data, or changes server-visible state.
        // Added DISABLED (not addAndEnable) — a fresh launcher shows them off, user opts in.
        installed += addDisabled(matrix, new FullbrightChip());
        installed += addDisabled(matrix, new CoordinatesHudChip());
        installed += addDisabled(matrix, new FpsMeterChip());
        return installed;
    }

    /**
     * Whether the official roster should be installed, per {@link #PROPERTY}.
     * Default-on; only the explicit opt-out values disable it.
     */
    public static boolean enabled() {
        String v = System.getProperty(PROPERTY);
        if (v == null) {
            return true;
        }
        String t = v.trim();
        return !(t.equalsIgnoreCase("false")
                || t.equalsIgnoreCase("none")
                || t.equalsIgnoreCase("off")
                || t.equals("0"));
    }

    /**
     * Add {@code chip} to {@code matrix} (unless its id is already present) and
     * enable it. Returns 1 if newly added, 0 if it was already there.
     */
    private static int addAndEnable(Matrix<Chip> matrix, Chip chip) {
        if (matrix.contains(chip.id())) {
            return 0;
        }
        matrix.add(chip);
        chip.setEnabled(true);
        return 1;
    }

    /**
     * Add {@code chip} to {@code matrix} (unless its id is already present) but leave it
     * DISABLED — for opt-in feature chips the launcher shows off by default. Returns 1 if
     * newly added, 0 if it was already there.
     */
    private static int addDisabled(Matrix<Chip> matrix, Chip chip) {
        if (matrix.contains(chip.id())) {
            return 0;
        }
        matrix.add(chip);
        return 1;
    }
}
