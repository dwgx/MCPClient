package net.marcloud.mcp.core.kd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * AUTOMATABLE (no native DLL). Exercises the inbound native → Java event sink
 * {@link KdBridge#onDebugEvent} that the JVMTI callback thread invokes: it
 * must build the right {@link DebugEvent.Kind} from the raw {@code kind} int and
 * hand it to the process-wide {@link DebugEventQueue#INSTANCE}, cheaply and
 * without re-entering JVMTI. The kind-int → enum mapping is the C6 wire contract
 * with the native side; a regression there would silently mislabel every
 * breakpoint / step / field-watch event.
 *
 * <p>{@code DebugEventQueueTest} covers the queue in isolation; this covers the
 * bridge-to-queue seam (package-private, only reachable from this package) that
 * the DLL actually calls.
 */
public class NativeDebugEventSinkTest {

    @Test
    public void onDebugEventForwardsToTheProcessQueueWithCorrectKind() {
        AtomicReference<DebugEvent> seen = new AtomicReference<>();
        DebugEventListener listener = seen::set;
        DebugEventQueue.INSTANCE.addListener(listener);
        int before = DebugEventQueue.INSTANCE.size();

        // Simulate the native callback: kind=3 is FIELD_MODIFICATION, numeric is
        // the new field value for that kind.
        KdBridge.onDebugEvent(3, Thread.currentThread(),
                "net/minecraft/client/Minecraft#running", 42L);

        DebugEvent got = seen.get();
        assertTrue("the process queue received the event",
                DebugEventQueue.INSTANCE.size() > before);
        assertEquals("kind int 3 maps to FIELD_MODIFICATION",
                DebugEvent.Kind.FIELD_MODIFICATION, got.kind());
        assertEquals("location passed through verbatim",
                "net/minecraft/client/Minecraft#running", got.location());
        assertEquals("numeric (field value) passed through", 42L, got.numeric());
        assertEquals("thread name resolved from the live thread",
                Thread.currentThread().getName(), got.threadName());
    }

    @Test
    public void unknownKindIntDegradesToUnknownNotACrash() {
        AtomicReference<DebugEvent> seen = new AtomicReference<>();
        DebugEventQueue.INSTANCE.addListener(seen::set);

        // A kind the native side never sends today must map to UNKNOWN, not throw.
        KdBridge.onDebugEvent(999, Thread.currentThread(), "x", 0L);

        assertEquals("out-of-range kind is UNKNOWN (defensive)",
                DebugEvent.Kind.UNKNOWN, seen.get().kind());
    }
}
