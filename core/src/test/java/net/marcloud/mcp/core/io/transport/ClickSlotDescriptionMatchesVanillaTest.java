package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.Test;

/**
 * Teeth for a tool description that told the model something FALSE about the protocol, which is
 * worse than the merely-undocumented fields {@code GridSemanticsAreDocumentedTest} guards: an
 * absent legend leaves the model uncertain, a wrong one makes it confidently wrong.
 *
 * <p>do_click_slot claimed actionNumber "must match the container's counter" and that "a wrong
 * actionNumber makes the server reject the transaction". Neither is true of vanilla 1.8.9:
 *
 * <ul>
 *   <li>{@code NetHandlerPlayServer.processClickWindow} (:1027-1050) never compares actionNumber
 *       to anything. It passes it to {@code S32PacketConfirmTransaction} in BOTH the accept
 *       (:1031) and reject (:1040) branches -- a pure echo.</li>
 *   <li>Acceptance turns on {@code ItemStack.areItemStacksEqual(packet.getClickedItem(), }
 *       {@code openContainer.slotClick(...))} (:1029) -- the ITEM claim, nothing else. The
 *       counter the old text meant is {@code Container.getNextTransactionID} (:561), which is
 *       incremented CLIENT-side by {@code PlayerControllerMP.windowClick} (:536); the server
 *       holds no counter to match against.</li>
 *   <li>actionNumber is validated in exactly one place in the whole protocol:
 *       {@code processConfirmTransaction} (:1142-1147) compares the C0F uid to the short stashed
 *       at :1039.</li>
 * </ul>
 *
 * <p>So the old text inverted the actual hazard. A stale actionNumber is harmless; a wrong item
 * claim makes the server call {@code setCanCraft(this.playerEntity, false)} (:1041), and since
 * the whole click body is gated on {@code getCanCraft} (:1012), EVERY later click on that window
 * is silently dropped -- no error, no reply. Believing the old description, a caller would tend
 * the harmless field and treat the dangerous one as an afterthought ("omit for an empty slot").
 *
 * <p>These assertions are string-shaped because a description IS a string; what keeps them from
 * being decoration is that half of them are NEGATIVE -- they fail if the specific false claims
 * come back, in any of the paraphrases the old text used.
 */
public class ClickSlotDescriptionMatchesVanillaTest {

    private static Tool tool(String name) {
        ToolRegistry reg = new ToolRegistry(new ToolContext(null, null, null, null, null));
        for (SyncToolSpecification spec : reg.all()) {
            if (spec.tool().name().equals(name)) {
                return spec.tool();
            }
        }
        throw new AssertionError("tool not found: " + name);
    }

    @Test
    public void theDescriptionNoLongerClaimsTheServerChecksActionNumber() {
        String desc = tool("do_click_slot").description();
        assertFalse("vanilla has no server-side counter to match: getNextTransactionID is "
                + "incremented by the CLIENT (PlayerControllerMP:536)",
                desc.contains("must match the container's counter"));
        assertFalse("processClickWindow never reads actionNumber except to echo it",
                desc.contains("wrong actionNumber makes the server reject"));
        assertFalse("no paraphrase of it either", desc.contains("transaction id, must match"));
    }

    @Test
    public void theDescriptionSaysActionNumberIsNotValidatedOnThisPacket() {
        String desc = tool("do_click_slot").description();
        assertTrue("the model needs to know the field is inert here, or it will hunt for a "
                + "counter that does not exist", desc.contains("NOT validated on this packet"));
        assertTrue("and that a stale value costs nothing", desc.contains("stale"));
    }

    @Test
    public void theDescriptionNamesTheItemClaimAsWhatDecidesAcceptance() {
        String desc = tool("do_click_slot").description();
        assertTrue("areItemStacksEqual on the clicked item is the ONLY accept/reject test",
                desc.contains("ITEM CLAIM"));
        assertTrue("the item must be described as the click's RESULT, since the server compares "
                + "against its own slotClick return, not against the slot",
                desc.contains("RESULT"));
    }

