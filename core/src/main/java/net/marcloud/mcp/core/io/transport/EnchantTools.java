package net.marcloud.mcp.core.io.transport;

import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;

import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.GameBridge;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.se.Ring;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.inventory.ContainerEnchantment;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.network.play.client.C11PacketEnchantItem;

/**
 * {@code do_enchant_item} — the enchant-table lapis spend, the one clientbound-family
 * C-packet a player performs as a real, server-observable act and that nothing in this
 * project could reach.
 *
 * <p><b>Why this needed a tool and not a GUI click.</b> Vanilla spends lapis from
 * {@code GuiEnchantment.mouseClicked:85-99}, which hit-tests the three option rows as
 * raw screen coordinates and never puts them in {@code buttonList}. Everything this
 * project drives a screen with — {@code GuiReflect.extractButtons}, then
 * {@code gui_click_element} — enumerates {@code buttonList}, so the three options are
 * invisible to {@code gui_snapshot} and unclickable by id. The audit's row is PARTIAL
 * for exactly that reason. The packet itself is trivial
 * ({@code C11PacketEnchantItem(windowId, button)}, two bytes); what is not trivial is
 * that the server <em>silently ignores</em> one that cannot work, so a tool that said
 * "sent" would be reporting an outcome it never observed.
 *
 * <p><b>What the server does with it</b> ({@code NetHandlerPlayServer.processEnchantItem:1059-1069},
 * verified against source): it is applied only when
 * {@code openContainer.windowId == packet.windowId && openContainer.getCanCraft(player) && !isSpectator()};
 * otherwise it is dropped with no reply and no error. Inside,
 * {@code ContainerEnchantment.enchantItem:243-253} refuses unless the option's level
 * is {@code > 0}, the lapis count covers {@code button + 1}, and the player's XP covers
 * {@code max(button + 1, optionLevel)} — creative excepted. So the pre-send gate here
 * mirrors vanilla's own gate exactly, which is what makes "refused, naming why" possible
 * instead of "sent, find out later".
 *
 * <p><b>Why button is bounded before anything is sent.</b> The option id indexes
 * {@code enchantLevels[id]}, an {@code int[3]}. A server receiving {@code button = 3}
 * raises {@link ArrayIndexOutOfBoundsException} inside the packet handler: the enchant
 * never happens and the connection dies with a crash report. The bound is therefore
 * checked here, before the send, where a refusal costs nothing.
 *
 * <p><b>Confirmation.</b> The client does not predict the enchant: in
 * {@code enchantItem} the mutating half sits behind {@code !worldPointer.isRemote}, so
 * locally the call is a pure gate. The enchanted stack and the spent lapis arrive as the
 * server's own slot updates, and {@link #verdictFor} accepts the effect only when the
 * item's total enchantment level actually rises. "Sent" is never an outcome.
 *
 * <p>Separate from {@link ToolRegistry} rather than added to it: that file is under
 * concurrent edit. The send helper below is a twin of its private {@code sendTyped} and
 * must keep the same three answers (veto / not-connected / success).
 */
public final class EnchantTools {

    /** Vanilla's option count: {@code ContainerEnchantment.enchantLevels} is an int[3]. */
    static final int OPTION_COUNT = 3;

    /**
     * One re-read of the enchant table, on the game thread. {@code windowId < 0} means
     * "not an open enchant table", which is a state, not a failure to read.
     */
    record EnchantView(int windowId, String item, int count, int enchantLevel,
                       int lapis, int xpLevel, boolean creative, List<Integer> options) {

        static EnchantView absent() {
            return new EnchantView(-1, null, 0, 0, -1, -1, false, List.of());
        }

        boolean readable() {
            return windowId >= 0;
        }

        /** Option {@code button}'s level as the server last pushed it, or 0 when unknown. */
        int option(int button) {
            return button >= 0 && button < options.size() ? options.get(button) : 0;
        }

        @Override
        public String toString() {
            if (!readable()) {
                return "no enchant table open";
            }
            return (item == null ? "empty" : item + " x" + count)
                    + " enchant=" + enchantLevel + " lapis=" + lapis + " xp=" + xpLevel
                    + " options=" + options;
        }
    }

    /** What one re-read can establish. Never {@code SENT}: "sent" is not an outcome. */
    enum EnchantVerdict {
        /** The item's total enchantment level rose, so the server's container applied it. */
        ENCHANTED,
        /** The item still reads the same. The enchant did not happen, or is still in flight. */
        NOT_ENCHANTED,
        /** The table could not be read (no client, no table, table closed), so nothing is claimed. */
        UNREADABLE
    }

