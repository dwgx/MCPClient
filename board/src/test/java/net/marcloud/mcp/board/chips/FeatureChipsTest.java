package net.marcloud.mcp.board.chips;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import net.marcloud.mcp.board.Chip;

/**
 * Headless teeth for the neutral local-only feature chips added to the launcher roster
 * ({@link FullbrightChip}, {@link CoordinatesHudChip}, {@link FpsMeterChip}). Since no live
 * MC is present in a unit run, every game-touching path must degrade to a safe no-op — these
 * assert the lifecycle state machine flips correctly AND that the reflective reads/writes
 * never throw and report "not applied / unavailable" when headless.
 */
public class FeatureChipsTest {

    @Test
    public void fullbrightTogglesLifecycleAndIsHeadlessSafe() {
        FullbrightChip chip = new FullbrightChip();
        assertEquals("Render", chip.category());
        assertFalse(chip.isEnabled());
        assertFalse(chip.appliedToGame());

        // Enable then disable headless: state flips, no game reached, nothing thrown.
        chip.setEnabled(true);
        assertTrue("enable flips the chip on", chip.isEnabled());
        assertFalse("no live game headless -> not applied", chip.appliedToGame());

        chip.setEnabled(false);
        assertFalse("disable flips the chip off", chip.isEnabled());
        assertFalse(chip.appliedToGame());
    }

    @Test
    public void coordinatesHudTogglesAndPositionDegradesToNull() {
        CoordinatesHudChip chip = new CoordinatesHudChip();
        assertEquals("Interface", chip.category());
        chip.setEnabled(true);
        assertTrue(chip.isEnabled());
        assertNull("no live player headless -> null position, not a crash", chip.position());
        chip.setEnabled(false);
        assertFalse(chip.isEnabled());
    }

    @Test
    public void fpsMeterTogglesAndFpsReadsThroughPublicAccessor() {
        FpsMeterChip chip = new FpsMeterChip();
        assertEquals("diagnostic", chip.category());
        chip.setEnabled(true);
        assertTrue(chip.isEnabled());
        // fps() reflects the static PUBLIC Minecraft.getDebugFPS(). The Minecraft class loads
        // headless (client is on the test classpath) so the static counter reads its initial
        // value 0 — a non-negative real read, NOT the -1 error sentinel. This is the teeth for
        // review finding #2: if fps() regresses to reflecting the PRIVATE debugFPS field
        // (getField throws), this returns -1 and the assertion fails.
        assertTrue("fps() reads through the public accessor (>=0), not the -1 error sentinel",
                chip.fps() >= 0);
        chip.setEnabled(false);
        assertFalse(chip.isEnabled());
    }

    @Test
    public void featureChipIdsAreStableAndDistinct() {
        Chip fb = new FullbrightChip();
        Chip co = new CoordinatesHudChip();
        Chip fps = new FpsMeterChip();
        // default id = simple class name; distinct so the matrix keys never collide
        assertEquals("FullbrightChip", fb.id());
        assertEquals("CoordinatesHudChip", co.id());
        assertEquals("FpsMeterChip", fps.id());
    }

    @Test
    public void reEnableIsIdempotentAndReversible() {
        FullbrightChip chip = new FullbrightChip();
        chip.setEnabled(true);
        chip.setEnabled(true); // no-op (setEnabled only fires on change)
        chip.setEnabled(false);
        chip.setEnabled(false);
        assertFalse(chip.isEnabled());
        assertFalse(chip.appliedToGame());
    }
}
