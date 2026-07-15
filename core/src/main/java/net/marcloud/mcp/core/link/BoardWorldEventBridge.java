package net.marcloud.mcp.core.link;

import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.DisconnectedEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.flt.seam.NettyTap.PacketTapHandler.MessageSnapshot;

/**
 * The core→board world-event pump (PHASE E, E.2). Subscribes to a WHITELIST of
 * core {@link net.marcloud.mcp.core.ke.event.GameEvent} types on the core
 * {@link EventBus} and republishes each as its matching board {@code Signal} on
 * the board {@code Trace} — via the reflective, zero-hard-dependency
 * {@link BoardTraceLink} (core never imports {@code net.marcloud.mcp.board.*}).
 * Board present ⇒ world events surface to chips; board absent ⇒ every mapping is a
 * silent no-op.
 *
 * <p><b>Bounded by design.</b> This bridge subscribes to specific leaf event
 * TYPES only — never the {@code GameEvent} base — so it can never accidentally
 * fan the entire event stream onto the board bus. Adding a new mapping is a
 * deliberate act: subscribe the new type and add its {@code onX} handler.
 *
 * <p><b>Honesty of payloads.</b> The bridge sees packets only as the frozen
 * {@link MessageSnapshot} ({@code className} + reference-free {@code summary}) that
 * the Netty tap publishes — never the live packet. So a packet-sourced signal can
 * only carry a field the PHASE-P summarizer already emits structurally:
 * <ul>
 *   <li>{@code DisconnectedEvent} → {@code DisconnectSignal(reasonText)} — Tier-1,
 *       reason comes straight from {@code DisconnectedEvent.reasonText()}.</li>
 *   <li>inbound {@code S02PacketChat} → {@code ChatReceiveSignal(text)} — Tier-1,
 *       text parsed from the chat summarizer's {@code text="..."} field.</li>
 *   <li>inbound {@code S23PacketBlockChange} → {@code BlockChangeSignal(x,y,z,state)}
 *       — Tier-2, coords + state parsed from the block-change summarizer's
 *       {@code at=x,y,z state=...} form.</li>
 *   <li>inbound {@code S06PacketUpdateHealth} → {@code HealthChangeSignal(hp)}
 *       — Tier-2, hp parsed from the health summarizer's {@code hp=<f>} field.</li>
 *   <li>inbound {@code S42PacketCombatEvent} (ENTITY_DIED only) → {@code DeathSignal(msg)}
 *       — Tier-2, death message parsed from the combat summarizer's
 *       {@code death="..."} field; the non-death combat events emit nothing.</li>
 *   <li>inbound {@code S38PacketPlayerListItem} (ADD_PLAYER only) → one
 *       {@code PlayerJoinSignal} per named entry, parsed from the player-list
 *       summarizer's {@code names=a,b,c} field (one packet can add several).</li>
 * </ul>
 * {@code PlayerLeaveSignal} is intentionally NOT wired: on the wire a
 * {@code REMOVE_PLAYER} entry carries only a UUID, never a name (the client decodes
 * {@code GameProfile(uuid, null)}), so the summary honestly has no name to map. The
 * signal ships as a typed contract only. Do NOT emit it with a fabricated name.
 *
 * <p>Fault-isolated: the {@link EventBus} already guards each subscriber, and
 * {@link BoardTraceLink} never throws onto the caller, so a board-side fault can
 * never reach the game/Netty thread.
 */
public final class BoardWorldEventBridge {

    private static final String S02_CHAT = "net.minecraft.network.play.server.S02PacketChat";
    private static final String S23_BLOCK_CHANGE =
            "net.minecraft.network.play.server.S23PacketBlockChange";
    private static final String S06_HEALTH =
            "net.minecraft.network.play.server.S06PacketUpdateHealth";
    private static final String S42_COMBAT =
            "net.minecraft.network.play.server.S42PacketCombatEvent";
    private static final String S38_PLAYER_LIST =
            "net.minecraft.network.play.server.S38PacketPlayerListItem";

