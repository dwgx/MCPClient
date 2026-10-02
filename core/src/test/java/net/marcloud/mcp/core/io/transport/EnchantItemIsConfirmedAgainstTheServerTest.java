package net.marcloud.mcp.core.io.transport;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import net.marcloud.mcp.core.io.transport.EnchantTools.EnchantVerdict;
import net.marcloud.mcp.core.io.transport.EnchantTools.EnchantView;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Items;

import org.junit.Test;

/**
 * {@code do_enchant_item} must confirm against the SERVER's table, must refuse the option ids
 * that would crash the server's packet handler, and must have no way to say "sent".
 *
 * <p><b>The gap this closes.</b> Of the twelve vanilla clientbound-C classes with no tool,
 * C11 is the one a player performs as a real act: putting a sword on a lapis option. It was
 * unreachable because {@code GuiEnchantment.mouseClicked:85-99} hit-tests the three options as raw
 * screen regions and never puts them in {@code buttonList}, which is the only list
 * {@code gui_click_element} can address. There was also no way to spend lapis at all.
 *
 * <p><b>Why the bound is checked here rather than by the server.</b>
 * {@code NetHandlerPlayServer.processEnchantItem:1064} applies the packet only when
 * {@code openContainer.windowId == packet.windowId}; a mismatch is dropped with no reply, no
 * error, no log. Worse, the option id indexes {@code ContainerEnchantment.enchantLevels}, an
 * {@code int[3]} read at line 253 — so {@code button = 3} is an ArrayIndexOutOfBoundsException
 * inside the server's packet handler: no enchant, and the connection dies. A bound the server
 * punishes with a crash is not a bound to leave to the server.
 *
 * <p><b>Why the confirmation is a pure function.</b> The polling loop needs a live client, and a
 * rule that can only be exercised live is a rule nobody checks: with the decision inside the
 * loop, replacing "re-read and compare" with "report that it was sent" runs green, because with
 * no client the read-back never happens and nothing enters the branch. That is mutation 1 below.
 */
public final class EnchantItemIsConfirmedAgainstTheServerTest {

    private static EnchantTools tools() {
        // No client collaborators: every read in the handler degrades to "unreadable"
        // rather than throwing, so this exercises the refusal paths headlessly.
        return new EnchantTools(new ToolContext(null, null, null, null, null));
    }

    private static CallToolResult call(int... button) {
        Map<String, Object> args = button.length == 0
                ? Map.of()
                : Map.of("button", (Object) Integer.valueOf(button[0]));
        return tools().doEnchantItem().callHandler()
                .apply(null, new CallToolRequest("do_enchant_item", args));
    }

    private static CallToolResult callRaw(Object button) {
        return tools().doEnchantItem().callHandler()
                .apply(null, new CallToolRequest("do_enchant_item", Map.of("button", button)));
    }

    private static String text(CallToolResult r) {
        return r.content().toString();
    }

    private static EnchantView view(int windowId, String item, int enchantLevel) {
        return new EnchantView(windowId, item, 1, enchantLevel, 3, 30, false, List.of(5, 9, 14));
    }

    // ---- the surface -------------------------------------------------------

    /**
     * The schema must publish the option bound, because the bound is the whole reason this tool
     * cannot simply forward a number it was handed.
     */
    @Test
    public void theOptionBoundIsPublishedInTheSchema() {
        Tool t = tools().doEnchantItem().tool();
        assertEquals("do_enchant_item", t.name());
        @SuppressWarnings("unchecked")
        Map<String, Object> schema = (Map<String, Object>) t.inputSchema();
        @SuppressWarnings("unchecked")
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        assertEquals("button is the only input", List.of("button"), schema.get("required"));
        assertTrue("the button property must exist", props.containsKey("button"));
        String desc = t.description();
        assertTrue("the description must state the legal ids, not leave them to prose: " + desc,
                desc.contains("0, 1 or 2"));
        assertTrue("the description must say the reply is a re-read: " + desc,
                desc.contains("never a 'sent'"));
    }

    // ---- refusals that cost nothing ----------------------------------------

    /**
     * Mutation target 2: the bound is checked BEFORE the client is touched. A {@code null}
     * ToolContext cannot even be read here, so reaching the read path would produce the
     * "no enchant table is open" message instead.
     */
    @Test
    public void anOptionIdOutsideZeroToTwoIsRefusedBeforeAnythingIsSent() {
        for (int bad : new int[] {-1, 3, 200}) {
            CallToolResult r = call(bad);
            assertTrue("button " + bad + " must be refused: " + text(r),
                    Boolean.TRUE.equals(r.isError()));
            assertTrue("the refusal must name the legal ids: " + text(r),
                    text(r).contains("0, 1, 2"));
            assertTrue("the refusal must say nothing was sent: " + text(r),
                    text(r).contains("Nothing was sent"));
        }
    }

