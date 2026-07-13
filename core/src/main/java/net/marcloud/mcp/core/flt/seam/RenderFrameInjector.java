package net.marcloud.mcp.core.flt.seam;

import java.lang.instrument.Instrumentation;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.agent.builder.ResettableClassFileTransformer;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;
import net.marcloud.mcp.core.se.SeProtectedObjects;

/**
 * Installs a runtime hook into {@code EntityRenderer.updateCameraAndRender} to fire a
 * render-frame callback once per rendered frame — the render-frame twin of
 * {@link TickInjector} (which hooks the 20Hz logic tick {@code Minecraft.runTick}).
 * Uses the same Byte Buddy {@link AgentBuilder} retransformation to inline
 * {@link RenderFrameAdvice} at the method EXIT.
 *
 * <p>Why this target (confirmed against the client vanilla mappings): {@code
 * updateCameraAndRender(float,long)} is the single per-frame render call site
 * ({@code Minecraft} calls it once outside the tick loop), the GL context is current
 * there, and its EXIT lands after both of MC's own 2D GUI sub-passes (HUD overlay and
 * any open screen) and before the buffer swap — exactly the seam a content overlay
 * needs. This is where {@code EntityRenderer.updateCameraAndRender}'s exit precedes
 * {@code Minecraft}'s FBO unbind/blit and {@code Display.update} swap.
 *
 * <p>Lifecycle copied verbatim from {@link TickInjector}: retransform via
 * Instrumentation, advice inlined (not delegated), transformer handle retained so
 * {@link #uninstall()} can truly revert, honest reset-failure handling. Installation
 * is idempotent-safe.
 */
public final class RenderFrameInjector {

    private static final String ENTITY_RENDERER =
            "net.minecraft.client.renderer.EntityRenderer";

    private volatile boolean installed;
    /** Retained so uninstall() can reset the retransform and truly revert the hook. */
    private volatile ResettableClassFileTransformer transformer;
    private volatile Instrumentation inst;

    /**
     * The revert operation, abstracted so {@link #uninstall()} can be exercised
     * without a live {@code -javaagent} JVM (mirrors {@link TickInjector.ResetAction}).
     */
    @FunctionalInterface
    interface ResetAction {
        /** @return true only if the retransform was confirmed reverted. */
        boolean reset();
    }

    private volatile ResetAction resetAction;

    /**
     * Install the render-frame hook: wire {@link RenderBridge} to {@code sink} and
     * retransform {@code EntityRenderer.updateCameraAndRender}. Safe to call once;
     * repeated calls are no-ops.
     *
     * @param inst the agent Instrumentation (must support retransform)
     * @param sink the render-frame consumer, or null to install the hook with no sink
     * @throws IllegalStateException if Instrumentation is unavailable
     */
    public synchronized void install(Instrumentation inst, RenderBridge.RenderFrameSink sink) {
        if (installed) {
            return;
        }
        if (inst == null || !inst.isRetransformClassesSupported()) {
            throw new IllegalStateException(
                    "Cannot install render-frame injector: Instrumentation/retransform "
                    + "unavailable. Start with -javaagent:core-agent.jar");
        }
        RenderBridge.setSink(sink);

        this.transformer = new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .disableClassFormatChanges()
                .type(ElementMatchers.named(ENTITY_RENDERER).and(notProtected()))
                .transform((builder, typeDescription, classLoader, module, protectionDomain) ->
                        builder.visit(Advice.to(RenderFrameAdvice.class)
                                .on(ElementMatchers.named("updateCameraAndRender")
                                        .and(ElementMatchers.takesArguments(float.class, long.class)))))
                .installOn(inst);
        this.inst = inst;
        this.resetAction = () -> transformer.reset(inst,
                AgentBuilder.RedefinitionStrategy.RETRANSFORMATION);
        installed = true;
    }

    /**
     * Uninstall the render-frame hook: reset the transformer, which retransforms
     * {@code EntityRenderer} back and stops render-frame callbacks. Returns true if it
     * was installed and reverted. Genuinely reversible (no restart needed).
     */
    public synchronized boolean uninstall() {
        if (!installed || resetAction == null) {
            return false;
        }
        boolean reverted;
        try {
            reverted = resetAction.reset();
        } catch (RuntimeException | Error e) {
            // reset() blew up: advice is still installed. Keep state intact so the
            // caller sees the honest failure rather than orphaning live advice.
            System.err.println("[RenderFrameInjector] render reset threw, retaining state: " + e);
            return false;
        }
        if (!reverted) {
            // Could not revert — do NOT clear state, or stale advice stays live while
            // we wrongly believe it is gone and could never retry.
            return false;
        }
        RenderBridge.setSink(null);
        transformer = null;
        resetAction = null;
        inst = null;
        installed = false;
        return true;
    }

    /**
     * Test seam: simulate a live install whose revert is governed by {@code action},
     * without a {@code -javaagent} JVM or a real EntityRenderer retransform.
     */
    synchronized void primeInstalledForTest(ResetAction action) {
        this.resetAction = action;
        this.installed = true;
    }

    public boolean isInstalled() {
        return installed;
    }

    /**
     * A Byte Buddy matcher rejecting any {@linkplain SeProtectedObjects protected}
     * Core class. EntityRenderer is never protected, but the guard is kept for
     * uniformity with {@link TickInjector} — the signer-independent backstop that no
     * protected class is ever a retransform target.
     */
    private static ElementMatcher.Junction<TypeDescription> notProtected() {
        ElementMatcher<TypeDescription> isProtected =
                target -> SeProtectedObjects.isProtected(target.getName());
        return ElementMatchers.not(isProtected);
    }
}
