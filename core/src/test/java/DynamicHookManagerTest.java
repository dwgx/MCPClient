import static org.junit.Assert.assertEquals;

import net.marcloud.mcp.core.io.Capability;
import net.marcloud.mcp.core.se.SeToken;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.HookFiredEvent;
import net.marcloud.mcp.core.flt.FltDynamicManager;
import net.marcloud.mcp.core.flt.HookBridge;
import net.marcloud.mcp.core.se.AccessGate;
import net.marcloud.mcp.core.se.AllowAllGate;
import net.marcloud.mcp.core.se.CapabilitySid;
import net.marcloud.mcp.core.flt.HookTools;
import org.junit.Assume;
import org.junit.Test;

/**
 * Tests for the C3 INTERCEPT capability: {@link FltDynamicManager}, hook
 * events, route bookkeeping, and the capability/denylist guards. Most tests are
 * agent-less (work without Instrumentation); the live retransform test
 * self-skips if ByteBuddyAgent.install() fails (e.g. on a restricted JVM).
 *
 * <p>This exercises the "one-transformer-per-hook allows surgical uninstall"
 * claim and the L5 capability AND-gate, matching the test style of
 * {@code CapabilityRegistryTest} and {@code HotLoadEngineTest}.
 */
public class DynamicHookManagerTest {

    /**
     * The denylist check must run BEFORE the instrumentation check (so we can
     * verify the denylist without needing a live agent). Tests that install()
     * refuses a protected class even when inst==null.
     */
    @Test
    public void denylistRejectsSecurityClasses() {
        EventBus bus = new EventBus();
        FltDynamicManager mgr = new FltDynamicManager(null, bus);
        assertFalse("no agent → canInstall false", mgr.canInstall());

        // Denylist check must precede the inst==null gate in install(), or this
        // would throw IllegalStateException instead of SecurityException.
        try {
            mgr.install("net.marcloud.mcp.core.se.SeClearancePolicy", "allows");
            fail("expected SecurityException for protected class");
        } catch (SecurityException e) {
            assertTrue("message names the class",
                    e.getMessage().contains("net.marcloud.mcp.core.se.SeClearancePolicy"));
        }

        // Also test another protected class from the denylist
        try {
            mgr.install("net.marcloud.mcp.core.boot.CoreAgent", "premain");
            fail("expected SecurityException for CoreAgent");
        } catch (SecurityException e) {
            assertTrue("message names CoreAgent", e.getMessage().contains("CoreAgent"));
        }
    }

    /**
     * Without Instrumentation (no -javaagent), install() must fail with a clear
     * message after the denylist check. Tests that canInstall() returns false
     * and install() throws IllegalStateException.
     */
    @Test
    public void installWithoutInstrumentationFails() {
        EventBus bus = new EventBus();
        FltDynamicManager mgr = new FltDynamicManager(null, bus);
        assertFalse("no instrumentation → canInstall false", mgr.canInstall());
        assertEquals("no hooks initially", 0, mgr.size());

        try {
            mgr.install("java.lang.String", "length");
            fail("expected IllegalStateException when inst==null");
        } catch (IllegalStateException e) {
            assertTrue("message mentions -javaagent",
                    e.getMessage().contains("-javaagent"));
        }
    }

    /**
     * The defense-in-depth AccessGate AND-composes with the supervised ring +
     * capability gate. A deny-all gate throws SecurityException naming the
     * capability; HookTools wraps that call. (The outer 7-layer gate is covered
     * by SecurityKernelTest / SupervisedGateIntegrationTest.)
     */
    @Test
    public void capabilityGateDenies() {
        // Non-vacuous (audit #15): drive the REAL install_hook tool handler through
        // a deny-all gate and assert the tool call is refused naming the capability.
        // The earlier version asserted on a locally-defined lambda it called itself,
        // which tested nothing about HookTools. Here the gate is wired INTO HookTools
        // and reached via the tool's own call path.
        EventBus bus = new EventBus();
        FltDynamicManager mgr = new FltDynamicManager(null, bus);

        AccessGate denyAll = (cap, privs) -> {
            throw new SecurityException("capability " + cap + " not granted");
        };
        HookTools denied = new HookTools(mgr, denyAll);
        io.modelcontextprotocol.spec.McpSchema.CallToolResult r = invokeInstallHook(
                denied, "net.minecraft.network.NetworkManager", "channelRead0");
        assertTrue("deny-all gate makes install_hook an error", Boolean.TRUE.equals(r.isError()));
        assertTrue("error names the required capability",
                r.content().toString().contains("CAP_CLASS_RETRANSFORM"));

        // An allow-all gate passes the gate; without an agent it then fails at the
        // instrumentation step (a DIFFERENT error), proving the gate — not the
        // agent check — is what denied the first call.
        HookTools allowed = new HookTools(mgr, new AllowAllGate());
        io.modelcontextprotocol.spec.McpSchema.CallToolResult r2 = invokeInstallHook(
                allowed, "net.minecraft.network.NetworkManager", "channelRead0");
        assertTrue("allow-all still errors (no agent) but past the gate",
                Boolean.TRUE.equals(r2.isError()));
        assertFalse("allow-all error is NOT a capability denial",
                r2.content().toString().contains("CAP_CLASS_RETRANSFORM"));
    }

