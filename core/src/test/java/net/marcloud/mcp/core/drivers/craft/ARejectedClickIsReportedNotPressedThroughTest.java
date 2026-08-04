package net.marcloud.mcp.core.drivers.craft;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The hazard this whole controller exists for: a click the server refuses LOCKS the window, and every
 * later click is dropped in silence.
 *
 * <p><b>The mechanism, from vanilla.</b> The 1.8.9 server never validates the action number on a click.
 * Acceptance turns on {@code ItemStack.areItemStacksEqual(packet.getClickedItem(), its own slotClick
 * result)} ({@code NetHandlerPlayServer:1029}). On mismatch it sends {@code S32(accepted=false)},
 * resyncs the window to its own state, and calls {@code setCanCraft(player, false)} (:1041). The entire
 * click body is gated on {@code getCanCraft} (:1012) with no else branch, so from then on EVERY click on
 * that window is silently dropped -- no error, no reply -- until the client echoes the action number
 * back in C0F (:1142-1147).
 *
 * <p><b>Why a naive controller reports success here.</b> {@code PlayerControllerMP.windowClick} applies
 * {@code slotClick} to the CLIENT container first (:537) and only then queues the packet, so a locked
 * window keeps moving on the client. The client's matrix fills, the client's result slot computes a
 * perfectly good output from it, and every {@code windowClick} returns normally. Believing any of that
 * is the "reports healthy while being wrong" defect in its purest form. The only synchronous evidence
 * is re-reading the window AFTER the resync has landed, which is what {@link CraftController#SETTLE_TICKS}
 * buys and what {@link FakeCraftWindow#resyncDelayTicks} models.
 */
public class ARejectedClickIsReportedNotPressedThroughTest {

    private static final int TICK_BOUND = 60;

    /**
     * A 2x2 craft whose THIRD click the server refuses.
     *
     * <p>Mid-placement on purpose. Click 1 is the pick-up and click 2 the first square, so both sides
     * agree on those; the refusal then lands while the controller still has three squares to fill and a
     * loaded cursor, which is the state a resync hands back and the messiest one to clean up. Rejecting
     * click 1 would leave nothing placed and nothing on the cursor -- a much easier path that would let a
     * broken cleanup pass.
     */
    private static FakeCraftWindow rejectingAtThirdClick() {
        FakeCraftWindow win = FakeCraftWindow.playerWindow().carrying(0, "planks", 0, 4);
        win.rejectAtClick = 3;
        return win;
    }

    @Test
    public void aRejectedPlacementIsReportedAndTheResultIsNeverClicked() {
        FakeCraftWindow win = rejectingAtThirdClick();
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("must terminate rather than wait forever", out.terminal());
        assertFalse("a craft the server refused is not a success: " + out.message(), out.ok());
        assertTrue("the window must have locked for this test to be testing anything", win.locked);
        assertTrue("the message must say the server refused the placement: " + out.message(),
            out.message().contains("did not accept the placement"));

        // THE load-bearing assertion. Pressing on would queue a take into a void and then report a
        // finished craft. Clicks alone cannot show this -- cleanup clicks too -- so the result slot,
        // which is the click that would claim the output, is counted separately.
        assertEquals("the result slot must never be clicked on a locked window: every click there is "
                + "dropped in silence and the craft would be reported as done",
            0, win.resultClicks);
        assertEquals("and no crafting table may exist on the server's side", 0,
            win.serverStoredCount("crafting_table", 0));
    }

    @Test
    public void theRejectionPathLeavesNothingInTheMatrixOrOnTheCursor() {
        // The terminal-path rule, on the path that makes it hardest. When the resync lands, the
        // controller is handed a matrix holding one plank AND a cursor holding three -- the server's own
        // state as of the last click it accepted. Both are dropped on the floor when the window closes
        // (ContainerPlayer:83-98 for the matrix, Container:516-525 for the cursor) and both are invisible
        // to world_view, which reads mainInventory only. So a model would learn about them as items
        // missing from its pockets with nothing in the report to explain it.
        //
        // This is also the ordering trap: with a loaded cursor, a LEFT click on a matrix cell holding
        // the same item DEPOSITS into the cell rather than lifting from it (Container:337-359). A
        // cleanup that drained the matrix before parking the cursor would therefore push three more
        // planks INTO the grid and leave four stranded where it found one.
        FakeCraftWindow win = rejectingAtThirdClick();
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertFalse("must have failed: " + out.message(), out.ok());
        assertEquals("no ingredient may be left in the matrix on a terminal path",
            "", win.matrixContents());
        assertNull("and nothing may be left on the cursor", win.cursor());
        assertFalse("so the outcome must not report anything stranded: " + out.message(),
            out.message().contains("stranded"));
        assertEquals("every plank the client can still see must be back in the inventory",
            4, win.storedCount("planks", 0));
    }

    @Test
    public void theClientsOwnViewOfARejectedCraftIsNotMistakenForTheServersAgreement() {
        // Pins the reason SETTLE_TICKS exists. Read the window in the same pass as the clicks and the
        // controller sees its own optimistic writes: a full matrix and a result slot the CLIENT computed
        // from it. The fake delays the resync by a tick for exactly this, so a controller that verified
        // immediately would find its own work and call the craft accepted.
        FakeCraftWindow win = rejectingAtThirdClick();
        win.resyncDelayTicks = 3;
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        CraftOutcome out = FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertFalse("a resync arriving three ticks late must still be caught: " + out.message(),
            out.ok());
        assertTrue("and the settle wait must be long enough to see it -- " + CraftController.SETTLE_TICKS
                + " ticks against a 3-tick delay: " + out.message(),
            out.message().contains("did not accept the placement"));
        assertEquals("no crafting table on the server", 0, win.serverStoredCount("crafting_table", 0));
        assertEquals("nothing stranded in the matrix", "", win.matrixContents());
        assertNull(win.cursor());
    }

    @Test
    public void theServerDroppingClicksInSilenceIsWhatMakesThisUndetectableWithoutARoundTrip() {
        // Documents the fake's own model, so a later change that made the fake apply every click
        // server-side -- which would quietly turn all three tests above into tests of nothing -- fails
        // here instead of passing everywhere.
        FakeCraftWindow win = rejectingAtThirdClick();
        CraftController c = new CraftController(CraftBench.find("crafting_table", 0));

        FakeCraftWindow.drive(c, win, TICK_BOUND);

        assertTrue("the fake must have dropped clicks server-side; if it did not, the window was never "
                + "really locked and nothing here was under test", win.droppedClicks > 0);
        assertTrue("and the client must have applied more clicks than the server accepted, which is the "
                + "divergence windowClick creates (PlayerControllerMP:537)",
            win.clicks > win.clicks - win.droppedClicks);
    }
}
