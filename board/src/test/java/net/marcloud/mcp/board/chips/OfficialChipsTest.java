package net.marcloud.mcp.board.chips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Matrix;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.signals.ChatSendSignal;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * PHASE E (E.3): the built-in chip roster installer. Proves the property gate,
 * that the installed chips are added AND enabled, that installation is
 * idempotent-friendly (no duplicate-id throw), and — the real teeth — that a chip
 * installed by {@code OfficialChips} is actually WIRED to the trace it was given:
 * publishing a {@link ChatSendSignal} increments the installed {@link ChatLogChip}'s
 * observed count.
 *
 * <p>The {@code mcp.board.officialChips} property is process-wide, so the fixture
 * snapshots and restores it around each test.
 */
public class OfficialChipsTest {

    private String saved;

    @Before
    public void snapshotProperty() {
        saved = System.getProperty(OfficialChips.PROPERTY);
        System.clearProperty(OfficialChips.PROPERTY);
    }

    @After
    public void restoreProperty() {
        if (saved == null) {
            System.clearProperty(OfficialChips.PROPERTY);
        } else {
            System.setProperty(OfficialChips.PROPERTY, saved);
        }
    }

    @Test
    public void defaultOnInstallsAndEnablesRoster() {
        Matrix<Chip> matrix = new Matrix<Chip>();
        Trace trace = new Trace();

        int n = OfficialChips.install(matrix, trace);

        assertEquals("default-on installs the two demonstrator chips", 2, n);
        assertTrue(matrix.size() >= 2);
        Chip chatLog = matrix.byId(new ChatLogChip(trace).id());
        Chip ticker = matrix.byId(new TickCounterChip(trace).id());
        assertNotNull("ChatLogChip must be installed", chatLog);
        assertNotNull("TickCounterChip must be installed", ticker);
        assertTrue("installed chips must be enabled", chatLog.isEnabled());
        assertTrue("installed chips must be enabled", ticker.isEnabled());
    }

    @Test
    public void flagFalseInstallsNothing() {
        System.setProperty(OfficialChips.PROPERTY, "false");
        Matrix<Chip> matrix = new Matrix<Chip>();
        int n = OfficialChips.install(matrix, new Trace());
        assertEquals(0, n);
        assertEquals("opt-out leaves a bare matrix", 0, matrix.size());
        assertFalse(OfficialChips.enabled());
    }

    @Test
    public void flagNoneInstallsNothing() {
        System.setProperty(OfficialChips.PROPERTY, "none");
        Matrix<Chip> matrix = new Matrix<Chip>();
        assertEquals(0, OfficialChips.install(matrix, new Trace()));
        assertEquals(0, matrix.size());
    }

    @Test
    public void unrecognizedFlagValueStaysOn() {
        System.setProperty(OfficialChips.PROPERTY, "yes-please");
        assertTrue("only false/none/off/0 opt out; anything else is on",
                OfficialChips.enabled());
    }

    @Test
    public void installIsIdempotentFriendly() {
        Matrix<Chip> matrix = new Matrix<Chip>();
        Trace trace = new Trace();
        int first = OfficialChips.install(matrix, trace);
        int second = OfficialChips.install(matrix, trace);
        assertEquals(2, first);
        assertEquals("second install must add nothing (ids already present)", 0, second);
        assertEquals("no duplicate chips", 2, matrix.size());
    }

    @Test
    public void installedChatLogChipIsWiredToTheTrace() {
        Matrix<Chip> matrix = new Matrix<Chip>();
        Trace trace = new Trace();
        OfficialChips.install(matrix, trace);

        ChatLogChip chatLog = (ChatLogChip) matrix.byId(new ChatLogChip(trace).id());
        assertNotNull(chatLog);
        long before = chatLog.sentCount();

        trace.publish(new ChatSendSignal("hi"));
        trace.publish(new ChatSendSignal("there"));

        assertEquals("installed ChatLogChip must observe sends on its trace",
                before + 2, chatLog.sentCount());
        assertEquals("last observed message round-trips", "there", chatLog.lastMessage());
    }

    @Test
    public void nullArgsRejected() {
        try {
            OfficialChips.install(null, new Trace());
            org.junit.Assert.fail("null matrix must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
        try {
            OfficialChips.install(new Matrix<Chip>(), null);
            org.junit.Assert.fail("null trace must be rejected");
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }
}
