package net.marcloud.mcp.board.chips;

import java.util.concurrent.atomic.AtomicLong;

import net.marcloud.mcp.board.Board;
import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.persist.DataView;
import net.marcloud.mcp.board.persist.Persistable;
import net.marcloud.mcp.board.persist.Store;
import net.marcloud.mcp.board.signals.ChatSendSignal;

/**
 * The first REAL consumer that wires two previously-unwired framework pieces
 * together: it subscribes to {@link ChatSendSignal} (a canonical {@link Trace}
 * signal that had no chip listening to it) and records an observable side effect
 * — a running count of outgoing chat messages and the last one seen — that
 * SURVIVES a save/load through the wave-A {@link Store} persistence engine
 * (which, until now, had no {@link Persistable} consumer outside its own tests).
 *
 * <p>It is a pure OBSERVER of the chat stream: it inspects each outgoing message
 * but never {@link ChatSendSignal#cancel() vetoes} the send, so enabling it can
 * never swallow a player's chat. That distinguishes it from a filtering chip and
 * keeps the demonstration honest about what it does.
 *
 * <p>Subscription discipline uses the framework's leak-proof
 * {@link Chip#track(Trace.Subscription) auto-subscription bag}: {@code onEnable}
 * tracks the subscription and the base {@link Chip} auto-cancels it on disable,
 * so {@code enabled == subscribed} holds without a hand-written
 * {@code onDisable}. (The older {@link TickCounterChip} demonstrates the manual
 * unsubscribe path; this chip demonstrates the {@code track()} path.)
 *
 * <p>Neutral by contract: no cheat/legit layering, just an optional
 * {@link #category()} of {@code "chat"}. The on-disk key is the stable
 * {@link #PERSIST_ID} — NEVER the display name — so renaming the feature label
 * can never orphan its saved counter.
 */
public final class ChatLogChip extends Chip implements Persistable {

    /**
     * The STABLE persistence id under which a {@link Store} keys this chip's
     * saved state. Deliberately not derived from {@link #name()} so a display
     * rename never orphans the data (the stable-id contract of {@link Store}).
     */
    public static final String PERSIST_ID = "chip.chatlog";

    private final Trace trace;

    /** Count of {@link ChatSendSignal}s observed while enabled since the last {@link #reset()}. */
    private final AtomicLong sent = new AtomicLong();

    /** The most recent outgoing message observed, or {@code ""} if none yet. Never null. */
    private volatile String last = "";

    /** Observe the shared {@link Board#trace()} bus (production wiring). */
    public ChatLogChip() {
        this(Board.trace());
    }

    /** Observe an explicit {@link Trace} (used by tests for isolation). */
    public ChatLogChip(Trace trace) {
        if (trace == null) {
            throw new IllegalArgumentException("trace must not be null");
        }
        this.trace = trace;
    }

    @Override
    public String category() {
        return "chat";
    }

    // ---- Trace consumer (via the leak-proof auto-subscription bag) ----------

    @Override
    protected void onEnable() {
        // track() registers the subscription in the base Chip's auto-cancel bag,
        // so disabling the chip unsubscribes it automatically — no onDisable needed.
        track(trace.subscribe(ChatSendSignal.class, new Trace.Listener<ChatSendSignal>() {
            @Override
            public void on(ChatSendSignal signal) {
                onChat(signal);
            }
        }));
    }

    /** Observe (never veto) one outgoing chat message. */
    private void onChat(ChatSendSignal signal) {
        sent.incrementAndGet();
        String msg = signal.message();
        last = msg == null ? "" : msg;
    }

    /** Total chat messages observed while enabled since the last {@link #reset()}. */
    public long sentCount() {
        return sent.get();
    }

    /** The most recent outgoing message observed, or {@code ""} if none. Never null. */
    public String lastMessage() {
        return last;
    }

    // ---- Persistable: the chip's own field layout, keyed by stable strings ---

    @Override
    public void save(DataView out) {
        out.putLong("sent", sent.get());
        out.putString("last", last);
    }

    @Override
    public void load(DataView in) {
        sent.set(in.getLong("sent", 0L));
        last = in.getString("last", "");
    }

    @Override
    public void reset() {
        sent.set(0L);
        last = "";
    }
}