    /**
     * The confirmation rule, as a pure function so it is reachable headlessly.
     *
     * <p>Separated from the polling loop for the reason {@code ToolRegistry.verdictFor}
     * documents: the loop needs a live client, and a rule that can only be exercised live
     * is a rule nobody checks. With the decision inside the loop, replacing "re-read and
     * compare" with "report that it was sent" runs green — with no client the read-back
     * never happens and nothing enters the branch.
     */
    static EnchantVerdict verdictFor(EnchantView before, EnchantView now) {
        if (before == null || now == null || !before.readable() || !now.readable()) {
            return EnchantVerdict.UNREADABLE;
        }
        if (before.item == null || before.count <= 0) {
            // Nothing was in the table, so there is nothing the server could have
            // enchanted and no before-value to compare against.
            return EnchantVerdict.UNREADABLE;
        }
        return now.enchantLevel > before.enchantLevel
                ? EnchantVerdict.ENCHANTED
                : EnchantVerdict.NOT_ENCHANTED;
    }

    private static final long CONFIRM_BUDGET_NANOS = 600_000_000L;

    private final ToolContext ctx;

    public EnchantTools(ToolContext ctx) {
        this.ctx = ctx;
    }

    public void registerAll(IoManager registry) {
        SyncToolSpecification spec = doEnchantItem();
        Tool t = spec.tool();
        registry.register(t.name(), spec, null, t.description(), true,
                Ring.forBuiltin(t.name(), Ring.R3));
    }

