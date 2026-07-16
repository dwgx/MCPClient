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
import net.marcloud.mcp.core.se.Privilege;
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

    /** The typed do_* send tools (renamed from send_* in W7; the 4 originals + 6 new). */
    private static final String[] SEND_TOOLS = {
        "do_client_status", "do_select_slot", "do_close_container", "do_dig",
        "do_set_abilities", "do_place_block", "do_click_slot", "do_set_creative_slot",
        "do_use_entity", "do_entity_action"
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

    /**
     * Every typed send tool must be gated on ALL FOUR security dimensions, at the
     * same level as send_raw_packet. The L4 privilege is the one that makes
     * {@code disable_privilege(SE_NET_RAW)} — the purpose-built kill switch for the
     * send surface — actually shut these off; without it a "disabled" send surface
     * still puts packets on the wire.
     */
    @Test
    public void everySendToolIsGatedOnAllFourDimensions() {
        for (String name : SEND_TOOLS) {
            SeToolRequirement req = SeToolRequirement.forTool(name, true);
            // L2 ring: R1 (outward network effect), like send_raw_packet
            assertEquals(name + " must be ring R1", Ring.R1, req.requiredRing());
            // L3 integrity: writes the network connection at HIGH
            assertEquals(name + " must write at HIGH integrity like send_raw_packet",
                    IntegrityLevel.HIGH, req.writesResourceAt());
            // L4 privilege: SE_NET_RAW, so disable_privilege(SE_NET_RAW) denies it
            assertEquals(name + " must require SE_NET_RAW so disable_privilege shuts it off",
                    Privilege.SE_NET_RAW, req.requiredPrivilege());
            // L5 capability: CAP_NETWORK_SEND
            assertTrue(name + " must require CAP_NETWORK_SEND",
                    req.requiredCaps().contains(CapabilitySid.CAP_NETWORK_SEND));
        }
    }

    /**
     * The registered-tool side of the gate check: every tool ToolRegistry actually
     * exposes that is a declared network sender ({@link SeToolRequirement#networkSendTools()})
     * is privilege-gated. Uses the explicit send set, NOT a name prefix — a prefix
     * ({@code startsWith("send_")}) silently stopped matching the tools once they were
     * renamed to {@code do_}, which is exactly the drift this guard must not have.
     * (The table-side bidirectional invariant lives in PolicySideTableDriftTest.)
     */
    @Test
    public void everyRegisteredSendToolIsPrivilegeGated() {
        ToolRegistry reg = registry();
        for (SyncToolSpecification spec : reg.all()) {
            String name = spec.tool().name();
            if (!SeToolRequirement.networkSendTools().contains(name)) {
                continue;
            }
            assertEquals(name + " is a registered send tool but declares no SE_NET_RAW"
                            + " — disable_privilege(SE_NET_RAW) would not stop it",
                    Privilege.SE_NET_RAW, SeToolRequirement.forTool(name, true).requiredPrivilege());
        }
    }

    @Test
    public void clientStatusRejectsUnknownStatusBeforeSending() {
        CallToolResult r = toolByName(registry(), "do_client_status").callHandler()
                .apply(null, new CallToolRequest("do_client_status", Map.of("status", "BOGUS")));
        assertTrue("unknown status is a validation error", Boolean.TRUE.equals(r.isError()));
        assertTrue(r.content().toString().toLowerCase().contains("unknown status"));
    }

    @Test
    public void heldItemRejectsOutOfRangeSlot() {
        CallToolResult r = toolByName(registry(), "do_select_slot").callHandler()
                .apply(null, new CallToolRequest("do_select_slot", Map.of("slot", 12)));
        assertTrue("slot 12 is out of range", Boolean.TRUE.equals(r.isError()));
        assertTrue(r.content().toString().contains("0-8"));
    }

    @Test
    public void digRejectsUnknownStatus() {
        CallToolResult r = toolByName(registry(), "do_dig").callHandler()
                .apply(null, new CallToolRequest("do_dig", Map.of("status", "NOPE")));
        assertTrue(Boolean.TRUE.equals(r.isError()));
        assertTrue(r.content().toString().toLowerCase().contains("unknown status"));
    }

    /**
     * A block action must never default its target to (0,0,0): that would silently
     * mine the world origin on a live server. Fails on the pre-fix code, which
     * defaulted each missing axis to 0 and sent the packet.
     */
    @Test
    public void digBlockActionRefusesToFabricateOrigin() {
        CallToolResult r = toolByName(registry(), "do_dig").callHandler()
                .apply(null, new CallToolRequest("do_dig", Map.of("status", "START_DESTROY_BLOCK")));
        assertTrue("missing coords on a block action must be an error, not (0,0,0)",
                Boolean.TRUE.equals(r.isError()));
        String text = r.content().toString();
        assertTrue(text, text.contains("required"));
        assertTrue("must say why, not just fail", text.contains("0,0,0"));
    }
}