    private static final String CHAT_RECEIVE_SIGNAL =
            "net.marcloud.mcp.board.signals.ChatReceiveSignal";
    private static final String DISCONNECT_SIGNAL =
            "net.marcloud.mcp.board.signals.DisconnectSignal";
    private static final String BLOCK_CHANGE_SIGNAL =
            "net.marcloud.mcp.board.signals.BlockChangeSignal";
    private static final String HEALTH_CHANGE_SIGNAL =
            "net.marcloud.mcp.board.signals.HealthChangeSignal";
    private static final String DEATH_SIGNAL =
            "net.marcloud.mcp.board.signals.DeathSignal";
    private static final String PLAYER_JOIN_SIGNAL =
            "net.marcloud.mcp.board.signals.PlayerJoinSignal";

    private final EventBus bus;
    private final BoardTraceLink link;

    /** Use the process-wide {@link BoardTraceLink#shared()} link. */
    public BoardWorldEventBridge(EventBus bus) {
        this(bus, BoardTraceLink.shared());
    }

    /** Use an explicit link (tests inject one wired to a fake trace). */
    public BoardWorldEventBridge(EventBus bus, BoardTraceLink link) {
        this.bus = bus;
        this.link = link;
    }

    /**
     * Subscribe the whitelisted event types on the bus. No-op if the bus is null.
     * Safe to call once at boot; board may be absent and each mapping stays a
     * silent no-op.
     */
    public void attach() {
        if (bus == null) {
            return;
        }
        bus.subscribe(DisconnectedEvent.class, this::onDisconnect);
        bus.subscribe(SeamPacketInboundEvent.class, this::onInbound);
    }

    // ---- mappings -----------------------------------------------------------

    /** DisconnectedEvent → DisconnectSignal(reasonText). Never throws. */
    private void onDisconnect(DisconnectedEvent event) {
        if (event == null) {
            return;
        }
        link.publish(DISCONNECT_SIGNAL,
                new Class<?>[] { String.class },
                event.reasonText());
    }

    /**
     * Inbound packet → a typed board signal, chosen by the snapshot's class name.
     * Only decoded packets carry a {@link MessageSnapshot} (raw ByteBufs arrive as
     * a different frozen type and are ignored here). Never throws.
     */
    private void onInbound(SeamPacketInboundEvent event) {
        if (event == null || !(event.rawMsg() instanceof MessageSnapshot snap)) {
            return;
        }
        String cn = snap.className();
        if (cn == null) {
            return;
        }
        if (cn.endsWith("S02PacketChat") || S02_CHAT.equals(cn)) {
            String text = parseQuoted(snap.summary(), "text=");
            link.publish(CHAT_RECEIVE_SIGNAL,
                    new Class<?>[] { String.class },
                    text == null ? "" : text);
            return;
        }
        if (cn.endsWith("S23PacketBlockChange") || S23_BLOCK_CHANGE.equals(cn)) {
            emitBlockChange(snap.summary());
            return;
        }
        if (cn.endsWith("S06PacketUpdateHealth") || S06_HEALTH.equals(cn)) {
            emitHealth(snap.summary());
            return;
        }
        if (cn.endsWith("S42PacketCombatEvent") || S42_COMBAT.equals(cn)) {
            emitDeath(snap.summary());
            return;
        }
        if (cn.endsWith("S38PacketPlayerListItem") || S38_PLAYER_LIST.equals(cn)) {
            emitPlayerJoins(snap.summary());
        }
        // Any other inbound packet type: not whitelisted, no signal.
    }

    /**
     * Parse the S23 summary {@code "blockChange at=x,y,z state=<...>"} into a
     * {@code BlockChangeSignal}. Emits nothing if the position cannot be parsed as
     * three integers (honest: no fake coordinates).
     */
    private void emitBlockChange(String summary) {
        if (summary == null) {
            return;
        }
        String at = parseToken(summary, "at=");
        if (at == null) {
            return;
        }
        String[] parts = at.split(",");
        if (parts.length != 3) {
            return; // "?" or malformed — do not invent coordinates
        }
        int x;
        int y;
        int z;
        try {
            x = Integer.parseInt(parts[0].trim());
            y = Integer.parseInt(parts[1].trim());
            z = Integer.parseInt(parts[2].trim());
        } catch (NumberFormatException e) {
            return; // non-numeric position — decline honestly
        }
        String state = parseAfter(summary, "state=");
        link.publish(BLOCK_CHANGE_SIGNAL,
                new Class<?>[] { int.class, int.class, int.class, String.class },
                x, y, z, state == null ? "" : state);
    }

