package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import org.junit.Test;

/**
 * {@code server_info} must report what the client holds, and label what it does not.
 *
 * <p>Three ways this could lie, each of which the repo has shipped somewhere else:
 *
 * <ul>
 *   <li><b>Presenting a stale ping as live.</b> {@code ServerData.pingToServer} is written by the
 *       server-BROWSER ping, before the session joined. 1.8.9's play session carries no live
 *       latency figure at all, so a bare "ping: 42ms" is a number about the past wearing the
 *       present's clothes. The reply labels it.</li>
 *   <li><b>Reading a count out of a string.</b> {@code ServerData.playerList} is the S2F list
 *       HEADER text, not a count; the live number is the net handler's player map. Reading the
 *       header's length would report a plausible wrong answer rather than none.</li>
 *   <li><b>Defaulting an absence.</b> A brand of "vanilla" or a version of 0 is a claim the server
 *       never made. {@code MC|Brand} is only set if the server pushed that payload, so "not sent"
 *       is the common, correct answer and must be distinguishable from "vanilla".</li>
 * </ul>
 *
 * <p>No live client is needed: the contract being pinned is the description and the schema, and
 * the handler's unreadable-world path is the one reachable reply.
 */
public final class ServerInfoDoesNotInventWhatItCannotReadTest {

    private static ToolRegistry registry() {
        return new ToolRegistry(new ToolContext(null, null, null, null, null));
    }

    private static SyncToolSpecification tool() {
        for (SyncToolSpecification s : registry().all()) {
            if (s.tool().name().equals("server_info")) {
                return s;
            }
        }
        throw new AssertionError("server_info is not in all()");
    }

    @Test
    public void itTakesNoArgumentsRatherThanOfferingOnesThatDoNothing() {
        Map<String, Object> schema = tool().tool().inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull("a schema that names properties invites a caller to pass them", props);
        assertTrue("server_info reads the live session, so there is nothing to parameterise: "
                + props.keySet(), props.isEmpty());
    }

    @Test
    public void itLabelsTheLatencyAsPreSessionRatherThanPresentingItAsLive() {
        String d = tool().tool().description();
        assertNotNull(d);
        assertTrue("the description must name browserPingMs and say it predates the session, or a "
                + "caller reads a stale number as current: " + d,
                d.contains("browserPingMs") && d.contains("BEFORE this session joined"));
        assertTrue("and it must say the play session carries no live latency, because that is "
                + "WHY the number is stale", d.contains("no live latency"));
    }

    @Test
    public void itPromisesToNameAnAbsenceRatherThanDefaultIt() {
        String d = tool().tool().description();
        assertTrue("an unreadable field must be named as such, never filled with a plausible "
                + "default: " + d, d.contains("not sent"));
    }

    @Test
    public void withoutAGameItErrorsRatherThanReportingAVanillaServer() {
        CallToolResultProbe r = new CallToolResultProbe(registry());
        String reply = r.call("server_info", Map.of());
        assertTrue("with no client there is no server to describe, and the honest reply is an "
                + "error -- a successful one would read as 'a vanilla server, no brand': " + reply,
                reply.startsWith("ERROR "));
    }

    /** Small indirection so the assertion text can name the tool without duplicating the lookup. */
    private static final class CallToolResultProbe {
        private final ToolRegistry reg;

        CallToolResultProbe(ToolRegistry reg) {
            this.reg = reg;
        }

        String call(String name, Map<String, Object> args) {
            for (SyncToolSpecification s : reg.all()) {
                if (s.tool().name().equals(name)) {
                    var res = s.callHandler().apply(null,
                            new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(name, args));
                    String text = "";
                    for (var c : res.content()) {
                        if (c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent t) {
                            text = t.text();
                        }
                    }
                    return (Boolean.TRUE.equals(res.isError()) ? "ERROR " : "OK ") + text;
                }
            }
            throw new AssertionError(name + " not registered");
        }
    }
}