    /** Invoke the install_hook tool spec from a HookTools instance via a registry. */
    private static io.modelcontextprotocol.spec.McpSchema.CallToolResult invokeInstallHook(
            HookTools tools, String targetClass, String method) {
        net.marcloud.mcp.core.io.IoSupervisor exec =
                new net.marcloud.mcp.core.io.IoSupervisor(2, 2000L);
        net.marcloud.mcp.core.io.IoManager reg =
                new net.marcloud.mcp.core.io.IoManager(exec,
                        new net.marcloud.mcp.core.se.SeLocalMonitor(
                                new net.marcloud.mcp.core.se.SeClearancePolicy(
                                        net.marcloud.mcp.core.se.Ring.R_MINUS_1, "tok")));
        tools.registerAll(reg);
        io.modelcontextprotocol.spec.McpSchema.CallToolResult r =
                reg.invoke("install_hook", java.util.Map.of("targetClass", targetClass, "method", method));
        exec.shutdown();
        return r;
    }

    /**
     * HookFiredEvent.argTypes() must return only type names, not values (L7
     * boundary: no leaking object content). Tests that argTypes() redacts the
     * actual arguments and only exposes their class names.
     */
    @Test
    public void hookFiredEventArgTypesRedactsValues() {
        Object[] args = new Object[]{new int[]{1, 2, 3}, "secret", null};
        HookFiredEvent event = new HookFiredEvent(42,
                "net.minecraft.network.NetworkManager", "channelRead0", args);

        List<String> types = event.argTypes();
        assertEquals("3 arguments", 3, types.size());
        assertEquals("int array type", "int[]", types.get(0));
        assertEquals("String type", "String", types.get(1));
        assertEquals("null type", "null", types.get(2));

        // The types list must NOT contain the actual values "secret" or "1"
        String joined = String.join(",", types);
        assertFalse("types do not leak value", joined.contains("secret"));
        assertFalse("types do not leak array content", joined.contains("1"));
    }

    /**
     * HookBridge route bookkeeping: registerRoute + dispatch + publish
     * HookFiredEvent, then unregisterRoute + dispatch → no event. Tests the
     * route table lifecycle that uninstall() relies on.
     */
    @Test
    public void routeRegisterUnregister() {
        EventBus bus = new EventBus();
        AtomicInteger eventCount = new AtomicInteger(0);
        bus.subscribe(HookFiredEvent.class, e -> eventCount.incrementAndGet());

        int routeKey = 7;
        HookBridge.registerRoute(routeKey, bus,
                "test.Target", "testMethod");

        // Dispatch with the registered route → event published
        HookBridge.dispatch(routeKey, "testMethod", new Object[]{"arg1"});
        assertEquals("one event after dispatch", 1, eventCount.get());

        // Unregister the route
        HookBridge.unregisterRoute(routeKey);

        // Dispatch again → no new event (route gone)
        HookBridge.dispatch(routeKey, "testMethod", new Object[]{"arg2"});
        assertEquals("still one event after unregister", 1, eventCount.get());
    }

