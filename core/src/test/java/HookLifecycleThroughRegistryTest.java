import static org.junit.Assert.assertEquals;

import net.marcloud.mcp.core.flt.HookBridge;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.lang.instrument.Instrumentation;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.HookFiredEvent;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.HookTools;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.io.IoSupervisor;
import net.marcloud.mcp.core.se.AllowAllGate;
import net.marcloud.mcp.core.se.SeLocalMonitor;
import net.marcloud.mcp.core.se.SeClearancePolicy;
import net.marcloud.mcp.core.se.Ring;
import org.junit.Assume;
import org.junit.Test;

/**
 * AUTOMATABLE (self-attach). The full install→fire→uninstall hook lifecycle driven
 * end-to-end through the SUPERVISED registry — i.e. via {@code
 * install_hook}/{@code uninstall_hook} tool calls, exactly as an MCP client would,
 * NOT by calling {@link FltDynamicManager} directly (that path is covered by
 * {@code DynamicHookManagerTest}). This proves the tool surface, the supervised
 * gate, the ByteBuddy retransform, the HookBridge route, and the EventBus all
 * cooperate through one real round-trip.
 *
 * <p>Uses {@code ByteBuddyAgent.install()} to self-attach; self-skips (no false
 * failure) if the JVM refuses self-attach.
 */
public class HookLifecycleThroughRegistryTest {

    /** Public static so retransform advice can reach it; already loaded (nested). */
    public static class Target {
        public String ping() {
            return "pong";
        }
    }

    private static IoManager registryWith(FltDynamicManager mgr) {
        IoSupervisor exec = new IoSupervisor(2, 3000L);
        IoManager reg = new IoManager(exec,
                new SeLocalMonitor(new SeClearancePolicy(Ring.R_MINUS_1, "tok")));
        new HookTools(mgr, new AllowAllGate()).registerAll(reg);
        return reg;
    }

    @Test
    public void installFireUninstallRoundTripThroughToolCalls() {
        Instrumentation inst;
        try {
            inst = net.bytebuddy.agent.ByteBuddyAgent.install();
        } catch (Throwable t) {
            Assume.assumeNoException("ByteBuddyAgent self-attach unavailable on this JVM", t);
            return;
        }
        Assume.assumeTrue("retransform must be supported",
                inst != null && inst.isRetransformClassesSupported());

        EventBus bus = new EventBus();
        FltDynamicManager mgr = new FltDynamicManager(inst, bus);
        IoManager reg = registryWith(mgr);

        AtomicInteger fired = new AtomicInteger(0);
        bus.subscribe(HookFiredEvent.class, e -> {
            if ("ping".equals(e.method())) {
                fired.incrementAndGet();
            }
        });

        Target target = new Target();
        assertEquals("baseline value before hook", "pong", target.ping());
        assertEquals("no events before hook", 0, fired.get());

        // 1) install_hook via the supervised tool call.
        CallToolResult install = reg.invoke("install_hook",
                Map.of("targetClass", Target.class.getName(), "method", "ping"));
        assertFalse("install_hook succeeds through the gate: " + install.content(),
                Boolean.TRUE.equals(install.isError()));
        assertEquals("manager records exactly one hook", 1, mgr.size());
        String hookId = extractHookId(install.content().toString());

        // 2) invoke the hooked method → a HookFiredEvent must fire through the bridge.
        assertEquals("hooked method still returns its real value", "pong", target.ping());
        assertEquals("exactly one HookFiredEvent after one call", 1, fired.get());

        // 3) uninstall_hook via the supervised tool call.
        CallToolResult uninstall = reg.invoke("uninstall_hook", Map.of("hookId", hookId));
        assertFalse("uninstall_hook succeeds: " + uninstall.content(),
                Boolean.TRUE.equals(uninstall.isError()));
        assertEquals("no hooks remain after uninstall", 0, mgr.size());

        // 4) invoke again → NO new event (advice reverted through the tool path).
        assertEquals("value unchanged after revert", "pong", target.ping());
        assertEquals("no new event fires after uninstall", 1, fired.get());
    }

    /** install_hook returns "installed hook <className>#ping@<routeKey>"; pull the id out. */
    private static String extractHookId(String message) {
        int at = message.indexOf(Target.class.getName());
        assertTrue("message must carry the hookId: " + message, at >= 0);
        String tail = message.substring(at);
        // strip any trailing JSON/text punctuation
        int end = tail.length();
        for (int i = 0; i < tail.length(); i++) {
            char c = tail.charAt(i);
            if (c == '"' || c == ']' || c == '}' || c == ' ' || c == ',' || c == ')') {
                end = i;
                break;
            }
        }
        return tail.substring(0, end);
    }
}
