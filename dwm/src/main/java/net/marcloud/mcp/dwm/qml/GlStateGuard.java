package net.marcloud.mcp.dwm.qml;

import net.minecraft.client.renderer.GlStateManager;

import org.lwjgl.opengl.GL11;

/**
 * Brackets a Skija frame so MC keeps rendering afterwards.
 *
 * <p>The problem is not GL state as such — it is that MC does not read GL state, it
 * <i>shadows</i> it. {@code GlStateManager} keeps its own mirror of blend, alpha, depth,
 * texture and colour, and skips redundant GL calls based on that mirror. Skija issues raw GL
 * directly, so after a Skija frame the real GL state and MC's mirror disagree, and MC will not
 * correct the difference because as far as its mirror is concerned nothing changed. The visible
 * result is a game that renders wrong, or not at all, from the next frame on.
 *
 * <p>So restoring GL alone is insufficient: the fix is to push/pop the real state around the
 * Skija frame <em>and</em> drive it back through {@code GlStateManager} so the mirror agrees
 * with reality on the way out.
 *
 * <p>Both methods are called on the render thread with MC's context current, and neither
 * throws: a GL fault here would otherwise take down the game loop.
 */
final class GlStateGuard {

    private GlStateGuard() {
    }

    /**
     * Save the state Skija is about to disturb. Pairs with exactly one {@link #leave()}.
     *
     * <p>{@code glPushAttrib} covers the fixed-function state MC's 2.1-era pipeline uses, and
     * the matrix pushes cover the transform stacks Skia sets up for its own projection.
     */
    static void enter() {
        try {
            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            GlStateManager.pushMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPushMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPushMatrix();
        } catch (Throwable t) {
            System.err.println("[dwm] GlStateGuard.enter faulted: " + t);
        }
    }

    /**
     * Restore the state and re-sync MC's shadow of it.
     *
     * <p>The {@code GlStateManager} calls after {@code glPopAttrib} are the load-bearing part:
     * popping fixes real GL, but MC's mirror is still whatever it was before Skija ran, so the
     * next frame's redundancy checks would be made against stale values. Setting these
     * explicitly through the manager puts both in agreement.
     */
    static void leave() {
        try {
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_PROJECTION);
            GL11.glPopMatrix();
            GL11.glMatrixMode(GL11.GL_MODELVIEW);
            GlStateManager.popMatrix();
            GL11.glPopAttrib();

            // Re-assert through the manager so its shadow matches the popped reality.
            GlStateManager.enableTexture2D();
            GlStateManager.enableAlpha();
            GlStateManager.enableBlend();
            // 1.8.9's GlStateManager takes raw GL enums here; the SourceFactor/DestFactor
            // enums belong to later MC versions.
            GlStateManager.tryBlendFuncSeparate(
                GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ZERO);
            GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
            GlStateManager.bindTexture(0);
        } catch (Throwable t) {
            System.err.println("[dwm] GlStateGuard.leave faulted: " + t);
        }
    }
}
