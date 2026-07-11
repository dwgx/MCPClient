import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.marcloud.mcp.core.kd.KdBridge;
import net.marcloud.mcp.core.kd.DebuggerException;
import org.junit.Assume;
import org.junit.Test;

/**
 * LIVE scaffold (default SKIPPED). Physically requires the native JVMTI agent
 * ({@code core-jvmti.dll}) loaded via {@code -agentpath}, so it cannot run in CI
 * (the DLL is built only where a suitable clang toolchain is present). Gated
 * behind BOTH {@code -Dmcp.it.live=true} AND {@link KdBridge#isAvailable()};
 * without them every test assume-skips with a clear message and never fails.
 *
 * <p>Run live with:
 * {@code ./mvnw -pl core test -Dtest=NativeDebugOpLiveIT -Dmcp.it.live=true \
 *   -DargLine="-agentpath:/abs/path/core-jvmti.dll"}
 *
 * <p>Covers the real native round-trip the headless {@code DebuggerBridgeFallbackTest}
 * cannot: a genuine JVMTI suspend/resume on a live thread.
 */
public class NativeDebugOpLiveIT {

    private static final boolean LIVE = Boolean.getBoolean("mcp.it.live");

    /**
     * When set (CI on a Windows runner with the agent attached), an unavailable
     * native bridge FAILS instead of skipping — so a stale JNI bind, a missing
     * DLL, or absent onload caps turns CI red rather than silently green. Default
     * (unset) keeps the historical Assume-skip behavior byte-identical.
     */
    private static final boolean NATIVE_REQUIRED = Boolean.getBoolean("mcp.it.nativeRequired");

    /** Skip-vs-fail decision for the live-native gate; RUN executes the body. */
    enum Gate { RUN, SKIP, FAIL }

    /**
     * Pure decision encoding what {@link #requireLiveNative()} does, so it can be
     * unit-tested headlessly without the DLL:
     * <ul>
     *   <li>required &amp; not (live &amp; available) → FAIL (broken bind is an error)</li>
     *   <li>not required &amp; not (live &amp; available) → SKIP (default opt-in gate)</li>
     *   <li>live &amp; available → RUN</li>
     * </ul>
     */
    static Gate gate(boolean live, boolean nativeRequired, boolean available) {
        boolean ready = live && available;
        if (ready) {
            return Gate.RUN;
        }
        return nativeRequired ? Gate.FAIL : Gate.SKIP;
    }

    private static void requireLiveNative() {
        if (NATIVE_REQUIRED) {
            // Required mode (CI): a missing/broken native agent must FAIL, not skip.
            assertTrue(
                    "native required (-Dmcp.it.nativeRequired=true) but -Dmcp.it.live is not set",
                    LIVE);
            assertTrue(
                    "native required but debugger not loaded (" + KdBridge.unavailableReason()
                            + "); launch with -agentpath:core-jvmti.dll",
                    KdBridge.isAvailable());
            return;
        }
        // Default path (unchanged): assume-skip when not live / agent absent.
        Assume.assumeTrue(
                "requires the native JVMTI agent; run with -Dmcp.it.live=true and "
                        + "-agentpath:core-jvmti.dll", LIVE);
        Assume.assumeTrue(
                "native debugger not loaded (" + KdBridge.unavailableReason()
                        + "); launch with -agentpath:core-jvmti.dll",
                KdBridge.isAvailable());
    }

    @Test
    public void suspendAndResumeAWorkerThreadThroughTheNativeAgent() throws Exception {
        requireLiveNative();

        // A parked worker we can safely suspend/resume without freezing the harness.
        Thread worker = new Thread(() -> {
            try {
                Thread.sleep(60_000L);
            } catch (InterruptedException ignored) {
                // expected on teardown
            }
        }, "mcp-live-it-worker");
        worker.setDaemon(true);
        worker.start();
        try {
            KdBridge.suspendThread(worker);
            KdBridge.resumeThread(worker);
            assertTrue("native suspend/resume returned without a JVMTI error", true);
        } catch (DebuggerException e) {
            fail("native JVMTI op failed on a live agent: " + e.getMessage());
        } finally {
            worker.interrupt();
        }
    }
}
