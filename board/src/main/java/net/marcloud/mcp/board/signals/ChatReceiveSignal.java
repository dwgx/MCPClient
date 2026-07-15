package net.marcloud.mcp.board.signals;

import net.marcloud.mcp.board.Signal;

/**
 * A chat message arrived from the server (inbound {@code S02PacketChat}). The
 * mcp-core→board bridge republishes it onto the {@link net.marcloud.mcp.board.Trace}
 * so chips can react to what the server said — the receive-side mirror of the
 * outgoing {@link ChatSendSignal}.
 *
 * <p>This is a Tier-1 world signal: its single payload — the plain, unformatted
 * chat {@link #text()} — is cleanly available from the PHASE-P chat summarizer, so
 * the bridge carries a real value, not a placeholder. (The bridge sees only a
 * reference-free summary of the packet, never the live packet, so the text is the
 * server's unformatted chat line, possibly truncated by the summarizer for very
 * long messages — see {@code HighValueSummarizers.Chat}.)
 *
 * <p>Immutable; not cancellable — an inbound line is a fact already delivered, not
 * a vetoable action. Mirrors {@link KeySignal}'s shape (one immutable payload + a
 * single accessor).
 */
public final class ChatReceiveSignal extends Signal {

    private final String text;

    /**
     * @param text the server's unformatted chat text (never {@code null}; coerced
     *             to {@code ""} when absent)
     */
    public ChatReceiveSignal(String text) {
        this.text = text == null ? "" : text;
    }

    /** The unformatted inbound chat text. Never {@code null}. */
    public String text() {
        return text;
    }

    @Override
    public String toString() {
        return "ChatReceiveSignal{text=\"" + text + "\"}";
    }
}
