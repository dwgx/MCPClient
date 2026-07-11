package net.marcloud.mcp.core.drivers.world;

import net.marcloud.mcp.core.flt.HookBridge;

import java.util.List;
import java.util.function.BooleanSupplier;

import net.marcloud.mcp.core.boot.AgentAccess;
import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.DisconnectedEvent;

/**
 * Remembers the most recent disconnect and, combined with the {@link PacketLog},
 * answers the user's core question: "why was I kicked, and what happened right
 * before?" Subscribes to {@link DisconnectedEvent} and keeps the last reason
 * plus when it happened.
 *
 * <p><b>Source honesty.</b> The disconnect signal that feeds this tracker is
 * produced only by injected {@code -javaagent} advice at {@code
 * NetworkManager.closeChannel} (see {@code HookBridge.onDisconnect}). Without
 * that agent the sensor is never installed, so "no disconnect observed" is not
 * an authoritative negative — it just means nothing is watching. The old
 * {@link #report(int)} returned the identical "No disconnect observed yet."
 * string in both cases, conflating a dead sensor with a real negative. Use
 * {@link #reportResult(int)} (or {@link #sensorInstalled()} / {@link
 * #observedAny()}) so the tool layer can surface {@code isError} when the
 * source is unavailable.
 */
public final class DisconnectTracker {

    private final PacketLog packetLog;
    /**
     * Probe for whether the disconnect sensor's data source is live. Defaults to
     * {@link AgentAccess#isLoaded()} because the disconnect advice only runs when
     * the {@code -javaagent} Instrumentation is present. Injectable so callers
     * (and tests) can model both the sensor-absent and sensor-live states.
     */
    private final BooleanSupplier sensorProbe;
    private volatile String lastReason;
    private volatile long lastDisconnectMillis;

    public DisconnectTracker(EventBus bus, PacketLog packetLog) {
        this(bus, packetLog, AgentAccess::isLoaded);
    }

    public DisconnectTracker(EventBus bus, PacketLog packetLog, BooleanSupplier sensorProbe) {
        this.packetLog = packetLog;
        this.sensorProbe = sensorProbe;
        bus.subscribe(DisconnectedEvent.class, this::onDisconnect);
    }

    private void onDisconnect(DisconnectedEvent e) {
        lastReason = e.reasonText();
        lastDisconnectMillis = System.currentTimeMillis();
    }

    /** True if a disconnect has been observed since startup. */
    public boolean hasDisconnected() {
        return lastReason != null;
    }

    /** Alias of {@link #hasDisconnected()} — a real disconnect was recorded. */
    public boolean observedAny() {
        return lastReason != null;
    }

    /**
     * True when the disconnect sensor is live, i.e. its data source is present.
     * Observing a disconnect proves the sensor was installed; otherwise this
     * defers to the injected probe (the {@code -javaagent} hook that feeds this
     * tracker). When false, {@link #observedAny()} being false means "nobody was
     * watching", NOT "confirmed no disconnect".
     */
    public boolean sensorInstalled() {
        return lastReason != null || sensorProbe.getAsBoolean();
    }

    /** The last kick/disconnect reason text, or null. */
    public String lastReason() {
        return lastReason;
    }

    /**
     * Structured answer that separates the two negatives the plain-string
     * {@link #report(int)} conflated. When {@code sensorInstalled} is false the
     * source is unavailable and the tool layer should return {@code isError};
     * when it is true but {@code observedAny} is false, that is an authoritative
     * "no disconnect yet". {@code text} is the same human-readable body as
     * {@link #report(int)}.
     */
    public record Report(boolean sensorInstalled, boolean observedAny, String text) {
    }

    /**
     * Build the structured report used by the disconnect_report tool: the
     * availability of the sensor plus the human-readable {@link #report(int)}
     * body. Lets the tool layer distinguish a dead sensor from a real negative.
     */
    public Report reportResult(int recentPackets) {
        return new Report(sensorInstalled(), observedAny(), report(recentPackets));
    }

    /**
     * Build a human-readable report: the reason plus the most recent packets
     * leading up to it — the raw material for an AI to diagnose the kick.
     */
    public String report(int recentPackets) {
        StringBuilder sb = new StringBuilder();
        if (lastReason == null) {
            sb.append("No disconnect observed yet.\n");
        } else {
            sb.append("Last disconnect reason: ").append(lastReason).append('\n');
            sb.append("At (epoch ms): ").append(lastDisconnectMillis).append('\n');
        }
        List<PacketLog.Entry> recent = packetLog.recent(recentPackets);
        sb.append("Recent packets (").append(recent.size()).append("):\n");
        for (PacketLog.Entry e : recent) {
            sb.append("  ").append(e).append('\n');
        }
        return sb.toString().stripTrailing();
    }
}
