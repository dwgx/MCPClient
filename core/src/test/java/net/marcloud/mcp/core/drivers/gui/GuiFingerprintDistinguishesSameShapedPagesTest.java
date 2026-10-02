package net.marcloud.mcp.core.drivers.gui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.junit.Test;

/**
 * The fingerprint is the only thing standing between a stale plan and a wrong click.
 *
 * <p>The lead captures {@code (epoch, fingerprint)} from a snapshot, reasons about it, then acts.
 * {@code validate} refuses the action if the screen changed in between. That guard has one blind
 * spot by construction: a screen that swaps its CONTENT while keeping the same OBJECT. A QML
 * {@code Loader} swapping pages does exactly this, and so does any screen that rebuilds its
 * buttons in place. The epoch does not move, because the identity is unchanged.
 *
 * <p>Counting elements does not close it either. Two pages with four controls each produce the
 * same counts, so the fingerprint matched and a click aimed at page A landed on page B — which
 * is a silent wrong action, the same failure class as the map-shaped {@code act_set} that
 * reported {@code ACTIVE} while the player never moved.
 *
 * <p>So the fingerprint carries an order-sensitive hash of the element labels. These tests pin
 * the three properties that actually matter, and each one fails on the count-only fingerprint.
 */
public class GuiFingerprintDistinguishesSameShapedPagesTest {

    /** A screen whose buttons can be swapped without the screen object changing. */
    private static class SwappableScreen extends GuiScreen {
        void setButtons(String... labels) {
            buttonList.clear();
            for (int i = 0; i < labels.length; i++) {
                buttonList.add(new GuiButton(i, 10, 10, 100, 20, labels[i]));
            }
        }

        void setOrder(String... labels) {
            buttonList.clear();
            for (int i = 0; i < labels.length; i++) {
                buttonList.add(new GuiButton(i, 10, 10, 100, 20, labels[i]));
            }
        }
    }

    /** A sibling screen type, used only to prove the class name is a fingerprint component. */
    private static final class OtherScreen extends SwappableScreen {
    }

    private final GuiSnapshotService svc = new GuiSnapshotService();

    /**
     * The bug this exists for: same screen object, same element count, different elements.
     *
     * <p>Fails on the old {@code name#count#count} fingerprint with {@code "expected:
     * not equal but was: SwappableScreen#4#0"} — the exact silent wrong-click.
     */
    @Test
    public void twoPagesWithTheSameElementCountAreNotTheSameScreen() {
        SwappableScreen s = new SwappableScreen();

        s.setButtons("Home", "Modules", "Settings", "Quit");
        String pageA = svc.fingerprint(s);

        // A Loader swap: same object, same number of controls, entirely different controls.
        s.setButtons("Combat", "Movement", "Render", "World");
        String pageB = svc.fingerprint(s);

        assertNotEquals("a content swap inside the same screen object must change the "
                        + "fingerprint, or a click planned against one page lands on the other",
                pageA, pageB);
    }

    /**
     * The fingerprint must not be so eager that ordinary stability breaks.
     *
     * <p>A guard that fires on every call is a guard the agent learns to bypass by re-snapshotting
     * before every action, which is exactly the discipline the guard exists to supply.
     */
    @Test
    public void anUnchangedScreenKeepsItsFingerprint() {
        SwappableScreen s = new SwappableScreen();
        s.setButtons("Home", "Modules", "Settings", "Quit");
        String first = svc.fingerprint(s);
        for (int i = 0; i < 5; i++) {
            assertEquals("recomputing the fingerprint on an unchanged screen must be stable",
                    first, svc.fingerprint(s));
        }
    }

    /**
     * Two buttons swapped in place are a different arrangement, not the same screen.
     *
     * <p>Order is load-bearing: the fingerprint describes what the agent saw and intends to
     * click, and index 0 versus index 3 are different targets.
     */
    @Test
    public void reorderingTheSameButtonsIsADifferentScreen() {
        SwappableScreen s = new SwappableScreen();
        s.setOrder("A", "B", "C", "D");
        String before = svc.fingerprint(s);
        s.setOrder("D", "C", "B", "A");
        assertNotEquals("the same controls in a different order are a different screen",
                before, svc.fingerprint(s));
    }

