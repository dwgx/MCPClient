package net.marcloud.mcp.core.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import net.marcloud.mcp.board.Backplane;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Drives {@code BoardTraceLink.resolveTrace()} through real {@code Backplane} discovery, which is
 * the only path that runs in production and the one path no test covered.
 *
 * <p>Measured on 2026-08-05: replacing {@code port.getClass().getMethod("trace")} with
 * {@code getMethod("features")} SURVIVED the whole selection. It compiles, because
 * {@code BoardPort.features()} exists and also returns Object; and it is fatal, because features
 * yields board's Matrix, which has no {@code publish} method, so {@code findPublish} returns null
 * and the link is permanently unavailable -- every {@code publishChatSend} / {@code publishPacketSend}
 * silently degrades to "board absent" for the entire run.
 *
 * <p><b>Why nothing caught it.</b> Every green assertion in {@code BoardTraceLinkTest} injects the
 * trace through the {@code setTraceForTest} seam, which bypasses discovery entirely, and the single
 * test that does reach {@code resolveTrace} asserts availability is FALSE with no port registered --
 * so the mutant agrees with it. One side of the seam was driven eight times and the other side
 * never, which is the shape this repo has now paid for repeatedly (the tick gate asserted only on
 * the submit side; the differ driven without its capture).
 *
 * <p><b>Why the port here is a hand-written stand-in rather than a real BoardPort.</b> The
 * production lookup is duck-typed -- it reflects a method NAME off whatever object the Backplane
 * holds -- so what the assertion needs is an object where {@code trace()} and {@code features()}
 * return DIFFERENT things, one publishable and one not. A real BoardPort would work too, but it
 * would tie this test to board's own wiring and hide the one property being pinned: that the link
 * asks for trace specifically. The stand-in makes the mutation's target explicit, and it is exactly
 * what board's contract promises core -- {@code trace()} carries publish, {@code features()} does not.
 */
public class BackplaneDiscoveryReachesTheTraceNotTheMatrixTest {

    /** The key core hardcodes to find board's port ({@code BoardTraceLink.BOARD_PORT_KEY}). */
    private static final String BOARD_PORT_KEY = "board.port";

    @Before
    public void clearBackplane() {
        Backplane.clear();
    }

    /**
     * Mandatory: the Backplane is static and surefire reuses this JVM, so a leaked port would make
     * every later "board absent" assertion in the module test something else. That is not
     * hypothetical -- {@code BoardTraceLinkTest.unavailableWhenNoPortRegistered} asserts exactly the
     * state this class installs.
     */
    @After
    public void clearBackplaneAgain() {
        Backplane.clear();
    }

    @Test
    public void discoveryFindsThePublishableTraceAndTheLinkGoesLive() {
        StandInPort port = new StandInPort();
        Backplane.register(BOARD_PORT_KEY, port);

        BoardTraceLink link = new BoardTraceLink();
        assertTrue("with a port whose trace() carries a one-arg publish, discovery must succeed. "
                + "This is the assertion that was missing: the success path of resolveTrace had "
                + "never been driven, so a lookup aimed at the wrong member looked identical to "
                + "board simply being absent",
                link.available());
    }

    @Test
    public void theLinkAsksForTraceAndNotForTheMatrixBesideIt() {
        StandInPort port = new StandInPort();
        Backplane.register(BOARD_PORT_KEY, port);

        BoardTraceLink link = new BoardTraceLink();
        assertTrue("precondition: the link resolved", link.available());
        assertEquals("resolution must have gone through trace(). If it read features() instead it "
                + "would still compile and still return an object, but that object has no publish "
                + "method, so the link would report unavailable while board is running perfectly -- "
                + "a silent, whole-run degradation with no error anywhere",
                1, port.traceCalls);
        assertEquals("and it must not be reading the matrix: features() carries no publish, so a "
                + "lookup that lands there disables the link for the life of the process",
                0, port.featuresCalls);
    }

    @Test
    public void aResolvedLinkActuallyPublishesTheChatSignal() {
        StandInPort port = new StandInPort();
        Backplane.register(BOARD_PORT_KEY, port);

        BoardTraceLink link = new BoardTraceLink();
        BoardTraceLink.ChatSendResult result = link.publishChatSend("hello");

        assertTrue("a resolved link must really publish; availability alone would be satisfied by a "
                + "link that found a method and never invoked it", result.published());
        assertFalse("nothing vetoed, so the send proceeds", result.cancelled());
        assertEquals("the signal must reach the trace exactly once -- the caller's message is the "
                + "payload board renders, and a dropped publish is invisible to both sides",
                1, port.trace.published.size());
        assertNotNull("and the payload must not be null", port.trace.published.get(0));
    }

    /**
     * The negative half, kept because every assertion above is also satisfied by a link that
     * reports available unconditionally -- and that link would claim board is present on a headless
     * run, which is the opposite failure and a louder one.
     */
    @Test
    public void aPortWhoseTraceCarriesNoPublishLeavesTheLinkUnavailable() {
        Backplane.register(BOARD_PORT_KEY, new PublishlessPort());

        BoardTraceLink link = new BoardTraceLink();
        assertFalse("a trace with no one-arg publish is unusable, and the link must say so rather "
                + "than hold a half-resolved handle it will fail on later",
                link.available());
    }

    @Test
    public void noPortRegisteredStillMeansUnavailable() {
        BoardTraceLink link = new BoardTraceLink();
        assertFalse("with an empty Backplane the link stays a no-op: board absent is the normal "
                + "headless case and must never look like a failure",
                link.available());
    }

    // ---- stand-ins -------------------------------------------------------------

    /**
     * A port with BOTH members the production lookup could reach, deliberately asymmetric:
     * {@code trace()} publishes, {@code features()} does not. That asymmetry IS the test -- with two
     * interchangeable members the mutation would be undetectable, which mirrors board's real shape
     * (BoardPort.trace() carries publish; BoardPort.features() returns the Matrix).
     */
    public static final class StandInPort {
        final RecordingTrace trace = new RecordingTrace();
        final Matrixish features = new Matrixish();
        int traceCalls;
        int featuresCalls;

        public Object trace() {
            traceCalls++;
            return trace;
        }

        public Object features() {
            featuresCalls++;
            return features;
        }
    }

    /** Has the one-arg {@code publish} that {@code findPublish} looks for. */
    public static final class RecordingTrace {
        final List<Object> published = new ArrayList<>();

        public void publish(Object signal) {
            published.add(signal);
        }
    }

    /** Stands in for board's Matrix: no publish, so landing here disables the link. */
    public static final class Matrixish {
        public int size() {
            return 0;
        }
    }

    /** A port whose trace is present but unusable. */
    public static final class PublishlessPort {
        public Object trace() {
            return new Matrixish();
        }

        public Object features() {
            return new Matrixish();
        }
    }
}
