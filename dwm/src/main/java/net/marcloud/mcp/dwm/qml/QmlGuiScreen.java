package net.marcloud.mcp.dwm.qml;

import net.marcloud.mcp.dwm.ui.UiKeys;
import net.marcloud.mcp.dwm.ui.UiWindowHost;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.shader.Framebuffer;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/**
 * A qml4j scene as a real {@link GuiScreen}.
 *
 * <p>This is the fourth point of the dwm contract, and the reason it is worth insisting on:
 * MC's screen lifecycle already solves show/hide, resize, input routing, focus and pause
 * semantics. Riding it means dwm does not reimplement any of that, and — on macOS especially —
 * that there is no second event loop competing for the main thread GLFW owns.
 *
 * <p><b>Coordinates.</b> qml4j wants framebuffer pixels with a top-left origin.
 * {@link Mouse} reports framebuffer pixels bottom-left (the shim scales GLFW's window units by
 * the Retina factor on the way in), and {@code mc.displayWidth/Height} are set from
 * {@code Display.getWidth()/getHeight()}, which are also framebuffer pixels. So the only
 * conversion needed is the vertical flip — no DPI scaling here, and adding any would double it.
 * {@link #handleMouseInput()} is overridden for exactly this reason: the inherited version
 * rescales into GUI units, which is the wrong space for us.
 *
 * <p><b>Nothing may escape into the game loop.</b> Every override is defensive; a fault leaves
 * the scene inert and the client running.
 */
public class QmlGuiScreen extends GuiScreen implements UiWindowHost {

    private final QmlUiSurface surface;

    /** Last known pointer position in framebuffer pixels, top-left origin. */
    private float pointerX;
    private float pointerY;

    /**
     * @param qmlPath resource path of the scene, resolved by qml4j's loader
     */
    public QmlGuiScreen(String qmlPath) {
        // `this` as the window host: this class owns the screen lifecycle, which is exactly what
        // the caption bar's verbs act on. Passing it at construction rather than wiring a setter
        // keeps the surface's host final -- it cannot change under a scene that already loaded.
        this.surface = new QmlUiSurface(qmlPath, this);
    }

    // ---- UiWindowHost ----------------------------------------------------------
    //
    // The caption bar sends verbs; this decides what they mean, the same division Windows draws
    // between a non-client area and the window manager. See UiWindowHost.
    //
    // Minimise is not among them any more, and the reason lives in UiWindowHost: it used to keep
    // this screen's Skia surface alive for a reopen, and nothing in this tree could ever reopen it
    // -- every open builds a NEW screen, so the kept surface was stranded unreachable GPU memory
    // while looking exactly like close from the user's side. With no screen cache and no taskbar,
    // "put it away but keep it" has no honest implementation, so the verb is gone rather than
    // answered with a lie.

    @Override
    public void initGui() {
        // Repeat events so held arrows/backspace behave in text fields, as vanilla text
        // screens do. Reset in onGuiClosed.
        Keyboard.enableRepeatEvents(true);
        surface.open(framebufferWidth(), framebufferHeight());
        // Deliberately NOT republishElements() here: see drawScreen. A QML layout assigns each
        // child's x/y while the scene lays out, and that runs inside surface.frame(), so at
        // initGui time every child is still at 0,0 and the whole table collapses onto one rect.
        //
        // There is no latch to reset. The one-shot flag this used to set is gone -- publishing is
        // now conditional on the collected table differing from the last one, and a resize gets a
        // freshly loaded scene whose first frame differs from whatever was published before, so
        // it publishes itself. See republishIfChanged.
    }

    /**
     * The last published table, held so the next one can be compared against it.
     *
     * <p>Null means nothing has been published yet, which is why {@code republishIfChanged}
     * cannot short-circuit on the very first frame.
     */
    private java.util.List<QmlElementBridge.Hit> lastPublished;

