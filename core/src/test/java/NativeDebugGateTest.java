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

    @Test
    public void liveAndAvailableRuns() {
        assertEquals(NativeDebugOpLiveIT.Gate.RUN,
                NativeDebugOpLiveIT.gate(true, true, true));
    }

    @Test
    public void requiredAndAvailableRuns() {
        assertEquals(NativeDebugOpLiveIT.Gate.RUN,
                NativeDebugOpLiveIT.gate(true, true, true));
    }
}
