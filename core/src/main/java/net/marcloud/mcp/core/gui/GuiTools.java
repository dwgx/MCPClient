package net.marcloud.mcp.core.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.registry.CapabilityRegistry;
import net.marcloud.mcp.core.security.Ring;

/**
 * The structured-GUI MCP tool surface: exposes the WHOLE clickable GUI to the LLM
 * as addressable elements and lets it drive the REAL handlers by element id.
 *
 * <ul>
 *   <li>{@code gui_snapshot} (R2 OBSERVE) — the grounding call: every button,
 *       slot, and text field of the open screen as {id,label,bounds,clickPoint}.</li>
 *   <li>{@code gui_click_element} (R1) — click an element by id.</li>
 *   <li>{@code gui_type_text} (R1) — type into a text field by id.</li>
 *   <li>{@code gui_press_key} (R1) — press a key on the current screen.</li>
 * </ul>
 *
 * <p>Actions take the {@code epoch} + {@code fingerprint} from a prior snapshot so
 * a screen that changed underneath is rejected loudly rather than misclicked.
 */
public final class GuiTools {

    private final GameAccess game;
    private final GuiSnapshotService snapshots;
    private final GuiActions actions;

    public GuiTools(GameAccess game, GuiSnapshotService snapshots) {
        this.game = game;
        this.snapshots = snapshots;
        this.actions = new GuiActions(game, snapshots);
    }

