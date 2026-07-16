package net.marcloud.mcp.core.drivers.observe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import net.marcloud.mcp.core.flt.seam.NettyTap;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketInboundEvent;
import net.marcloud.mcp.core.flt.seam.events.SeamPacketOutboundEvent;
import net.marcloud.mcp.core.io.http.Json;
import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.PacketJournal;
import net.marcloud.mcp.core.ke.event.EventBus;

import org.junit.Before;
import org.junit.Test;

/**
 * Teeth for the {@code packet_view} tool handler (the ObserveTools coverage hole
 * flagged in the 2026-07-15b handoff). Drives the handler directly over a
 * hand-built {@link PacketJournal} — publishing frozen {@code MessageSnapshot}s
 * with and without typed fields — and asserts the observable JSON: only entries
 * that carry typed fields appear, and the dir / class / sinceSeq / limit filters
 * and the honest tap-not-installed guard behave.
 *
 * <p>Non-vacuous: the {@code sinceSeq} test pins the {@code seq <= sinceSeq}
 * boundary, so a handler using {@code <} (off-by-one, leaking the boundary seq)
 * would fail it.
 */
public class ObserveToolsTest {

    private static final String POSLOOK = "net.minecraft.network.play.server.S08PacketPlayerPosLook";
    private static final String HEALTH = "net.minecraft.network.play.server.S06PacketUpdateHealth";
    private static final String PLAYER = "net.minecraft.network.play.client.C03PacketPlayer";
    private static final String NOFIELDS = "net.minecraft.network.play.server.S3EPacketTeams";

    private EventBus bus;
    private PacketJournal journal;
    private ObserveTools tools;

    @Before
    public void setUp() {
        GameClock.INSTANCE.reset();
        bus = new EventBus();
        journal = new PacketJournal(64);
        journal.attach(bus);
        // seams == null → tapInstalled() is false (honest guard path is reachable).
        tools = new ObserveTools(GameClock.INSTANCE, null, journal, null);
    }

    // ===== helpers =====

    private static NettyTap.PacketTapHandler.MessageSnapshot withFields(
            String cls, Map<String, Object> fields) {
        return new NettyTap.PacketTapHandler.MessageSnapshot(cls, "", fields);
    }

    private static NettyTap.PacketTapHandler.MessageSnapshot noFields(String cls) {
        return new NettyTap.PacketTapHandler.MessageSnapshot(cls, "", null);
    }

    private void inbound(String cls, Map<String, Object> fields) {
        bus.publish(new SeamPacketInboundEvent(withFields(cls, fields)));
    }

    private void inboundNoFields(String cls) {
        bus.publish(new SeamPacketInboundEvent(noFields(cls)));
    }

    private void outbound(String cls, Map<String, Object> fields) {
        bus.publish(new SeamPacketOutboundEvent(withFields(cls, fields)));
    }

    private static CallToolResult call(SyncToolSpecification spec, Map<String, Object> args) {
        return spec.callHandler().apply(null, new CallToolRequest(spec.tool().name(), args));
    }

    private static String text(CallToolResult r) {
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                return t.text();
            }
        }
        fail("no text content in result");
        return null;
    }

    private static Map<String, Object> parseJson(String json) {
        Map<String, Object> m = Json.readObject(json);
        assertNotNull("top-level JSON is an object", m);
        return m;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> entries(CallToolResult r) {
        Map<String, Object> out = parseJson(text(r));
        return (List<Object>) out.get("entries");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> row(Object entry) {
        return (Map<String, Object>) entry;
    }

    // ===== tests =====

    @Test
    public void omitsEntriesWithNoTypedFieldsAndShapesTheRow() {
        inbound(HEALTH, Map.of("hp", 20.0, "food", 18));
        inboundNoFields(NOFIELDS); // B/C tier: null fields → must be omitted

        CallToolResult r = call(tools.packetView(), Map.of());
        assertFalse("not an error", Boolean.TRUE.equals(r.isError()));

        List<Object> es = entries(r);
        assertEquals("only the one A-tier (fields-carrying) packet appears", 1, es.size());
        Map<String, Object> row = row(es.get(0));
        assertEquals(1L, ((Number) row.get("seq")).longValue());
        assertEquals("IN", row.get("dir"));
        assertEquals(HEALTH, row.get("class"));
        assertEquals("S06PacketUpdateHealth", row.get("simpleName"));
        Map<String, Object> fields = row(row.get("fields"));
        assertEquals(20.0, ((Number) fields.get("hp")).doubleValue(), 0.0001);
        assertEquals(18, ((Number) fields.get("food")).intValue());
    }

    @Test
    public void dirFilterKeepsOnlyMatchingDirection() {
        inbound(POSLOOK, Map.of("x", 1.0)); // seq 1, IN
        outbound(PLAYER, Map.of("onGround", true)); // seq 2, OUT

        List<Object> out = entries(call(tools.packetView(), Map.of("dir", "OUT")));
        assertEquals(1, out.size());
        assertEquals(PLAYER, row(out.get(0)).get("class"));

        List<Object> in = entries(call(tools.packetView(), Map.of("dir", "IN")));
        assertEquals(1, in.size());
        assertEquals(POSLOOK, row(in.get(0)).get("class"));
    }

    @Test
    public void classSubstringFilterNarrows() {
        inbound(POSLOOK, Map.of("x", 1.0));
        inbound(HEALTH, Map.of("hp", 20.0));

        List<Object> es = entries(call(tools.packetView(), Map.of("class", "UpdateHealth")));
        assertEquals("only the class-substring match survives", 1, es.size());
        assertEquals(HEALTH, row(es.get(0)).get("class"));
    }

    @Test
    public void sinceSeqExcludesThatSeqAndBelow() {
        inbound(POSLOOK, Map.of("x", 1.0)); // seq 1
        inbound(HEALTH, Map.of("hp", 20.0)); // seq 2
        outbound(PLAYER, Map.of("onGround", true)); // seq 3

        // sinceSeq == 2 means strictly-greater: seq 3 only. A handler using `<`
        // instead of `<=` would leak seq 2 and this size check would fail.
        List<Object> es = entries(call(tools.packetView(), Map.of("sinceSeq", 2)));
        assertEquals("only seq strictly greater than sinceSeq", 1, es.size());
        assertEquals(3L, ((Number) row(es.get(0)).get("seq")).longValue());
    }

    @Test
    public void limitKeepsNewestOldestFirst() {
        for (int i = 0; i < 5; i++) {
            inbound(HEALTH, Map.of("hp", (double) i));
        }
        List<Object> es = entries(call(tools.packetView(), Map.of("limit", 2)));
        assertEquals(2, es.size());
        // newest two (seq 4, 5), preserving oldest-first order
        assertEquals(4L, ((Number) row(es.get(0)).get("seq")).longValue());
        assertEquals(5L, ((Number) row(es.get(1)).get("seq")).longValue());
    }

    @Test
    public void emptyJournalWithNoTapIsHonestError() {
        // Nothing published, seams == null → the guard must refuse to imply "no packets".
        CallToolResult r = call(tools.packetView(), Map.of());
        assertTrue("empty journal + no tap is an honest error, not a false 'no packets'",
                Boolean.TRUE.equals(r.isError()));
        assertTrue("message says the tap is not installed",
                text(r).contains("tap not installed"));
    }
}