    /**
     * Re-derive the element table from the live QML tree and install it into {@code buttonList},
     * but only when it would actually differ from what is published.
     *
     * <p>Why the table exists at all: {@code gui_snapshot} reflects {@code GuiScreen.buttonList},
     * so before this an agent could operate every vanilla menu in the game and none of ours.
     * There is no alternative route that does not break the module boundary — {@code dwm} may not
     * import {@code core} (enforced by {@code DwmEntryTest}), and the only thing the extractor
     * reads is what vanilla already reflects.
     *
     * <p><b>The difference check is a real comparison, and it depends on {@code Hit} being a
     * value.</b> {@code List.equals} asks each element whether it equals the element in the same
     * position, so without {@code Hit.equals} two collections of identical rows are never equal
     * and this method degenerates into "rebuild buttonList sixty times a second" — with a
     * correct-looking result, so nothing observes the cost. A control that MOVES is a difference,
     * and {@code Hit} compares its rectangle and the live QML node behind it for exactly that
     * reason.
     *
     * <p>Cheap when nothing moved: the collection is a bounded walk of the live tree, so a
     * static page costs one walk and no mutation of {@code buttonList}. That is the property the
     * one-shot rule was protecting, and it is kept — what the one-shot rule did NOT protect is
     * the agent's ability to see page two.
     */
    private void republishIfChanged() {
        if (Boolean.getBoolean("mcp.dwm.noelements")) {
            lastPublished = null;
            return;
        }
        java.util.List<QmlElementBridge.Hit> hits;
        try {
            hits = QmlElementBridge.enumerate(surface.rootItem(), width, height,
                    mc.displayWidth, mc.displayHeight, surface.uiScale());
        } catch (Throwable t) {
            // An unreadable tree is not an empty one. Publishing an empty table would erase a
            // working panel, so the last good one stays.
            return;
        }
        if (hits != null && hits.equals(lastPublished)) {
            return;
        }
        lastPublished = hits;
        republishElements();
    }

    private void republishElements() {
        // Bisect switch. Opening this panel kills the JVM with no Java exception and no hs_err
        // log, which is a bad shape to debug by inspection: the try/catch below cannot catch a
        // native death, so it silently proves nothing. This flag makes the question
        // answerable in one build and two runs -- with it set, the element table is never built.
        if (Boolean.getBoolean("mcp.dwm.noelements")) {
            buttonList.clear();
            return;
        }
        try {
            java.util.List<QmlElementBridge.Hit> hits = QmlElementBridge.enumerate(
                    surface.rootItem(), width, height,
                    mc.displayWidth, mc.displayHeight, surface.uiScale());
            QmlElementBridge.publish(buttonList, hits);
        } catch (Throwable t) {
            // A screen with an empty element table is still a working screen; a screen that
            // throws out of initGui is not. Never let a diagnostic take the UI down with it.
            buttonList.clear();
            System.err.println("[dwm] element table publish failed: " + t);
        }
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        // Unconditional: whatever dismissed this screen -- Escape, the caption's close, the host
        // swapping screens -- the surface goes with it. A scene nobody is going to reopen is a GPU
        // surface and a Skia context held for nothing, and this tree has no re-open path that a
        // retained one could ever serve (see the UiWindowHost note above).
        surface.close();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Deliberately no drawDefaultBackground(): the scene composites over the live frame and
        // paints its own background where it wants one.
        surface.setFramebufferId(currentFramebufferId());
        surface.frame(framebufferWidth(), framebufferHeight(), System.nanoTime());

        // Publish AFTER the frame, not in initGui. A QML layout assigns each child's x/y while
        // the scene lays out, and that happens inside surface.frame() -- so at initGui time every
        // child still sits at 0,0 and the whole element table collapses onto one rectangle. That
        // is not hypothetical: the first live run published four navigation buttons all reporting
        // {x:12, y:32, w:66, h:20}, so a click on any of them hit whichever was drawn last.
        //
        // The table is republished when the CONTENT changes, not once and never again.
        //
        // It was one-shot -- the layout is stable once laid out, and rebuilding every frame would
        // churn buttonList sixty times a second for nothing. But "stable" is only true for the
        // page you started on. A live client found it: an agent opened the panel, saw six
        // controls, clicked a navigation item, and the element table did not change -- the agent was
        // pinned to the first page while a person navigates freely, and worse, the stale
        // rectangles kept answering clicks for controls the panel had already left. The comment
        // above this line was worried about exactly that hazard and the one-shot rule delivered
        // it on every navigation instead of only on resize.
        //
        // So: collect, compare, publish only on a difference. Same no-duplicates guarantee (the
        // table is cleared and rebuilt only when it would actually differ), and navigation becomes
        // visible to the agent.
        republishIfChanged();
    }

