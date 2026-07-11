package net.marcloud.mcp.core.drivers.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiTextField;

import org.junit.Test;

/**
 * Headless tests for the GUI action-trajectory log (Phase 6). They cover both the
 * bounded {@link GuiTrajectory} ring buffer itself and the instrumentation in
 * {@link GuiActions}: driving synthetic actions through the pure {@code *OnScreen}
 * seams (mirroring {@code GuiSnapshotTest}, no live game) and asserting each is
 * recorded in order with the correct kind, element id and result. A no-op
 * trajectory (or un-instrumented actions) would fail these.
 */
public class GuiTrajectoryTest {

    /**
     * A screen that overrides the protected handlers so the actions layer can drive
     * them headlessly without touching the {@code Minecraft} singleton (vanilla
     * {@code mouseClicked} dereferences {@code mc} to play a sound / fire actions).
     */
    private static final class FakeScreen extends GuiScreen {
        int clicks;
        int keys;

        void addButton(GuiButton b) {
            this.buttonList.add(b);
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
            clicks++;
        }

        @Override
        protected void keyTyped(char typedChar, int keyCode) {
            keys++;
        }
    }

    /** A screen declaring a focusable text field plus the safe handler overrides. */
    private static final class FakeTextScreen extends GuiScreen {
        private final GuiTextField field = new GuiTextField(0, null, 40, 60, 120, 20);

        FakeTextScreen() {
            field.setFocused(true); // so textboxKeyTyped actually applies text headlessly
        }

        @Override
        protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
            // no-op: keep focus, avoid mc deref
        }
    }

    // ---- ring buffer ---------------------------------------------------------

    @Test
    public void ringBufferKeepsOrderAndBoundsToCapacity() {
        GuiTrajectory log = new GuiTrajectory(3);
        assertEquals(3, log.capacity());
        assertTrue(log.recent().isEmpty());

        log.record(GuiTrajectory.KIND_CLICK, "b0", true, "m0", "S#1#0", "S#1#0");
        log.record(GuiTrajectory.KIND_TYPE, "t0", true, "m1", "S#1#0", "S#1#0");
        log.record(GuiTrajectory.KIND_PRESS, "key:1", false, "m2", "S#1#0", "S#1#0");
        log.record(GuiTrajectory.KIND_CLICK, "b1", true, "m3", "S#1#0", "S#2#0");

        List<GuiTrajectory.Entry> all = log.recent();
        // capacity 3 -> oldest ("b0") evicted, order preserved oldest-first
        assertEquals(3, all.size());
        assertEquals("t0", all.get(0).elementId());
        assertEquals("key:1", all.get(1).elementId());
        assertEquals("b1", all.get(2).elementId());
        assertFalse(all.get(1).ok());

        // recent(n) returns the most-recent n, oldest-first
        List<GuiTrajectory.Entry> last2 = log.recent(2);
        assertEquals(2, last2.size());
        assertEquals("key:1", last2.get(0).elementId());
        assertEquals("b1", last2.get(1).elementId());

        // negative / zero yield none
        assertTrue(log.recent(0).isEmpty());
        assertTrue(log.recent(-5).isEmpty());
    }

    // ---- instrumentation: actions are recorded -------------------------------

    @Test
    public void actionsRecordInOrderWithKindElementIdAndResult() throws Exception {
        GuiTrajectory log = new GuiTrajectory(16);
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, log);

        FakeScreen screen = new FakeScreen();
        screen.addButton(new GuiButton(1, 0, 0, 100, 20, "Go"));
        // capture the (epoch, fingerprint) the way the lead would from a snapshot
        int epoch = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(320, 240, 1, 320, 240), false).epoch();
        String fp = svc.fingerprint(screen);

        // 1) a successful click on b0
        GuiActions.Result click = actions.clickOnScreen(screen, epoch, fp, "b0", 0);
        assertTrue(click.message(), click.ok());
        assertEquals(1, screen.clicks); // real handler was driven

        // 2) a press of Escape (keyCode 1)
        GuiActions.Result press = actions.pressKeyOnScreen(screen, epoch, fp, (char) 0, 1);
        assertTrue(press.ok());
        assertEquals(1, screen.keys);

        // 3) a click on a missing element -> failure, still recorded
        GuiActions.Result miss = actions.clickOnScreen(screen, epoch, fp, "b99", 0);
        assertFalse(miss.ok());

        List<GuiTrajectory.Entry> log3 = log.recent();
        assertEquals(3, log3.size());

        GuiTrajectory.Entry e0 = log3.get(0);
        assertEquals(GuiTrajectory.KIND_CLICK, e0.kind());
        assertEquals("b0", e0.elementId());
        assertTrue(e0.ok());
        assertEquals("FakeScreen#1#0", e0.beforeFingerprint());
        assertEquals("FakeScreen#1#0", e0.afterFingerprint());

        GuiTrajectory.Entry e1 = log3.get(1);
        assertEquals(GuiTrajectory.KIND_PRESS, e1.kind());
        assertEquals("key:1", e1.elementId());
        assertTrue(e1.ok());

        GuiTrajectory.Entry e2 = log3.get(2);
        assertEquals(GuiTrajectory.KIND_CLICK, e2.kind());
        assertEquals("b99", e2.elementId());
        assertFalse(e2.ok());
    }

    @Test
    public void typeActionRecordedAsTypeKindWithFieldId() throws Exception {
        GuiTrajectory log = new GuiTrajectory(8);
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, log);

        FakeTextScreen screen = new FakeTextScreen();
        int epoch = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(320, 240, 1, 320, 240), false).epoch();
        String fp = svc.fingerprint(screen);

        GuiActions.Result r = actions.typeTextOnScreen(screen, epoch, fp, "t0", "hi", true);
        assertTrue(r.message(), r.ok());

        List<GuiTrajectory.Entry> entries = log.recent();
        assertEquals(1, entries.size());
        GuiTrajectory.Entry e = entries.get(0);
        assertEquals(GuiTrajectory.KIND_TYPE, e.kind());
        assertEquals("t0", e.elementId());
        assertTrue(e.ok());
        assertNotNull(e.beforeFingerprint());
    }

    @Test
    public void staleScreenActionIsRecordedAsFailure() throws Exception {
        GuiTrajectory log = new GuiTrajectory(8);
        GuiSnapshotService svc = new GuiSnapshotService();
        GuiActions actions = new GuiActions(null, svc, log);

        FakeScreen screen = new FakeScreen();
        screen.addButton(new GuiButton(1, 0, 0, 100, 20, "Go"));
        int epoch = svc.buildSnapshot(screen, false,
                GuiSnapshotService.viewport(320, 240, 1, 320, 240), false).epoch();
        String fp = svc.fingerprint(screen);

        // structure drifts under the captured fingerprint -> guard rejects
        screen.addButton(new GuiButton(2, 0, 30, 100, 20, "More"));

        GuiActions.Result r = actions.clickOnScreen(screen, epoch, fp, "b0", 0);
        assertFalse(r.ok());
        assertEquals(0, screen.clicks); // handler NOT driven

        List<GuiTrajectory.Entry> entries = log.recent();
        assertEquals(1, entries.size());
        assertEquals(GuiTrajectory.KIND_CLICK, entries.get(0).kind());
        assertFalse(entries.get(0).ok());
    }
}
