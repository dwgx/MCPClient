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
import io.modelcontextprotocol.spec.McpSchema.ToolAnnotations;
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
                .title("Snapshot open GUI as elements")
                .description("Ground yourself in the OPEN GUI. Returns the whole screen in two "
                        + "halves.\n"
                        + "(1) 'elements' — everything you can ADDRESS: every button "
                        + "('b0','b1',...), every container slot ('s13',...), every text field "
                        + "('t0'), and every row of every scrollable list ('r0:3'), each with its "
                        + "label, real rectangle, click point and enabled/visible state. A slot "
                        + "carries its item as {item:'minecraft:stone', name, count, damage, "
                        + "maxStack, enchantments, nbtKeys, nbt} — the registry name, so it matches "
                        + "what world_view and transfer_item say about the same item.\n"
                        + "LISTS: a screen that scrolls has a 'w0','w1',... element per list — the "
                        + "Controls screen's key bindings, a resource-pack screen's two pack lists, "
                        + "Video Settings' option rows, the Statistics grid. It reports rowCount, "
                        + "scroll, maxScroll and which rows are on screen. Its rows are the "
                        + "'r0:3' elements, and a key binding row says which key it holds right now, "
                        + "whether it is armed for rebinding, and which other bindings already use "
                        + "that key; a resource pack row says its title and whether it is selected. "
                        + "ONLY THE ROWS CURRENTLY ON SCREEN ARE LISTED: scroll the list and "
                        + "snapshot again to reach the rest.\n"
                        + "A ROW ID IS NOT A POSITION. 'r0:3' always means row 3 of that list's "
                        + "contents, before and after any scroll, so it cannot silently come to "
                        + "mean a different control. The flip side is that the snapshot's "
                        + "'fingerprint' folds in every list's scroll, so scrolling invalidates a "
                        + "reference — call gui_snapshot again after you scroll. A row that has "
                        + "scrolled out of view is refused by name, with the scroll it would need.\n"
                        + "A list can also own controls drawn in its HEADER band, above the "
                        + "rows: 'h1:0','h1:1','h1:2' on the Statistics screen are its three "
                        + "sort targets, each with the rectangle vanilla will act on. Press one "
                        + "like any other element; the result is confirmed by re-reading the "
                        + "list's row ORDER, and vanilla cycles a column ascending -> descending "
                        + "-> unsorted, so a third press restores the original order.\n"
                        + "A 'r0:3#0' element is a real button drawn INSIDE that row (a key "
                        + "binding's 'change key' and 'reset', a Video Settings row's two option "
                        + "controls). Statistics rows are read-only: vanilla's plain GuiSlot has no "
                        + "mouseClicked, so they are listed and readable but cannot be pressed.\n"
                        + "(2) 'panel' — everything you can only READ, which is what lets you "
                        + "choose: the panel's real title, every inventory it holds with its "
                        + "contents, and then whatever that panel adds — a chest's rows, a villager's "
                        + "trades with their real costs and remaining uses, an anvil's result with "
                        + "its XP and lapis bills, an enchantment table's three offers with their "
                        + "lapis price, a furnace's input/fuel/output plus burn and cook progress, "
                        + "a brewing stand's bottles and countdown. 'panel' is omitted for a "
                        + "screen that says nothing beyond its buttons.\n"
                        + "Pass an element id to gui_click_element / gui_type_text to act; NEVER "
                        + "guess pixels. Also returns an 'epoch' and 'fingerprint' you must pass "
                        + "back to action tools so a changed screen is caught. Returns screen=null "
                        + "when no GUI is open (use scan_surroundings for the world instead).")
                .inputSchema(schema(Map.of(
                        "onlyInteractable", prop("boolean",
                                "only include visible+enabled interactable elements (default true)")),
                        List.of()))
                .annotations(ToolAnnotations.builder()
                        .title("Snapshot open GUI as elements")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
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
                .title("Snapshot open GUI with annotated PNG")
                .description("[requires: GLFW-window] "
                        + "Like gui_snapshot, but ALSO returns a Set-of-Marks annotated PNG of the "
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
                .annotations(ToolAnnotations.builder()
                        .title("Snapshot open GUI with annotated PNG")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
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
                .title("Click a GUI element by id")
                .description("[requires: GLFW-window] "
                        + "Click a GUI element by its id (from gui_snapshot). Never send pixels: "
                        + "the click point is recomputed from the LIVE screen, so the id is all "
                        + "you need. For a slot this drives the real mouseClicked, which sends the "
                        + "click to the server (pick up / place / split items); for a button it "
                        + "runs its action. Pass the 'epoch' and 'fingerprint' from the snapshot "
                        + "you decided against; if the screen's identity or its set of controls "
                        + "changed since, the click is REFUSED (call gui_snapshot again). "
                        + "button: 'left' (default), 'right', or 'middle' for pick-block.\n"
                        + "OPTIONAL 'gesture' selects a fuller pointer interaction, because "
                        + "vanilla derives its click mode from inputs a single press never "
                        + "supplies:\n"
                        + "  'click' (default) — one press, as before.\n"
                        + "  'press-release' — press then release; a complete human click.\n"
                        + "  'double-click' — two presses inside vanilla's 250ms window, then a "
                        + "release; this is what COLLECTS a matching stack (mode 6).\n"
                        + "  'shift-click' — shift held genuinely during the press AND the "
                        + "release; this is what QUICK-MOVES a stack (mode 1). The modifier is "
                        + "written into the real keyboard state, so vanilla's own shift test "
                        + "decides the mode; it is never simulated.\n"
                        + "  'pick-block' — the button vanilla synthesises for pick-block, read "
                        + "from the live key binding, not hardcoded (mode 3).\n"
                        + "  'drag-split' — press on elementId, sweep the slots named in "
                        + "'dragOver', then release; this is what SPREADS a stack (mode 5). "
                        + "Requires 'dragOver'.\n"
                        + "  'scroll' — turn the mouse wheel by 'wheel' notches. Address a "
                        + "scrolling list by its OWN id ('w0') and this drives that list: the "
                        + "notch is injected as the mouse's wheel event and the screen's own "
                        + "handleMouseInput moves the list's own scroll offset -- the same path a "
                        + "human's wheel takes, not a re-implementation. Use it to reach a row "
                        + "that is not on screen, then gui_snapshot again, because scrolling moves "
                        + "the snapshot's 'fingerprint' and a reference taken before the scroll is "
                        + "refused. On a screen that consumes no wheel -- a plain container has no "
                        + "wheel handler in vanilla at all -- the result says so.\n"
                        + "OPTIONAL 'modifiers': ['shift'] — held for the whole gesture.\n"
                        + "OPTIONAL 'dragOver': element ids to sweep during a drag-split.\n"
                        + "OPTIONAL 'wheel': notches for 'scroll'. Vanilla's own GuiSlot wheel branch "
                        + "moves a list DOWN for a NEGATIVE event, so a negative number "
                        + "scrolls down.\n"
                        + "A creative tab has no button, so address it as 'tab:N' (N is the "
                        + "vanilla tab index, 0-11) and it is driven as a press+release.\n"
                        + "A list row ('r0:3', or 'r0:3#0' for a button drawn inside it) is "
                        + "pressed and released through the screen's own mouseClicked, so the row's "
                        + "real entry handles it: pressing a key binding row ARMS that binding, "
                        + "which you then send with gui_press_key. A row press always sends the "
                        + "release half too, because vanilla disables a list when a row accepts the "
                        + "press and only the release turns it back on. A row that has scrolled "
                        + "out of view is refused by name with the scroll it needs; a row of a "
                        + "plain GuiSlot (the Statistics grid) is refused because vanilla gives it "
                        + "no click handler at all.\n"
                        + "The result carries a 'verdict', never a 'sent': CONFIRMED means a "
                        + "re-read proved the effect happened, and the message names WHICH "
                        + "read-back proved it (the clicked slot, the held item, the destination "
                        + "slots, the scroll offset, the tab index). NOT_CONFIRMED means the "
                        + "handler was driven and nothing observable changed; for a slot that is "
                        + "an error (the server rejected it or the window id is wrong), for a "
                        + "button or a screen with no wheel handler it is normal and is NOT "
                        + "evidence of failure. UNREADABLE means the state could not be read "
                        + "back; REFUSED_STALE / NO_ELEMENT mean NOTHING was clicked; "
                        + "REFUSED_DESTRUCTIVE means the element's click point lies outside the "
                        + "container panel, where vanilla would THROW the carried stack into the "
                        + "world, so it was refused.")
                .inputSchema(schema(Map.of(
                        "epoch", prop("integer", "the snapshot epoch you are acting against"),
                        "fingerprint", prop("string", "the snapshot fingerprint you are acting against"),
                        "elementId", prop("string", "element id from gui_snapshot: 'b0'/'s13'/'t0', "
                                + "'w0' for a list to scroll, 'r0:3' for a list row, 'r0:3#0' for a "
                                + "button drawn inside a row, or 'tab:N' for a creative tab"),
                        "button", prop("string", "'left' (default), 'right', or 'middle' (pick-block)"),
                        "gesture", prop("string", "'click' (default) | 'press-release' | 'double-click' | 'shift-click' | 'pick-block' | 'drag-split' | 'scroll'"),
                        "modifiers", prop("array", "modifier keys held during the gesture, e.g. ['shift']"),
                        "dragOver", prop("array", "element ids to sweep during a 'drag-split'"),
                        "wheel", prop("integer", "notches for a 'scroll' (default 1; negative scrolls up)")),
                        List.of("epoch", "fingerprint", "elementId")))
                .annotations(ToolAnnotations.builder()
                        .title("Click a GUI element by id")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(true)
                        .build())
                .build();
        return new SyncToolSpecification(tool, (exchange, request) -> {
            Map<String, Object> a = request.arguments();
            String elementId = str(a, "elementId");
            String fingerprint = str(a, "fingerprint");
            if (elementId == null || fingerprint == null) {
                return err("epoch, fingerprint and elementId are required");
            }
            int epoch = intArg(a, "epoch", -1);
            String buttonName = str(a, "button");
            String gestureName = str(a, "gesture");

            // A plain click keeps the exact historical path, so nothing that
            // worked before can change behaviour by arriving here.
            if (gestureName == null && a.get("modifiers") == null && a.get("dragOver") == null
                    && a.get("wheel") == null && buttonName == null) {
                try {
                    GuiActions.ClickResult r = actions.click(epoch, fingerprint, elementId, 0);
                    String body = "verdict=" + r.verdict() + ": " + r.message();
                    return r.ok() ? ok(body) : err(body);
                } catch (Exception e) {
                    return err("gui_click_element failed: " + rootMsg(e));
                }
            }

            int button = "right".equalsIgnoreCase(buttonName) ? 1 : 0;
            try {
                GuiActions.Gesture g = buildGesture(a, gestureName, button);
                GuiActions.ClickResult r = actions.gesture(epoch, fingerprint, elementId, g);
                String body = "verdict=" + r.verdict() + ": " + r.message();
                return r.ok() ? ok(body) : err(body);
            } catch (IllegalArgumentException e) {
                return err("gui_click_element: " + e.getMessage());
            } catch (Exception e) {
                return err("gui_click_element failed: " + rootMsg(e));
            }
        });
    }

    /**
     * Map the tool's optional gesture arguments onto a {@link GuiActions.Gesture}.
     *
     * <p>An unknown gesture name is REFUSED rather than silently degraded to a
     * plain click: an agent that asked to shift-click and silently got a plain
     * click would move the wrong item, and the refusal names the valid set.
     */
    private GuiActions.Gesture buildGesture(Map<String, Object> a, String gestureName, int button) {
        java.util.Set<GuiActions.Modifier> mods = new java.util.LinkedHashSet<>();
        for (Object o : listArg(a, "modifiers")) {
            String m = String.valueOf(o).trim().toLowerCase(java.util.Locale.ROOT);
            if ("shift".equals(m)) {
                mods.add(GuiActions.Modifier.SHIFT);
            } else if ("ctrl".equals(m) || "control".equals(m)) {
                mods.add(GuiActions.Modifier.CTRL);
            } else {
                throw new IllegalArgumentException("unknown modifier '" + o
                        + "'; valid modifiers are 'shift' and 'ctrl'");
            }
        }

        String g = gestureName == null ? "click" : gestureName.trim().toLowerCase(java.util.Locale.ROOT);
        switch (g) {
            case "click":
                return new GuiActions.Gesture(button, mods, false, 1, List.of(), 0);
            case "press-release":
            case "pressrelease":
                return new GuiActions.Gesture(button, mods, true, 1, List.of(), 0);
            case "double-click":
            case "doubleclick":
                return new GuiActions.Gesture(button, mods, true, 2, List.of(), 0);
            case "shift-click":
            case "shiftclick":
                mods.add(GuiActions.Modifier.SHIFT);
                return new GuiActions.Gesture(0, mods, true, 1, List.of(), 0);
            case "pick-block":
            case "pickblock":
                // The button is resolved from the live key binding by the driver;
                // -1 here means "ask the driver", never a hardcoded middle click.
                return new GuiActions.Gesture(Integer.MIN_VALUE, mods, true, 1, List.of(), 0);
            case "drag-split":
            case "dragsplit": {
                List<String> over = listArg(a, "dragOver");
                if (over.isEmpty()) {
                    throw new IllegalArgumentException(
                            "a drag-split needs 'dragOver': the element ids to sweep the stack across");
                }
                return new GuiActions.Gesture(button, mods, true, 1,
                        over.stream().map(id -> new Point(-1, -1)).collect(java.util.stream.Collectors.toList()),
                        0);
            }
            case "scroll": {
                int notches = intArg(a, "wheel", 1);
                return new GuiActions.Gesture(button, mods, false, 0, List.of(), notches);
            }
            default:
                throw new IllegalArgumentException("unknown gesture '" + gestureName
                        + "'; valid gestures are 'click', 'press-release', 'double-click', "
                        + "'shift-click', 'pick-block', 'drag-split' and 'scroll'");
        }
    }

    /** Read an array-of-strings argument; empty when absent. */
    private static List<String> listArg(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        if (v instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                if (o != null) {
                    out.add(o.toString());
                }
            }
            return out;
        }
        if (v instanceof String s && !s.isBlank()) {
            return List.of(s);
        }
        return List.of();
    }

    private SyncToolSpecification guiTypeText() {
        Tool tool = Tool.builder()
                .name("gui_type_text")
                .title("Type text into a GUI text field")
                .description("[requires: GLFW-window] "
                        + "Type text into a text-field element by id (from gui_snapshot). Focuses "
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
                .annotations(ToolAnnotations.builder()
                        .title("Type text into a GUI text field")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(false)
                        .build())
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
                .title("Press a key on the current GUI screen")
                .description("[requires: GLFW-window] "
                        + "Press a key on the current GUI screen: 'Escape' (close), 'Return'/'Enter' "
                        + "(confirm), 'Tab', 'Backspace', or a single character. Drives the real "
                        + "GuiScreen.keyTyped. Pass the snapshot 'epoch' and 'fingerprint'.")
                .inputSchema(schema(Map.of(
                        "epoch", prop("integer", "the snapshot epoch you are acting against"),
                        "fingerprint", prop("string", "the snapshot fingerprint you are acting against"),
                        "key", prop("string", "key name ('Escape','Return','Tab','Backspace') or a single character")),
                        List.of("epoch", "fingerprint", "key")))
                .annotations(ToolAnnotations.builder()
                        .title("Press a key on the current GUI screen")
                        .readOnlyHint(false)
                        .destructiveHint(true)
                        .idempotentHint(false)
                        .openWorldHint(true)
                        .build())
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
                .title("Review recent GUI actions")
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
                .annotations(ToolAnnotations.builder()
                        .title("Review recent GUI actions")
                        .readOnlyHint(true)
                        .destructiveHint(false)
                        .idempotentHint(true)
                        .openWorldHint(false)
                        .build())
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
