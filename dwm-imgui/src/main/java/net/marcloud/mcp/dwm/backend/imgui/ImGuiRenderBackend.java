package net.marcloud.mcp.dwm.backend.imgui;

import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.gl3.ImGuiImplGl3;

import net.marcloud.mcp.dwm.backend.BackendCaps;
import net.marcloud.mcp.dwm.backend.BackendHost;
import net.marcloud.mcp.dwm.backend.DrawContext;
import net.marcloud.mcp.dwm.backend.FontHandle;
import net.marcloud.mcp.dwm.backend.FontSpec;
import net.marcloud.mcp.dwm.backend.FrameInput;
import net.marcloud.mcp.dwm.backend.FrameMetrics;
import net.marcloud.mcp.dwm.backend.RenderBackend;
import net.marcloud.mcp.dwm.backend.TextMetrics;
import net.marcloud.mcp.dwm.backend.TextureData;
import net.marcloud.mcp.dwm.backend.TextureHandle;
import net.marcloud.mcp.dwm.gl.GlStateGuard;

/**
 * Dear ImGui {@link RenderBackend} — the DrawContext-axis backend that renders the DWM
 * MD3 component tree via imgui-java's GL3 renderer with native rounded rects, clipping,
 * and real font text. Sibling of {@code GlRenderBackend} on the same axis: the {@code
 * UiComposer} drives the SAME MaterialButton tree into this backend's
 * {@link ImGuiDrawContext}.
 *
 * <p>Uses ImGui's BACKGROUND draw list (no window chrome): after {@code ImGui.newFrame()}
 * the component tree emits primitives into {@link ImGui#getBackgroundDrawList()}, then
 * {@code ImGui.render()} + {@link ImGuiImplGl3#renderDrawData} flushes them over the game
 * frame. Wrapped in {@link GlStateGuard} enter/leave so MC's GlStateManager shadow is
 * reconciled after imgui's raw GL (the black/white/invisible fix, reused verbatim).
 *
 * <p>Does NOT use {@code ImGuiImplGlfw} (compiled against LWJGL 3.4.1 → NoSuchMethodError
 * on the game's 3.3.6); input wiring is a later increment. GL3 backend uses only stable
 * GL bindings.
 */
public final class ImGuiRenderBackend implements RenderBackend {

    private final GlStateGuard guard = new GlStateGuard();
    private final ImGuiImplGl3 implGl3 = new ImGuiImplGl3();
    private final ImGuiDrawContext dc = new ImGuiDrawContext();

    private boolean ctxCreated;
    private volatile int fbW = 1;
    private volatile int fbH = 1;
    private boolean inFrame;
    private boolean loggedFirstFrame;

    @Override
    public String id() {
        return "imgui";
    }

    @Override
    public BackendCaps caps() {
        // Native uniform rounding + clip + text; no per-corner radius or layer-opacity
        // compositing (folded into vertex alpha) or native shadow. Generous atlas size.
        return new BackendCaps(false, true, false, true, false, 4096);
    }

    @Override
    public void onAttach(BackendHost host) {
        try {
            fbW = Math.max(1, host.framebufferWidthPx());
            fbH = Math.max(1, host.framebufferHeightPx());
            ImGui.createContext();
            ctxCreated = true;
            ImGuiIO io = ImGui.getIO();
            io.setIniFilename(null);
            io.setDisplaySize(fbW, fbH);
            io.setDisplayFramebufferScale(1f, 1f);
            boolean ok = implGl3.init("#version 150");
            System.err.println("[ImGuiRenderBackend] attached: fb=" + fbW + "x" + fbH + " gl3.init=" + ok);
        } catch (Throwable t) {
            System.err.println("[ImGuiRenderBackend] onAttach faulted (backend disabled): " + t);
            onDetach();
        }
    }

    @Override
    public void onDetach() {
        try {
            if (ctxCreated) {
                ImGui.destroyContext();
                ctxCreated = false;
            }
        } catch (Throwable t) {
            System.err.println("[ImGuiRenderBackend] onDetach faulted: " + t);
        }
    }

    @Override
    public void beginFrame(FrameInput in, FrameMetrics metrics) {
        if (!ctxCreated) {
            return;
        }
        this.fbW = Math.max(1, metrics.widthPx());
        this.fbH = Math.max(1, metrics.heightPx());
        if (!loggedFirstFrame) {
            loggedFirstFrame = true;
            System.err.println("[ImGuiRenderBackend] first frame: fb=" + fbW + "x" + fbH + " (UI drawing)");
        }
        guard.enter();
        ImGuiIO io = ImGui.getIO();
        io.setDisplaySize(fbW, fbH);
        implGl3.newFrame();
        ImGui.newFrame();
        dc.bind(ImGui.getBackgroundDrawList());
        inFrame = true;
    }

    @Override
    public DrawContext draw() {
        return dc;
    }

    @Override
    public void endFrame() {
        if (!ctxCreated || !inFrame) {
            return;
        }
        try {
            dc.endFrameCleanup();
            ImGui.render();
            // renderDrawData is the frame's last GL work; guard.leave() then reconciles
            // MC's shadow before control returns to MC.
            implGl3.renderDrawData(ImGui.getDrawData());
        } catch (Throwable t) {
            System.err.println("[ImGuiRenderBackend] endFrame faulted (frame skipped): " + t);
        } finally {
            inFrame = false;
            guard.leave();
        }
    }

    @Override
    public TextureHandle uploadTexture(TextureData rgba) {
        return new TextureHandle(0L);
    }

    @Override
    public void freeTexture(TextureHandle h) {
        // no-op
    }

    @Override
    public FontHandle loadFont(FontSpec spec) {
        return new FontHandle(0L); // default atlas font
    }

    @Override
    public TextMetrics measureText(FontHandle f, CharSequence s, float sizePx) {
        int n = s == null ? 0 : s.length();
        float w = n * sizePx * 0.5f;
        return new TextMetrics(w, sizePx * 0.8f, sizePx * 0.2f);
    }
}
