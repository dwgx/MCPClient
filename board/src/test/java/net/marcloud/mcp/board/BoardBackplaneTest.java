package net.marcloud.mcp.board;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

/** Regression tests for the {@link Board} facade and {@link Backplane} registry. */
public class BoardBackplaneTest {

    @After
    public void tearDown() {
        Board.shutdown();
        Backplane.clear();
    }

    @Test
    public void facadeSingletonsAreStableAndNonNull() {
        assertSame(Board.trace(), Board.trace());
        assertSame(Board.features(), Board.features());
    }

    @Test
    public void initIsIdempotentAndRegistersOnBackplane() {
        assertFalse(Board.isStarted());
        Board.init();
        Board.init();
        assertTrue(Board.isStarted());
        assertTrue(Backplane.has("board"));
    }

    /**
     * Review finding M3: init() must publish a reflection-friendly BoardPort (with
     * id()/isStarted()/start()), NOT a raw Trace — a peer discovers Board by
     * reflecting those methods, and a raw Trace has none of them. This drives the
     * registered service exactly as mcp-core would, purely by reflection.
     */
    @Test
    public void initPublishesAReflectablePeerPort() throws Exception {
        Board.init();
        // Peer lookup path: BoardPort.KEY, then the "board" alias.
        Object port = Backplane.find("board.port");
        if (port == null) {
            port = Backplane.find("board");
        }
        assertTrue("a peer port must be registered", port != null);
        // Drive it the way a zero-dependency peer would — reflection only.
        Object id = port.getClass().getMethod("id").invoke(port);
        org.junit.Assert.assertEquals("board", id);
        Object running = port.getClass().getMethod("isStarted").invoke(port);
        org.junit.Assert.assertEquals(Boolean.TRUE, running);
        // start() is idempotent and must not throw
        port.getClass().getMethod("start").invoke(port);

        // A peer reaches the live bus + feature matrix through the port, as opaque
        // Objects (no Board type needed). These are the accessors a render seam /
        // MCP tool would use; pin them so the peer-facing surface can't silently break.
        Object trace = port.getClass().getMethod("trace").invoke(port);
        assertSame("port.trace() must be the live Board Trace", Board.trace(), trace);
        Object features = port.getClass().getMethod("features").invoke(port);
        assertSame("port.features() must be the live Board features Matrix",
                Board.features(), features);
    }

    @Test
    public void shutdownUnregistersAndClears() {
        Board.init();
        Board.shutdown();
        assertFalse(Board.isStarted());
        assertFalse(Backplane.has("board"));
    }

    @Test
    public void backplaneFindByTypeReturnsRegisteredService() {
        Runnable svc = new Runnable() {
            @Override
            public void run() {
            }
        };
        Backplane.register(Runnable.class, svc);
        assertSame(svc, Backplane.find(Runnable.class));
    }

    @Test
    public void backplaneAbsentServiceDegradesToNull() {
        assertNull(Backplane.find("does.not.exist"));
        assertNull(Backplane.find(Comparable.class));
    }

    @Test
    public void backplaneUnregisterRemoves() {
        Backplane.register("k", "v");
        assertSame("v", Backplane.find("k"));
        Backplane.unregister("k");
        assertNull(Backplane.find("k"));
    }
}
