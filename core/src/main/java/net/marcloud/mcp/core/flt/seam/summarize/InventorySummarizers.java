package net.marcloud.mcp.core.flt.seam.summarize;

import java.util.Map;

import net.minecraft.network.play.client.C09PacketHeldItemChange;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C10PacketCreativeInventoryAction;
import net.minecraft.network.play.client.C11PacketEnchantItem;
import net.minecraft.network.play.server.S09PacketHeldItemChange;
import net.minecraft.network.play.server.S2DPacketOpenWindow;
import net.minecraft.network.play.server.S2FPacketSetSlot;
import net.minecraft.network.play.server.S30PacketWindowItems;
import net.minecraft.network.play.server.S31PacketWindowProperty;

/**
 * A-tier summarizers for the INVENTORY / window family — held-item changes (both
 * directions), window open, single-slot + full-window item updates, window
 * properties, and the client's own window clicks / creative set / enchant.
 *
 * <p>ItemStacks are surfaced via {@code String.valueOf(stack)} (vanilla toString =
 * "count x item@meta"); we do not decode NBT here. Several server getters are
 * obfuscated ({@code func_149175_c} = windowId, etc.) — verified against source.
 */
final class InventorySummarizers {

    private InventorySummarizers() {
    }

    static void registerInto(PacketSummarizerRegistry r) {
        r.register(new HeldItemServer(), "net.minecraft.network.play.server.S09PacketHeldItemChange");
        r.register(new OpenWindow(), "net.minecraft.network.play.server.S2DPacketOpenWindow");
        r.register(new SetSlot(), "net.minecraft.network.play.server.S2FPacketSetSlot");
        r.register(new WindowItems(), "net.minecraft.network.play.server.S30PacketWindowItems");
        r.register(new WindowProperty(), "net.minecraft.network.play.server.S31PacketWindowProperty");
        r.register(new HeldItemClient(), "net.minecraft.network.play.client.C09PacketHeldItemChange");
        r.register(new ClickWindow(), "net.minecraft.network.play.client.C0EPacketClickWindow");
        r.register(new CreativeSet(), "net.minecraft.network.play.client.C10PacketCreativeInventoryAction");
        r.register(new EnchantItem(), "net.minecraft.network.play.client.C11PacketEnchantItem");
    }

    private static String item(net.minecraft.item.ItemStack s) {
        return s == null ? "empty" : String.valueOf(s);
    }

