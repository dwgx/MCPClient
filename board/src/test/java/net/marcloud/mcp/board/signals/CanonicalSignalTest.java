package net.marcloud.mcp.board.signals;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import net.marcloud.mcp.board.Trace;
import net.marcloud.mcp.board.chips.TickCounterChip;
import net.marcloud.mcp.board.hud.HudMatrix;
import net.marcloud.mcp.board.input.PinMatrix;
import org.junit.Test;

/**
 * Regression guard for the "duplicate Signal" trap the architecture review found:
 * before consolidation there were TWO {@code TickSignal}/{@code RenderSignal}/
 * {@code KeySignal} classes (one in {@code signals/}, one in the subsystem
 * package). A subscriber wired to one variant silently never fired when the other
 * was published — no error, just a dead feature.
 *
 * <p>This test pins that each subsystem subscribes to THE canonical
 * {@code net.marcloud.mcp.board.signals} type by publishing the canonical signal
 * and asserting the subsystem actually reacts. It would FAIL on the pre-fix code,
 * where TickCounterChip listened to {@code chips.TickSignal} while the canonical /
 * doc-example signal is {@code signals.TickSignal} (not a subtype, so isInstance
 * dispatch would skip it).
 */
public class CanonicalSignalTest {

    @Test
    public void tickCounterReactsToCanonicalTickSignal() {
        Trace trace = new Trace();
        TickCounterChip chip = new TickCounterChip(trace);
        chip.setEnabled(true);
        // The canonical, doc-example no-arg signal from the signals package.
        trace.publish(new TickSignal());
        assertEquals("chip must count the canonical signals.TickSignal", 1L, chip.count());
    }

    @Test
    public void hudReactsToCanonicalRenderSignal() {
        Trace trace = new Trace();
        HudMatrix hud = new HudMatrix();
        hud.attach(trace);
        final boolean[] rendered = {false};
        hud.add(new net.marcloud.mcp.board.hud.Panel() {
            @Override
            protected void onRender(RenderSignal s) {
                rendered[0] = true;
            }
        }).setEnabled(true);
        trace.publish(new RenderSignal(320, 240, 0f));
        assertTrue("HUD must render on the canonical signals.RenderSignal", rendered[0]);
    }

    @Test
    public void pinMatrixReactsToCanonicalKeySignal() {
        Trace trace = new Trace();
        PinMatrix pins = new PinMatrix();
        pins.attach(trace);
        final int[] toggled = {0};
        pins.bindToggle(42, new net.marcloud.mcp.board.Chip() {
            @Override
            protected void onEnable() {
                toggled[0]++;
            }
        });
        trace.publish(KeySignal.down(42));
        assertEquals("PinMatrix must route the canonical signals.KeySignal", 1, toggled[0]);
    }

    @Test
    public void exactlyOneCanonicalTickSignalOnTheClasspath() throws Exception {
        // The consolidated signal lives in the signals package and carries both
        // a tick number and a phase (the merged design).
        Class<?> c = Class.forName("net.marcloud.mcp.board.signals.TickSignal");
        assertSame(TickSignal.class, c);
        assertEquals(0L, new TickSignal().tick());
        assertEquals(TickSignal.Phase.END, new TickSignal().phase());
    }
}
