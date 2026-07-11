import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import net.marcloud.mcp.core.debug.DebuggerBridge;
import net.marcloud.mcp.core.debug.DebuggerException;
import org.junit.Assume;
import org.junit.Test;

/**
 * LIVE scaffold (default SKIPPED). Physically requires the native JVMTI agent
 * ({@code core-jvmti.dll}) loaded via {@code -agentpath}, so it cannot run in CI
 * (the DLL is built only where a suitable clang toolchain is present). Gated
 * behind BOTH {@code -Dmcp.it.live=true} AND {@link DebuggerBridge#isAvailable()};
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

    private static void requireLiveNative() {
        Assume.assumeTrue(
                "requires the native JVMTI agent; run with -Dmcp.it.live=true and "
                        + "-agentpath:core-jvmti.dll", LIVE);
        Assume.assumeTrue(
                "native debugger not loaded (" + DebuggerBridge.unavailableReason()
                        + "); launch with -agentpath:core-jvmti.dll",
                DebuggerBridge.isAvailable());
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
            DebuggerBridge.suspendThread(worker);
            DebuggerBridge.resumeThread(worker);
            assertTrue("native suspend/resume returned without a JVMTI error", true);
        } catch (DebuggerException e) {
            fail("native JVMTI op failed on a live agent: " + e.getMessage());
        } finally {
            worker.interrupt();
        }
    }
}