    @Override
    public void handleMouseInput() {
        // VANILLA GETS ITS TURN TOO, and until this line the proxy buttons were reachable only
        // by a tool: `GuiActions` reflects `mouseClicked` and calls it directly, while a human's
        // click never got there because this override never called `super`. That asymmetry is
        // the worst kind of bug -- the thing works for the automated user and not for the person
        // -- and it would have made every published element un-clickable by hand.
        //
        // Called first, so vanilla's own button state (hover, `selectedButton`, the press sound
        // call) is settled before qml4j sees the event. Both paths then do the same thing:
        // vanilla walks `buttonList`, and a proxy emits the QML `clicked` signal a person would.
        try {
            super.handleMouseInput();
        } catch (Throwable t) {
            System.err.println("[dwm] vanilla input dispatch faulted: " + t);
        }
        try {
            // Framebuffer pixels; flip Y from LWJGL2's bottom-left origin to qml4j's top-left.
            int h = framebufferHeight();
            pointerX = Mouse.getEventX();
            pointerY = h - Mouse.getEventY() - 1;

            int button = Mouse.getEventButton();
            int wheel = Mouse.getEventDWheel();

            if (wheel != 0) {
                // LWJGL2 reports one notch as +/-120; qml4j wants notches, +y = up.
                surface.wheel(pointerX, pointerY, 0.0F, wheel / 120.0F);
            } else if (button >= 0) {
                if (Mouse.getEventButtonState()) {
                    surface.pointerDown(pointerX, pointerY, button);
                } else {
                    surface.pointerUp(pointerX, pointerY, button);
                }
            } else {
                surface.pointerMove(pointerX, pointerY);
            }
        } catch (Throwable t) {
            System.err.println("[dwm] mouse input faulted: " + t);
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        try {
            boolean shift = GuiScreen.isShiftKeyDown();
            boolean control = GuiScreen.isCtrlKeyDown();
            int uiKey = QmlKeyMap.fromLwjgl(keyCode, shift);

            // Escape closes the screen unless the scene claims it (a dialog wanting to
            // dismiss itself first, say).
            if (uiKey == UiKeys.ESCAPE) {
                if (!surface.key(uiKey, null, shift, control)) {
                    close();
                }
                return;
            }

            // Clipboard combos are identified by SCANCODE, not by the typed character: with
            // Ctrl held the layout decodes to a non-printable control character (Ctrl+C is
            // 0x03), so the letter has to be recovered from the key itself. Vanilla's own
            // helpers define the combos, including the Cmd-instead-of-Ctrl mapping on macOS.
            String combo = clipboardCombo(keyCode);
            if (combo != null) {
                surface.key(UiKeys.NONE, combo, shift, true);
                return;
            }

            // Printable characters travel as text; MC has already decoded the layout for us,
            // so there is no keymap to reimplement here.
            String text = isPrintable(typedChar) ? String.valueOf(typedChar) : null;
            surface.key(uiKey, text, shift, control);
        } catch (Throwable t) {
            System.err.println("[dwm] key input faulted: " + t);
        }
    }

    /**
     * The clipboard letter for a Ctrl/Cmd combo on {@code keyCode}, or null if it is not one.
     *
     * <p>Delegates the combo definition to vanilla's {@code isKeyComboCtrl*} so the modifier
     * rules — including Cmd on macOS and the requirement that Shift and Alt are up — stay in
     * one place rather than being restated here.
     */
    private static String clipboardCombo(int keyCode) {
        if (GuiScreen.isKeyComboCtrlC(keyCode)) {
            return "c";
        }
        if (GuiScreen.isKeyComboCtrlX(keyCode)) {
            return "x";
        }
        if (GuiScreen.isKeyComboCtrlV(keyCode)) {
            return "v";
        }
        return null;
    }

    /**
     * The game keeps running behind the scene.
     *
     * <p>A UI overlay is not a pause menu, and pausing would also stop the integrated server
     * in singleplayer — surprising for something meant to sit on top of live gameplay.
     */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /**
     * Dismiss this screen and hand control back to the game.
     *
     * <p>Public rather than protected because it is also {@link UiWindowHost#close()} — the same
     * action whether it comes from Escape, a caption button or the host. Widening it does not widen
     * what a caller can do: displaying a screen is already anyone's to do through {@code Minecraft}.
     */
    @Override
    public void close() {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc != null) {
            mc.displayGuiScreen(null);
        }
    }

    /** Diagnostic: last backend failure, or null. */
    public String lastError() {
        return surface.lastError();
    }

    // ---- framebuffer geometry -------------------------------------------------

    private int framebufferWidth() {
        Framebuffer fb = framebuffer();
        if (fb != null && fb.framebufferWidth > 0) {
            return fb.framebufferWidth;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.displayWidth > 0 ? mc.displayWidth : 1;
    }

    private int framebufferHeight() {
        Framebuffer fb = framebuffer();
        if (fb != null && fb.framebufferHeight > 0) {
            return fb.framebufferHeight;
        }
        Minecraft mc = Minecraft.getMinecraft();
        return mc != null && mc.displayHeight > 0 ? mc.displayHeight : 1;
    }

    /**
     * MC's own framebuffer object id, or -1 when unavailable.
     *
     * <p>-1 rather than 0 matters: 0 is the default framebuffer, and wrapping that instead of
     * MC's would target the wrong surface. The backend treats a non-positive id as "keep the
     * last known good one".
     */
    private int currentFramebufferId() {
        Framebuffer fb = framebuffer();
        return fb != null ? fb.framebufferObject : -1;
    }

    private Framebuffer framebuffer() {
        try {
            Minecraft mc = Minecraft.getMinecraft();
            return mc != null ? mc.getFramebuffer() : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static boolean isPrintable(char c) {
        return c >= 32 && c != 127;
    }
}
