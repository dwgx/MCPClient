package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * The client is about to send a raw protocol packet (via a typed {@code send_*}
 * tool or {@code send_raw_packet}). The packet-level counterpart to
 * {@link ChatSendSignal}: it fires in the {@link State#PRE} phase carrying the
 * outgoing packet's fully-qualified class name, so a subscribing chip can inspect
 * WHAT is about to go out and {@link #cancel()} it to suppress the send (e.g. a
 * safety chip that refuses attack packets, or a rate-limiter).
 *
 * <p>If no subscriber cancels it, the publisher proceeds with the real send. The
 * payload is immutable — chips veto or allow, they do not rewrite the packet here
 * (the board never touches {@code net.minecraft} types; it sees only the class
 * name String the mcp-core link hands it).
 *
 * <p>Like {@link ChatSendSignal}, a {@link #cancel(String)} overload records
 * <em>why</em> the send was vetoed; {@link #reason()} surfaces it so the mcp-core
 * link can report the rationale back to the LLM. The plain {@link #cancel()}
 * leaves {@link #reason()} {@code null}.
 */
public final class PacketSendSignal extends Signal.Cancellable {

    private final String packetClass;
    private String reason;

    /**
     * A pre-send, cancellable packet signal.
     *
     * @param packetClass the fully-qualified class name of the packet about to be
     *                    sent (mcp-core passes {@code packet.getClass().getName()})
     */
    public PacketSendSignal(String packetClass) {
        super(State.PRE);
        this.packetClass = packetClass;
    }

    /** The fully-qualified class name of the outgoing packet. */
    public String packetClass() {
        return packetClass;
    }

    /**
     * Veto the send <em>and</em> record why. Delegates to the frozen
     * {@link #cancel()} (cancellation semantics unchanged), then stores
     * {@code reason} for {@link #reason()}.
     *
     * @param reason human-readable rationale (surfaced to the LLM via mcp-core);
     *               may be {@code null}
     */
    public void cancel(String reason) {
        cancel();
        this.reason = reason;
    }

    /**
     * Why this send was vetoed, or {@code null} if it was not cancelled with a
     * reason (not cancelled, or cancelled via the no-arg {@link #cancel()}).
     */
    public String reason() {
        return reason;
    }
}
