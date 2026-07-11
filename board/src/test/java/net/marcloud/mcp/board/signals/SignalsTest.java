package net.marcloud.mcp.board.signals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.board.Signal;
import net.marcloud.mcp.board.Trace;
import org.junit.Test;

/**
 * Regression tests for the concrete {@link Signal} tree. These exercise each
 * signal's payload and its behaviour when routed through a real {@link Trace},
 * so they fail if a signal type is missing, mistyped, or loses its payload
 * accessors.
 */
public class SignalsTest {

    // ---- TickSignal --------------------------------------------------------

    @Test
    public void tickDefaultsToEndPhase() {
        TickSignal tick = new TickSignal();
        assertEquals(TickSignal.Phase.END, tick.phase());
    }

    @Test
    public void tickKeepsExplicitPhaseAndCoercesNull() {
        assertEquals(TickSignal.Phase.START, new TickSignal(TickSignal.Phase.START).phase());
        assertEquals(TickSignal.Phase.END, new TickSignal(null).phase());
    }

    @Test
    public void tickIsDeliveredThroughTrace() {
        Trace trace = new Trace();
        final TickSignal.Phase[] seen = {null};
        trace.subscribe(TickSignal.class, new Trace.Listener<TickSignal>() {
            @Override
            public void on(TickSignal s) {
                seen[0] = s.phase();
            }
        });
        trace.publish(new TickSignal(TickSignal.Phase.START));
        assertEquals(TickSignal.Phase.START, seen[0]);
    }

    // ---- RenderSignal ------------------------------------------------------

    @Test
    public void renderCarriesFramePayload() {
        RenderSignal render = new RenderSignal(1920, 1080, 0.5f);
        assertEquals(0.5f, render.partialTicks(), 0.0f);
        assertEquals(1920, render.screenWidth());
        assertEquals(1080, render.screenHeight());
    }

    @Test
    public void renderIsDeliveredThroughTrace() {
        Trace trace = new Trace();
        final int[] area = {0};
        trace.subscribe(RenderSignal.class, new Trace.Listener<RenderSignal>() {
            @Override
            public void on(RenderSignal s) {
                area[0] = s.screenWidth() * s.screenHeight();
            }
        });
        trace.publish(new RenderSignal(320, 240, 1.0f));
        assertEquals(320 * 240, area[0]);
    }

    // ---- KeySignal ---------------------------------------------------------

    @Test
    public void keyCarriesCodeAndPressState() {
        KeySignal down = new KeySignal(57, true);
        assertEquals(57, down.keyCode());
        assertTrue(down.isPressed());
        assertFalse(new KeySignal(57, false).isPressed());
    }

    @Test
    public void keyIsDeliveredThroughTrace() {
        Trace trace = new Trace();
        final int[] code = {-1};
        trace.subscribe(KeySignal.class, new Trace.Listener<KeySignal>() {
            @Override
            public void on(KeySignal s) {
                if (s.isPressed()) {
                    code[0] = s.keyCode();
                }
            }
        });
        trace.publish(new KeySignal(15, true));
        assertEquals(15, code[0]);
    }

    // ---- ChatSendSignal (Cancellable example) ------------------------------

    @Test
    public void chatSendIsCancellableInPrePhase() {
        ChatSendSignal chat = new ChatSendSignal("hello world");
        assertEquals("hello world", chat.message());
        assertEquals(Signal.Cancellable.State.PRE, chat.state());
        assertFalse(chat.isCancelled());
    }

    @Test
    public void chatSendCanBeVetoedBySubscriber() {
        Trace trace = new Trace();
        trace.subscribe(ChatSendSignal.class, new Trace.Listener<ChatSendSignal>() {
            @Override
            public void on(ChatSendSignal s) {
                if (s.message().startsWith(".")) {
                    s.cancel();
                }
            }
        });
        ChatSendSignal command = trace.publish(new ChatSendSignal(".fly on"));
        assertTrue(command.isCancelled());

        ChatSendSignal normal = trace.publish(new ChatSendSignal("hi"));
        assertFalse(normal.isCancelled());
    }

    @Test
    public void chatSendIsASignalCancellable() {
        // Type-relationship guard: ChatSendSignal must be usable wherever a
        // Signal.Cancellable is expected (so the Trace veto path applies).
        Signal.Cancellable c = new ChatSendSignal("x");
        assertSame(Signal.Cancellable.State.PRE, c.state());
    }
}
