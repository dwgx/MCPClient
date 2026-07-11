import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import net.marcloud.mcp.core.kd.DebugEvent;
import net.marcloud.mcp.core.kd.DebugEventQueue;
import org.junit.Test;

/** Pure-Java (no native): drop-oldest bounding, listener isolation, delivery. */
public class DebugEventQueueTest {

    private static DebugEvent ev(String loc) {
        return DebugEvent.of(1, Thread.currentThread(), loc, 0);
    }

    @Test
    public void offerPastCapacityDropsOldestWithoutBlocking() {
        DebugEventQueue q = new DebugEventQueue(16); // min capacity
        for (int i = 0; i < 100; i++) {
            q.offer(ev("e" + i));
        }
        assertTrue("bounded", q.size() <= 16);
        // The most recent event is retained (drop-oldest, not drop-newest).
        assertTrue("keeps newest",
                q.snapshot().stream().anyMatch(e -> e.location().equals("e99")));
    }

    @Test
    public void throwingListenerIsIsolated() {
        DebugEventQueue q = new DebugEventQueue(16);
        AtomicInteger good = new AtomicInteger();
        q.addListener(e -> { throw new RuntimeException("boom"); });
        q.addListener(e -> good.incrementAndGet());
        q.offer(ev("x"));
        assertEquals("second listener still ran despite the first throwing", 1, good.get());
    }

    @Test
    public void listenersReceiveEvents() {
        DebugEventQueue q = new DebugEventQueue(16);
        AtomicInteger n = new AtomicInteger();
        q.addListener(e -> n.incrementAndGet());
        q.offer(ev("a"));
        q.offer(ev("b"));
        assertEquals(2, n.get());
        q.clear();
        assertEquals(0, q.size());
    }
}
