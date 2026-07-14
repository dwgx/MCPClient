package net.marcloud.mcp.core.ke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;

import org.junit.Test;

import net.marcloud.mcp.core.ke.event.EventBus;
import net.marcloud.mcp.core.ke.event.events.TickEvent;

/**
 * PHASE T (T.5): the Timeline folds every EventBus event into a safe, tick-stamped
 * entry, bounded by its ring capacity, oldest-first.
 */
public class TimelineTest {

    @Test
    public void recordsEventsWithTickIdAndKind() {
        Timeline tl = new Timeline(16);
        tl.record(new TickEvent(7L, GameClock.Phase.START));
        List<Timeline.Entry> tail = tl.tail();
        assertEquals(1, tail.size());
        assertEquals("tickId is carried onto the timeline", 7L, tail.get(0).tickId());
        assertEquals("kind is the event simple name", "TickEvent", tail.get(0).kind());
    }

    @Test
    public void attachViaBusFoldsPublishedEvents() {
        EventBus bus = new EventBus();
        Timeline tl = new Timeline(16);
        tl.attach(bus);
        bus.publish(new TickEvent(1L));
        bus.publish(new TickEvent(2L));
        List<Timeline.Entry> tail = tl.tail();
        assertEquals("both published events landed on the timeline", 2, tail.size());
        assertEquals(1L, tail.get(0).tickId());
        assertEquals(2L, tail.get(1).tickId());
    }

    @Test
    public void ringEvictsOldestWhenOverCapacity() {
        Timeline tl = new Timeline(3);
        for (long i = 1; i <= 5; i++) {
            tl.record(new TickEvent(i));
        }
        List<Timeline.Entry> tail = tl.tail();
        assertEquals("capped at capacity", 3, tail.size());
        assertEquals("oldest evicted, keeps newest 3 oldest-first", 3L, tail.get(0).tickId());
        assertEquals(5L, tail.get(2).tickId());
    }

    @Test
    public void tailLimitReturnsMostRecent() {
        Timeline tl = new Timeline(16);
        for (long i = 1; i <= 6; i++) {
            tl.record(new TickEvent(i));
        }
        List<Timeline.Entry> last2 = tl.tail(2);
        assertEquals(2, last2.size());
        assertEquals(5L, last2.get(0).tickId());
        assertEquals(6L, last2.get(1).tickId());
        assertTrue("negative limit yields none", tl.tail(-1).isEmpty());
    }
}
