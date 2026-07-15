package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import net.marcloud.mcp.core.se.CapabilityCatalog;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.se.IntegrityLevel;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.se.SeToolRequirement;
import org.junit.Test;

/**
 * Teeth for the W6 typed send_* tools: each is registered, arg-validates before
 * touching the (veto-guarded) send path, and is declared in all three security
 * side tables at the same level as send_raw_packet (R1 / CAP_NETWORK_SEND /
 * L3 HIGH). A missing gate-table entry is a real security hole, so these fail on
 * any drift.
 */
public class SendToolsW6Test {

    private static final String[] SEND_TOOLS = {
        "send_client_status", "send_held_item", "send_close_window", "send_dig"
    };

    private static SyncToolSpecification toolByName(ToolRegistry reg, String name) {
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals(name)) {
                return spec;
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    private static ToolRegistry registry() {
        return new ToolRegistry(new ToolContext(null, null, null, null, null));
    }

    @Test
    public void allSendToolsAreRegistered() {
        ToolRegistry reg = registry();
        for (String name : SEND_TOOLS) {
            assertNotNull(name + " must be registered", toolByName(reg, name));
        }
    }

    @Test
    public void everySendToolIsGatedInAllThreeTables() {
        for (String name : SEND_TOOLS) {
            // Ring R1 (outward network effect), like send_raw_packet
            assertEquals(name + " must be ring R1", Ring.R1, Ring.forBuiltin(name, Ring.R3));
            // CapabilityCatalog: CAP_NETWORK_SEND
            assertTrue(name + " must require CAP_NETWORK_SEND",
                    CapabilityCatalog.requiredFor(name, true).contains(CapabilitySid.CAP_NETWORK_SEND));
            // SeToolRequirement: declares an integrity write (HIGH) — non-null
            assertEquals(name + " must write at HIGH integrity like send_raw_packet",
                    IntegrityLevel.HIGH,
                    SeToolRequirement.forTool(name, true).writesResourceAt());
        }
    }

    @Test
    public void clientStatusRejectsUnknownStatusBeforeSending() {
        CallToolResult r = toolByName(registry(), "send_client_status").callHandler()
                .apply(null, new CallToolRequest("send_client_status", Map.of("status", "BOGUS")));
        assertTrue("unknown status is a validation error", Boolean.TRUE.equals(r.isError()));
        assertTrue(r.content().toString().toLowerCase().contains("unknown status"));
    }

    @Test
    public void heldItemRejectsOutOfRangeSlot() {
        CallToolResult r = toolByName(registry(), "send_held_item").callHandler()
                .apply(null, new CallToolRequest("send_held_item", Map.of("slot", 12)));
        assertTrue("slot 12 is out of range", Boolean.TRUE.equals(r.isError()));
        assertTrue(r.content().toString().contains("0-8"));
    }

    @Test
    public void digRejectsUnknownStatus() {
        CallToolResult r = toolByName(registry(), "send_dig").callHandler()
                .apply(null, new CallToolRequest("send_dig", Map.of("status", "NOPE")));
        assertTrue(Boolean.TRUE.equals(r.isError()));
        assertTrue(r.content().toString().toLowerCase().contains("unknown status"));
    }
}
