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
 */
public final class ChatSendSignal extends Signal.Cancellable {

    private final String message;

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
}
