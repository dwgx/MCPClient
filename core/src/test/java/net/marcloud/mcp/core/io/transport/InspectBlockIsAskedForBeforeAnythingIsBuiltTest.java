package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.Test;

/**
 * {@code inspect_block} must be askable, and must not answer a question it did not read.
 *
 * <p>Three things are pinned here, each of which is a way this surface has lied before:
 *
 * <ul>
 *   <li><b>It is registered.</b> A tool that exists as a class but never reaches {@code all()} is
 *       a tool no model can call. This repo has shipped that exact gap for other surfaces.</li>
 *   <li><b>Its schema declares the coordinates.</b> The {@code move} object that produced a
 *       permanently-ACTIVE-but-stationary intent was {@code {"type":"object"}} with no properties:
 *       a schema that declares nothing is not a weaker check, it is no check. So the schema is
 *       asserted to name x, y and z and to require all three.</li>
 *   <li><b>A missing world is an error, not a safe verdict.</b> Handled without a live client the
 *       only reachable reply is "no world"; it must not read as "the cell is fine".</li>
 * </ul>
 *
 * <p>No live game is needed: the handler's no-world path is the contract being pinned, and the
 * vanilla spawn rule it carries is pinned in {@code TheBlockInspectorAnswersTheSpawnGateTest}.
 */
public final class InspectBlockIsAskedForBeforeAnythingIsBuiltTest {

    private static ToolRegistry registry() {
        return new ToolRegistry(new ToolContext(null, null, null, null, null));
    }

    private static SyncToolSpecification tool(ToolRegistry reg) {
        for (SyncToolSpecification s : reg.all()) {
            if (s.tool().name().equals("inspect_block")) {
                return s;
            }
        }
        throw new AssertionError("inspect_block is not in all(): it would be unreachable to every "
                + "model, which is the failure mode this test exists to prevent");
    }

    @Test
    @SuppressWarnings("unchecked")
    public void itIsRegisteredAndDeclaresTheCoordinatesItNeeds() {
        SyncToolSpecification spec = tool(registry());

        Map<String, Object> schema = (Map<String, Object>) spec.tool().inputSchema();
        assertNotNull("no schema at all", schema);
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull("the schema declares no properties, so it cannot reject a missing axis: "
                + schema, props);

        for (String axis : List.of("x", "y", "z")) {
            assertNotNull("the schema does not mention " + axis + ", so a caller learns its absence "
                    + "only from a handler error: " + props, props.get(axis));
        }
        List<String> required = (List<String>) schema.get("required");
        assertNotNull("nothing is required, so a position with one axis reads as a position",
                required);
        for (String axis : List.of("x", "y", "z")) {
            assertTrue("required must name " + axis + ", not just describe it: " + required,
                    required.contains(axis));
        }
    }

    @Test
    public void withoutAWorldItRefusesRatherThanReportingACellIsSafe() {
        ToolRegistry reg = registry();
        SyncToolSpecification spec = tool(reg);
        CallToolResult r = spec.callHandler()
                .apply(null, new CallToolRequest("inspect_block",
                        Map.of("x", 10, "y", 64, "z", 10)));

        String text = "";
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                text = t.text();
            }
        }
        assertTrue("with no world loaded the honest answer is an error saying so, because a "
                + "successful reply here would be read as 'that cell is safe': " + text,
                Boolean.TRUE.equals(r.isError()));
        assertTrue("and it must name the cause, not just fail: " + text,
                text.contains("world") || text.contains("GameBridge"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void itsDescriptionStatesTheDirectionOfTheLightComparison() {
        SyncToolSpecification spec = tool(registry());
        String d = spec.tool().description();
        assertNotNull(d);
        assertTrue("the description must say the gate is '7 or below', because the natural "
                + "assumption is the opposite and it is the mistake this gate was written wrong "
                + "once already: " + d, d.contains("7 or below"));
        assertTrue("and it must warn that a missing floor is a hole a mob walks in through, which "
                + "is what turns a shelter into a coffin: " + d, d.contains("NOW"));
    }
}
