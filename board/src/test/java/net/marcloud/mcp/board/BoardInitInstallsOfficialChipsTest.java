package net.marcloud.mcp.board;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.board.chips.ChatLogChip;
import net.marcloud.mcp.board.chips.OfficialChips;
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
        // Board.FEATURES is a process-wide singleton and shutdown() only DISABLES
        // chips (it does not empty the matrix), so a prior test's chips would linger
        // and, since install is idempotent, would not be re-enabled. Fully clear the
        // matrix so each test sees a truly bare board regardless of run order.
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
}