    /**
     * Parse the S06 summary {@code "health hp=<f> food=<i> sat=<f>"} into a
     * {@code HealthChangeSignal(float)}. Emits nothing if {@code hp=} is absent or
     * not a float (honest: no invented health value).
     */
    private void emitHealth(String summary) {
        if (summary == null) {
            return;
        }
        String hp = parseToken(summary, "hp=");
        if (hp == null) {
            return;
        }
        float health;
        try {
            health = Float.parseFloat(hp.trim());
        } catch (NumberFormatException e) {
            return; // non-numeric — decline honestly
        }
        link.publish(HEALTH_CHANGE_SIGNAL,
                new Class<?>[] { float.class },
                health);
    }

    /**
     * Parse the S42 summary and, only for {@code event=ENTITY_DIED}, emit a
     * {@code DeathSignal(message)} carrying the quoted {@code death="..."} text.
     * The non-death combat events (ENTER_COMBAT / END_COMBAT) carry no message and
     * emit nothing.
     */
    private void emitDeath(String summary) {
        if (summary == null) {
            return;
        }
        String event = parseToken(summary, "event=");
        if (!"ENTITY_DIED".equals(event)) {
            return; // only a death is a DeathSignal
        }
        String msg = parseQuoted(summary, "death=");
        link.publish(DEATH_SIGNAL,
                new Class<?>[] { String.class },
                msg == null ? "" : msg);
    }

    /**
     * Parse the S38 summary and, only for {@code action=ADD_PLAYER}, emit one
     * {@code PlayerJoinSignal} per name in the {@code names=a,b,c} field (a single
     * packet can add several players). Emits nothing for other actions or when no
     * names are present (e.g. {@code names=-}).
     */
    private void emitPlayerJoins(String summary) {
        if (summary == null) {
            return;
        }
        String action = parseToken(summary, "action=");
        if (!"ADD_PLAYER".equals(action)) {
            return; // only an add is a join; REMOVE carries no name on the wire
        }
        String names = parseToken(summary, "names=");
        if (names == null || names.isEmpty() || "-".equals(names)) {
            return; // no honestly-available names
        }
        for (String name : names.split(",")) {
            String trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            link.publish(PLAYER_JOIN_SIGNAL,
                    new Class<?>[] { String.class },
                    trimmed);
        }
    }

    // ---- tiny summary parsers (format defined by HighValueSummarizers) ------

    /**
     * Value of {@code key} up to the next space, or null if absent. E.g.
     * {@code parseToken("blockChange at=1,2,3 state=x", "at=")} → {@code "1,2,3"}.
     */
    private static String parseToken(String s, String key) {
        int i = s.indexOf(key);
        if (i < 0) {
            return null;
        }
        int start = i + key.length();
        int end = s.indexOf(' ', start);
        return end < 0 ? s.substring(start) : s.substring(start, end);
    }

    /**
     * Value of a quoted field {@code key="..."} (the chat summarizer's shape), or
     * null if the opening {@code key="} or its closing quote is absent.
     */
    private static String parseQuoted(String s, String key) {
        if (s == null) {
            return null;
        }
        int i = s.indexOf(key + "\"");
        if (i < 0) {
            return null;
        }
        int start = i + key.length() + 1;
        int end = s.indexOf('"', start);
        return end < 0 ? null : s.substring(start, end);
    }

    /** Everything after {@code key} to end of string, or null if {@code key} absent. */
    private static String parseAfter(String s, String key) {
        int i = s.indexOf(key);
        return i < 0 ? null : s.substring(i + key.length());
    }
}
