package net.marcloud.mcp.core.ke;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * PHASE T (T.1): the single game clock is monotonic, one counter, and gives an
 * off-thread reader the last completed tick.
 */
public class GameClockTest {

    @Test
    public void advanceIsMonotonicAndStartsAtZero() {
        GameClock c = new GameClock();
        assertEquals("no ticks yet => 0", 0L, c.tickId());
        assertEquals("first advance => 1", 1L, c.advance());
        assertEquals(2L, c.advance());
        assertEquals(3L, c.advance());
        assertEquals(3L, c.tickId());
        assertEquals("lastCompletedTick tracks the counter", 3L, c.lastCompletedTick());
    }

    @Test
    public void postWorldPhaseDoesNotBumpTheTickNumber() {
        GameClock c = new GameClock();
        c.advance(GameClock.Phase.START);           // tick 1
        long id = c.advance(GameClock.Phase.POST_WORLD);
        assertEquals("POST_WORLD is a same-tick phase, not a new tick", 1L, id);
        assertEquals(1L, c.tickId());
        assertEquals(GameClock.Phase.POST_WORLD, c.lastPhase());
    }

    @Test
    public void monoTimeAdvancesAcrossTicks() {
        GameClock c = new GameClock();
        long before = c.lastTickMonoNs();
        c.advance();
        long after = c.lastTickMonoNs();
        assertTrue("monotonic nanotime does not go backwards", after >= before);
    }

    @Test
    public void resetReturnsToPreFirstTickState() {
        GameClock c = new GameClock();
        c.advance();
        c.advance();
        c.reset();
        assertEquals(0L, c.tickId());
        assertEquals(GameClock.Phase.START, c.lastPhase());
    }
}
