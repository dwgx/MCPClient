package net.marcloud.mcp.board.link;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.board.Backplane;
import net.marcloud.mcp.board.Board;

import org.junit.After;
import org.junit.Test;

/**
 * Regression tests for the reflection bridge {@link McpLink} and the outward
 * {@link BoardPort}. The key invariant under test is the PEER decoupling: board
 * does not depend on core, so on board's own classpath {@code McpCore} /
 * {@code CoreBootstrap} are ABSENT — every core-facing call must degrade to
 * {@code false}/{@code null} WITHOUT throwing, and never touch a core type.
 *
 * <p>These fail on absent/old code: without McpLink (or if it let reflection
 * throw instead of catching) the class would not compile or the calls would blow
 * up rather than returning the graceful-degradation values asserted here.
 */
public class McpLinkTest {

    @After
    public void tearDown() {
        Board.shutdown();
        Backplane.clear();
    }

    @Test
    public void coreAbsentOnBoardClasspathSoPresenceIsFalse() {
        // board/pom.xml depends only on client (provided) + junit — never core.
        assertFalse("mcp-core must not be on board's classpath (peer decoupling)",
                McpLink.isCorePresent());
    }

    @Test
    public void coreRunningIsFalseAndDoesNotThrowWhenAbsent() {
        assertFalse(McpLink.isCoreRunning());
    }

    @Test
    public void tryStartDegradesToFalseWhenCoreAbsent() {
        // No CoreBootstrap on the classpath → ignition impossible → false, no throw.
        assertFalse(McpLink.tryStart());
    }

    @Test
    public void findCorePortIsNullWhenCoreNeverRegistered() {
        assertNull(McpLink.findCorePort());
    }

    @Test
    public void findCorePortSeesAReflectivelyRegisteredPeer() {
        // Simulate core registering its port on the neutral backplane. board reads
        // it back as an opaque Object with zero compile-time coupling to core.
        Object fakeCorePort = new Object();
        Backplane.register("mcp.port", fakeCorePort);
        assertSame(fakeCorePort, McpLink.findCorePort());
    }

    @Test
    public void publishBoardPortRegistersDiscoverableHandle() {
        assertFalse(Backplane.has(BoardPort.KEY));
        BoardPort port = McpLink.publishBoardPort();
        assertNotNull(port);
        assertTrue(Backplane.has(BoardPort.KEY));
        assertSame(port, Backplane.find(BoardPort.KEY));
    }

    @Test
    public void boardPortExposesBoardStateReflectivelySafely() {
        BoardPort port = new BoardPort();
        assertEquals("board", port.id());
        assertFalse(port.isStarted());
        // trace()/features() are handed out as opaque Objects (no core type leak).
        assertNotNull(port.trace());
        assertNotNull(port.features());

        assertTrue(port.start());
        assertTrue(port.isStarted());
        assertTrue(Board.isStarted());
    }
}
