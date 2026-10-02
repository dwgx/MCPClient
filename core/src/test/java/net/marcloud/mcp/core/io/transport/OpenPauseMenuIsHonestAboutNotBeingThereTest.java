package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.se.Ring;

import org.junit.Test;

/**
 * {@code open_pause_menu} must degrade honestly when there is no game to press ESC on.
 *
 * <p>An agent can call this tool before {@code GameBridge} is initialised, or against a headless
 * build. The alternative to degrading is an exception escaping the handler, which the MCP layer
 * turns into an opaque transport error: the caller would learn that something broke and not
 * whether a menu opened. This pins the two properties that make it usable — it is REGISTERED
 * under the name the gate tables declare, and it reports {@code isError=true} with a message a
 * model can act on.
 *
 * <p><b>Non-vacuity.</b> The handler runs; the assertion is on the {@link CallToolResult} it
 * produced, and the non-error branch cannot be reached without a live game, so the test cannot
 * pass by the handler being absent. Drop the {@code try}/{@code catch} in
 * {@link EscPanelTools#openPauseMenu()} and this errors instead of passing.
 */
public class OpenPauseMenuIsHonestAboutNotBeingThereTest {

    @Test
    public void theToolRegistersUnderTheNameTheGateTablesDeclare() {
        IoSupervisor exec = new IoSupervisor(4, 2000L);
        try {
            IoManager registry = new IoManager(exec, (net.marcloud.mcp.core.se.SeReferenceMonitor) null);
            new EscPanelTools().registerAll(registry);
            assertTrue("open_pause_menu must be a registered builtin: " + registry.names(),
                    registry.names().contains("open_pause_menu"));
            assertEquals("it changes what is on screen and nothing else -- no packet leaves the "
                            + "client -- so it belongs beside open_overlay at R3",
                    Ring.R3, Ring.forBuiltin("open_pause_menu", Ring.R2));
        } finally {
            exec.shutdown();
        }
    }

    @Test
    public void withNoGameRunningItReportsAnErrorRatherThanThrowing() throws Exception {
        SyncToolSpecification spec = new EscPanelTools().openPauseMenu();
        Tool tool = spec.tool();
        assertEquals("open_pause_menu", tool.name());
        assertTrue("an agent plans from the description, so it has to say what the door is for",
                tool.description().contains("displayInGameMenu"));
        assertTrue("and it has to warn that the alternative tools cannot deliver ESC",
                tool.description().contains("press_key_binding"));

        CallToolResult result = spec.callHandler().apply(null,
                new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(
                        "open_pause_menu", Map.of()));

        assertNotNull(result);
        assertEquals("no client means nothing opened, and that is an error the caller can read -- "
                        + "never a bare exception and never a success it cannot back",
                true, result.isError());
        String text = textOf(result);
        assertTrue("the message must name what went wrong, not just that something did: " + text,
                text.contains("game thread") || text.contains("GameBridge"));
    }

    private static String textOf(CallToolResult result) {
        StringBuilder sb = new StringBuilder();
        result.content().forEach(c -> {
            if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent t) {
                sb.append(t.text());
            }
        });
        return sb.toString();
    }
}
