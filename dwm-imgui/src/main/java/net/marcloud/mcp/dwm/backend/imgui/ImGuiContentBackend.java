package net.marcloud.mcp.dwm.backend.imgui;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.gl3.ImGuiImplGl3;

import net.marcloud.mcp.dwm.backend.BackendCaps;
import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.ContentBackend;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.gl.GlStateGuard;

/**
 * The Dear ImGui overlay backend — implements DWM's {@link ContentBackend} and renders
 * an ImGui window each game render frame via SpaiR imgui-java's GL3 renderer, guarded by
 * {@link GlStateGuard} so MC's own rendering survives. Sibling of {@code GlContentBackend}
 * on the same SPI; imgui / Dear-ImGui types live ONLY in this adapter package.
 *
 * <p><b>The two-layer GL reconciliation.</b> {@link ImGuiImplGl3#renderDrawData} does its
 * own raw-GL backup/restore (mirrors {@code imgui_impl_opengl3.cpp}) — it returns the
 * DRIVER state to entry values. But it has ZERO knowledge of Minecraft 1.8.9's
 * {@code GlStateManager} Java-side SHADOW cache, which no-ops a later
 * {@code enableX()}/{@code bindTexture()} when the shadow already matches. So after imgui
 * restores raw GL, MC's next frame trusts a stale shadow, skips the real call, and the
 * frame renders wrong (purple-black / black). {@link GlStateGuard#leave()} is what closes
 * that gap: it write-throughs MC's private shadow fields (blend/depth/texture/active-unit)
 * and re-asserts program 0 / FBO — exactly the fix already built and regression-tested for
 * the handwritten-GL backend, reused verbatim here. The partial overlap (imgui also
 * restores blend/depth/texture raw GL) is harmless and idempotent.
 *
 * <p><b>Honest scope (first increment).</b> Renders a single static ImGui window to prove
 * "an ImGui overlay appears in-game and MC still renders." No input yet: {@code
 * ImGuiImplGlfw} is deliberately NOT used — imgui-java 1.92.0's GLFW backend was compiled
 * against LWJGL 3.4.1 and risks {@code NoSuchMethodError} on the game's 3.3.6, whereas the
 * GL3 render backend uses only stable GL bindings identical across 3.3.x/3.4.x. Input
 * wiring from MC is a later increment. Every GL touch is on the render thread with the
 * context current; the whole frame is fault-isolated so an imgui/GL fault never breaks the
 * game render thread.
 */
public final class ImGuiContentBackend implements ContentBackend {

    private final GlStateGuard guard = new GlStateGuard();
    private final ImGuiImplGl3 implGl3 = new ImGuiImplGl3();

    private boolean ctxCreated;
    private volatile int fbW = 1;
    private volatile int fbH = 1;

    @Override
    public String id() {
        return "imgui";
    }

    @Override
    public BackendCaps caps() {
        // imgui can do path/clip/per-corner via its draw list, but this backend does not
        // expose the DrawContext primitive axis (it owns its own ImGui tree), so advertise
        // a conservative set; a generous max texture size for the font atlas.
        return new BackendCaps(false, false, false, false, false, 4096);
    }

    @Override
    public void onAttach(BackendHost host) {
        try {
            fbW = Math.max(1, host.framebufferWidthPx());
            fbH = Math.max(1, host.framebufferHeightPx());

            // Create the ImGui context (loads imgui-java64.dll on first ImGui touch; the
            // native path is set via -Dimgui.library.path in the launch jvm-args).
            ImGui.createContext();
            ctxCreated = true;
            ImGuiIO io = ImGui.getIO();
            io.setIniFilename(null); // no imgui.ini writes
            io.setDisplaySize(fbW, fbH);
            io.setDisplayFramebufferScale(1f, 1f);

            // GL3 renderer. "#version 150" for MC's GL 3.2 compat profile (do NOT rely on
            // the null default of "#version 130"). init() returns false on a context that
            // is not >= 3.2 — logged so a live smoke run surfaces it.
            boolean ok = implGl3.init("#version 150");
            System.err.println("[ImGuiContentBackend] attached: fb=" + fbW + "x" + fbH
                    + " gl3.init=" + ok);
            if (!ok) {
                System.err.println("[ImGuiContentBackend] ImGuiImplGl3.init returned false "
                        + "(GL context may be < 3.2) — overlay inert.");
            }
        } catch (Throwable t) {
            System.err.println("[ImGuiContentBackend] onAttach faulted (overlay disabled): " + t);
            onDetach();
        }
    }

    @Override
    public void onDetach() {
        // imgui-java 1.92.0's ImGuiImplGl3 has no dispose(); destroying the context frees
        // the renderer's GL resources. Fault-isolated so a double-detach cannot throw.
        try {
            if (ctxCreated) {
                ImGui.destroyContext();
                ctxCreated = false;
            }
        } catch (Throwable t) {
            System.err.println("[ImGuiContentBackend] onDetach faulted: " + t);
        }
    }

    @Override
    public void resize(int fbWidth, int fbHeight, int fbId, int fbFormat) {
        this.fbW = Math.max(1, fbWidth);
        this.fbH = Math.max(1, fbHeight);
    }

    @Override
    public void submitInput(FrameInput in) {
        // no-op: static overlay first increment (see class doc — no ImGuiImplGlfw).
    }

    @Override
    public void renderFrame(FrameMetrics m, long nanoTime) {
        if (!ctxCreated) {
            return;
        }
        try {
            guard.enter();

            ImGuiIO io = ImGui.getIO();
            io.setDisplaySize(fbW, fbH);
            implGl3.newFrame();
            ImGui.newFrame();

            // A simple window proving imgui draws over the live game. Content is static;
            // once the next-frame-correct check passes live, this grows into the real UI.
            ImGui.setNextWindowSize(240f, 90f);
            ImGui.setNextWindowPos(16f, 16f);
            if (ImGui.begin("DWM imgui overlay")) {
                ImGui.text("imgui-java live over MC 1.8.9");
                ImGui.text(fbW + " x " + fbH);
            }
            ImGui.end();

            ImGui.render();
            // renderDrawData MUST be the frame's last GL work; guard.leave() then
            // reconciles MC's shadow immediately, before control returns to MC.
            implGl3.renderDrawData(ImGui.getDrawData());
        } catch (Throwable t) {
            System.err.println("[ImGuiContentBackend] renderFrame faulted (frame skipped): " + t);
        } finally {
            guard.leave();
        }
    }

    @Override
    public boolean wantsContinuousFrames() {
        return false;
    }

    @Override
    public boolean consumedPointer() {
        return false;
    }

    @Override
    public boolean consumedKeyboard() {
        return false;
    }
}