    /**
     * {@code Container.slotClick} assigns its return value in exactly two branches -- :271 for
     * mode 1 (a copy of the stack {@code transferStackInSlot} reports) and :296 for mode 0 (the
     * slot's contents before the click). Modes 2,3,4,5,6 fall through every assignment and return
     * the initial null of :142. Sending an item for those is therefore an automatic mismatch, and
     * an automatic window lock. Each mode the schema advertises must be accounted for.
     */
    @Test
    public void everyClickModeTheSchemaAdvertisesHasAStatedItemRule() {
        String desc = tool("do_click_slot").description();
        assertTrue("modes 2-6 return null from slotClick, so item MUST be omitted",
                desc.contains("modes 2,3,4,5,6") && desc.contains("ALWAYS empty"));
        assertTrue("mode 0's rule is the slot's pre-click contents", desc.contains("mode 0"));
        assertTrue("mode 1's rule differs from mode 0's and must be stated separately",
                desc.contains("mode 1"));
        assertTrue("omission must be named as the action for the empty-result modes",
                desc.contains("OMIT"));
    }

    @Test
    public void theDescriptionStatesThatRejectionSilentlyLocksTheWindow() {
        String desc = tool("do_click_slot").description();
        // The real hazard, and the one the old text omitted entirely: setCanCraft(false) at
        // NetHandlerPlayServer:1041 gates the whole click body at :1012 thereafter.
        assertTrue("a caller must know later clicks vanish rather than error",
                desc.contains("SILENTLY DROPPED"));
        assertTrue("and that the rejected click was NOT undone -- slotClick already ran at :1027 "
                + "before the comparison at :1029", desc.contains("ALREADY applied"));
        assertTrue("the escape hatch is the C0F echo (:1142-1147), the one place actionNumber is "
                + "actually checked", desc.contains("C0F"));
        assertTrue("windowId 0 is the case that cannot be reopened: inventoryContainer is built "
                + "once per player (EntityPlayer:181)", desc.contains("windowId 0"));
    }

    /**
     * Derived rather than hand-listed, in the spirit of {@code GridSemanticsAreDocumentedTest}:
     * every argument the schema accepts must appear in the description. Adding a knob without a
     * legend fails here.
     */
    @Test
    public void everyInputArgumentIsMentionedInTheDescription() {
        Tool t = tool("do_click_slot");
        String desc = t.description();
        Object props = ((Map<?, ?>) t.inputSchema()).get("properties");
        assertTrue("schema properties must be a map to derive from", props instanceof Map);
        for (Object key : ((Map<?, ?>) props).keySet()) {
            assertTrue("do_click_slot accepts '" + key + "' but the description never mentions it",
                    desc.contains(String.valueOf(key)));
        }
    }

    /** The tool reports "sent", never "accepted" -- so the description must not promise more. */
    @Test
    public void theDescriptionAdmitsItOnlyReportsThePacketWasSent() {
        String desc = tool("do_click_slot").description();
        assertTrue("sendTyped returns ok(\"sent ...\") with no server acknowledgement, so a "
                + "success result is not evidence the click landed",
                desc.contains("SENT"));
        assertTrue("the caller needs a stated way to actually check",
                desc.contains("read_inventory"));
    }

    /**
     * The other silent no-op, distinct from the lock: {@code processClickWindow}'s guard at :1012
     * also requires {@code openContainer.windowId == packet.getWindowId()}, so a mismatched
     * windowId does nothing whatsoever. The schema's "0 = own inventory" invites exactly this
     * mistake while a chest is open.
     */
    @Test
    public void theDescriptionWarnsAboutTheMismatchedWindowIdNoOp() {
        String desc = tool("do_click_slot").description();
        assertTrue("a windowId that is not the currently open window is dropped at the guard",
                desc.contains("currently has open"));
        assertTrue("named concretely, because the schema's '0 = own inventory' invites it",
                desc.contains("while a chest is open"));
    }

    /** Sanity: the requires-tag convention the rest of the kernel uses is still intact here. */
    @Test
    public void theRequiresTagIsPreserved() {
        assertTrue(tool("do_click_slot").description()
                .startsWith("[requires: connected-to-server, container open]"));
    }
}
