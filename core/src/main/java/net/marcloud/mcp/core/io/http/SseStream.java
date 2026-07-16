package net.marcloud.mcp.core.io.http;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import net.marcloud.mcp.core.flt.seam.NettyTap;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketOutboundEvent;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.GameEvent;

/**
 * A.10 — the outward Server-Sent Events stream: one long-lived HTTP response that
 * pushes {@link GameEvent}s to the client as they fire, so an AI does not have to
 * poll {@code world_view}/{@code packets_tail}. Downstream half of the streaming
 * story; the upstream half (continuous control) is already the durable
 * {@code act_set} intent, so no bidirectional socket is needed.
 *
 * <p><b>Why SSE, not WebSocket:</b> the need is unidirectional (game → AI) and the
 * facade is the JDK {@link com.sun.net.httpserver.HttpServer} (zero-dep). SSE is a
 * plain {@code text/event-stream} HTTP response the JDK server serves natively; a
 * WebSocket 101 upgrade would require replacing the HTTP server and adding a whole
 * new attack surface. SSE also inherits the facade's bearer-auth + non-loopback
 * guard for free (it routes through the same authorized {@code handle}).
 *
 * <p><b>Reference-free (L7):</b> a frame carries only primitives, enum-ish strings,
 * the event's simple class name, and — for Netty-tap packets — the same
 * {@link NettyTap.PacketTapHandler.MessageSnapshot} projection the packet tools
 * use (className + summary + reference-free field map). No live {@code Packet} /
 * {@code Entity} / {@code ByteBuf} ever reaches the socket.
 *
 * <p><b>Threading / lifetime:</b> each connection subscribes a {@link Consumer} to
 * the {@link EventBus} and blocks its serving thread writing frames until the
 * client disconnects (a write throws {@link IOException}) or the server stops; the
 * subscription is always removed in {@code finally}, so a dropped client can never
 * leak a subscriber. Publishing runs on the game/Netty thread, so the per-event
 * work here is cheap: project to a small map, serialize, enqueue.
 */
public final class SseStream {

    /** Cap concurrent streams so a client cannot exhaust the server thread pool. */
    private static final int MAX_STREAMS = 4;

    private final EventBus bus;
    private final AtomicInteger open = new AtomicInteger();

    public SseStream(EventBus bus) {
        this.bus = bus;
    }

    /** Kinds recognized by the {@code kinds} filter (case-insensitive). */
    private enum Kind { TICK, PACKET, WORLD, OTHER }

