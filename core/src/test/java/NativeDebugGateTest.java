import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * Headless guard for the live-native skip-vs-fail wiring in
 * {@link NativeDebugOpLiveIT}. Exercises the pure {@link NativeDebugOpLiveIT#gate}
 * decision without needing the DLL:
 * <ul>
 *   <li>required &amp; unavailable → FAIL (broken bind turns CI red, never skips)</li>
 *   <li>not required &amp; unavailable → SKIP (default opt-in posture, unchanged)</li>
 *   <li>live &amp; available → RUN</li>
 * </ul>
 * FAILS to compile on the pre-change source (no {@code gate()}/{@code Gate} exists).
 */
public class NativeDebugGateTest {

    @Test
    public void requiredButUnavailableFails() {
        assertEquals(NativeDebugOpLiveIT.Gate.FAIL,
                NativeDebugOpLiveIT.gate(true, true, false));
    }

    @Test
    public void notRequiredAndNotLiveSkips() {
        assertEquals(NativeDebugOpLiveIT.Gate.SKIP,
                NativeDebugOpLiveIT.gate(false, false, false));
    }

    @Test
    public void liveButNotRequiredAndUnavailableSkips() {
        assertEquals(NativeDebugOpLiveIT.Gate.SKIP,
                NativeDebugOpLiveIT.gate(true, false, false));
    }

    /**
     * The row an operator actually hits: {@code -Dmcp.it.live=true} plus a working
     * {@code -agentpath}, WITHOUT opting into {@code -Dmcp.it.nativeRequired}. This
     * used to pass {@code (true, true, true)}, byte-identical to
     * {@link #requiredAndAvailableRuns()} below, so the {@code nativeRequired=false}
     * arm of RUN was never pinned by anything. A regression that made RUN depend on
     * the required flag would have kept the whole suite green while silently skipping
     * the one IT in this module that can genuinely pass.
     */
    @Test
    public void liveAndAvailableRunsEvenWhenNotRequired() {
        assertEquals(NativeDebugOpLiveIT.Gate.RUN,
                NativeDebugOpLiveIT.gate(true, false, true));
    }

    @Test
    public void requiredAndAvailableRuns() {
        assertEquals(NativeDebugOpLiveIT.Gate.RUN,
                NativeDebugOpLiveIT.gate(true, true, true));
    }
}
