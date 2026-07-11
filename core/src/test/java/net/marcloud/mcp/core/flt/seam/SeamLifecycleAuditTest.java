package net.marcloud.mcp.core.flt.seam;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import net.marcloud.mcp.core.ke.event.EventBus;
import org.junit.Test;

/**
 * Regression tests for two adversarial-audit findings in the seam layer:
 *
 * <ul>
 *   <li>MEDIUM#10 — {@link TickInjector#uninstall()} used to clear its
 *       installed/transformer state UNCONDITIONALLY after {@code reset()}, so a
 *       reset that returned false (or threw) orphaned live advice while the
 *       manager reported "uninstalled" and could never retry.
 *   <li>HIGH#7 — {@link InputHook} used to pick the "first nonzero long field"
 *       as the GLFW window handle, which can latch onto an unrelated long
 *       (e.g. a systemTime) and report a bogus handle. It now resolves the
 *       handle only via the explicit display accessor and fails honestly when
 *       that accessor is unavailable.
 * </ul>
 *
 * <p>These live in the seam package so they can drive the package-private test
 * seams ({@code TickInjector.primeInstalledForTest}, {@code
 * InputHook.resolveWindowHandle}) without a live {@code -javaagent} JVM or GLFW.
 */
public class SeamLifecycleAuditTest {

    // ----- MEDIUM#10: tick reset-failure must retain lifecycle state -----

    @Test
    public void resetReturningFalseRetainsInstalledStateForRetry() {
        TickInjector injector = new TickInjector(new EventBus());
        AtomicInteger resetCalls = new AtomicInteger(0);
        // Simulate a live install whose revert reports failure.
        injector.primeInstalledForTest(() -> {
            resetCalls.incrementAndGet();
            return false; // reset could not revert.
        });
        assertTrue("precondition: injector considered installed", injector.isInstalled());

        boolean result = injector.uninstall();

        assertFalse("uninstall must report the honest false when reset fails", result);
        assertEquals("reset must have actually been invoked", 1, resetCalls.get());
        // The core of the fix: state is NOT cleared, so the stale advice is not
        // orphaned and the caller can retry. (Old code set installed=false here.)
        assertTrue("installed state must be RETAINED after a failed reset",
                injector.isInstalled());

        // A retry that now succeeds must be able to actually run reset again and
        // then flip the state — impossible if the first failure had wiped it.
        injector.primeInstalledForTest(() -> {
            resetCalls.incrementAndGet();
            return true;
        });
        assertTrue("retry succeeds once reset confirms revert", injector.uninstall());
        assertEquals("reset invoked again on retry", 2, resetCalls.get());
        assertFalse("installed cleared only after a confirmed-successful reset",
                injector.isInstalled());
    }

    @Test
    public void resetThrowingRetainsInstalledStateForRetry() {
        TickInjector injector = new TickInjector(new EventBus());
        injector.primeInstalledForTest(() -> {
            throw new IllegalStateException("retransform blew up");
        });

        boolean result = injector.uninstall();

        assertFalse("uninstall reports false when reset throws", result);
        assertTrue("installed state RETAINED after reset threw (advice still live)",
                injector.isInstalled());
    }

    // ----- HIGH#7: window handle must come from the explicit accessor -----

    @Test
    public void windowResolverIgnoresArbitraryLongWhenAccessorAbsent() {
        // This fixture has NO getWindow() accessor but DOES carry a nonzero
        // static long (mimicking a systemTime the old "first nonzero long"
        // heuristic would have grabbed). Honest behavior: report no window (0L),
        // NOT the arbitrary long.
        long resolved = InputHook.resolveWindowHandle(NoAccessorButHasLong.class.getName());
        assertEquals("must NOT treat an arbitrary long as the window handle", 0L, resolved);
        // Prove the misleading long was actually reachable, so the test is not vacuous.
        assertEquals("fixture really exposes a tempting nonzero long",
                1_700_000_000_000L, NoAccessorButHasLong.systemTime);
    }

    @Test
    public void windowResolverReturnsZeroWhenDisplayClassMissing() {
        long resolved =
                InputHook.resolveWindowHandle("net.marcloud.does.not.Exist$Display");
        assertEquals("missing display class → honest no-window", 0L, resolved);
    }

    @Test
    public void windowResolverUsesExplicitAccessorHandle() {
        long resolved = InputHook.resolveWindowHandle(FakeDisplay.class.getName());
        assertEquals("must return the handle from the explicit getWindow() accessor",
                0xDEADBEEFL, resolved);
    }

    /** Fixture: no getWindow(), but a nonzero static long to tempt the old scan. */
    public static final class NoAccessorButHasLong {
        public static long systemTime = 1_700_000_000_000L;

        private NoAccessorButHasLong() {
        }
    }

    /** Fixture: explicit getWindow() accessor, as the real compat Display exposes. */
    public static final class FakeDisplay {
        public static long getWindow() {
            return 0xDEADBEEFL;
        }
    }
}