    /**
     * Serve one SSE connection: write the event-stream headers, subscribe to the
     * bus, then drain projected frames to {@code out} until the client disconnects.
     * Blocks the calling (serving) thread for the connection's lifetime. The caller
     * (HttpFacade) has already authorized the request and owns closing the exchange.
     *
     * @param out      the response body stream (headers already sent, length 0)
     * @param kindsArg optional CSV of kinds to include (tick,packet,world,other);
     *                 null/blank = all
     */
    public void serve(OutputStream out, String kindsArg) throws IOException {
        if (open.incrementAndGet() > MAX_STREAMS) {
            open.decrementAndGet();
            writeFrame(out, "error", "{\"error\":\"too many concurrent streams (max "
                    + MAX_STREAMS + ")\"}");
            return;
        }
        Set<Kind> want = parseKinds(kindsArg);
        // Bounded hand-off: the game/Netty publisher offers and NEVER blocks (drops
        // when full = backpressure that protects the game thread); this serving
        // thread drains and writes. A dropped frame is acceptable for a live feed.
        java.util.concurrent.BlockingQueue<String> q =
                new java.util.concurrent.LinkedBlockingQueue<>(1024);
        Consumer<GameEvent> sub = ev -> {
            try {
                String frame = project(ev, want);
                if (frame != null) {
                    q.offer(frame); // non-blocking; drop if the consumer fell behind
                }
            } catch (Throwable ignored) {
                // a bad projection must never propagate into the game thread
            }
        };
        bus.subscribe(GameEvent.class, sub);
        try {
            // greet so the client sees the stream is live even before the first event
            writeFrame(out, "hello", "{\"stream\":\"v1\",\"kinds\":\"" + kindsCsv(want) + "\"}");
            // Poll on a short cadence and write a keep-alive comment on each idle
            // tick. The keep-alive is not just liveness: it is how a dead client is
            // detected promptly — com.sun.net.httpserver gives no disconnect callback,
            // so only a write reveals the closed socket (IOException → clean exit +
            // unsubscribe). A short cadence bounds how long an abandoned stream keeps
            // its bus subscription (and its per-event projection work) alive.
            long lastKeepAlive = System.nanoTime();
            while (true) {
                String frame = q.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (frame != null) {
                    out.write(frame.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } else if (System.nanoTime() - lastKeepAlive >= 1_000_000_000L) {
                    out.write(": keep-alive\n\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    lastKeepAlive = System.nanoTime();
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            // client disconnected — normal end of stream
        } finally {
            bus.unsubscribe(sub);
            open.decrementAndGet();
        }
    }

    /** Current number of open streams (diagnostics/tests). */
    public int openStreams() {
        return open.get();
    }

    // ---- projection (reference-free) --------------------------------------

    /** Project an event to an SSE frame, or null if its kind is filtered out. */
    private static String project(GameEvent ev, Set<Kind> want) {
        Kind k = kindOf(ev);
        if (!want.contains(k)) {
            return null;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", k.name().toLowerCase(Locale.ROOT));
        m.put("event", ev.getClass().getSimpleName());
        m.put("tickId", ev.tickId());
        m.put("mono", ev.timestampNanos());
        Object raw = rawMsgOf(ev);
        if (raw instanceof NettyTap.PacketTapHandler.MessageSnapshot snap) {
            m.put("dir", ev instanceof SeamPacketInboundEvent ? "IN" : "OUT");
            m.put("class", snap.className());
            if (snap.summary() != null && !snap.summary().isEmpty()) {
                m.put("summary", snap.summary());
            }
            if (snap.fields() != null && !snap.fields().isEmpty()) {
                m.put("fields", snap.fields()); // already an immutable reference-free copy
            }
        } else {
            // Prefer the event's own reference-free typed projection; fall back to a
            // truncated toString only when a subclass offers none.
            Map<String, Object> typed = ev.streamSummary();
            if (typed != null && !typed.isEmpty()) {
                m.put("fields", typed);
            } else {
                String s = String.valueOf(ev);
                if (s.length() > 300) {
                    s = s.substring(0, 300);
                }
                m.put("summary", s);
            }
        }
        return frame("event", Json.write(m));
    }

    private static Object rawMsgOf(GameEvent ev) {
        if (ev instanceof SeamPacketInboundEvent in) {
            return in.rawMsg();
        }
        if (ev instanceof SeamPacketOutboundEvent out) {
            return out.rawMsg();
        }
        return null;
    }

    private static Kind kindOf(GameEvent ev) {
        if (ev instanceof SeamPacketInboundEvent || ev instanceof SeamPacketOutboundEvent) {
            return Kind.PACKET;
        }
        String n = ev.getClass().getSimpleName();
        if (n.equals("TickEvent")) {
            return Kind.TICK;
        }
        if (n.contains("World") || n.contains("BlockChange") || n.contains("Chat")
                || n.contains("Disconnect") || n.contains("Health") || n.contains("Death")) {
            return Kind.WORLD;
        }
        return Kind.OTHER;
    }

    // ---- SSE framing / filter helpers -------------------------------------

    private static String frame(String eventName, String jsonData) {
        return "event: " + eventName + "\ndata: " + jsonData + "\n\n";
    }

    private static void writeFrame(OutputStream out, String eventName, String jsonData)
            throws IOException {
        out.write(frame(eventName, jsonData).getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    private static Set<Kind> parseKinds(String csv) {
        if (csv == null || csv.isBlank()) {
            return java.util.EnumSet.allOf(Kind.class);
        }
        Set<Kind> out = java.util.EnumSet.noneOf(Kind.class);
        for (String part : csv.split(",")) {
            String p = part.trim().toUpperCase(Locale.ROOT);
            if (p.isEmpty()) {
                continue;
            }
            try {
                out.add(Kind.valueOf(p));
            } catch (IllegalArgumentException ignored) {
                // unknown kind ignored
            }
        }
        return out.isEmpty() ? java.util.EnumSet.allOf(Kind.class) : out;
    }

    private static String kindsCsv(Set<Kind> want) {
        StringBuilder sb = new StringBuilder();
        for (Kind k : want) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(k.name().toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }
}
