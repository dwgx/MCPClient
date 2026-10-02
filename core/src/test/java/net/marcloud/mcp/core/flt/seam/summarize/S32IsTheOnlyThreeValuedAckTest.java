package net.marcloud.mcp.core.flt.seam.summarize;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import org.junit.Test;

/**
 * The container protocol's only three-valued acknowledgement must be visible.
 *
 * <p>{@code S32PacketConfirmTransaction} was the one high-value packet with no summarizer, and
 * the absence is not a missing feature -- it is a missing TRUTH. Three outcomes exist and an
 * operator could previously see none of them:
 *
 * <ul>
 *   <li><b>accepted</b> -- the server re-ran the click and {@code areItemStacksEqual} agreed, so
 *       the client and server hold the same inventory. Proves this click applied, and says
 *       nothing about whether the CRAFT worked -- that arrives as a slot change.
 *   <li><b>rejected</b> -- the server disagreed, sent {@code S32(false)} and additionally called
 *       {@code setCanCraft(false)}. From that moment EVERY subsequent click in that window is
 *       dropped until a C0F unlock, so this is a hard error, not a warning.
 *   <li><b>silence</b> -- no S32 at all means the windowId did not match and the whole packet was
 *       discarded without a reply. The absence is itself the third outcome.
 * </ul>
 *
 * <p>With no projection, {@code packet_view} and the journal were blind to the first two, so
 * {@code do_click_slot}'s entire correctness story rested on a reply nobody could read.
 */
public class S32IsTheOnlyThreeValuedAckTest {

    /** The registry the tools actually use, not one assembled here. */
    private static PacketSummarizerRegistry registry() {
        return PacketSummarizerRegistry.defaults();
    }

    private static Map<String, Object> project(Object packet) {
        Map<String, Object> out = registry().projectStructured(packet);
        if (out == null || out.isEmpty()) {
            throw new AssertionError("S32 produced no projection, so the server's answer to a "
                    + "click is invisible: the whole point of this packet is that it is the only "
                    + "three-valued acknowledgement the container protocol has");
        }
        return out;
    }

    private static net.minecraft.network.play.server.S32PacketConfirmTransaction s32(
            int win, short action, boolean accepted) {
        return new net.minecraft.network.play.server.S32PacketConfirmTransaction(win, action, accepted);
    }

    @Test
    public void anAcceptedReplySaysTheServerAgreedAndSaysWhatThatDoesNotMean() {
        Map<String, Object> out = project(s32(7, (short) 42, true));

        assertEquals(7, out.get("windowId"));
        assertEquals(42, out.get("actionNumber"));
        assertEquals(Boolean.TRUE, out.get("accepted"));
        assertTrue("the wording must not overclaim: an accepted click is not a successful CRAFT, "
                        + "that arrives as a slot change -- got " + out.get("meaning"),
                String.valueOf(out.get("meaning")).contains("this click")
                        || String.valueOf(out.get("meaning")).contains("applied"));
        assertFalse("nor may it imply a recipe ran",
                String.valueOf(out.get("meaning")).contains("crafted"));
    }

    @Test
    public void aRejectedReplyNamesTheLockBecauseThatIsTheConsequence() {
        // The difference that matters: false does not mean "this click did not happen", it means
        // the window is now refusing every later click until a C0F unlock. A projection that
        // only said accepted=false leaves the caller retrying into a locked window.
        Map<String, Object> out = project(s32(7, (short) 43, false));

        assertEquals(Boolean.FALSE, out.get("accepted"));
        String meaning = String.valueOf(out.get("meaning"));
        // Case-insensitively and on the SUBSTANCE: a reader must be able to tell that retrying is
        // pointless, which is the one thing this packet tells them that no other packet does.
        String lower = meaning.toLowerCase(java.util.Locale.ROOT);
        assertTrue("must say the window is locked: " + meaning, lower.contains("locked"));
        assertTrue("must say how it unlocks, or a caller retries forever: " + meaning,
                lower.contains("c0f"));
    }

    @Test
    public void theTwoOutcomesAreDistinguishableInTheTextSummary() {
        // The text form is what an operator reads in a log, so a bare true/false there loses the
        // same distinction the projection carries.
        PacketSummarizerRegistry r = registry();
        String yes = r.summarize(s32(0, (short) 0, true));
        String no = r.summarize(s32(0, (short) 0, false));

        assertTrue(yes, yes.contains("ACCEPTED"));
        assertTrue(no, no.contains("REJECTED"));
        assertFalse("the rejected form must not read as a mere negative", no.contains("NOT_ACCEPTED"));
    }

    @Test
    public void theWindowAndActionAreCarriedSoAStaleReplyCanBeToldFromACurrentOne() {
        // Silence is the third outcome -- a windowId mismatch gets no reply at all. A reply
        // carrying a windowId and action number is what lets a caller tell "answered" from
        // "answered something else", so both must survive the projection.
        Map<String, Object> out = project(s32(3, (short) 91, true));
        assertNotNull(out.get("windowId"));
        assertNotNull(out.get("actionNumber"));
    }

    @Test
    public void itReachesTheRegistryTheToolsActuallyRead() {
        // Registration, not just the class existing: `packet_view` resolves through
        // `defaults()`, and a summarizer that is written but never registered there is the same
        // absence as no summarizer at all -- which is the defect being closed.
        assertFalse("packet_view resolves through defaults(); a summarizer registered somewhere "
                        + "else is still invisible",
                registry().projectStructured(s32(1, (short) 1, true)).isEmpty());
    }

    @Test
    public void theClickItAnswersIsStillProjectedSoTheTwoCanBeCorrelated() {
        // The ack is only half the story. If the C0E side went away the reply would be an
        // answer to nothing, so both halves of the contract are pinned here.
        PacketSummarizerRegistry r = registry();
        assertFalse("the C0E half must stay projected",
                r.projectStructured(new net.minecraft.network.play.client.C0EPacketClickWindow(
                        0, 0, 0, 0, null, (short) 0)).isEmpty());
        assertFalse("and the slot change that carries the outcome",
                r.projectStructured(new net.minecraft.network.play.server.S2FPacketSetSlot(
                        0, 0, null)).isEmpty());
    }
}
