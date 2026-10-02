package net.marcloud.mcp.core.drivers.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The NON-interactive half of the open screen: everything a person reads off a
 * panel to decide what to click, and nothing they click.
 *
 * <p>{@code gui_snapshot} already answers "what can I press" ({@link GuiElement}).
 * This record answers "what does it all say", which is the half that lets a model
 * CHOOSE: a chest's real title, a villager's trades with their real costs, an
 * anvil's result, an enchantment table's three offers and lapis price, a furnace's
 * three slots and progress, a brewing stand's bottles.
 *
 * <p><b>Shape, not a per-screen schema.</b> {@link #facts} is an open map rather
 * than thirty typed fields, because a screen this project has never seen still
 * has to produce something useful. {@link GuiPanelReflect} always emits the
 * {@link #GENERIC_INVENTORIES} block (every {@code IInventory} the container holds,
 * with its real display name, size and contents) and then adds whatever the
 * screen's own kind contributes on top.
 *
 * @param kind       what the panel IS, as a lower-case word ({@code chest},
 *                   {@code villager}, {@code furnace}, ...), or {@code "container"}
 *                   / {@code "screen"} when nothing more specific applied
 * @param title      the title the panel itself draws, "" when it draws none
 * @param facts      the open fact map; always carries the generic inventory block
 * @param unreadable field/read failures, so drift is visible instead of silent
 */
public record GuiPanelState(String kind, String title, Map<String, Object> facts,
                            List<String> unreadable) {

    /** Key of the always-present generic inventory block. */
    public static final String GENERIC_INVENTORIES = "inventories";

    /** Fact key saying whether the container was one of the families read in full. */
    public static final String RECOGNISED = "recognised";

    /** The state of a screen that is not open. */
    public static final GuiPanelState NONE =
            new GuiPanelState("none", "", Map.of(), List.of());

    public GuiPanelState {
        facts = facts == null ? Map.of() : Map.copyOf(facts);
        unreadable = unreadable == null ? List.of() : List.copyOf(unreadable);
    }

    /** Ordered map view for JSON emission. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", kind);
        m.put("title", title);
        m.putAll(facts);
        m.put("unreadable", unreadable);
        return m;
    }

    /** One fact, or null. Callers read this rather than probing the map twice. */
    public Object fact(String name) {
        return facts.get(name);
    }

    /**
     * Whether this panel says anything a click could be aimed at. A panel whose
     * only content is a title and an empty fact map is not worth the agent's
     * budget, and {@code gui_snapshot} omits the block entirely.
     */
    public boolean hasContent() {
        if (!title.isEmpty()) {
            return true;
        }
        for (Map.Entry<String, Object> e : facts.entrySet()) {
            // Bookkeeping is not content. `recognised: false` is how an unknown
            // container says "the generic block below is all I have"; without this
            // exclusion every empty panel would publish itself as worth reading.
            if (RECOGNISED.equals(e.getKey())) {
                continue;
            }
            // The generic block with no inventory of substance does not count; a
            // populated one does, and any kind-specific fact does.
            if (!GENERIC_INVENTORIES.equals(e.getKey()) || !emptyInventories(e.getValue())) {
                return true;
            }
        }
        return false;
    }

    private static boolean emptyInventories(Object v) {
        return !(v instanceof List<?> list) || list.isEmpty();
    }
}
