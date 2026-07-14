package net.marcloud.mcp.core.ke.event;

import static org.junit.Assert.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;

import net.marcloud.mcp.core.ke.GameClock;
import net.marcloud.mcp.core.ke.event.events.TickEvent;
import net.marcloud.mcp.core.ke.event.events.PacketSentEvent;

/**
 * PHASE T (T.7): the EventBus dispatch cache must route correctly across warm
 * reuse AND after subscription changes — a stale cache would drop/misroute events.
 * These would FAIL if the cache were not invalidated on subscribe/unsubscribe.
 */
public class EventBusDispatchCacheTest {

    @Test
    public void warmCacheDeliversRepeatedlyToTheSameHandler() {
        EventBus bus = new EventBus();
        AtomicInteger n = new AtomicInteger();
        bus.subscribe(TickEvent.class, e -> n.incrementAndGet());
        bus.publish(new TickEvent(1L)); // cold: builds cache
        bus.publish(new TickEvent(2L)); // warm: reuses cache
        bus.publish(new TickEvent(3L));
        assertEquals("every publish delivered, warm or cold", 3, n.get());
    }

    @Test
    public void baseTypeSubscriberReceivesAllSubclasses() {
        EventBus bus = new EventBus();
        AtomicInteger all = new AtomicInteger();
        bus.subscribe(GameEvent.class, e -> all.incrementAndGet());
        bus.publish(new TickEvent(1L));
        bus.publish(new PacketSentEvent(null));
        assertEquals("GameEvent subscriber catches every subclass", 2, all.get());
    }

    @Test
    public void subscribeAfterFirstPublishInvalidatesCache() {
        EventBus bus = new EventBus();
        AtomicInteger a = new AtomicInteger();
        bus.subscribe(TickEvent.class, e -> a.incrementAndGet());
        bus.publish(new TickEvent(1L));   // warms cache for TickEvent with 1 subscriber
        AtomicInteger b = new AtomicInteger();
        bus.subscribe(TickEvent.class, e -> b.incrementAndGet()); // must clear cache
        bus.publish(new TickEvent(2L));
        assertEquals("first handler still fires", 2, a.get());
        assertEquals("late subscriber fires too (cache was invalidated)", 1, b.get());
    }

    @Test
    public void unsubscribeInvalidatesCache() {
        EventBus bus = new EventBus();
        AtomicInteger a = new AtomicInteger();
        java.util.function.Consumer<TickEvent> h = e -> a.incrementAndGet();
        bus.subscribe(TickEvent.class, h);
        bus.publish(new TickEvent(1L));   // warm
        bus.unsubscribe(h);
        bus.publish(new TickEvent(2L));   // must NOT deliver
        assertEquals("unsubscribed handler stops receiving", 1, a.get());
    }

    @Test
    public void eventStampsCurrentTickIdAtConstruction() {
        GameClock.INSTANCE.reset();
        GameClock.INSTANCE.advance(); // tick 1
        GameClock.INSTANCE.advance(); // tick 2
        TickEvent e = new TickEvent(GameClock.INSTANCE.tickId());
        assertEquals("GameEvent captures the clock's current tick", 2L, e.tickId());
        GameClock.INSTANCE.reset(); // don't leak state to other tests
    }
}
