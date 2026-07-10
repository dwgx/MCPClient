package net.marcloud.mcp.core.gui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A single addressable, clickable element of the live GUI. The LLM picks an
 * element by its {@link #id} (kind-prefixed, e.g. {@code "b0"}, {@code "s13"},
 * {@code "t0"}) and the server drives the REAL vanilla handler using
 * {@link #clickPoint} — no pixel guessing.
 *
 * <p>Coordinates ({@code bounds}, {@code clickPoint}) are in scaled-GUI space
 * with a top-left origin, the same space {@code GuiScreen.mouseClicked} expects.
 *
 * @param id         kind-prefixed stable id within one snapshot ("b0"/"s13"/"t0"/"l0")
 * @param kind       one of {@code button|slot|textfield|label}
 * @param role       accessibility-style role: {@code PUSHBUTTON|CELL|EDIT|TEXT}
 * @param name       the visible label / display string (may be empty, never null)
 * @param value      the current value where meaningful (textfield text, item name), else ""
 * @param bounds     rectangle in scaled-GUI space
 * @param clickPoint center of the element in scaled-GUI space
 * @param state      enabled/visible/focused/hovered flags
 * @param actions    handler verbs the element supports (e.g. "click", "setText")
 * @param attributes kind-specific extras (slotNumber, windowId, itemId, count, hasStack)
 */
public record GuiElement(
        String id,
        String kind,
        String role,
        String name,
        String value,
        Bounds bounds,
        Point clickPoint,
        State state,
        List<String> actions,
        Map<String, Object> attributes) {

    /** Kind constants (element {@code id} prefixes derive from these). */
    public static final String KIND_BUTTON = "button";
    public static final String KIND_SLOT = "slot";
    public static final String KIND_TEXTFIELD = "textfield";
    public static final String KIND_LABEL = "label";

    /** Role constants (accessibility-style). */
    public static final String ROLE_PUSHBUTTON = "PUSHBUTTON";
    public static final String ROLE_CELL = "CELL";
    public static final String ROLE_EDIT = "EDIT";
    public static final String ROLE_TEXT = "TEXT";

    /** Defensive copy so the record stays immutable even if callers pass mutable collections. */
    public GuiElement {
        name = name == null ? "" : name;
        value = value == null ? "" : value;
        actions = actions == null ? List.of() : List.copyOf(actions);
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }

    /** Ordered map view for JSON emission / the MCP tool layer. */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("kind", kind);
        m.put("role", role);
        m.put("name", name);
        m.put("value", value);
        m.put("bounds", bounds == null ? null : bounds.toMap());
        m.put("clickPoint", clickPoint == null ? null : clickPoint.toMap());
        m.put("state", state == null ? null : state.toMap());
        m.put("actions", actions);
        m.put("attributes", attributes);
        return m;
    }
}
