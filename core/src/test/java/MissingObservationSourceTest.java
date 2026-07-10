import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import java.util.List;
import java.util.Map;

import net.marcloud.mcp.core.agent.AgentAccess;
import net.marcloud.mcp.core.introspect.IntrospectionService;
import net.marcloud.mcp.core.introspect.MethodInfo;
import net.marcloud.mcp.core.mcp.ToolContext;
import net.marcloud.mcp.core.mcp.ToolRegistry;
import net.marcloud.mcp.core.state.PacketLog;
import org.junit.Test;

/**
 * Regression guard for MEDIUM#13: a missing observation SOURCE must not be
 * reported as a real negative observation.
 *
 * <p>These tests run in the headless suite where no {@code -javaagent} is
 * present, so {@link AgentAccess#isLoaded()} is deterministically false — the
 * exact condition under which the packet tap is dead and find_method searches
 * only the tiny seed set. Both tests would PASS against the old behavior (which
 * returned a clean "no packets" / empty "no matches") if that behavior were
 * still authoritative; they assert the source-unavailable signal instead.
 */
public class MissingObservationSourceTest {

    /** Fetch a built-in tool's spec by name from the registry. */
    private static SyncToolSpecification toolByName(ToolRegistry reg, String name) {
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals(name)) {
                return spec;
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    @Test
    public void recentPacketsReportsUnavailableWhenTapIsDown() {
        // Precondition: no agent in the test JVM, so the packet hooks that FEED
        // the log were never installed — the tap is genuinely unavailable.
        assertFalse("test JVM must have no -javaagent for this regression",
                AgentAccess.isLoaded());

        // Empty log (nothing ever recorded) + dead tap.
        PacketLog emptyLog = new PacketLog(64);
        ToolContext ctx = new ToolContext(null, null, null, emptyLog, null);
        ToolRegistry reg = new ToolRegistry(ctx);

        CallToolResult res = toolByName(reg, "recent_packets")
                .callHandler().apply(null, new CallToolRequest("recent_packets", Map.of()));

        // OLD behavior returned ok("(no packets recorded yet)") — a clean negative
        // that looks authoritative. It must now be an explicit unavailable error.
        assertTrue("empty log with dead tap must be an error, not a clean negative",
                Boolean.TRUE.equals(res.isError()));
        String text = res.content().toString().toLowerCase();
        assertTrue("must explain the tap is unavailable, not that there are no packets",
                text.contains("unavailable"));
        assertFalse("must NOT claim an authoritative empty observation",
                text.contains("no packets recorded yet"));
    }

    @Test
    public void recentPacketsHappyPathStillReturnsObservedPackets() {
        // Source-present analogue: once packets ARE in the ring, the tool must
        // still report them unchanged (the fix only touches the empty case).
        PacketLog log = new PacketLog(64);
        log.recordInbound("S00Login");
        log.recordOutbound("C00Handshake");

        ToolContext ctx = new ToolContext(null, null, null, log, null);
        ToolRegistry reg = new ToolRegistry(ctx);

        CallToolResult res = toolByName(reg, "recent_packets")
                .callHandler().apply(null, new CallToolRequest("recent_packets", Map.of()));

        assertFalse("recorded packets is a real observation, not an error",
                Boolean.TRUE.equals(res.isError()));
        String text = res.content().toString();
        assertTrue("should list the observed inbound packet", text.contains("S00Login"));
        assertTrue("should list the observed outbound packet", text.contains("C00Handshake"));
    }

    @Test
    public void findMethodReportsIncompletenessInSeedOnlyMode() {
        // No agent -> loadedClasses() is the tiny curated seed, so the search
        // space is known-incomplete.
        assertFalse("test JVM must have no -javaagent for this regression",
                AgentAccess.isLoaded());

        IntrospectionService svc = new IntrospectionService(
                getClass().getClassLoader(), List.of());

        // A method name that cannot exist in the seed set (Object/String/etc.).
        try {
            List<MethodInfo> results =
                    svc.findMethod("noSuchMethodNameZzz", null, null, 100);
            // OLD behavior returned an empty list here — an authoritative
            // "no matches" over a search space of ~4 classes. That is the bug.
            fail("seed-only find_method must not return a clean empty 'no matches'; "
                    + "got " + results.size() + " results without signalling incompleteness");
        } catch (IllegalStateException expected) {
            String msg = expected.getMessage().toLowerCase();
            assertTrue("must explain the search space is incomplete / seed-only",
                    msg.contains("seed-only") || msg.contains("incomplete")
                            || msg.contains("agentless"));
        }
    }

    @Test
    public void findMethodStillReturnsRealMatchesFromSeed() {
        // Happy path within the seed: toString exists on Object/String, so a
        // non-empty result must be returned WITHOUT raising incompleteness.
        IntrospectionService svc = new IntrospectionService(
                getClass().getClassLoader(), List.of());

        List<MethodInfo> results = svc.findMethod("toString", null, null, 100);

        assertFalse("seed contains classes with toString; must return matches",
                results.isEmpty());
        for (MethodInfo m : results) {
            assertTrue("all matches contain the queried name",
                    m.name().toLowerCase().contains("tostring"));
        }
    }

    @Test
    public void findMethodBlankNameStillReturnsEmptyWithoutRaising() {
        // Blank filter is a caller error handled before the search, so it must
        // stay a plain empty list — the incompleteness guard must not fire here.
        IntrospectionService svc = new IntrospectionService(
                getClass().getClassLoader(), List.of());

        List<MethodInfo> results = svc.findMethod("", null, null, 100);

        assertEquals("blank method filter yields empty, no incompleteness error",
                0, results.size());
    }
}
