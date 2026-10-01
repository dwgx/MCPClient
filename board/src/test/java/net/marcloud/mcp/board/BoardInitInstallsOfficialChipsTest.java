package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.board.chips.ChatLogChip;
import net.marcloud.mcp.board.chips.OfficialChips;
import net.marcloud.mcp.board.signals.TickSignal;
import net.marcloud.mcp.board.chips.TickCounterChip;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * PHASE E.3 (ADR-0003): teeth for {@link Board#init()} delegating to
 * {@link OfficialChips#install}. Proves the frozen (§L2) facade now installs the
 * built-in roster on start, and still honors the opt-out.
 *
 * <p>Non-vacuous: deleting the {@code OfficialChips.install(...)} line from
 * {@code Board.init()} makes {@link #initInstallsAndEnablesTheOfficialRoster} fail
 * (the matrix stays empty).
 *
 * <p>{@code Board} holds process-wide static singletons and {@code
 * mcp.board.officialChips} is a process-wide property, so the fixture shuts the
 * board down and snapshots/restores the property around every test.
 */
public class BoardInitInstallsOfficialChipsTest {

    private String savedProp;

    @Before
    public void reset() {
        savedProp = System.getProperty(OfficialChips.PROPERTY);
        System.clearProperty(OfficialChips.PROPERTY);
        // Board.FEATURES is a process-wide singleton, so the fixture fully resets it
        // regardless of run order. shutdown() now empties the matrix itself — it used to
        // only disable, which is exactly what left a restart with a dead roster (see
        // restartingReinstallsAnEnabledRoster). The explicit clear() below keeps each test
        // independent of that and of any other test's leftovers.
        Board.shutdown();
        Board.features().clear();
    }

    @After
    public void tearDown() {
        Board.shutdown();
        Board.features().clear();
        if (savedProp == null) {
            System.clearProperty(OfficialChips.PROPERTY);
        } else {
            System.setProperty(OfficialChips.PROPERTY, savedProp);
        }
    }

    @Test
    public void initInstallsAndEnablesTheOfficialRoster() {
        // ids are stable per chip type; build throwaway instances just to read them
        String chatLogId = new ChatLogChip(new Trace()).id();
        String tickerId = new TickCounterChip(new Trace()).id();

        Board.init();

        assertTrue("Board reports started after init", Board.isStarted());
        Chip chatLog = Board.features().byId(chatLogId);
        Chip ticker = Board.features().byId(tickerId);
        assertNotNull("Board.init installs ChatLogChip", chatLog);
        assertNotNull("Board.init installs TickCounterChip", ticker);
        assertTrue("installed ChatLogChip is enabled", chatLog.isEnabled());
        assertTrue("installed TickCounterChip is enabled", ticker.isEnabled());
    }

    @Test
    public void optOutFlagLeavesRosterEmpty() {
        System.setProperty(OfficialChips.PROPERTY, "false");
        String chatLogId = new ChatLogChip(new Trace()).id();

        Board.init();

        assertTrue("Board still starts under opt-out", Board.isStarted());
        assertNull("opt-out installs no official chips",
                Board.features().byId(chatLogId));
        assertEquals("opt-out leaves a bare matrix", 0, Board.features().size());
    }

    /**
     * The restart path, which had no coverage at all: {@code shutdown()} then {@code init()}
     * is a documented sequence ({@code BoardPort.start()} reaches it), and it used to come up
     * with the full roster listed and <b>every chip off</b>. The ids were still present, so
     * {@code OfficialChips.addAndEnable} early-returned without re-enabling — and the two chips
     * that are "enabled out of the box" stayed unsubscribed from the tick bus. Nothing
     * reported it, because a listed roster reads as a working one.
     */
    @Test
    public void restartingReinstallsAnEnabledRoster() {
        String chatLogId = new ChatLogChip(new Trace()).id();
        String tickerId = new TickCounterChip(new Trace()).id();

        Board.init();
        assertTrue("precondition: the roster is enabled after a first init",
                Board.features().byId(chatLogId).isEnabled());

        Board.shutdown();
        Board.init();

        Chip chatLog = Board.features().byId(chatLogId);
        Chip ticker = Board.features().byId(tickerId);
        assertNotNull("a restart must reinstall ChatLogChip", chatLog);
        assertNotNull("a restart must reinstall TickCounterChip", ticker);
        assertTrue("a restart must not leave the default-on chip disabled",
                chatLog.isEnabled());
        assertTrue("a restart must not leave the default-on chip disabled", ticker.isEnabled());
    }

    /**
     * The bus is a separate resource from the matrix, and a fix for the matrix can quietly
     * break the bus.
     *
     * <p>An earlier revision of the shutdown fix replaced {@code FEATURES.disableAll()} with
     * {@code FEATURES.clear()} and dropped the {@code TRACE.clear()} beside it. The roster
     * tests above still passed — the five roster chips ARE matrix members — so nothing went
     * red. But {@code Matrix.clear()} only reaches chips inside the matrix, and
     * {@link Trace} is a process-wide bus that anything can subscribe to. A non-chip
     * subscriber survived shutdown: still active, still receiving ticks, still subscribed
     * after a restart.
     *
     * <p>So the guard has to subscribe something the matrix does not know about. A chip would
     * not do — it would be caught by the matrix clear and this test would pass for the wrong
     * reason, which is exactly what happened the first time.
     */
    @Test
    public void shutdownDropsSubscribersThatAreNotChipsInTheMatrix() {
        final int[] hits = {0};
        Board.trace().subscribe(TickSignal.class, signal -> hits[0]++);
        assertTrue("precondition: the non-chip subscriber is on the bus",
                Board.trace().hasSubscribers(TickSignal.class));

        Board.init();
        Board.shutdown();

        assertFalse("a subscriber that is NOT a chip in the feature matrix must still be dropped "
                + "by shutdown -- Matrix.clear() only knows about the chips inside it",
                Board.trace().hasSubscribers(TickSignal.class));

        Board.trace().publish(TickSignal.endOfTick());
        assertEquals("and it must not still be receiving signals after the board is stopped",
                0, hits[0]);
    }

    /**
     * A restart must actually re-subscribe, not merely flip a flag: the chips are advertised
     * as working out of the box, and the observable of that is the tick bus.
     */
    @Test
    public void restartingResubscribesToTheTickBus() {
        Board.init();
        String tickerId = new TickCounterChip(new Trace()).id();
        assertTrue("precondition: the ticker is on the tick bus after a first init",
                Board.trace().hasSubscribers(TickSignal.endOfTick().getClass()));

        Board.shutdown();
        Board.init();

        assertNotNull("a restart reinstalls the ticker", Board.features().byId(tickerId));
        assertTrue("a restart must resubscribe to the tick bus, not just re-enable the chip",
                Board.trace().hasSubscribers(TickSignal.endOfTick().getClass()));
    }
}