    @Test
    public void aScreenWithNoButtonsStillFingerprints() {
        SwappableScreen s = new SwappableScreen();
        String fp = svc.fingerprint(s);
        assertEquals("an empty screen is a stable, matchable fingerprint", fp, svc.fingerprint(s));
        assertEquals("and it still names the screen so a null screen is distinguishable",
                true, fp.startsWith(s.getClass().getSimpleName()));
    }

    /**
     * Two different screen classes with identical button sets must not collide.
     *
     * <p>Retained from the pre-existing contract: the class name is the first component, and
     * dropping it would make every menu look like every other menu.
     */
    @Test
    public void differentScreenClassesDoNotCollide() {
        SwappableScreen a = new SwappableScreen();
        a.setButtons("One", "Two");
        String fpA = svc.fingerprint(a);

        // GuiScreen is abstract and buttonList is protected, so the second class under test is
        // a sibling subclass. What matters is that the class name is a fingerprint component:
        // dropping it would make every menu look like every other menu.
        OtherScreen plain = new OtherScreen();
        plain.setButtons("One", "Two");

        assertNotEquals("two screens with byte-identical button sets are still different "
                + "screens", fpA, svc.fingerprint(plain));
    }

    @Test
    public void aNullScreenHasTheStableNoScreenFingerprint() {
        assertEquals("none#0#0#0", svc.fingerprint(null));
    }

    /**
     * Coordinates must not contribute to the token; content and scroll must.
     *
     * <p>Proved by moving the list's rectangle instead of by reading this file's
     * source: the same rows, the same scroll, the same labels, only a different
     * layout. A token that had picked up a position would move here, and a token that
     * had picked up the scroll would not move in
     * {@link GuiListRowsTest#aScrollMovesTheSnapshotFingerprintButNotTheEpoch}, which
     * is where the scroll is proved.
     */
    @Test
    public void relayingOutAListDoesNotMoveTheFingerprint() throws Exception {
        ScrolledScreen s = new ScrolledScreen();
        String before = svc.fingerprint(s);
        s.relayout();
        assertEquals("only the rectangle moved -- same list, same rows, same scroll -- so "
                        + "the structural token must not move with it", before,
                svc.fingerprint(s));
    }

    /**
     * A screen owning one real {@code net.minecraft.client.gui.GuiSlot}, so the test
     * can move that list's own rectangle without touching the extractor.
     */
    static final class ScrolledScreen extends net.minecraft.client.gui.GuiScreen {
        final net.minecraft.client.gui.GuiSlot list =
                new net.minecraft.client.gui.GuiSlot(null, 200, 100, 10, 90, 20) {
                    @Override
                    protected int getSize() {
                        return 5;
                    }

                    @Override
                    protected void elementClicked(int slotIndex, boolean dbl, int mx, int my) {
                    }

                    @Override
                    protected boolean isSelected(int slotIndex) {
                        return false;
                    }

                    @Override
                    protected void drawBackground() {
                    }

                    @Override
                    protected void drawSlot(int entryID, int x, int y, int height, int mx, int my) {
                    }
                };

        void relayout() throws Exception {
            java.lang.reflect.Field top = net.minecraft.client.gui.GuiSlot.class
                    .getDeclaredField("top");
            top.setAccessible(true);
            top.setInt(list, 40);
            java.lang.reflect.Field bottom = net.minecraft.client.gui.GuiSlot.class
                    .getDeclaredField("bottom");
            bottom.setAccessible(true);
            bottom.setInt(list, 120);
        }
    }

    @Test
    public void everyButtonInTheListContributesToTheToken() {
        SwappableScreen s = new SwappableScreen();
        s.setButtons("same", "same", "same", "same");
        String allSame = svc.fingerprint(s);
        s.setButtons("same", "same", "same", "DIFFERENT");
        assertNotEquals("changing only the LAST button must still change the fingerprint -- a "
                        + "token that ignored late elements would miss exactly the elements an "
                        + "agent is most likely to have just added", allSame,
                svc.fingerprint(s));
        // (a line here used to read assertEquals(List.of().size(), 0), which is assertEquals(0, 0)
        //  and can never fail. The property above is the one that carries the weight.)
    }
}
