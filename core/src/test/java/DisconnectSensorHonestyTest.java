import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.core.agent.AgentAccess;
import net.marcloud.mcp.core.event.EventBus;
import net.marcloud.mcp.core.event.events.DisconnectedEvent;
import net.marcloud.mcp.core.state.DisconnectTracker;
import net.marcloud.mcp.core.state.PacketLog;
import net.minecraft.util.ChatComponentText;
import org.junit.Test;

/**
 * Regression guard for GAP-4 (honesty): disconnect_report conflated a DEAD
 * disconnect sensor with a real "no disconnect" negative.
 *
 * <p>The disconnect signal is fed only by injected {@code -javaagent} advice at
 * {@code NetworkManager.closeChannel} (see {@code HookBridge.onDisconnect}), off
 * by default. The old {@link DisconnectTracker#report(int)} returned the
 * identical "No disconnect observed yet." string whether the sensor was never
 * installed or the sensor was live and genuinely saw nothing — so the tool layer
 * could not surface {@code isError} for the unavailable case (the same bug
 * already fixed for recent_packets via {@code AgentAccess.isLoaded()}).
 *
 * <p>These tests would PASS against the old code for the plain report string
 * (it is unchanged), but FAIL against it because the state-distinguishing API
 * they assert on ({@code sensorInstalled}/{@code observedAny}/{@code
 * reportResult}) did not exist — the old tracker had no way to tell the two
 * negatives apart. That absence IS the bug this fix closes.
 */
public class DisconnectSensorHonestyTest {

    /** Fresh tracker whose sensor probe reports the source as absent. */
    private static DisconnectTracker sensorAbsent(EventBus bus, PacketLog log) {
        return new DisconnectTracker(bus, log, () -> false);
    }

    /** Fresh tracker whose sensor probe reports the source as live. */
    private static DisconnectTracker sensorLive(EventBus bus, PacketLog log) {
        return new DisconnectTracker(bus, log, () -> true);
    }

    @Test
    public void deadSensorIsDistinctFromRealNegative() {
        EventBus bus = new EventBus();
        PacketLog log = new PacketLog(64);

        // Sensor never installed, nothing observed: must NOT masquerade as an
        // authoritative negative.
        DisconnectTracker dead = sensorAbsent(bus, log);
        DisconnectTracker.Report deadReport = dead.reportResult(20);
        assertFalse("dead sensor must report source unavailable",
                deadReport.sensorInstalled());
        assertFalse("dead sensor observed nothing", deadReport.observedAny());

        // Sensor live, still nothing observed: this IS an authoritative negative.
        DisconnectTracker live = sensorLive(bus, log);
        DisconnectTracker.Report liveReport = live.reportResult(20);
        assertTrue("live sensor must report source available",
                liveReport.sensorInstalled());
        assertFalse("live sensor genuinely observed no disconnect",
                liveReport.observedAny());

        // The crux: the two negatives are the SAME human string yet MUST be
        // distinguishable via sensorInstalled — old code exposed no such signal.
        assertEquals("plain report body is identical for both negatives",
                deadReport.text(), liveReport.text());
        assertTrue("distinguishable only through the new availability flag",
                deadReport.sensorInstalled() != liveReport.sensorInstalled());
    }

    @Test
    public void observedDisconnectImpliesSensorInstalled() {
        EventBus bus = new EventBus();
        PacketLog log = new PacketLog(64);

        // Even with the probe forced false, a delivered event proves the sensor
        // was live — availability must follow the evidence, not just the probe.
        DisconnectTracker t = sensorAbsent(bus, log);
        bus.publish(new DisconnectedEvent(new ChatComponentText("kicked: flying")));

        DisconnectTracker.Report r = t.reportResult(20);
        assertTrue("observing a disconnect proves the sensor was installed",
                r.sensorInstalled());
        assertTrue("a real disconnect was observed", r.observedAny());
        assertTrue("report carries the real reason",
                r.text().contains("kicked: flying"));
    }

    @Test
    public void defaultProbeIsAgentBackedAndUnavailableHeadless() {
        // The production default binds the probe to AgentAccess.isLoaded(). The
        // headless suite has no -javaagent, so the sensor is deterministically
        // unavailable — this pins the fix to the real data source, not just the
        // injected test probe.
        assertFalse("test JVM must have no -javaagent for this regression",
                AgentAccess.isLoaded());

        EventBus bus = new EventBus();
        DisconnectTracker t = new DisconnectTracker(bus, new PacketLog(64));

        assertFalse("agentless default tracker must report source unavailable",
                t.sensorInstalled());
        assertFalse("nothing observed", t.observedAny());
    }
}
