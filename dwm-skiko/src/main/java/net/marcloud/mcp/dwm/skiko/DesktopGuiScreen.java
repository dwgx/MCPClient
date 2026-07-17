package net.marcloud.mcp.dwm.skiko;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;

import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.DefaultBackendRegistry;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.component.Component;
import net.marcloud.mcp.dwm.component.FrameComponentContext;
import net.marcloud.mcp.dwm.compositor.Compositor;
import net.marcloud.mcp.dwm.compositor.UiComposer;
import net.marcloud.mcp.dwm.compositor.WidgetId;
import net.marcloud.mcp.dwm.desktop.DesktopBoot;
import net.marcloud.mcp.dwm.gl.FrameClock;
import net.marcloud.mcp.dwm.gl.GameBackendHost;

/**
 * The DWM launcher as ONE real {@link GuiScreen} — the single-pipeline replacement for the
 * old three-system overlay (a bytecode-injected render-frame painter + a raw Mouse poller +
 * an empty input-swallowing {@code DesktopScreen}). This is the architecture every reference
 * MC 1.8.9 client uses (Southside {@code PowerClickGui}, Novoline {@code DropdownGUI},
 * Wurst {@code NavigatorScreen}): the ClickGUI IS a GuiScreen, and MC's own screen lifecycle
 * is the sole authority for render, input, resize, and focus.
 *
 * <p>All input the MD3 component tree hit-tests flows through ONE {@link FrameInput} built
 * each frame — pointer + button mask from MC's callbacks, scroll from the wheel event, and
 * typed keys collected between frames. There is no parallel poller and no second screen.
 *
 * <ul>
 *   <li><b>Render</b> — MC calls {@link #drawScreen} every frame inside its GUI pass (GL
 *       context current, 2D ortho + GlStateManager state already set up); we drive the MD3
 *       tree through {@link UiComposer} + the Skiko backend.</li>
 *   <li><b>Input</b> — MC routes {@link #mouseClicked}/{@link #mouseReleased} and the wheel
 *       (via {@link #handleMouseInput}) to us because we are {@code currentScreen}; the
 *       pointer arrives as {@code drawScreen}'s args; typed chars via {@link #keyTyped}.</li>
 *   <li><b>Resize/focus</b> — being current, MC ungrabs the cursor and stops feeding the
 *       world look/keys for free; a resize re-runs {@link #initGui} with fresh
 *       {@code width}/{@code height}. Nothing here owns a framebuffer to rebuild.</li>
 * </ul>
 *
 * <p><b>P1 scope.</b> Uses the existing pixel-space {@link SkikoRenderBackend} unchanged, so
 * the tree lays out in framebuffer pixels and MC's ScaledResolution mouse coords are converted
 * to pixels ({@link ScreenPointerState#scaledToPixel}). P2 flips both to scaled space and makes
 * the backend target MC's currently-bound FBO per frame (the resize fix). RShift-toggle and
 * movement-key handling still live in {@code DesktopInputState}; P3 shrinks that to pure state.
 *
 * <p>Lives in {@code dwm-skiko} — the only module that compile-sees BOTH MC's {@code GuiScreen}
 * ({@code client}, provided) and the Skiko backend. Every backend touch is fault-isolated so a
 * GL/Skia fault degrades to a skipped frame, never a crash on the render thread.
 */
public final class DesktopGuiScreen extends GuiScreen {

    /** LWJGL scancode for the launcher's open key (RSHIFT) — filtered so keyTyped never
     *  re-drives the DesktopInputState open/close toggle (the screen owns open/close now). */
    private static final int KEY_RSHIFT = 0x36;
    private static final int KEY_ESCAPE = 0x01;
    /** Backdrop dim: ~47% black (alpha 0x78) — darkens the live world/menu behind the panel. */
    private static final int DIM_ARGB = 0x78000000;

    private final ScreenPointerState pointer = new ScreenPointerState();
    private final FrameClock clock = new FrameClock();

    // Built in initGui() (MC calls it on show + on every resize); nulled defensively so a
    // fault during build leaves a safe no-op screen rather than a half-wired one.
    private UiComposer composer;

    // Scroll notches accumulated since the last frame (from the wheel event), read once per
    // drawScreen then cleared — same read-once-per-frame discipline the reference clients use.
    private float pendingScrollY;
    // Scancodes typed since the last frame, drained into FrameInput.keyEvents each frame. The
    // tree's DesktopInputState.update edge-detects a per-frame down-set, so a one-frame pulse
    // reads as a single press (a held key repeats keyTyped, staying in the set = one char).
    private final List<Integer> pendingKeys = new ArrayList<>();

