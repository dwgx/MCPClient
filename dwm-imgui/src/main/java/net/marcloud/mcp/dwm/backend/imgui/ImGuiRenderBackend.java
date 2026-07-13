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
    private boolean entered;         // guard.enter() done; endFrame must guard.leave()
    private boolean newFrameStarted; // ImGui.newFrame() ran; must pair with render()
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
        // Shut the GL3 renderer down (frees its program / VBO-EBO / font texture) BEFORE
        // destroying the context — the ImGui API contract, and prevents a second set of
        // device objects leaking on a re-attach (hot-swap off imgui and back). Each step
        // fault-isolated so one failure cannot skip the rest.
        try {
            implGl3.shutdown();
        } catch (Throwable t) {
            System.err.println("[ImGuiRenderBackend] implGl3.shutdown faulted: " + t);
        }
        try {
            if (ctxCreated) {
                ImGui.destroyContext();
            }
        } catch (Throwable t) {
            System.err.println("[ImGuiRenderBackend] destroyContext faulted: " + t);
        } finally {
            ctxCreated = false;
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
        // ATOMIC begin: guard.enter() first; if ImGui setup throws, unwind (guard.leave)
        // and rethrow so a returned beginFrame always owes an endFrame and a thrown one
        // is already clean — guard.leave() must never be orphaned.
        guard.enter();
        entered = true;
        try {
            ImGuiIO io = ImGui.getIO();
            io.setDisplaySize(fbW, fbH);
            implGl3.newFrame();
            ImGui.newFrame();
            newFrameStarted = true; // ImGui.newFrame ran; endFrame MUST render to pair it
            dc.bind(ImGui.getBackgroundDrawList());
            inFrame = true;
        } catch (Throwable t) {
            unwind();
            throw t;
        }
    }

    @Override
    public DrawContext draw() {
        return dc;
    }

    @Override
    public void endFrame() {
        // Run whenever begin entered the guard, even if setup faulted before inFrame.
        if (!entered) {
            return;
        }
        unwind();
    }

    /** Balance beginFrame: render the started ImGui frame (pairs newFrame), guard.leave. */
    private void unwind() {
        try {
            if (newFrameStarted) {
                dc.endFrameCleanup();
                // ImGui.newFrame() MUST be paired with a render() or the next newFrame
                // asserts, so render even on a faulted frame.
                ImGui.render();
                implGl3.renderDrawData(ImGui.getDrawData());
            }
        } catch (Throwable t) {
            System.err.println("[ImGuiRenderBackend] unwind/render faulted: " + t);
        } finally {
            newFrameStarted = false;
            inFrame = false;
            entered = false;
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
