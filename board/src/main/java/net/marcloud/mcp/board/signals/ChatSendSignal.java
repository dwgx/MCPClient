package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * The player is about to send a chat message (or run a slash command typed in
 * chat). This is the worked example of a {@link Signal.Cancellable}: it fires
 * in the {@link State#PRE} phase, so a subscribing chip can inspect the
 * outgoing {@link #message()} and {@link #cancel()} it to swallow the send
 * (e.g. to intercept a custom {@code .command} prefix, or to filter output).
 *
 * <p>If no subscriber cancels it, the publisher proceeds with the real send.
 * The payload is immutable — chips veto or allow, they do not rewrite it here.
 *
 * <p>Beyond the frozen boolean {@link #cancel()}, this signal also offers a
 * {@link #cancel(String)} overload that records <em>why</em> the send was
 * vetoed. The reason is surfaced by {@link #reason()} so the mcp-core link can
 * report back to the LLM the exact rationale a chip used to suppress the
 * message (mirrors the classic {@code Event.cancel(String reason)} shape).
 */
public final class ChatSendSignal extends Signal.Cancellable {

    private final String message;
    private String reason;

    /**
     * A pre-send, cancellable chat signal.
     *
     * @param message the message the player is about to send
     */
    public ChatSendSignal(String message) {
        super(State.PRE);
        this.message = message;
    }

    /** The outgoing chat message (or command) about to be sent. */
    public String message() {
        return message;
    }

    /**
     * Veto the send <em>and</em> record why. Delegates to the frozen
     * {@link #cancel()} for the actual veto (so cancellation semantics are
     * unchanged), then stores {@code reason} for {@link #reason()} to report.
     * The plain {@link #cancel()} leaves {@link #reason()} {@code null}.
     *
     * @param reason human-readable rationale for suppressing the message
     *               (surfaced to the LLM via mcp-core); may be {@code null}
     */
    public void cancel(String reason) {
        cancel();
        this.reason = reason;
    }

    /**
     * Why this send was vetoed, or {@code null} if it was not cancelled with a
     * reason (either not cancelled at all, or cancelled via the no-arg
     * {@link #cancel()}).
     */
    public String reason() {
        return reason;
    }
}
