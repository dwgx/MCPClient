package net.marcloud.mcp.core.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.signals.ChatReceiveSignal;
import net.marcloud.mcp.board.signals.ChatSendSignal;
import org.junit.Test;

/**
 * PHASE E (E.1): the reflective core→board publish facade.
 *
 * <p>Two contracts under test. (1) <b>Board absent / no port</b>: with no
 * {@code BoardPort} registered on the {@code Backplane}, a fresh link resolves to
 * unavailable and every call is a silent no-op that never throws — the
 * "board absent ⇒ core runs standalone" degradation. (2) <b>Injected trace</b>:
 * given a real board {@link Trace} (injected via the package-private
 * {@code setTraceForTest} seam, bypassing {@code Backplane}), the link publishes a
 * real {@code ChatSendSignal} and reads the veto outcome reflectively — a
 * subscriber that does not cancel yields {@code cancelled=false}, one that
 * {@code cancel(reason)}s yields {@code cancelled=true} + the exact reason.
 *
 * <p>board is on core's TEST classpath only (not main compile), so these are real
 * reflective round-trips against genuine board {@code Signal} classes — the same
 * path taken at runtime — not mocked stand-ins.
 */
public class BoardTraceLinkTest {

    /** Resolve {@code Trace}'s single-arg {@code publish} method for injection. */
    private static Method tracePublishMethod() {
        for (Method m : Trace.class.getMethods()) {
            if (m.getName().equals("publish") && m.getParameterCount() == 1) {
                return m;
            }
        }
        throw new IllegalStateException("Trace.publish(Signal) not found");
    }

    // ---- (1) board absent / no port registered -----------------------------

    @Test
    public void unavailableWhenNoPortRegistered() {
        BoardTraceLink link = new BoardTraceLink();
        assertFalse("no BoardPort registered => link unavailable", link.available());
    }

    @Test
    public void publishChatSendIsNoopAndProceedsWhenBoardAbsent() {
        BoardTraceLink link = new BoardTraceLink();
        BoardTraceLink.ChatSendResult r = link.publishChatSend("hello");
        assertFalse("board absent => not published", r.published());
        assertFalse("board absent => not cancelled (caller should still send)", r.cancelled());
        assertNull(r.reason());
    }

    @Test
    public void genericPublishIsNoopWhenBoardAbsent() {
        BoardTraceLink link = new BoardTraceLink();
        assertFalse(link.publish("net.marcloud.mcp.board.signals.ChatReceiveSignal",
                new Class<?>[] { String.class }, "x"));
    }

    @Test
    public void nullSignalFqcnNeverThrows() {
        BoardTraceLink link = new BoardTraceLink();
        assertFalse(link.publish(null, new Class<?>[0]));
    }

    // ---- (2) injected real trace: veto round-trip ---------------------------

    @Test
    public void chatSendNotCancelledWhenNoSubscriberVetoes() {
        Trace trace = new Trace();
        // an observer that never cancels
        trace.subscribe(ChatSendSignal.class, s -> { /* observe only */ });

        BoardTraceLink link = new BoardTraceLink();
        link.setTraceForTest(trace, tracePublishMethod());

        assertTrue("injected trace => available", link.available());
        BoardTraceLink.ChatSendResult r = link.publishChatSend("hi there");
        assertTrue("published to the injected trace", r.published());
        assertFalse("no veto => not cancelled", r.cancelled());
        assertNull("no veto => no reason", r.reason());
    }

    @Test
    public void chatSendCancelledWithReasonSurfacesReason() {
        Trace trace = new Trace();
        trace.subscribe(ChatSendSignal.class, s -> s.cancel("blocked by filter"));

        BoardTraceLink link = new BoardTraceLink();
        link.setTraceForTest(trace, tracePublishMethod());

        BoardTraceLink.ChatSendResult r = link.publishChatSend("/op me");
        assertTrue(r.published());
        assertTrue("chip vetoed => cancelled", r.cancelled());
        assertEquals("blocked by filter", r.reason());
    }

    @Test
    public void chatSendCancelledWithoutReasonHasNullReason() {
        Trace trace = new Trace();
        trace.subscribe(ChatSendSignal.class, s -> s.cancel());

        BoardTraceLink link = new BoardTraceLink();
        link.setTraceForTest(trace, tracePublishMethod());

        BoardTraceLink.ChatSendResult r = link.publishChatSend("x");
        assertTrue(r.cancelled());
        assertNull("plain cancel() leaves reason null", r.reason());
    }

    @Test
    public void genericPublishReachesInjectedTrace() {
        Trace trace = new Trace();
        final String[] seen = { null };
        trace.subscribe(ChatReceiveSignal.class, s -> seen[0] = s.text());

        BoardTraceLink link = new BoardTraceLink();
        link.setTraceForTest(trace, tracePublishMethod());

        boolean ok = link.publish("net.marcloud.mcp.board.signals.ChatReceiveSignal",
                new Class<?>[] { String.class }, "server says hi");
        assertTrue("generic publish succeeded", ok);
        assertEquals("server says hi", seen[0]);
    }

    @Test
    public void boardSideThrowIsContained() {
        Trace trace = new Trace();
        trace.subscribe(ChatSendSignal.class, s -> { throw new RuntimeException("boom"); });

        BoardTraceLink link = new BoardTraceLink();
        link.setTraceForTest(trace, tracePublishMethod());

        // Trace isolates the throwing subscriber; the link must still not throw and
        // reports the signal as published + not cancelled (the fault swallowed the veto).
        BoardTraceLink.ChatSendResult r = link.publishChatSend("x");
        assertTrue(r.published());
        assertFalse(r.cancelled());
    }
}
