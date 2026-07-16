package net.marcloud.mcp.board.chips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.board.Board;
import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Matrix;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.signals.TickSignal;

/**
 * Regression tests for the SAMPLE chips. These prove the framework's three
 * headline use cases end to end and fail on absent/old code: a tick-driven chip
 * that subscribes on the {@link Trace}, a reflection-safe startup-screen chip,
 * and a login/auth stub — all neutral {@link Chip}s exercising
 * {@code onLoad/onEnable/onDisable}.
 */
public class SampleChipsTest {

    // ---- (a) tick-driven chip: Trace subscription via a Chip lifecycle -------

    @Test
    public void tickCounterCountsOnlyWhileEnabled() {
        Trace trace = new Trace();
        TickCounterChip chip = new TickCounterChip(trace);

        // disabled: no subscription, no counting
        trace.publish(new TickSignal(1));
        assertEquals(0, trace.subscriberCount());
        assertEquals(0L, chip.count());

        // enabling subscribes and starts counting
        chip.setEnabled(true);
        assertEquals(1, trace.subscriberCount());
        trace.publish(new TickSignal(2));
        trace.publish(new TickSignal(3));
        assertEquals(2L, chip.count());

        // disabling unsubscribes and stops counting
        chip.setEnabled(false);
        assertEquals(0, trace.subscriberCount());
        trace.publish(new TickSignal(4));
        assertEquals(2L, chip.count());
    }

    @Test
    public void tickCounterReEnableDoesNotDoubleSubscribe() {
        Trace trace = new Trace();
        TickCounterChip chip = new TickCounterChip(trace);
        chip.setEnabled(true);
        chip.setEnabled(false);
        chip.setEnabled(true);
        // exactly one live subscription after cycling
        assertEquals(1, trace.subscriberCount());
        trace.publish(new TickSignal(1));
        assertEquals(1L, chip.count());
        chip.reset();
        assertEquals(0L, chip.count());
    }

    @Test
    public void tickCounterWorksThroughBoardFacadeAndMatrix() {
        Board.shutdown();
        Board.trace().clear();
        Board.init();
        // E.3 (ADR-0003): init() now installs the official roster (incl. a TickCounterChip);
        // clear it so this test adds and drives its OWN chip without a duplicate-id clash.
        Board.features().clear();
        try {
            Matrix<Chip> features = Board.features();
            TickCounterChip chip = new TickCounterChip(); // binds Board.trace()
            features.add(chip);
            chip.setEnabled(true);
            Board.trace().publish(new TickSignal(1));
            Board.trace().publish(new TickSignal(2));
            assertEquals(2L, chip.count());
            features.remove(chip);
            // removal disabled + unloaded the chip, so its subscription is gone
            Board.trace().publish(new TickSignal(3));
            assertEquals(2L, chip.count());
        } finally {
            Board.shutdown();
            Board.trace().clear();
        }
    }

    // ---- (b) startup-screen replace chip: reflection/headless-safe -----------

    @Test
    public void startupScreenChipMarksAndClearsHeadlessSafe() {
        StartupScreenChip chip = new StartupScreenChip();
        assertEquals("startup", chip.category());
        assertFalse(chip.isMarked());

        chip.setEnabled(true);
        assertTrue(chip.isMarked());
        // headless test JVM has no live Minecraft, so it degrades to a no-op mark
        assertFalse(chip.appliedToGame());

        chip.setEnabled(false);
        assertFalse(chip.isMarked());
        assertFalse(chip.appliedToGame());
    }

    // ---- (c) login/auth stub chip: lifecycle state machine -------------------

    @Test
    public void loginChipDrivesAuthStateThroughLifecycle() {
        Matrix<Chip> matrix = new Matrix<Chip>();
        LoginChip chip = (LoginChip) matrix.add(new LoginChip());
        assertEquals("login", chip.category());
        // onLoad ran via Matrix.add; headless => offline username
        assertEquals("offline", chip.username());
        assertEquals(LoginChip.AuthState.LOGGED_OUT, chip.state());
        assertFalse(chip.isAuthenticated());

        chip.setEnabled(true);
        assertTrue(chip.isAuthenticated());
        assertEquals(LoginChip.AuthState.AUTHENTICATED, chip.state());

        chip.setEnabled(false);
        assertFalse(chip.isAuthenticated());
        assertEquals(LoginChip.AuthState.LOGGED_OUT, chip.state());
    }

    @Test
    public void sampleChipsExposeStableIds() {
        assertEquals("TickCounterChip", new TickCounterChip(new Trace()).id());
        assertEquals("StartupScreenChip", new StartupScreenChip().id());
        assertEquals("LoginChip", new LoginChip().id());
    }

    @Test
    public void tickSignalCarriesItsTickNumber() {
        TickSignal s = new TickSignal(7L);
        assertEquals(7L, s.tick());
        assertNotNull(s); // Signal timestamp is set by the base class
        assertSame(s, new Trace().publish(s)); // publish returns the signal
    }
}