    /**
     * Build (or rebuild, on resize) the launcher: a backend registry with the Skiko backend
     * active, the shared {@link DesktopBoot} bundle (theme + live board-chip catalog), and the
     * {@link UiComposer} that walks the MD3 tree each frame. Fault-isolated: any wiring failure
     * leaves {@code composer == null} so {@link #drawScreen} no-ops instead of throwing.
     */
    @Override
    public void initGui() {
        // MC calls initGui() on show AND on EVERY window resize (via setWorldAndResolution).
        // Build the composer + Skiko backend exactly ONCE and reuse it across resizes — a
        // fresh backend per resize would leak a native DirectContext + Surface each time
        // (Skiko Managed native memory). The single backend already re-targets MC's current
        // framebuffer per frame (SkikoRenderBackend.beginFrame re-queries the bound FBO and
        // rebuilds its wrap on a size change), so reuse is both leak-free AND handles resize:
        // the layout picks up the fresh width/height every frame, no menu-owned FBO to lag.
        if (composer != null) {
            return;
        }
        try {
            BackendHost host = new GameBackendHost(0L);
            DefaultBackendRegistry registry = new DefaultBackendRegistry();
            registry.register(new SkikoRenderBackend());
            registry.activate("skiko");

            Compositor compositor = new Compositor();
            DesktopBoot boot = DesktopBoot.create();
            FrameComponentContext ctx = new FrameComponentContext(
                    boot.theme(), compositor.store(), WidgetId.root("overlay"));
            Component root = boot.root();
            this.composer = new UiComposer(host, registry, compositor, ctx, root);
            System.out.println("[DesktopGuiScreen] launcher screen armed (single-GuiScreen path)");
        } catch (Throwable t) {
            System.err.println("[DesktopGuiScreen] initGui faulted (inert screen): " + t);
            this.composer = null;
        }
    }

    // ---- render -----------------------------------------------------------------------

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        // E3 backdrop dim: a semi-transparent black wash over the whole screen (Novoline's
        // approach), drawn with MC's own Gui.drawRect in ScaledResolution space — no GL
        // isolation needed, and because doesGuiPauseGame()==false the live world (or main-menu
        // panorama) still renders behind it, so the dim reads as "darkened background" not
        // black. Drawn FIRST so the launcher panel sits on top. width/height are the current
        // scaled dims MC set on this screen, so it always covers a freshly-resized window.
        drawRect(0, 0, this.width, this.height, DIM_ARGB);

        if (composer == null) {
            return;
        }
        // Pointer arrives in ScaledResolution (GUI) coords; the P1 backend lays out in
        // framebuffer pixels, so convert with the live scale factor.
        int scale = scaleFactor();
        pointer.moveTo(
                ScreenPointerState.scaledToPixel(mouseX, scale),
                ScreenPointerState.scaledToPixel(mouseY, scale));

        // Drain this frame's scroll + typed keys, then reset the accumulators.
        float scroll = pendingScrollY;
        pendingScrollY = 0f;
        List<Integer> keys = pendingKeys.isEmpty() ? List.of() : new ArrayList<>(pendingKeys);
        pendingKeys.clear();

        FrameInput input = new FrameInput(
                pointer.pointerX(), pointer.pointerY(), pointer.buttonMask(),
                0f, scroll, List.of(), keys);
        try {
            // scale=1: the tree already lays out in the backend's pixel space; dt is the real
            // wall-clock delta so animations run at the true render rate.
            composer.driveFrame(input, 1f, clock.tick());
        } catch (Throwable t) {
            System.err.println("[DesktopGuiScreen] drawScreen faulted (frame skipped): " + t);
        }
    }

    // ---- input ------------------------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        pointer.press(mouseButton);
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int state) {
        pointer.release(state);
    }

    /**
     * The scroll wheel: MC's {@code handleMouseInput} pumps the wheel event; add its delta
     * (accumulated notches) so {@link #drawScreen} can feed it into this frame's input. Call
     * super first so normal button events still reach {@code mouseClicked}/{@code mouseReleased}.
     */
    @Override
    public void handleMouseInput() throws java.io.IOException {
        super.handleMouseInput();
        try {
            int dWheel = org.lwjgl.input.Mouse.getDWheel();
            if (dWheel != 0) {
                pendingScrollY += dWheel / 120f; // +/-120 per physical notch, up positive
            }
        } catch (Throwable ignored) {
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == KEY_ESCAPE) {
            close(); // ESC hands control back to the game
            return;
        }
        if (keyCode == KEY_RSHIFT) {
            return; // the open key — must not re-drive the launcher's own open/close toggle
        }
        // Queue the scancode for next frame's FrameInput.keyEvents (search box / rename).
        pendingKeys.add(keyCode);
    }

    // ---- lifecycle --------------------------------------------------------------------

    /** Free the Skiko backend's native resources when the screen goes away. */
    @Override
    public void onGuiClosed() {
        try {
            if (composer != null) {
                composer.detachActive();
            }
        } catch (Throwable ignored) {
        }
        composer = null;
    }

    /** Don't pause singleplayer — the world (or main-menu panorama) keeps rendering behind
     *  the launcher, so a dim has something to show through. */
    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    /** Read the live GUI scale factor; 1 on any fault (never zero coords). */
    private int scaleFactor() {
        try {
            Minecraft m = this.mc != null ? this.mc : Minecraft.getMinecraft();
            return new ScaledResolution(m).getScaleFactor();
        } catch (Throwable t) {
            return 1;
        }
    }

    /** Close this launcher screen, returning input/focus to the game. */
    private void close() {
        try {
            Minecraft m = this.mc != null ? this.mc : Minecraft.getMinecraft();
            m.displayGuiScreen(null);
            m.setIngameFocus();
        } catch (Throwable ignored) {
        }
    }
}
