package net.marcloud.mcp.board.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.signals.KeySignal;

/** Regression tests for the {@link PinMatrix} keybind registry + routing. */
public class PinMatrixTest {

    static final class Toggleable extends Chip {
    }

    @Test
    public void routesSyntheticKeySignalToBoundChip() {
        PinMatrix matrix = new PinMatrix();
        Toggleable chip = new Toggleable();
        matrix.bindToggle(50, chip);

        assertEquals(1, matrix.route(KeySignal.down(50)));
        assertTrue(chip.isEnabled());
        assertEquals(1, matrix.route(KeySignal.down(50)));
        assertFalse(chip.isEnabled());
    }

    @Test
    public void publishOnAttachedTraceDrivesPins() {
        Trace trace = new Trace();
        PinMatrix matrix = new PinMatrix().attach(trace);
        Toggleable chip = new Toggleable();
        matrix.bindToggle(88, chip);

        trace.publish(KeySignal.down(88));
        assertTrue(chip.isEnabled());
    }

    @Test
    public void detachStopsRouting() {
        Trace trace = new Trace();
        PinMatrix matrix = new PinMatrix().attach(trace);
        Toggleable chip = new Toggleable();
        matrix.bindToggle(88, chip);

        matrix.detach();
        assertFalse(matrix.isAttached());
        trace.publish(KeySignal.down(88));
        assertFalse(chip.isEnabled());
    }

    @Test
    public void oneKeyDrivesMultiplePins() {
        PinMatrix matrix = new PinMatrix();
        Toggleable a = new Toggleable();
        Toggleable b = new Toggleable();
        matrix.bindToggle(65, a);
        matrix.bindToggle(65, b);

        assertEquals(2, matrix.route(KeySignal.down(65)));
        assertTrue(a.isEnabled());
        assertTrue(b.isEnabled());
    }

    @Test
    public void nonMatchingKeyDrivesNothing() {
        PinMatrix matrix = new PinMatrix();
        Toggleable chip = new Toggleable();
        matrix.bindToggle(50, chip);

        assertEquals(0, matrix.route(KeySignal.down(51)));
        assertFalse(chip.isEnabled());
    }

    @Test
    public void faultingPinIsIsolated() {
        PinMatrix matrix = new PinMatrix();
        final Toggleable good = new Toggleable();
        // A pin whose action throws must not starve the good pin bound to the same key.
        matrix.add(Pin.toggle(77, new Pin.Action() {
            @Override
            public void set(boolean active) {
                throw new RuntimeException("boom");
            }
        }));
        matrix.bindToggle(77, good);

        matrix.route(KeySignal.down(77));
        assertTrue(good.isEnabled());
    }

    @Test
    public void byKeyAndRemoveByKey() {
        PinMatrix matrix = new PinMatrix();
        matrix.bindToggle(50, new Toggleable());
        matrix.bindToggle(50, new Toggleable());
        matrix.bindToggle(60, new Toggleable());

        assertEquals(2, matrix.byKey(50).size());
        assertEquals(3, matrix.size());
        assertEquals(2, matrix.removeByKey(50));
        assertEquals(1, matrix.size());
        assertEquals(0, matrix.byKey(50).size());
    }

    @Test
    public void holdBindingActiveWhileHeld() {
        PinMatrix matrix = new PinMatrix();
        Toggleable chip = new Toggleable();
        matrix.bindHold(90, chip);

        matrix.route(KeySignal.down(90));
        assertTrue(chip.isEnabled());
        matrix.route(KeySignal.up(90));
        assertFalse(chip.isEnabled());
    }

    @Test
    public void clearRemovesAllPins() {
        PinMatrix matrix = new PinMatrix();
        matrix.bindToggle(50, new Toggleable());
        matrix.bindToggle(60, new Toggleable());
        matrix.clear();
        assertEquals(0, matrix.size());
    }
}