    /** Register all GUI tools into the supervised registry with their true rings. */
    public void registerAll(CapabilityRegistry registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    Ring.forBuiltin(tool.name(), Ring.R2));
        }
    }

    private List<SyncToolSpecification> all() {
        List<SyncToolSpecification> t = new ArrayList<>();
        t.add(guiSnapshot());
        t.add(guiClickElement());
        t.add(guiTypeText());
        t.add(guiPressKey());
        return t;
    }

    // ===== helpers =====

    private static CallToolResult ok(String s) {
        return CallToolResult.builder().addTextContent(s).isError(false).build();
    }

    private static CallToolResult err(String s) {
        return CallToolResult.builder().addTextContent(s).isError(true).build();
    }

    private static String str(Map<String, Object> a, String k) {
        Object v = (a == null) ? null : a.get(k);
        return v == null ? null : v.toString();
    }

    private static int intArg(Map<String, Object> a, String k, int fallback) {
        Object v = (a == null) ? null : a.get(k);
        if (v instanceof Number n) {
            return n.intValue();
        }
        if (v != null) {
            try {
                return Integer.parseInt(v.toString());
            } catch (NumberFormatException ignored) {
                // fall through
            }
        }
        return fallback;
    }

    private static boolean boolArg(Map<String, Object> a, String k, boolean fallback) {
        Object v = (a == null) ? null : a.get(k);
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? fallback : Boolean.parseBoolean(v.toString());
    }

    private static Map<String, Object> schema(Map<String, Object> props, List<String> required) {
        return Map.of("type", "object", "properties", props, "required", required);
    }

    private static Map<String, Object> prop(String type, String desc) {
        return Map.of("type", type, "description", desc);
    }

    private SyncToolSpecification guiSnapshot() {
        Tool tool = Tool.builder()
                .name("gui_snapshot")
                .description("Ground yourself in the OPEN GUI: returns every clickable element of "
                        + "the current screen (buttons, inventory/container slots, text fields) as a "
                        + "structured list — each with an id (e.g. 'b0','s13','t0'), label, bounds, "
                        + "and clickPoint. Pass an element id to gui_click_element / gui_type_text to "
                        + "act; NEVER guess pixels. Also returns an 'epoch' and 'fingerprint' you must "
                        + "pass back to action tools so a changed screen is caught. Returns screen=null "
                        + "when no GUI is open (use scan_surroundings for the world instead).")
                .inputSchema(schema(Map.of(
                        "onlyInteractable", prop("boolean",
                                "only include visible+enabled interactable elements (default true)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            boolean onlyInteractable = boolArg(request.arguments(), "onlyInteractable", true);
            try {
                GuiSnapshot snap = snapshots.snapshot(game, onlyInteractable);
                return ok(snap.toJson());
            } catch (Exception e) {
                return err("gui_snapshot failed: " + rootMsg(e));
            }
        });
    }

    private SyncToolSpecification guiClickElement() {
        Tool tool = Tool.builder()
                .name("gui_click_element")
                .description("Click a GUI element by its id (from gui_snapshot). Drives the REAL "
                        + "handler on the game thread — for a slot this sends the click to the server "
                        + "(picks up/moves items); for a button it runs its action. Pass the 'epoch' "
                        + "and 'fingerprint' from the snapshot you decided against; if the screen "
                        + "changed since, the click is REFUSED (call gui_snapshot again). button: "
                        + "'left' (default) or 'right'.")
                .inputSchema(schema(Map.of(
                        "epoch", prop("integer", "the snapshot epoch you are acting against"),
                        "fingerprint", prop("string", "the snapshot fingerprint you are acting against"),
                        "elementId", prop("string", "element id from gui_snapshot, e.g. 'b0' or 's13'"),
                        "button", prop("string", "'left' (default) or 'right'")),
                        List.of("epoch", "fingerprint", "elementId")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String elementId = str(a, "elementId");
            String fingerprint = str(a, "fingerprint");
            if (elementId == null || fingerprint == null) {
                return err("epoch, fingerprint and elementId are required");
            }
            int epoch = intArg(a, "epoch", -1);
            int button = "right".equalsIgnoreCase(str(a, "button")) ? 1 : 0;
            try {
                GuiActions.Result r = actions.click(epoch, fingerprint, elementId, button);
                return r.ok() ? ok(r.message()) : err(r.message());
            } catch (Exception e) {
                return err("gui_click_element failed: " + rootMsg(e));
            }
        });
    }

    private SyncToolSpecification guiTypeText() {
        Tool tool = Tool.builder()
                .name("gui_type_text")
                .description("Type text into a text-field element by id (from gui_snapshot). Focuses "
                        + "the field first, then drives its real key handler. Set clearFirst=true to "
                        + "replace existing text. Pass the snapshot 'epoch' and 'fingerprint'; a "
                        + "changed screen is refused.")
                .inputSchema(schema(Map.of(
                        "epoch", prop("integer", "the snapshot epoch you are acting against"),
                        "fingerprint", prop("string", "the snapshot fingerprint you are acting against"),
                        "elementId", prop("string", "text-field element id, e.g. 't0'"),
                        "text", prop("string", "the text to type"),
                        "clearFirst", prop("boolean", "clear the field before typing (default false)")),
                        List.of("epoch", "fingerprint", "elementId", "text")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String elementId = str(a, "elementId");
            String fingerprint = str(a, "fingerprint");
            String text = str(a, "text");
            if (elementId == null || fingerprint == null || text == null) {
                return err("epoch, fingerprint, elementId and text are required");
            }
            int epoch = intArg(a, "epoch", -1);
            boolean clearFirst = boolArg(a, "clearFirst", false);
            try {
                GuiActions.Result r = actions.typeText(epoch, fingerprint, elementId, text, clearFirst);
                return r.ok() ? ok(r.message()) : err(r.message());
            } catch (Exception e) {
                return err("gui_type_text failed: " + rootMsg(e));
            }
        });
    }

    private SyncToolSpecification guiPressKey() {
        Tool tool = Tool.builder()
                .name("gui_press_key")
                .description("Press a key on the current GUI screen: 'Escape' (close), 'Return'/'Enter' "
                        + "(confirm), 'Tab', 'Backspace', or a single character. Drives the real "
                        + "GuiScreen.keyTyped. Pass the snapshot 'epoch' and 'fingerprint'.")
                .inputSchema(schema(Map.of(
                        "epoch", prop("integer", "the snapshot epoch you are acting against"),
                        "fingerprint", prop("string", "the snapshot fingerprint you are acting against"),
                        "key", prop("string", "key name ('Escape','Return','Tab','Backspace') or a single character")),
                        List.of("epoch", "fingerprint", "key")))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String key = str(a, "key");
            String fingerprint = str(a, "fingerprint");
            if (key == null || fingerprint == null) {
                return err("epoch, fingerprint and key are required");
            }
            int epoch = intArg(a, "epoch", -1);
            int[] mapped = mapKey(key);
            if (mapped == null) {
                return err("unknown key '" + key + "'; use Escape/Return/Tab/Backspace or a single character");
            }
            try {
                GuiActions.Result r = actions.pressKey(epoch, fingerprint, (char) mapped[0], mapped[1]);
                return r.ok() ? ok(r.message()) : err(r.message());
            } catch (Exception e) {
                return err("gui_press_key failed: " + rootMsg(e));
            }
        });
    }

    /** Map a key name (or single char) to {char, LWJGL keyCode}. Null if unknown. */
    private static int[] mapKey(String key) {
        switch (key.toLowerCase(java.util.Locale.ROOT)) {
            case "escape": case "esc":   return new int[] {0, 1};    // Keyboard.KEY_ESCAPE
            case "return": case "enter": return new int[] {'\n', 28}; // KEY_RETURN
            case "tab":                  return new int[] {'\t', 15};  // KEY_TAB
            case "backspace":            return new int[] {8, 14};    // KEY_BACK
            case "space":                return new int[] {' ', 57};  // KEY_SPACE
            default:
                if (key.length() == 1) {
                    return new int[] {key.charAt(0), 0}; // char-only; keyCode 0 (handlers key off char)
                }
                return null;
        }
    }

    private static String rootMsg(Throwable e) {
        Throwable c = (e.getCause() != null) ? e.getCause() : e;
        return c.getMessage() != null ? c.getMessage() : c.toString();
    }
}
