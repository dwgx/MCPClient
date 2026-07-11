package net.marcloud.mcp.board.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.board.Chip;
import net.marcloud.mcp.board.signals.KeySignal;

/** Regression tests for the {@link Pin} keybind contract. */
public class PinTest {

    /** A minimal chip whose enable/disable toggles are observable. */
    static final class Toggleable extends Chip {
        int enables;
        int disables;

        @Override
        protected void onEnable() {
            enables++;
        }

        @Override
        protected void onDisable() {
            disables++;
        }
    }

    @Test
    public void toggleFlipsChipOnKeyDownAndIgnoresKeyUp() {
        Toggleable chip = new Toggleable();
        Pin pin = Pin.toggle(50, chip);

        assertFalse(chip.isEnabled());
        assertTrue(pin.handle(KeySignal.down(50)));
        assertTrue(chip.isEnabled());
        assertEquals(1, chip.enables);

        // Key-up must NOT flip a toggle pin back.
        assertFalse(pin.handle(KeySignal.up(50)));
        assertTrue(chip.isEnabled());

        // Next press flips it off.
        assertTrue(pin.handle(KeySignal.down(50)));
        assertFalse(chip.isEnabled());
        assertEquals(1, chip.disables);
    }

    @Test
    public void holdIsActiveOnlyWhileKeyDown() {
        Toggleable chip = new Toggleable();
        Pin pin = Pin.hold(60, chip);

        assertTrue(pin.handle(KeySignal.down(60)));
        assertTrue(chip.isEnabled());

        assertTrue(pin.handle(KeySignal.up(60)));
        assertFalse(chip.isEnabled());
        assertEquals(1, chip.enables);
        assertEquals(1, chip.disables);
    }

    @Test
    public void ignoresNonMatchingKey() {
        Toggleable chip = new Toggleable();
        Pin pin = Pin.toggle(50, chip);

        assertFalse(pin.handle(KeySignal.down(99)));
        assertFalse(chip.isEnabled());
        assertEquals(0, chip.enables);
    }

    @Test
    public void constructionSyncsChipPinAttribute() {
        Toggleable chip = new Toggleable();
        assertEquals(Chip.NO_PIN, chip.pin());
        Pin.toggle(42, chip);
        assertEquals(42, chip.pin());
    }

    @Test
    public void actionBackedPinTracksState() {
        final boolean[] state = {false};
        final int[] calls = {0};
        Pin pin = Pin.toggle(70, new Pin.Action() {
            @Override
            public void set(boolean active) {
                state[0] = active;
                calls[0]++;
            }
        });

        assertNull(pin.chip());
        pin.handle(KeySignal.down(70));
        assertTrue(state[0]);
        assertTrue(pin.isActive());
        pin.handle(KeySignal.down(70));
        assertFalse(state[0]);
        assertEquals(2, calls[0]);
    }

    @Test
    public void disabledPinIgnoresSignals() {
        Toggleable chip = new Toggleable();
        Pin pin = Pin.toggle(50, chip);
        pin.setEnabled(false);

        assertFalse(pin.handle(KeySignal.down(50)));
        assertFalse(chip.isEnabled());
    }

    @Test
    public void exposesModeAndBoundChip() {
        Toggleable chip = new Toggleable();
        Pin pin = Pin.hold(50, chip);
        assertEquals(Pin.Mode.HOLD, pin.mode());
        assertEquals(50, pin.keyCode());
        assertSame(chip, pin.chip());
    }
}
