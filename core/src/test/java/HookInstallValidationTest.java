import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.hook.DynamicHookManager;
import org.junit.Assume;
import org.junit.Test;

/**
 * Regression tests for two C3 INTERCEPT audit findings on
 * {@link DynamicHookManager}:
 *
 * <ul>
 *   <li>MEDIUM#11 — {@code install} used to hand back a hookId for a blank or
 *       nonexistent method, recording a hook that could never fire. These tests
 *       assert install now REJECTS such requests ({@link IllegalArgumentException})
 *       and records nothing. (Old behaviour returned a hookId with a live agent,
 *       or threw {@link IllegalStateException} at the agent gate without one — so
 *       asserting the {@code IllegalArgumentException} + {@code size()==0} fails
 *       against the old code either way.)</li>
 *   <li>MEDIUM#8 — {@code uninstall} used to drop its record even when the
 *       underlying {@code transformer.reset} returned false, losing the only
 *       handle able to retry the revert. This test drives a transformer whose
 *       {@code reset} fails once then succeeds, asserting the hook is RETAINED
 *       after the failure and only removed after a confirmed success.</li>
 * </ul>
 *
 * <p>Style mirrors {@code DynamicHookManagerTest}: JUnit 4, agent-less where
 * possible, self-skipping live-agent path.
 */
public class HookInstallValidationTest {

    /** Sample target for the live-agent path; declares exactly one method. */
    public static class Sample {
        public String probe() {
            return "raw";
        }
    }

    /**
     * (a) Nonexistent method: install must reject and record nothing. Uses
     * {@code java.lang.String} (loaded, unprotected) so it runs without an agent
     * — the method-existence check runs BEFORE the retransform gate.
     */
    @Test
    public void installNonexistentMethodRejectedAndRecordsNothing() {
        EventBus bus = new EventBus();
        DynamicHookManager mgr = new DynamicHookManager(null, bus);

        try {
            mgr.install("java.lang.String", "definitelyMissing");
            fail("expected IllegalArgumentException for nonexistent method");
        } catch (IllegalArgumentException e) {
            assertTrue("message names the missing method",
                    e.getMessage().contains("definitelyMissing"));
        }
        assertEquals("no hook recorded for a missing method", 0, mgr.size());
        assertTrue("nothing listed", mgr.list().isEmpty());
    }

    /**
     * (a') Blank/whitespace method name: install must reject and record nothing.
     */
    @Test
    public void installBlankMethodRejectedAndRecordsNothing() {
        EventBus bus = new EventBus();
        DynamicHookManager mgr = new DynamicHookManager(null, bus);

        for (String blank : new String[]{"", "   "}) {
            try {
                mgr.install("java.lang.String", blank);
                fail("expected IllegalArgumentException for blank method '" + blank + "'");
            } catch (IllegalArgumentException e) {
                // expected
            }
        }
        assertEquals("no hook recorded for a blank method", 0, mgr.size());
    }

    /**
     * (a'') Faithful repro with a LIVE agent: under the old code, install on a
     * loaded class with a nonexistent method passed every gate and RECORDED a
     * hook (returning a hookId) even though the retransform matched no method.
     * Now it is rejected before recording. Self-skips if self-attach is
     * unavailable.
     */
    @Test
    public void installNonexistentMethodWithLiveAgentRecordsNothing() {
        Instrumentation inst;
        try {
            inst = net.bytebuddy.agent.ByteBuddyAgent.install();
        } catch (Throwable t) {
            Assume.assumeNoException("ByteBuddyAgent self-attach failed", t);
            return;
        }
        Assume.assumeTrue("retransform supported",
                inst != null && inst.isRetransformClassesSupported());

        EventBus bus = new EventBus();
        DynamicHookManager mgr = new DynamicHookManager(inst, bus);
        // Force Sample loaded so Class.forName + isModifiableClass would pass.
        assertEquals("raw", new Sample().probe());

        try {
            mgr.install(Sample.class.getName(), "ghostMethod");
            fail("expected IllegalArgumentException; old code returned a dead hookId");
        } catch (IllegalArgumentException e) {
            assertTrue("message names the missing method",
                    e.getMessage().contains("ghostMethod"));
        }
        assertEquals("no dead hook recorded past the agent gate", 0, mgr.size());
    }

    /**
     * (b) A failed reset must RETAIN the hook so uninstall can be retried. Seeds
     * a record whose transformer.reset returns false on the first call and true
     * on the second, then asserts: first uninstall -> false and still listed;
     * second uninstall -> true and removed. Old code dropped the record on the
     * first (failed) reset, so the "still listed after failure" assertion fails
     * against it.
     */
    @Test
    public void failedResetRetainsHookForRetry() throws Exception {
        EventBus bus = new EventBus();
        DynamicHookManager mgr = new DynamicHookManager(null, bus);

        AtomicInteger resetCalls = new AtomicInteger(0);
        ResettableClassFileTransformer transformer = failingThenSucceedingTransformer(resetCalls);

        String hookId = "java.lang.String#length@999";
        DynamicHookManager.HookRecord record = new DynamicHookManager.HookRecord(
                hookId, "java.lang.String", "length", 999, transformer, System.nanoTime());

        // Seed the private hook map (no public API records a custom transformer).
        Map<String, DynamicHookManager.HookRecord> map = hookMap(mgr);
        map.put(hookId, record);
        assertEquals("seeded one hook", 1, mgr.size());

        // First uninstall: reset() returns false -> record RETAINED for retry.
        boolean first = mgr.uninstall(hookId);
        assertFalse("uninstall reports the failed revert", first);
        assertEquals("hook retained after failed reset", 1, mgr.size());
        assertTrue("hook still listed after failed reset",
                mgr.list().stream().anyMatch(r -> r.hookId().equals(hookId)));

        // Second uninstall: reset() now returns true -> record removed.
        boolean second = mgr.uninstall(hookId);
        assertTrue("retry succeeds once reset succeeds", second);
        assertEquals("hook removed after confirmed reset", 0, mgr.size());
        assertEquals("reset was invoked exactly twice", 2, resetCalls.get());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, DynamicHookManager.HookRecord> hookMap(DynamicHookManager mgr)
            throws Exception {
        var f = DynamicHookManager.class.getDeclaredField("hooks");
        f.setAccessible(true);
        return (Map<String, DynamicHookManager.HookRecord>) f.get(mgr);
    }

    /**
     * A {@link ResettableClassFileTransformer} proxy whose {@code reset(...)}
     * returns false on the first invocation and true thereafter; all other
     * methods return type-appropriate defaults.
     */
    private static ResettableClassFileTransformer failingThenSucceedingTransformer(AtomicInteger calls) {
        InvocationHandler h = (Object proxy, Method method, Object[] args) -> {
            switch (method.getName()) {
                case "reset":
                    return calls.incrementAndGet() >= 2 ? Boolean.TRUE : Boolean.FALSE;
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "StubResettableClassFileTransformer";
                default:
                    return method.getReturnType() == boolean.class ? Boolean.FALSE : null;
            }
        };
        return (ResettableClassFileTransformer) Proxy.newProxyInstance(
                ResettableClassFileTransformer.class.getClassLoader(),
                new Class<?>[]{ResettableClassFileTransformer.class}, h);
    }
}
