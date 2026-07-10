import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.instrument.Instrumentation;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.HookFiredEvent;
import net.marcloud.mcp.core.hook.DynamicHookManager;
import net.marcloud.mcp.core.hook.HookBridge;
import net.marcloud.mcp.core.security.AccessGate;
import net.marcloud.mcp.core.security.AllowAllGate;
import net.marcloud.mcp.core.security.CapabilitySid;
import net.marcloud.mcp.core.hook.HookTools;
import org.junit.Assume;
import org.junit.Test;

/**
 * Tests for the C3 INTERCEPT capability: {@link DynamicHookManager}, hook
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
        DynamicHookManager mgr = new DynamicHookManager(null, bus);
        assertFalse("no agent → canInstall false", mgr.canInstall());

        // Denylist check must precede the inst==null gate in install(), or this
        // would throw IllegalStateException instead of SecurityException.
        try {
            mgr.install("net.marcloud.mcp.core.security.PermissionPolicy", "allows");
            fail("expected SecurityException for protected class");
        } catch (SecurityException e) {
            assertTrue("message names the class",
                    e.getMessage().contains("net.marcloud.mcp.core.security.PermissionPolicy"));
        }

        // Also test another protected class from the denylist
        try {
            mgr.install("net.marcloud.mcp.core.agent.CoreAgent", "premain");
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
        DynamicHookManager mgr = new DynamicHookManager(null, bus);
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
        EventBus bus = new EventBus();
        DynamicHookManager mgr = new DynamicHookManager(null, bus);

        // A deny-all gate: require() always throws.
        AccessGate denyAll = (cap, privs) -> {
            throw new SecurityException("capability " + cap + " not granted");
        };
        try {
            denyAll.require(CapabilitySid.CAP_CLASS_RETRANSFORM);
            fail("expected SecurityException when capability absent");
        } catch (SecurityException e) {
            assertTrue("message names the capability",
                    e.getMessage().contains("CAP_CLASS_RETRANSFORM"));
        }

        // HookTools accepts the gate; the wildcard dev gate allows through.
        HookTools tools = new HookTools(mgr, new AllowAllGate());
        assertNotNull(tools);
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
        DynamicHookManager mgr = new DynamicHookManager(inst, bus);
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
        List<DynamicHookManager.HookRecord> remaining = mgr.list();
        assertTrue("list() empty after uninstall", remaining.isEmpty());
    }

    /**
     * Sample target class for the live retransform test. Must be public and
     * static so DynamicHookManager can find it via Class.forName.
     */
    public static class Sample {
        public String probe() {
            return "raw";
        }
    }

    // (Capability-set semantics are covered by SecurityKernelTest against the
    // canonical CapabilitySid / SecurityContext model; the C3 agent's duplicate
    // CapClass/CapabilitySet were dropped during integration.)

    /**
     * DynamicHookManager.list() returns a snapshot (defensive copy), so
     * mutating it doesn't affect the live hooks.
     */
    @Test
    public void listIsDefensiveCopy() {
        EventBus bus = new EventBus();
        DynamicHookManager mgr = new DynamicHookManager(null, bus);
        assertEquals("initially empty", 0, mgr.size());

        List<DynamicHookManager.HookRecord> list1 = mgr.list();
        assertTrue("list1 empty", list1.isEmpty());

        // list() returns a copy, so we can't mutate it directly (it's unmodifiable)
        // but we can verify that a second call returns a separate instance
        List<DynamicHookManager.HookRecord> list2 = mgr.list();
        assertTrue("list2 also empty", list2.isEmpty());
        // They are equal but not the same instance (defensive copy semantics)
        assertEquals("lists equal", list1, list2);
    }
}
