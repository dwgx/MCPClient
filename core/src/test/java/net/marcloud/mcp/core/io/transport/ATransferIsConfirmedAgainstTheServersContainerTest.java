package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.junit.Test;

/**
 * {@code transfer_item} must confirm against the server's container, and must be gated like the
 * click it is built on.
 *
 * <p><b>Why this tool exists at all.</b> {@code do_click_slot} can already speak mode 1, but what
 * the server compares is the RESULT of the click, so a caller has to compute the server's own
 * {@code Container.transferStackInSlot} to fill in the claim. Getting that wrong is not a refusal:
 * the server applies the click, resyncs every slot back over the client, and LOCKS the window,
 * after which every later click is silently dropped. That is the whole reason chests, furnaces and
 * crafting tables were unusable, and it is why the confirmation has to be a re-read rather than a
 * receipt.
 *
 * <p><b>What is pinned here.</b> Without a live client the only reachable handler path is "no
 * container", and that is precisely the branch worth pinning: it must refuse, and it must refuse
 * for the reason a real caller needs -- that there is nothing to move and where to look -- rather
 * than reporting a successful transfer. The write-confirmation loop and the gate tables are covered
 * by {@code RegisteredBuiltinGateCoverageTest}, {@code SendToolsW6Test} and
 * {@code PolicySideTableDriftTest}, which is why those three went red when the tool was added and
 * had to be answered rather than worked around.
 */
public final class ATransferIsConfirmedAgainstTheServersContainerTest {

    private static ToolRegistry registry() {
        return new ToolRegistry(new ToolContext(null, null, null, null, null));
    }

    private static SyncToolSpecification tool(ToolRegistry reg) {
        for (SyncToolSpecification s : reg.all()) {
            if (s.tool().name().equals("transfer_item")) {
                return s;
            }
        }
        throw new AssertionError("transfer_item is not in all(): a tool that never reaches the "
                + "registry is one no model can call");
    }