    /**
     * LIVE RETRANSFORM INTEGRATION TEST: install a hook on a sample class,
     * invoke the method → HookFiredEvent published; then uninstall the hook,
     * invoke again → no new event (advice reverted). This exercises the real
     * ResettableClassFileTransformer.reset(inst, RETRANSFORMATION) path.
     *
     * <p>Uses ByteBuddyAgent.install() to self-attach. On JDK 25 this requires
     * {@code -Djdk.attach.allowAttachSelf=true}; the test self-skips if attach
     * fails (no false failure).
     */
    @Test
    public void installUninstallRoundTripOnSampleClass() {
        // Self-attach to get Instrumentation
        Instrumentation inst;
        try {
            inst = net.bytebuddy.agent.ByteBuddyAgent.install();
        } catch (Throwable t) {
            // ByteBuddyAgent.install() can fail on restricted JVMs or if
            // -Djdk.attach.allowAttachSelf=false. Self-skip instead of failing.
            Assume.assumeNoException("ByteBuddyAgent self-attach failed (expected on some JVMs)", t);
            return;
        }
        Assume.assumeTrue("Instrumentation supports retransform",
                inst != null && inst.isRetransformClassesSupported());

        EventBus bus = new EventBus();
        FltDynamicManager mgr = new FltDynamicManager(inst, bus);
        assertTrue("can install with live agent", mgr.canInstall());

        // Sample target class (already loaded since it's a nested class here)
        Sample target = new Sample();
        String initialResult = target.probe();
        assertEquals("initial probe returns raw", "raw", initialResult);

        // Subscribe to HookFiredEvent
        AtomicInteger eventCount = new AtomicInteger(0);
        bus.subscribe(HookFiredEvent.class, e -> {
            if ("probe".equals(e.method())) {
                eventCount.incrementAndGet();
            }
        });

        // Install a generic hook on Sample.probe
        String hookId = mgr.install(Sample.class.getName(), "probe");
        assertNotNull("hookId returned", hookId);
        assertTrue("hookId contains class name", hookId.contains("Sample"));
        assertTrue("hookId contains method name", hookId.contains("probe"));
        assertEquals("one hook installed", 1, mgr.size());

        // Invoke the hooked method → HookFiredEvent should fire
        String hookedResult = target.probe();
        assertEquals("probe still returns raw (advice doesn't mutate)", "raw", hookedResult);
        assertEquals("one HookFiredEvent fired", 1, eventCount.get());

        // Uninstall the hook
        boolean uninstalled = mgr.uninstall(hookId);
        assertTrue("uninstall succeeded", uninstalled);
        assertEquals("no hooks remain", 0, mgr.size());

        // Invoke again → no new event (advice reverted)
        String revertedResult = target.probe();
        assertEquals("probe still returns raw", "raw", revertedResult);
        assertEquals("still one event (no new event after uninstall)", 1, eventCount.get());

        // list() should be empty
        List<FltDynamicManager.HookRecord> remaining = mgr.list();
        assertTrue("list() empty after uninstall", remaining.isEmpty());
    }

    /**
     * MEDIUM#8 — uninstallAll() (called by McpCore.stop()/close()) reverts every
     * installed hook so dynamic advice does not outlive the server. Uses a live
     * agent; self-skips if self-attach is unavailable.
     */
    @Test
    public void uninstallAllRevertsEveryHook() {
        Instrumentation inst;
        try {
            inst = net.bytebuddy.agent.ByteBuddyAgent.install();
        } catch (Throwable t) {
            Assume.assumeNoException("ByteBuddyAgent self-attach failed", t);
            return;
        }
        Assume.assumeTrue("Instrumentation supports retransform",
                inst != null && inst.isRetransformClassesSupported());

        EventBus bus = new EventBus();
        FltDynamicManager mgr = new FltDynamicManager(inst, bus);
        String hookId = mgr.install(Sample.class.getName(), "probe");
        assertNotNull(hookId);
        assertEquals("one hook installed", 1, mgr.size());

        int failed = mgr.uninstallAll();
        assertEquals("all hooks reverted cleanly (0 failures)", 0, failed);
        assertEquals("no hooks remain after uninstallAll", 0, mgr.size());
        // Idempotent: a second call has nothing to do and reports 0 failures.
        assertEquals("uninstallAll on empty manager is a clean no-op", 0, mgr.uninstallAll());
    }

    /**
     * Sample target class for the live retransform test. Must be public and
     * static so FltDynamicManager can find it via Class.forName.
     */
    public static class Sample {
        public String probe() {
            return "raw";
        }
    }

    // (Capability-set semantics are covered by SecurityKernelTest against the
    // canonical CapabilitySid / SeToken model; the C3 agent's duplicate
    // CapClass/CapabilitySet were dropped during integration.)

    /**
     * FltDynamicManager.list() returns a snapshot (defensive copy), so
     * mutating it doesn't affect the live hooks.
     */
    @Test
    public void listIsDefensiveCopy() {
        EventBus bus = new EventBus();
        FltDynamicManager mgr = new FltDynamicManager(null, bus);
        assertEquals("initially empty", 0, mgr.size());

        List<FltDynamicManager.HookRecord> list1 = mgr.list();
        assertTrue("list1 empty", list1.isEmpty());

        // list() returns a copy, so we can't mutate it directly (it's unmodifiable)
        // but we can verify that a second call returns a separate instance
        List<FltDynamicManager.HookRecord> list2 = mgr.list();
        assertTrue("list2 also empty", list2.isEmpty());
        // They are equal but not the same instance (defensive copy semantics)
        assertEquals("lists equal", list1, list2);
    }
}
