package net.marcloud.mcp.board;

/**
 * Subscriber priority — the board's clock decides which chip gets the signal
 * first. When a {@link Signal} is published, subscribers run from
 * {@link #HIGHEST} to {@link #LOWEST}. Within the same tier, registration order
 * is preserved.
 *
 * <p>Priority matters for {@link Signal.Cancellable}: a high-priority subscriber
 * can veto an action before lower-priority ones observe it.
 *
 * <p>FROZEN framework contract (design doc 06 §7).
 */
public enum Clock {
    HIGHEST,
    HIGH,
    NORMAL,
    LOW,
    LOWEST;

    /** Default tier used when a subscriber does not specify one. */
    public static final Clock DEFAULT = NORMAL;
}