    SyncToolSpecification doEnchantItem() {
        Tool tool = Tool.builder()
                .name("do_enchant_item")
                .title("Spend lapis on an enchant-table option")
                .description("[requires: connected-to-server, enchant table open] Take one of the "
                        + "three enchant-table options: spends button+1 lapis, spends the "
                        + "matching XP, and applies the enchantment server-side. 'button' is the "
                        + "option id as the table numbers them -- 0, 1 or 2 -- and nothing else is "
                        + "accepted: the id indexes an int[3] on the server, so an out-of-range "
                        + "value is an array index out of bounds in the packet handler and the "
                        + "connection dies with a crash report. The window id is read off the "
                        + "open table, so it cannot be the wrong one.\n"
                        + "The three options are NOT reachable through gui_click_element -- "
                        + "vanilla hit-tests them as raw screen regions and never puts them in "
                        + "buttonList, so they have no element id. This tool is the only way to "
                        + "take one.\n"
                        + "The reply is a re-read, never a 'sent': it confirms only once the "
                        + "server's own slot update lands and the item's total enchantment level "
                        + "rises, and reports the lapis and XP it sees when it does not. A refusal "
                        + "names the gate that refused it (option level 0, not enough lapis, not "
                        + "enough XP), because the server applies this packet only when the "
                        + "option is live and would otherwise drop it without a word.")
                .annotations(ToolAnnotations.builder()
                        .title("Spend lapis on an enchant-table option")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(true)
                        .build())
                .inputSchema(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "button", Map.of("type", "integer",
                                        "description", "option id 0, 1 or 2 (vanilla's three "
                                                + "lapis options); costs button+1 lapis")),
                        "required", List.of("button")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            Object raw = a == null ? null : a.get("button");
            if (!(raw instanceof Number n)) {
                return error("button is required: an integer 0, 1 or 2 (the enchant table's "
                        + "three lapis options). No packet was sent.");
            }
            int button = n.intValue();
            if (button < 0 || button >= OPTION_COUNT) {
                return error("button " + button + " is not one of the table's three options "
                        + "(0, 1, 2). Vanilla indexes the option list with this id, so a value "
                        + "outside 0-2 crashes the server's packet handler rather than "
                        + "enchanting anything. Nothing was sent; read the option ids from "
                        + "recent_packets or the table itself.");
            }
            return enchantAndConfirm(button);
        });
    }

    private CallToolResult enchantAndConfirm(int button) {
        EnchantView before = readTable();
        if (!before.readable()) {
            return error("no enchant table is open, so there is nothing this packet could apply: "
                    + "the server only acts on it when its open container is the one the packet "
                    + "names. Open a table with an item in slot 0 and lapis in slot 1, then "
                    + "re-read the table before sending anything.");
        }
        if (before.item == null || before.count <= 0) {
            return error("the table's slot 0 is empty, so there is nothing to enchant. Put the "
                    + "item in the table and re-read it; nothing was sent.");
        }

        int cost = button + 1;
        int level = before.option(button);
        if (level <= 0) {
            return error("option " + button + " reads level 0 on this table, and vanilla refuses "
                    + "an option whose level is 0 -- its own click handler would not send this "
                    + "packet either. The options offered depend on bookshelves and on the item; "
                    + "re-read the table and take an option that reads above 0. Nothing was sent.");
        }
        if (!before.creative && before.lapis < cost) {
            return error("option " + button + " costs " + cost + " lapis and the table holds "
                    + before.lapis + ", which the server would refuse (lapis are only spent in "
                    + "non-creative). Nothing was sent.");
        }
        if (!before.creative && before.xpLevel < Math.max(cost, level)) {
            return error("option " + button + " costs " + Math.max(cost, level) + " XP levels and "
                    + "the player has " + before.xpLevel + ", which the server would refuse. "
                    + "Nothing was sent.");
        }

        CallToolResult sent = send(new C11PacketEnchantItem(before.windowId(), button),
                "do_enchant_item button=" + button);
        if (Boolean.TRUE.equals(sent.isError())) {
            return sent;
        }

        long deadline = System.nanoTime() + CONFIRM_BUDGET_NANOS;
        EnchantView last = before;
        while (System.nanoTime() < deadline) {
            EnchantView now = readTable();
            last = now;
            switch (verdictFor(before, now)) {
                case ENCHANTED:
                    return ok("sent do_enchant_item button=" + button + ", and the item in the "
                            + "table now reads enchantment level " + now.enchantLevel() + " (was "
                            + before.enchantLevel() + "), lapis " + before.lapis() + " -> "
                            + now.lapis() + ", xp " + before.xpLevel() + " -> " + now.xpLevel()
                            + " -- confirmed against the server's table, not just on the wire");
                case UNREADABLE:
                    return error("do_enchant_item button=" + button + " was sent, but the table "
                            + "could not be re-read, so whether the server applied it is unknown "
                            + "and nothing is claimed. Re-read the table before assuming it "
                            + "worked or retrying: a second lapis spend on an enchant that did "
                            + "land costs you the lapis twice.");
                case NOT_ENCHANTED:
                default:
                    break;
            }
            try {
                Thread.sleep(50L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return error("interrupted while confirming do_enchant_item button=" + button
                        + "; the packet was sent but the table has not changed and was not "
                        + "re-read");
            }
        }
        return error("the enchant packet for option " + button + " was sent and 600ms later the "
                + "table still reads " + last + " -- the item is unchanged and the lapis are "
                + "where they were, so the server did not apply it. The gates were open when it "
                + "was sent, so the refusal is the server's own (a plugin, a re-roll it will not "
                + "authorise, or a race with the table closing). Re-read the table before "
                + "retrying: a second spend on an enchant that did land costs the lapis twice.");
    }

    /** The live table, read on the game thread. Absent rather than throwing when unreadable. */
    EnchantView readTable() {
        try {
            return GameBridge.onGameThread(() -> {
                GameAccess g = ctx.game();
                EntityPlayerSP p = g == null ? null : g.player();
                if (p == null || !(p.openContainer instanceof ContainerEnchantment c)) {
                    return EnchantView.absent();
                }
                ItemStack target = c.tableInventory.getStackInSlot(0);
                ItemStack lapis = c.tableInventory.getStackInSlot(1);
                return new EnchantView(c.windowId, itemName(target), countOf(target),
                        totalEnchantLevel(target), countOf(lapis), p.experienceLevel,
                        p.capabilities.isCreativeMode,
                        List.of(c.enchantLevels[0], c.enchantLevels[1], c.enchantLevels[2]));
            });
        } catch (Throwable t) {
            return EnchantView.absent();
        }
    }

    /**
     * Sum of the enchantment levels on a stack, read straight off the tag list exactly as
     * {@code EnchantmentHelper.getEnchantmentLevel(int, ItemStack):41-67} reads it.
     *
     * <p>The total, not {@code isItemEnchanted()}: enchanting an already-enchanted item is a
     * normal act and must still move the number, and a book turns into an enchanted book,
     * so any single-flag check would call two real enchants "nothing happened".
     */
    static int totalEnchantLevel(ItemStack stack) {
        if (stack == null) {
            return 0;
        }
        NBTTagList list = stack.getEnchantmentTagList();
        if (list == null) {
            return 0;
        }
        int total = 0;
        for (int i = 0; i < list.tagCount(); i++) {
            NBTTagCompound tag = list.getCompoundTagAt(i);
            total += tag.getShort("lvl");
        }
        return total;
    }

    private static String itemName(ItemStack stack) {
        if (stack == null || stack.getItem() == null || stack.stackSize <= 0) {
            return null;
        }
        // Item.itemRegistry, not Block.blockRegistry: the stack being enchanted is a sword.
        return String.valueOf(Item.itemRegistry.getNameForObject(stack.getItem()));
    }

    private static int countOf(ItemStack stack) {
        return stack == null ? 0 : stack.stackSize;
    }

    /** Twin of {@code ToolRegistry.sendTyped}: veto, not-connected, success — in that order. */
    private CallToolResult send(net.minecraft.network.Packet<?> packet, String label) {
        try {
            boolean sent = ctx.actions().sendRawPacket(packet);
            return sent ? ok("sent " + label) : error("not connected — no open channel to send on");
        } catch (Exception e) {
            Throwable cause = e instanceof java.util.concurrent.ExecutionException ? e.getCause() : e;
            if (cause instanceof net.marcloud.mcp.core.drivers.action.ActionManager.PacketVetoedException) {
                return error("vetoed: " + cause.getMessage());
            }
            return error(label + " failed: " + e);
        }
    }

    private static CallToolResult ok(String text) {
        return CallToolResult.builder().addTextContent(text).isError(false).build();
    }

    private static CallToolResult error(String text) {
        return CallToolResult.builder().addTextContent(text).isError(true).build();
    }
}