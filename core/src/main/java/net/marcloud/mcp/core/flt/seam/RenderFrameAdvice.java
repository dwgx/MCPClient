package net.marcloud.mcp.core.flt.seam;

import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy advice body inlined into {@code EntityRenderer.updateCameraAndRender}.
 * The render-frame twin of {@link TickAdvice}: fires at method EXIT (after MC's own
 * 2D GUI pass — HUD overlay + any open screen — and before the buffer swap), so a
 * content overlay drawn here lands over the finished game frame. Runs on the game
 * render thread with the GL context current; observes only, forwards to
 * {@link RenderBridge}.
 */
public final class RenderFrameAdvice {

    private RenderFrameAdvice() {
    }

    /** Inlined at the exit of {@code EntityRenderer.updateCameraAndRender(float,long)}. */
    @Advice.OnMethodExit
    static void exit() {
        RenderBridge.onRenderFrame();
    }
}