    static final class HeldItemServer implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S09PacketHeldItemChange".equals(cn);
        }
        @Override public String summarize(Object p) {
            return "heldItemSet slot=" + ((S09PacketHeldItemChange) p).getHeldItemHotbarIndex();
        }
        @Override public Map<String, Object> project(Object p) {
            return PacketView.of().put("slot", ((S09PacketHeldItemChange) p).getHeldItemHotbarIndex()).buildMap();
        }
    }

    static final class OpenWindow implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S2DPacketOpenWindow".equals(cn);
        }
        @Override public String summarize(Object p) {
            S2DPacketOpenWindow s = (S2DPacketOpenWindow) p;
            return "openWindow win=" + s.getWindowId() + " gui=" + s.getGuiId()
                    + " slots=" + s.getSlotCount()
                    + " title=" + (s.getWindowTitle() == null ? "" : s.getWindowTitle().getUnformattedText());
        }
        @Override public Map<String, Object> project(Object p) {
            S2DPacketOpenWindow s = (S2DPacketOpenWindow) p;
            return PacketView.of().put("windowId", s.getWindowId()).put("guiId", s.getGuiId())
                    .put("slotCount", s.getSlotCount()).put("hasSlots", s.hasSlots())
                    .put("entityId", s.getEntityId())
                    .put("title", s.getWindowTitle() == null ? null : s.getWindowTitle().getUnformattedText())
                    .buildMap();
        }
    }

    static final class SetSlot implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S2FPacketSetSlot".equals(cn);
        }
        @Override public String summarize(Object p) {
            S2FPacketSetSlot s = (S2FPacketSetSlot) p;
            return "setSlot win=" + s.func_149175_c() + " slot=" + s.func_149173_d()
                    + " item=" + item(s.func_149174_e());
        }
        @Override public Map<String, Object> project(Object p) {
            S2FPacketSetSlot s = (S2FPacketSetSlot) p;
            return PacketView.of().put("windowId", s.func_149175_c()).put("slot", s.func_149173_d())
                    .put("item", item(s.func_149174_e())).buildMap();
        }
    }

    static final class WindowItems implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S30PacketWindowItems".equals(cn);
        }
        @Override public String summarize(Object p) {
            S30PacketWindowItems s = (S30PacketWindowItems) p;
            net.minecraft.item.ItemStack[] items = s.getItemStacks();
            return "windowItems win=" + s.func_148911_c() + " count=" + (items == null ? 0 : items.length);
        }
        @Override public Map<String, Object> project(Object p) {
            S30PacketWindowItems s = (S30PacketWindowItems) p;
            net.minecraft.item.ItemStack[] items = s.getItemStacks();
            java.util.List<Object> list = new java.util.ArrayList<>();
            if (items != null) {
                for (net.minecraft.item.ItemStack it : items) {
                    list.add(item(it));
                }
            }
            return PacketView.of().put("windowId", s.func_148911_c())
                    .put("count", list.size()).put("items", list).buildMap();
        }
    }

    static final class WindowProperty implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.server.S31PacketWindowProperty".equals(cn);
        }
        @Override public String summarize(Object p) {
            S31PacketWindowProperty s = (S31PacketWindowProperty) p;
            return "windowProp win=" + s.getWindowId() + " idx=" + s.getVarIndex() + " val=" + s.getVarValue();
        }
        @Override public Map<String, Object> project(Object p) {
            S31PacketWindowProperty s = (S31PacketWindowProperty) p;
            return PacketView.of().put("windowId", s.getWindowId())
                    .put("property", s.getVarIndex()).put("value", s.getVarValue()).buildMap();
        }
    }

    static final class HeldItemClient implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C09PacketHeldItemChange".equals(cn);
        }
        @Override public String summarize(Object p) {
            return "heldItemReq slot=" + ((C09PacketHeldItemChange) p).getSlotId();
        }
        @Override public Map<String, Object> project(Object p) {
            return PacketView.of().put("slot", ((C09PacketHeldItemChange) p).getSlotId()).buildMap();
        }
    }

    static final class ClickWindow implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C0EPacketClickWindow".equals(cn);
        }
        @Override public String summarize(Object p) {
            C0EPacketClickWindow s = (C0EPacketClickWindow) p;
            return "clickWindow win=" + s.getWindowId() + " slot=" + s.getSlotId()
                    + " button=" + s.getUsedButton() + " mode=" + s.getMode()
                    + " item=" + item(s.getClickedItem());
        }
        @Override public Map<String, Object> project(Object p) {
            C0EPacketClickWindow s = (C0EPacketClickWindow) p;
            return PacketView.of().put("windowId", s.getWindowId()).put("slot", s.getSlotId())
                    .put("button", s.getUsedButton()).put("mode", s.getMode())
                    .put("actionNumber", (int) s.getActionNumber())
                    .put("item", item(s.getClickedItem())).buildMap();
        }
    }

    static final class CreativeSet implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C10PacketCreativeInventoryAction".equals(cn);
        }
        @Override public String summarize(Object p) {
            C10PacketCreativeInventoryAction s = (C10PacketCreativeInventoryAction) p;
            return "creativeSet slot=" + s.getSlotId() + " item=" + item(s.getStack());
        }
        @Override public Map<String, Object> project(Object p) {
            C10PacketCreativeInventoryAction s = (C10PacketCreativeInventoryAction) p;
            return PacketView.of().put("slot", s.getSlotId()).put("item", item(s.getStack())).buildMap();
        }
    }

    static final class EnchantItem implements PacketSummarizer {
        @Override public boolean handles(String cn) {
            return "net.minecraft.network.play.client.C11PacketEnchantItem".equals(cn);
        }
        @Override public String summarize(Object p) {
            C11PacketEnchantItem s = (C11PacketEnchantItem) p;
            return "enchantItem win=" + s.getWindowId() + " button=" + s.getButton();
        }
        @Override public Map<String, Object> project(Object p) {
            C11PacketEnchantItem s = (C11PacketEnchantItem) p;
            return PacketView.of().put("windowId", s.getWindowId()).put("button", s.getButton()).buildMap();
        }
    }
}