    private static String call(ToolRegistry reg, Map<String, Object> args) {
        CallToolResult r = tool(reg).callHandler()
                .apply(null, new CallToolRequest("transfer_item", args));
        String text = "";
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                text = t.text();
            }
        }
        return (Boolean.TRUE.equals(r.isError()) ? "ERROR " : "OK ") + text;
    }

    /**
     * The container's numbering is not the inventory's, and confusing them is silent.
     *
     * <p>Caught on a live client: {@code world_view} listed a log at inventory index 0 and this
     * tool answered "slot 0 holds nothing". Both were correct. {@code ContainerPlayer} slot 0 is
     * the CRAFTING OUTPUT; the hotbar's slot 0 is container slot 36, and inventory index N is
     * container slot N+9. A caller that reads an inventory listing and passes the number straight
     * through gets a confident, wrong answer -- so the offset is stated rather than guessed at.
     */
    @Test
    public void theContainerSlotNumberingIsNamedWhenASlotIsEmpty() {
        String reply = call(registry(), Map.of("windowId", 0, "slotId", 0));

        assertTrue("a refusal must say the numbering is the container's, not the inventory's: "
                + reply, reply.contains("CONTAINER"));
        assertTrue("and it must give the offset, because 'it is empty' alone sends the caller "
                + "back to read the same container and get the same answer: " + reply,
                reply.contains("36"));
    }

    @Test
    public void withNoContainerOpenItRefusesRatherThanClaimingAMove() {
        String reply = call(registry(), Map.of("windowId", 0, "slotId", 36));

        assertTrue("with nothing open there is nothing to move, and the honest reply is an error: "
                + reply, reply.startsWith("ERROR "));
        assertTrue("it must say the slot held nothing, because that is the fact it checked: "
                + reply, reply.contains("holds nothing"));
        assertTrue("and it must name where to look instead, so a caller is not left guessing: "
                + reply, reply.contains("container") || reply.contains("window"));
    }

    @Test
    public void itRefusesToGuessRatherThanSendingABarePacket() {
        ToolRegistry reg = registry();
        assertTrue("a missing windowId is refused",
                call(reg, Map.of("slotId", 36)).startsWith("ERROR "));
        assertTrue("a missing slotId is refused",
                call(reg, Map.of("windowId", 0)).startsWith("ERROR "));
    }

    /**
     * The decision that makes the tool honest, tested without a client.
     *
     * <p>The polling loop needs a live container, so a rule living inside it is a rule nobody
     * checks: replacing "re-read and compare" with "report that it was sent" ran green before this
     * existed. {@code verdictFor} is that rule, and it has one job -- a slot that did not change
     * must never read as a move, because the packet having gone out says nothing about the server
     * having applied it.
     */
    @Test
    public void anUnchangedSlotIsNeverAMove() {
        var same = slot("minecraft:dirt", 4);
        assertEquals("the slot is identical, so the server's container did not change: a claim of "
                        + "success here is the audit's section 2.5 failure exactly",
                ToolRegistry.TransferVerdict.NOT_MOVED,
                ToolRegistry.verdictFor(same, same));

        assertEquals("the stack shrank: moved",
                ToolRegistry.TransferVerdict.MOVED,
                ToolRegistry.verdictFor(same, slot("minecraft:dirt", 0)));

        assertEquals("a different item is a change: moved",
                ToolRegistry.TransferVerdict.MOVED,
                ToolRegistry.verdictFor(same, slot("minecraft:stone", 1)));

        assertEquals("the same item at a different count is a change: moved",
                ToolRegistry.TransferVerdict.MOVED,
                ToolRegistry.verdictFor(same, slot("minecraft:dirt", 3)));
    }

    @Test
    public void anUnreadableSlotClaimsNothing() {
        assertEquals("a slot that could not be read is not 'unchanged' and not 'moved'; folding "
                        + "either is how a failed read becomes a reported result",
                ToolRegistry.TransferVerdict.UNREADABLE,
                ToolRegistry.verdictFor(null, slot("minecraft:dirt", 1)));
        assertEquals(ToolRegistry.TransferVerdict.UNREADABLE,
                ToolRegistry.verdictFor(slot("minecraft:dirt", 1), null));
    }

    @Test
    public void theVerdictSetHasNoSentOutcome() {
        for (var v : ToolRegistry.TransferVerdict.values()) {
            assertNotEquals("'the packet was sent' is a fact about the wire, not an outcome, and "
                            + "an enum that can express it is an enum that will eventually be "
                            + "returned as one: " + v,
                    "SENT", v.name());
        }
    }

    private static ToolRegistry.SlotView slot(String item, int count) {
        return new ToolRegistry.SlotView(item, count);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void itsDescriptionStatesThatTheResultIsReadBackNotAssumed() {
        String d = tool(registry()).tool().description();
        assertNotNull(d);
        assertTrue("a caller must know this tool re-reads the server's container, because that is "
                + "the difference between it and do_click_slot: " + d,
                d.contains("SERVER") || d.contains("afterwards"));
        assertTrue("and it must warn about the window LOCK, which is the failure a hand-built "
                + "shift-click produces and which is silent by design: " + d, d.contains("LOCKED"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void itsSchemaNamesTheSlotItMovesFromAndRequiresBothArguments() {
        Map<String, Object> schema = (Map<String, Object>) tool(registry()).tool().inputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull("no properties means nothing can be validated: " + schema, props);
        assertNotNull("the slot to move FROM must be named, since that is the whole argument: "
                + props, props.get("slotId"));
        assertNotNull(props.get("windowId"));
        List<String> required = (List<String>) schema.get("required");
        assertNotNull("a transfer with no windowId would default to 0 and silently move the wrong "
                + "thing", required);
        assertTrue(required.contains("windowId") && required.contains("slotId"));
    }
}
