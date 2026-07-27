package net.marcloud.mcp.dwm.qml;

import net.marcloud.mcp.dwm.ui.UiKeys;
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
public class QmlGuiScreen extends GuiScreen {

    private final QmlUiSurface surface;

    /** Last known pointer position in framebuffer pixels, top-left origin. */
    private float pointerX;
    private float pointerY;

    /**
     * @param qmlPath resource path of the scene, resolved by qml4j's loader
     */
    public QmlGuiScreen(String qmlPath) {
        this.surface = new QmlUiSurface(qmlPath);
    }

    @Override
    public void initGui() {
        // Repeat events so held arrows/backspace behave in text fields, as vanilla text
        // screens do. Reset in onGuiClosed.
        Keyboard.enableRepeatEvents(true);
        surface.open(framebufferWidth(), framebufferHeight());
    }

    @Override
    public void onGuiClosed() {
        Keyboard.enableRepeatEvents(false);
        surface.close();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // Deliberately no drawDefaultBackground(): the scene composites over the live frame and
        // paints its own background where it wants one.
        surface.setFramebufferId(currentFramebufferId());
        surface.frame(framebufferWidth(), framebufferHeight(), System.nanoTime());
    }

    @Override
    public void handleMouseInput() {
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

    /** Dismiss this screen and hand control back to the game. */
    protected void close() {
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