    /** A missing or non-numeric button is refused by name, not defaulted to option 0. */
    @Test
    public void aMissingOrNonNumericButtonIsRefused() {
        CallToolResult missing = call();
        assertTrue(Boolean.TRUE.equals(missing.isError()));
        assertTrue("must name the argument it wants: " + text(missing),
                text(missing).contains("button is required"));

        CallToolResult str = callRaw("one");
        assertTrue("a spelled-out id must not reach the send path: " + text(str),
                Boolean.TRUE.equals(str.isError()));
    }

    /**
     * With no client there is no table, so the handler must refuse and claim nothing -- and it
     * must not fall through to the send path, whose {@code ctx.actions()} is null here.
     */
    @Test
    public void withNoEnchantTableOpenNothingIsSent() {
        CallToolResult r = call(0);
        assertTrue(Boolean.TRUE.equals(r.isError()));
        assertTrue("must name the missing precondition: " + text(r),
                text(r).contains("no enchant table is open"));
        assertTrue("must not report success: " + text(r), !text(r).contains("sent "));
    }

    // ---- the confirmation rule ---------------------------------------------

    /**
     * MUTATION 1: changing the rule's comparison from {@code >} to {@code >=} -- or replacing the
     * whole body with a "report that it was sent" -- makes this fail. An unchanged table is the
     * state a refused enchant leaves behind, and it must never read as success.
     */
    @Test
    public void aTableThatReadsUnchangedIsNotSuccess() {
        assertEquals(EnchantVerdict.NOT_ENCHANTED, EnchantTools.verdictFor(view(7, "sword", 0), view(7, "sword", 0)));
        assertEquals(EnchantVerdict.NOT_ENCHANTED, EnchantTools.verdictFor(view(7, "sword", 2), view(7, "sword", 1)));
    }

    /**
     * A second enchant on an already-enchanted item is a real act, and it must move the verdict.
     * The number that moves is the TOTAL enchantment level, not {@code isItemEnchanted()} --
     * the flag is true before and after and would call every re-enchant a no-op.
     */
    @Test
    public void aReEnchantThatRaisesTheTotalIsSuccess() {
        assertEquals(EnchantVerdict.ENCHANTED, EnchantTools.verdictFor(view(7, "sword", 0), view(7, "sword", 3)));
        assertEquals(EnchantVerdict.ENCHANTED, EnchantTools.verdictFor(view(7, "sword", 2), view(7, "sword", 5)));
    }

    /**
     * MUTATION 3: dropping the "the table is readable" guard turns a table that closed mid-wait
     * into NOT_ENCHANTED, i.e. a claim about a table nobody read.
     */
    @Test
    public void aTableThatCannotBeReReadClaimsNothing() {
        assertEquals(EnchantVerdict.UNREADABLE,
                EnchantTools.verdictFor(view(7, "sword", 0), EnchantView.absent()));
        assertEquals(EnchantVerdict.UNREADABLE,
                EnchantTools.verdictFor(EnchantView.absent(), view(7, "sword", 4)));
        assertEquals(EnchantVerdict.UNREADABLE,
                EnchantTools.verdictFor(null, view(7, "sword", 4)));
        // Nothing in the table: no before-value, so nothing to have risen.
        assertEquals(EnchantVerdict.UNREADABLE,
                EnchantTools.verdictFor(view(7, null, 0), view(7, "sword", 4)));
    }

    // ---- the read-back the verdict rests on ---------------------------------

    /**
     * The total is read off the real tag list, on a real ItemStack. A summary that answered
     * {@code isItemEnchanted() ? 1 : 0} -- the obvious shortcut -- answers 1 here, and the
     * enchantment-a-book case (a plain book gains an enchantment list) is covered by the plain
     * stack answering 0.
     */
    @Test
    public void theReadBackTotalSumsTheEnchantmentTagList() {
        net.minecraft.init.Bootstrap.register();   // the item registry an ItemStack resolves through

        ItemStack plain = new ItemStack(Items.diamond_sword);
        assertEquals("an unenchanted stack reads 0", 0, EnchantTools.totalEnchantLevel(plain));

        ItemStack sharpened = new ItemStack(Items.diamond_sword);
        sharpened.addEnchantment(Enchantment.sharpness, 3);
        assertEquals("a Sharpness III sword reads 3", 3, EnchantTools.totalEnchantLevel(sharpened));

        ItemStack twice = new ItemStack(Items.diamond_sword);
        twice.addEnchantment(Enchantment.sharpness, 3);
        twice.addEnchantment(Enchantment.unbreaking, 2);
        assertEquals("the total is a sum, not the first entry: " + twice,
                5, EnchantTools.totalEnchantLevel(twice));
        assertEquals("an absent stack reads 0", 0, EnchantTools.totalEnchantLevel(null));
    }
}