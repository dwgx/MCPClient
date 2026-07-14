package net.marcloud.mcp.core.link;

import static org.junit.Assert.assertFalse;

import org.junit.Test;

import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * PHASE T (T.8): the board fan-out bridge is zero-hard-dependency and degrades to
 * a silent no-op when board is absent. In the core test classpath board is NOT
 * present, so this proves the reflection-miss path never throws into the publisher
 * and the bridge stays unavailable — the "board absent ⇒ core runs standalone"
 * contract. (The live board round-trip is covered by board's own suite + a live run.)
 */
public class BoardClockBridgeTest {

    @Test
    public void degradesToNoopWhenBoardAbsent() {
        EventBus bus = new EventBus();
        BoardClockBridge bridge = new BoardClockBridge(bus);
        bridge.attach();
        // Publishing a tick must not throw even though board isn't on the classpath.
        bus.publish(new TickEvent(1L));
        bus.publish(new TickEvent(2L));
        assertFalse("board absent => bridge never becomes available", bridge.isAvailable());
    }

    @Test
    public void nullBusAttachIsSafe() {
        BoardClockBridge bridge = new BoardClockBridge(null);
        bridge.attach(); // no throw
        assertFalse(bridge.isAvailable());
    }
}
