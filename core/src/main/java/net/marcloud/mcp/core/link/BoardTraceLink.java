package net.marcloud.mcp.core.link;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The core→board publish facade — the generalization of {@link BoardClockBridge}'s
 * one-shot reflective resolution into a reusable surface for sending ANY board
 * {@code Signal} from core, WITHOUT a compile-time dependency on board (PHASE E).
 * core and board are zero-hard-dependency peers: core must never
 * {@code import net.marcloud.mcp.board.*}. So this link reaches the board
 * {@code Trace} purely by reflection — board present ⇒ signals are published;
 * board absent ⇒ every call is a silent no-op.
 *
 * <p>Discovery mirrors {@link BoardClockBridge#resolve()}: resolve the board port
 * off the {@code Backplane} by the neutral {@code "board.port"} key, drive it by
 * reflected method names, and cache the reflected handles after the first success.
 * Nothing here can throw onto the caller — a reflection miss or a board-side throw
 * is swallowed and marks the link unavailable.
 *
 * <p><b>Chat veto (E.1).</b> {@link #publishChatSend(String)} publishes a board
 * {@code ChatSendSignal} in its {@code PRE} phase and reads back whether a chip
 * vetoed the send (and why) — returning a {@link ChatSendResult} the caller
 * ({@code ActionManager.sendChat}) uses to honor the veto. Because the board
 * {@code Trace.publish(E)} returns the very signal it was given, the cancelled
 * flag and reason are read straight off the published instance.
 *
 * <p><b>Threading.</b> Handles are resolved lazily under a lock and cached in
 * {@code volatile} fields, matching {@code BoardClockBridge}. Publishing is
 * delegated synchronously to the board {@code Trace} on the calling thread.
 */
public final class BoardTraceLink {

    private static final String BACKPLANE = "net.marcloud.mcp.board.Backplane";
    private static final String BOARD_PORT_KEY = "board.port";
    private static final String CHAT_SEND_SIGNAL = "net.marcloud.mcp.board.signals.ChatSendSignal";
    private static final String PACKET_SEND_SIGNAL = "net.marcloud.mcp.board.signals.PacketSendSignal";

    /** The process-wide link used by production call sites (e.g. ActionManager). */
    private static final BoardTraceLink SHARED = new BoardTraceLink();

    /** The shared production link. Tests construct their own isolated instance. */
    public static BoardTraceLink shared() {
        return SHARED;
    }

    /**
     * The outcome of a {@link #publishChatSend(String)} attempt.
     *
     * @param published {@code true} if the signal reached a live board
     *                  {@code Trace}; {@code false} if board was absent (no veto
     *                  was possible — the caller should proceed with the send)
     * @param cancelled {@code true} if a board chip vetoed the send
     * @param reason    the veto rationale a chip recorded, or {@code null} (not
     *                  cancelled, or cancelled without a reason)
     */
    public record ChatSendResult(boolean published, boolean cancelled, String reason) {

        /** A "board absent" result: not published, not cancelled, no reason. */
        static ChatSendResult absent() {
            return new ChatSendResult(false, false, null);
        }
    }

    /**
     * The outcome of a {@link #publishPacketSend(String)} attempt — the packet-send
     * counterpart to {@link ChatSendResult}.
     *
     * @param published {@code true} if the signal reached a live board {@code Trace}
     * @param cancelled {@code true} if a board chip vetoed the send
     * @param reason    the veto rationale a chip recorded, or {@code null}
     */
    public record PacketSendResult(boolean published, boolean cancelled, String reason) {

        /** A "board absent" result: not published, not cancelled, no reason. */
        static PacketSendResult absent() {
            return new PacketSendResult(false, false, null);
        }
    }

    // ---- cached reflected handles (resolved lazily, then reused) -------------

    private volatile boolean traceResolved;
    private volatile Object trace;          // board Trace instance
    private volatile Method tracePublish;   // Trace.publish(Signal) -> Signal

    private volatile boolean chatResolved;
    private volatile boolean chatAvailable;
    private volatile Constructor<?> chatSendCtor; // ChatSendSignal(String)
    private volatile Method isCancelled;          // Cancellable.isCancelled()
    private volatile Method reasonOf;             // ChatSendSignal.reason()

    private volatile boolean packetResolved;
    private volatile boolean packetAvailable;
    private volatile Constructor<?> packetSendCtor; // PacketSendSignal(String)
    private volatile Method packetIsCancelled;      // Cancellable.isCancelled()
    private volatile Method packetReasonOf;         // PacketSendSignal.reason()

    /** Per-signal-class constructor cache for the generic {@link #publish} path. */
    private final Map<String, Constructor<?>> ctorCache = new ConcurrentHashMap<>();

    public BoardTraceLink() {
    }

    /** True once the board {@code Trace} has been resolved and is publishable. */
    public boolean available() {
        if (!traceResolved) {
            resolveTrace();
        }
        return trace != null && tracePublish != null;
    }

    /**
     * Publish a board {@code ChatSendSignal(message)} in its {@code PRE} phase and
     * read back the veto outcome. Never throws. When board is absent, returns
     * {@link ChatSendResult#absent()} — the caller then sends normally (no board,
     * no veto). When present, the returned result reports whether a chip cancelled
     * the send and any reason it recorded.
     *
     * @param message the outgoing chat message about to be sent
     * @return the publish + veto outcome (never {@code null})
     */
    public ChatSendResult publishChatSend(String message) {
        try {
            if (!available()) {
                return ChatSendResult.absent();
            }
            if (!chatResolved) {
                resolveChat();
            }
            if (!chatAvailable) {
                // Board Trace is present but the ChatSendSignal contract could not
                // be resolved — treat as absent so the caller still sends.
                return ChatSendResult.absent();
            }
            Object signal = chatSendCtor.newInstance(message);
            Object returned = tracePublish.invoke(trace, signal);
            Object published = returned != null ? returned : signal;
            boolean cancelled = (Boolean) isCancelled.invoke(published);
            String reason = (String) reasonOf.invoke(published);
            return new ChatSendResult(true, cancelled, reason);
        } catch (Throwable t) {
            // Board vanished / signature changed / chip threw — never surface it.
            // Report "absent" so the caller proceeds with the send unharmed.
            return ChatSendResult.absent();
        }
    }

    /**
     * Publish a board {@code PacketSendSignal(packetClass)} in its {@code PRE} phase
     * and read back the veto outcome — the packet-send counterpart to
     * {@link #publishChatSend(String)}. Never throws; board absent ⇒
     * {@link PacketSendResult#absent()} (caller sends normally).
     *
     * @param packetClass the FQCN of the packet about to be sent
     * @return the publish + veto outcome (never {@code null})
     */
    public PacketSendResult publishPacketSend(String packetClass) {
        try {
            if (!available()) {
                return PacketSendResult.absent();
            }
            if (!packetResolved) {
                resolvePacket();
            }
            if (!packetAvailable) {
                return PacketSendResult.absent();
            }
            Object signal = packetSendCtor.newInstance(packetClass);
            Object returned = tracePublish.invoke(trace, signal);
            Object published = returned != null ? returned : signal;
            boolean cancelled = (Boolean) packetIsCancelled.invoke(published);
            String reason = (String) packetReasonOf.invoke(published);
            return new PacketSendResult(true, cancelled, reason);
        } catch (Throwable t) {
            return PacketSendResult.absent();
        }
    }

    /**
     * Generic escape hatch: construct a board signal by fully-qualified class name
     * and publish it on the board {@code Trace}. Never throws.
     *
     * @param signalFqcn fully-qualified board signal class name
     * @param paramTypes the signal constructor's parameter types (may be empty)
     * @param args       the constructor arguments (must match {@code paramTypes})
     * @return {@code true} if the signal was constructed and published to a live
     *         board {@code Trace}; {@code false} if board was absent or anything
     *         reflective failed
     */
    public boolean publish(String signalFqcn, Class<?>[] paramTypes, Object... args) {
        try {
            if (signalFqcn == null || !available()) {
                return false;
            }
            Class<?>[] types = paramTypes == null ? new Class<?>[0] : paramTypes;
            Constructor<?> ctor = ctorCache.computeIfAbsent(signalFqcn, fqcn -> {
                try {
                    return Class.forName(fqcn).getConstructor(types);
                } catch (Throwable t) {
                    return null;
                }
            });
            if (ctor == null) {
                return false;
            }
            Object signal = ctor.newInstance(args);
            tracePublish.invoke(trace, signal);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    // ---- reflective resolution ---------------------------------------------

    /**
     * One-time resolution of the board {@code Trace} + its {@code publish(Signal)}
     * method off the {@code Backplane}. On any miss, {@link #trace} stays null and
     * the link is a no-op for this run (board absent / not started).
     */
    private synchronized void resolveTrace() {
        if (traceResolved) {
            return;
        }
        traceResolved = true;
        try {
            Class<?> backplane = Class.forName(BACKPLANE);
            Method find = backplane.getMethod("find", String.class);
            Object port = find.invoke(null, BOARD_PORT_KEY);
            if (port == null) {
                return; // board not started / not registered — no-op
            }
            Object traceObj = port.getClass().getMethod("trace").invoke(port);
            if (traceObj == null) {
                return;
            }
            Method publish = findPublish(traceObj.getClass());
            if (publish == null) {
                return;
            }
            this.trace = traceObj;
            this.tracePublish = publish;
        } catch (Throwable t) {
            // ClassNotFound (board absent) is the expected miss — stay a no-op.
            this.trace = null;
            this.tracePublish = null;
        }
    }

    /**
     * One-time resolution of the {@code ChatSendSignal(String)} constructor plus
     * the {@code isCancelled()} / {@code reason()} accessors. Independent of trace
     * resolution so the generic {@link #publish} path works even if this fails.
     */
    private synchronized void resolveChat() {
        if (chatResolved) {
            return;
        }
        chatResolved = true;
        try {
            Class<?> chatSignal = Class.forName(CHAT_SEND_SIGNAL);
            this.chatSendCtor = chatSignal.getConstructor(String.class);
            // isCancelled() is inherited from Signal.Cancellable; getMethod finds it.
            this.isCancelled = chatSignal.getMethod("isCancelled");
            this.reasonOf = chatSignal.getMethod("reason");
            this.chatAvailable = true;
        } catch (Throwable t) {
            this.chatAvailable = false;
        }
    }

    /**
     * One-time resolution of the {@code PacketSendSignal(String)} constructor plus
     * its {@code isCancelled()} / {@code reason()} accessors. Independent of chat
     * resolution (dedicated handles) so the two veto paths never interfere.
     */
    private synchronized void resolvePacket() {
        if (packetResolved) {
            return;
        }
        packetResolved = true;
        try {
            Class<?> sig = Class.forName(PACKET_SEND_SIGNAL);
            this.packetSendCtor = sig.getConstructor(String.class);
            this.packetIsCancelled = sig.getMethod("isCancelled");
            this.packetReasonOf = sig.getMethod("reason");
            this.packetAvailable = true;
        } catch (Throwable t) {
            this.packetAvailable = false;
        }
    }

    /** Resolve the single-arg {@code publish} method by name (erased to Signal). */
    private static Method findPublish(Class<?> traceClass) {
        for (Method m : traceClass.getMethods()) {
            if (m.getName().equals("publish") && m.getParameterCount() == 1) {
                return m;
            }
        }
        return null;
    }

    // ---- test seam ----------------------------------------------------------

    /**
     * Inject a board {@code Trace} + its {@code publish} {@link Method} directly,
     * bypassing {@code Backplane} discovery. Used by tests to drive the link
     * against a real (or fake) trace without registering a live {@code BoardPort}.
     * The chat handles ({@code ChatSendSignal} ctor + accessors) are still resolved
     * reflectively on demand from the classpath.
     *
     * @param trace         the trace object to publish onto
     * @param publishMethod its single-arg {@code publish} method
     */
    void setTraceForTest(Object trace, Method publishMethod) {
        this.trace = trace;
        this.tracePublish = publishMethod;
        this.traceResolved = true;
    }
}
