package net.marcloud.mcp.core.drivers.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import java.awt.image.BufferedImage;
import java.util.Base64;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ImageContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import net.marcloud.mcp.core.GameAccess;
import net.marcloud.mcp.core.GameBridge;
import net.marcloud.mcp.core.io.IoManager;
import net.marcloud.mcp.core.se.Ring;
import net.marcloud.mcp.core.drivers.video.ScreenCapture;

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

    /** Default trajectory ring capacity (recent GUI actions kept for review). */
    private static final int TRAJECTORY_CAPACITY = 128;

    private final GameAccess game;
    private final GuiSnapshotService snapshots;
    private final GuiTrajectory trajectory;
    private final GuiActions actions;

    public GuiTools(GameAccess game, GuiSnapshotService snapshots) {
        this.game = game;
        this.snapshots = snapshots;
        this.trajectory = new GuiTrajectory(TRAJECTORY_CAPACITY);
        this.actions = new GuiActions(game, snapshots, trajectory);
    }

    /** Register all GUI tools into the supervised registry with their true rings. */
    public void registerAll(IoManager registry) {
        for (SyncToolSpecification spec : all()) {
            var tool = spec.tool();
            registry.register(tool.name(), spec, null, tool.description(), true,
                    Ring.forBuiltin(tool.name(), Ring.R2));
        }
    }

    private List<SyncToolSpecification> all() {
        List<SyncToolSpecification> t = new ArrayList<>();
        t.add(guiSnapshot());
        t.add(guiSnapshotImage());
        t.add(guiClickElement());
        t.add(guiTypeText());
        t.add(guiPressKey());
        t.add(guiTrajectory());
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

    /**
     * Separate tool for the Set-of-Marks annotated screenshot. Split out from
     * gui_snapshot so the plain JSON grounding call stays a free R2 read, while
     * the image path — which drives glReadPixels exactly like capture_screen —
     * carries its own SE_SCREEN_CAP / CAP_SCREEN_CAP gate (L4 gates per-tool, not
     * per-arg, so a shared tool couldn't gate only the image branch).
     */
    private SyncToolSpecification guiSnapshotImage() {
        Tool tool = Tool.builder()
                .name("gui_snapshot_image")
                .description("Like gui_snapshot, but ALSO returns a Set-of-Marks annotated PNG of the "
                        + "current frame: a numbered box on each element (the number IS its id, e.g. "
                        + "'b0'/'s13') so you can cross-reference the JSON element list against the "
                        + "picture. Costs image tokens and drives glReadPixels (requires screen-capture "
                        + "clearance, same as capture_screen). Prefer plain gui_snapshot for routine "
                        + "grounding; use this only when you need to SEE the layout.")
                .inputSchema(schema(Map.of(
                        "onlyInteractable", prop("boolean",
                                "only include visible+enabled interactable elements (default true)"),
                        "maxEdge", prop("integer",
                                "max long-edge pixels of the PNG, 64-1600 (default 1024)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            boolean onlyInteractable = boolArg(a, "onlyInteractable", true);
            int maxEdge = Math.max(64, Math.min(1600,
                    intArg(a, "maxEdge", ScreenCapture.DEFAULT_MAX_EDGE)));
            return snapshotWithImage(onlyInteractable, maxEdge);
        });
    }

    /**
     * Build the snapshot and its Set-of-Marks annotated PNG in ONE game-thread
     * pass so the drawn boxes match the elements captured from the very same frame
     * (no drift between a JSON read and a separately-timed screenshot).
     * {@link GuiSnapshotService#snapshot} marshals to the game thread too, but the
     * executor is reentrancy-safe so it runs inline here.
     */
    private CallToolResult snapshotWithImage(boolean onlyInteractable, int maxEdge) {
        try {
            byte[][] pngBox = new byte[1][];
            // The ONLY irreducibly-live step is captureFrame (glReadPixels). The
            // annotate + encode assembly is pulled into buildAnnotatedPng so it is
            // headless-testable with a synthetic frame; the same code runs here.
            GuiSnapshot snap = GameBridge.onGameThread(() -> {
                GuiSnapshot s = snapshots.snapshot(game, onlyInteractable);
                if (s.screen() != null) {
                    pngBox[0] = buildAnnotatedPng(ScreenCapture.captureFrame(game), s, maxEdge);
                }
                return s;
            });
            return assembleResult(snap, pngBox[0]);
        } catch (Exception e) {
            return err("gui_snapshot (includeImage) failed: " + rootMsg(e));
        }
    }

    /**
     * Annotate a captured frame with the Set-of-Marks overlay for {@code snap}'s
     * elements and PNG-encode it (downscaled to {@code maxEdge}). Pure/non-GL —
     * split out so the annotate→encode pipeline is testable headless with a
     * synthetic {@link BufferedImage}. Package-private for the test.
     */
    static byte[] buildAnnotatedPng(BufferedImage frame, GuiSnapshot snap, int maxEdge)
            throws java.io.IOException {
        BufferedImage marked = SoMOverlay.annotate(frame, snap.elements(), snap.viewport());
        return ScreenCapture.encodePng(marked, maxEdge);
    }

    /**
     * Assemble the tool result: the snapshot JSON as text, plus (if present) the
     * PNG as base64 {@link ImageContent}. Pure — split out so the base64 +
     * ImageContent assembly is testable headless. Package-private for the test.
     */
    static CallToolResult assembleResult(GuiSnapshot snap, byte[] png) {
        CallToolResult.Builder out = CallToolResult.builder()
                .addTextContent(snap.toJson())
                .isError(false);
        if (png != null) {
            String b64 = Base64.getEncoder().encodeToString(png);
            out.addContent(ImageContent.builder(b64, "image/png").build());
        }
        return out.build();
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

    private SyncToolSpecification guiTrajectory() {
        Tool tool = Tool.builder()
                .name("gui_trajectory")
                .description("Review your recent GUI actions as a screen-before -> action -> "
                        + "screen-after log. Returns the most recent entries (default all in the "
                        + "buffer), each with the action kind (click/type/press), the element id or "
                        + "key, whether it succeeded (with the message), and the structural screen "
                        + "fingerprint captured just before and just after the action — so you can "
                        + "see what each action changed. Read-only; drives nothing. Pass 'n' to cap "
                        + "how many recent entries to return.")
                .inputSchema(schema(Map.of(
                        "n", prop("integer", "max number of most-recent entries to return (default all)")),
                        List.of()))
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            int n = intArg(a, "n", -1);
            List<GuiTrajectory.Entry> entries = (n >= 0) ? trajectory.recent(n) : trajectory.recent();
            return ok(trajectoryJson(entries));
        });
    }

    /** Serialize trajectory entries to a compact JSON array. */
    private static String trajectoryJson(List<GuiTrajectory.Entry> entries) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"count\":").append(entries.size()).append(",\"entries\":[");
        for (int i = 0; i < entries.size(); i++) {
            GuiTrajectory.Entry e = entries.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"time\":").append(e.timeMillis())
                    .append(",\"kind\":\"").append(esc(e.kind())).append('"')
                    .append(",\"elementId\":\"").append(esc(e.elementId())).append('"')
                    .append(",\"ok\":").append(e.ok())
                    .append(",\"message\":\"").append(esc(e.message())).append('"')
                    .append(",\"before\":\"").append(esc(e.beforeFingerprint())).append('"')
                    .append(",\"after\":\"").append(esc(e.afterFingerprint())).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    private static String esc(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
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
