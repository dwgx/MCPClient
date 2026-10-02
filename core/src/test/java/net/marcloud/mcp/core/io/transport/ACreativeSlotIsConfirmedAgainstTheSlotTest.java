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
 * {@code do_set_creative_slot} must confirm against the slot, and must have no way to say "sent".
 *
 * <p><b>The bug this pins.</b> The handler's last line was
 * {@code return sendTyped(new C10PacketCreativeInventoryAction(slot, stack), "set_creative_slot " + slot)},
 * so it reported the SEND and stopped. Measured on a live client: {@code send_chat} with
 * {@code /gamemode 1}, then {@code do_set_creative_slot {"slot":0,"item":"minecraft:dirt","count":8}}
 * answered {@code sent set_creative_slot 0} and the inventory then contained no dirt. This is
 * section 2.5 of the repo's own audit, unclosed in this one tool.
 *
 * <p><b>Why the reply cannot be "sent".b> {@code NetHandlerPlayServer.processCreativeInventoryAction:1078}
 * wraps its entire body in {@code theItemInWorldManager.isCreative()}, and inside it line 1104
 * requires {@code slotId >= 1 && slotId < 45}. A packet outside creative mode, or at slot 0, is not
 * refused, not answered and not logged — it cannot do anything at all. So the old reply asserted
 * success for a write the server was structurally incapable of performing, and the agent could not
 * tell that from a write that worked.
 *
 * <p><b>What is pinned here.</b> {@link ToolRegistry#creativeVerdictFor} is a pure function, for
 * the same reason {@code verdictFor} is: the polling loop needs a live client, and a rule that can
 * only be exercised live is a rule nobody checks — with the decision inside the loop, replacing
 * "re-read and compare" with "report that it was sent" runs green, because with no client the
 * read-back never happens and nothing enters the branch. The mutations below are exactly that one.
 */
public final class ACreativeSlotIsConfirmedAgainstTheSlotTest {

    private static ToolRegistry registry() {
        return new ToolRegistry(new ToolContext(null, null, null, null, null));
    }

    private static SyncToolSpecification tool(ToolRegistry reg) {
        for (SyncToolSpecification s : reg.all()) {
            if (s.tool().name().equals("do_set_creative_slot")) {
                return s;
            }
        }
        throw new AssertionError("do_set_creative_slot is not in all(): a tool that never reaches "
                + "the registry is one no model can call");
    }

    private static String call(ToolRegistry reg, Map<String, Object> args) {
        CallToolResult r = tool(reg).callHandler()
                .apply(null, new CallToolRequest("do_set_creative_slot", args));
        String text = "";
        for (Content c : r.content()) {
            if (c instanceof TextContent t) {
                text = t.text();
            }
        }
        return (Boolean.TRUE.equals(r.isError()) ? "ERROR " : "OK ") + text;
    }

    private static ToolRegistry.SlotView slot(String item, int count) {
        return new ToolRegistry.SlotView(item, count);
    }

    /**
     * MUTATION TARGET 1 — the success condition itself.
     *
     * <p>Flipping {@code wanted.equals(now)} to {@code !wanted.equals(now)} (the "did anything
     * change" shape this deliberately does NOT use) must turn this red.
     */
    @Test
    public void onlyTheSlotActuallyHoldingWhatWasSentCountsAsSet() {
        var dirt8 = slot("minecraft:dirt", 8);

        assertEquals("the slot holds exactly what was sent: the write landed",
                ToolRegistry.CreativeSlotVerdict.SET,
                ToolRegistry.creativeVerdictFor(dirt8, dirt8));

        assertEquals("the slot holds a DIFFERENT item: not the write we asked for, so not SET. A "
                        + "'did it change' test calls this a success, and that is the lie: the "
                        + "server put something else there",
                ToolRegistry.CreativeSlotVerdict.NOT_SET,
                ToolRegistry.creativeVerdictFor(dirt8, slot("minecraft:stone", 8)));

        assertEquals("the right item at the wrong count is not the write we asked for",
                ToolRegistry.CreativeSlotVerdict.NOT_SET,
                ToolRegistry.creativeVerdictFor(dirt8, slot("minecraft:dirt", 1)));

        assertEquals("an empty slot is not what was sent",
                ToolRegistry.CreativeSlotVerdict.NOT_SET,
                ToolRegistry.creativeVerdictFor(dirt8, ToolRegistry.SlotView.nothing()));
    }

    /**
     * MUTATION TARGET 2 — a CLEAR is a real write.
     *
     * <p>Flipping the comparison to skip empty {@code wanted} values (the "clearing is trivially
     * fine, don't bother checking" shortcut) must turn this red. It is the same shortcut that
     * would report a failed clear as a success.
     */
    @Test
    public void aClearIsConfirmedByTheSlotReadingEmpty() {
        var empty = ToolRegistry.SlotView.nothing();

        assertEquals("the slot was cleared and now reads empty: confirmed",
                ToolRegistry.CreativeSlotVerdict.SET,
                ToolRegistry.creativeVerdictFor(empty, ToolRegistry.SlotView.nothing()));

        assertEquals("we asked to clear it and it still holds dirt: refused, and it must be "
                        + "refused — 'empty' and 'cleared' are the same value, so only the "
                        + "comparison with what we wanted distinguishes them",
                ToolRegistry.CreativeSlotVerdict.NOT_SET,
                ToolRegistry.creativeVerdictFor(empty, slot("minecraft:dirt", 8)));
    }

    /**
     * MUTATION TARGET 3 — an unreadable slot claims nothing.
     *
     * <p>Returning {@code NOT_SET} for null instead of {@code UNREADABLE} must turn this red: a
     * failed read reported as "the write was refused" is a different and equally wrong claim.
     */
    @Test
    public void anUnreadableSlotClaimsNothing() {
        assertEquals("we could not read what is there now, so no verdict about the write",
                ToolRegistry.CreativeSlotVerdict.UNREADABLE,
                ToolRegistry.creativeVerdictFor(slot("minecraft:dirt", 8), null));

        assertEquals("an unreadable WANTED is equally unresolvable, and is not 'clear the slot'",
                ToolRegistry.CreativeSlotVerdict.UNREADABLE,
                ToolRegistry.creativeVerdictFor(null, slot("minecraft:dirt", 8)));
    }

    /**
     * MUTATION TARGET 4 — the type must not be able to express the defect.
     *
     * <p>This is the shape {@code transfer_item} adopted and the reason it was adopted: adding a
     * {@code SENT} constant must turn this red, because an enum that can say "sent" is an enum
     * that eventually gets returned as one. There is deliberately no fourth "unconfirmed" value
     * either — an unknown outcome is an ERROR with an explanation, not a success with a caveat.
     */
    @Test
    public void theVerdictSetHasNoSentOrUnconfirmedOutcome() {
        for (var v : ToolRegistry.CreativeSlotVerdict.values()) {
            assertNotEquals("'the packet was sent' is a fact about the wire, not an outcome: " + v,
                    "SENT", v.name());
            assertNotEquals("'unconfirmed' is the same hole wearing a hat: a caller reading OK "
                    + "cannot act on it, and isError=false is what tells it to try: " + v,
                    "UNCONFIRMED", v.name());
            assertNotEquals("MAYBE / UNKNOWN would be the same defect again: " + v,
                    "MAYBE", v.name());
        }
        assertEquals("the whole point is that every value names an OBSERVED slot state; three is "
                        + "the complete set of things a re-read can establish",
                3, ToolRegistry.CreativeSlotVerdict.values().length);
    }

    /**
     * MUTATION TARGET 5 — the handler must be unable to report a send as an outcome.
     *
     * <p>With no client attached there is no player, so the slot cannot be read. The fixed
     * handler therefore REFUSES BEFORE SENDING, and the reply has to say so: an unreadable slot
     * means the write's effect could never be confirmed, and firing it anyway would recreate the
     * original defect one layer down.
     *
     * <p>This is also the only assertion that can distinguish the fix from the original bug
     * headlessly. With no connection the transport fails either way, so "it is an ERROR" alone
     * passes for BOTH the fixed and the reverted code -- that was measured, and it is why the
     * first version of this test proved nothing. What separates them is the refusal to send at
     * all: the reverted handler attempts {@code sendTyped} and its label reaches the caller.
     * Restoring {@code return sendTyped(...)} here must turn this red, and so must relabelling
     * that send as "sent set_creative_slot".
     */
    @Test
    public void anUnreadableSlotIsRefusedBeforeAnythingIsSent() {
        String reply = call(registry(), Map.of("slot", 36));

        assertTrue("this must be an ERROR: " + reply, reply.startsWith("ERROR "));
        assertTrue("it must refuse RATHER THAN SEND, because the write could never be confirmed "
                + "-- this is the assertion the reverted code fails: " + reply,
                reply.contains("refusing to send"));
        assertTrue("and it must say nothing was sent, so a caller cannot read a refusal as a "
                + "write already in flight: " + reply, reply.contains("Nothing was sent"));
        assertTrue("the old success string must not appear anywhere: " + reply,
                !reply.contains("sent set_creative_slot"));
    }

    /**
     * The refusal must name the numbering, or the caller re-sends the same impossible slot.
     *
     * <p>The live repro passed slot 0. ContainerPlayer slot 0 is the crafting OUTPUT and the
     * server's own guard is {@code slotId >= 1 && slotId < 45}, so a "slot 0" write cannot ever
     * land -- a caller told only "refused" would try slot 1, then 2, then give up on the tool.
     */
    @Test
    public void theRefusalNamesTheWritableSlotRange() {
        String reply = call(registry(), Map.of("slot", 36));

        assertTrue("the refusal must give the writable range, which the server states nowhere on "
                + "the wire: " + reply, reply.contains("1-44"));
        assertTrue("and the container-vs-inventory offset, because a caller reading an inventory "
                + "listing passes N where N+9 is meant: " + reply, reply.contains("36-44"));
    }

    /**
     * The refusal must name the real cause, or it is decoration.
     *
     * <p>Creative mode is the overwhelmingly common cause and the server states it nowhere on the
     * wire, so a bare "the write failed" sends the agent off to retry the same doomed call. The
     * client's {@code capabilities.isCreativeMode} is the server's own answer — ItemInWorldManager
     * .setGameType pushes it with S39 and the client copies it verbatim — so naming it is a fact,
     * not a guess.
     */
    @Test
    public void theDescriptionNamesTheSilentDropAndTheSlotNumbering() {
        String d = tool(registry()).tool().description();
        assertNotNull(d);

        assertTrue("a caller must know the reply is a slot read-back, because that is the whole "
                + "difference from the bug: " + d,
                d.contains("read") || d.contains("RE-READ") || d.contains("re-read"));
        assertTrue("and it must name creative mode as the thing that silently drops the packet — "
                + "processCreativeInventoryAction:1078 has no else branch to report from: " + d,
                d.contains("creative"));
        assertTrue("the container-vs-inventory offset must be stated, because a caller reading an "
                + "inventory listing and passing the number through is off by nine: " + d,
                d.contains("N+9"));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void itsSchemaNamesTheContainerSlotItWrites() {
        Map<String, Object> schema = (Map<String, Object>) tool(registry()).tool().inputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertNotNull("no properties means nothing can be validated: " + schema, props);
        assertNotNull(props.get("slot"));
        assertNotNull(props.get("item"));
        List<String> required = (List<String>) schema.get("required");
        assertNotNull("a write with no slot would have to default, and the default is the "
                + "crafting output — a slot the server never writes", required);
        assertTrue(required.contains("slot"));
    }
}