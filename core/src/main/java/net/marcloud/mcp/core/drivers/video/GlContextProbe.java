package net.marcloud.mcp.core.drivers.video;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL32;

/**
 * A read-only snapshot of the live OpenGL context the LWJGL3 shim handed the game
 * — GL version, vendor, renderer, and the context profile mask. This is the piece
 * of the {@code dev_probe} diagnostic that both (a) proves we are running on a real
 * GPU context (not headless) and (b) feeds KI-1 (the mipmap blue-speckle bug),
 * whose leading suspect is a core-vs-compatibility profile difference under LWJGL3
 * affecting {@code GL_TEXTURE_MAX_LEVEL}/LOD sampling.
 *
 * <p><b>Threading:</b> GL calls require the thread that owns the current context
 * (the render/game thread). Callers MUST invoke {@link #capture()} through
 * {@code GameBridge.onGameThread(...)}. Off-thread or headless (no current context)
 * → every GL call throws or returns null; {@link #capture()} swallows that and
 * returns {@link #absent(String)} so the probe degrades gracefully instead of
 * crashing.
 */
public record GlContextProbe(boolean present,
                             String version,
                             String vendor,
                             String renderer,
                             String shadingLanguage,
                             int profileMask,
                             boolean coreProfile,
                             boolean compatibilityProfile,
                             String note) {

    /** "No GL context" snapshot (headless, off-thread, or a driver that refused). */
    public static GlContextProbe absent(String why) {
        return new GlContextProbe(false, null, null, null, null, 0, false, false, why);
    }

    /**
     * Read the current GL context strings. Call ON the game/render thread. Any
     * failure (no current context, driver refusal) yields {@link #absent(String)}.
     */
    public static GlContextProbe capture() {
        try {
            // GATE FIRST — do NOT call any raw gl* before confirming a context.
            // With no current context, LWJGL3's raw glGetString can hard-CRASH the
            // JVM (native segfault, not a Java exception). GL.getCapabilities()
            // instead throws a catchable IllegalStateException when this thread has
            // no capabilities set, so we detect "headless / off render thread"
            // safely and never touch native GL without a context.
            try {
                GL.getCapabilities();
            } catch (Throwable noCtx) {
                return absent("no current GL context (headless or off render thread)");
            }

            String version = GL11.glGetString(GL11.GL_VERSION);
            if (version == null) {
                return absent("GL context present but GL_VERSION null (driver refused)");
            }
            String vendor = GL11.glGetString(GL11.GL_VENDOR);
            String renderer = GL11.glGetString(GL11.GL_RENDERER);
            String glsl = safeGet(GL20.GL_SHADING_LANGUAGE_VERSION);

            int mask = 0;
            boolean core = false;
            boolean compat = false;
            try {
                // GL_CONTEXT_PROFILE_MASK is GL 3.2+ (on GL32 in LWJGL 3.3.x). On a
                // pre-3.2 / pure-compat context this query itself errors — treat
                // "unavailable" as compat.
                mask = GL11.glGetInteger(GL32.GL_CONTEXT_PROFILE_MASK);
                core = (mask & GL32.GL_CONTEXT_CORE_PROFILE_BIT) != 0;
                compat = (mask & GL32.GL_CONTEXT_COMPATIBILITY_PROFILE_BIT) != 0;
            } catch (Throwable t) {
                // Query unsupported → almost certainly a compatibility context.
                compat = true;
            }

            String note = core && !compat
                    ? "CORE profile — KI-1 suspect: fixed-function + GL_TEXTURE_MAX_LEVEL/LOD may misbehave"
                    : "compatibility profile (expected for 1.8.9 fixed-function)";

            return new GlContextProbe(true, version, vendor, renderer, glsl,
                    mask, core, compat, note);
        } catch (Throwable t) {
            return absent("GL query failed: " + t);
        }
    }

    private static String safeGet(int enumValue) {
        try {
            return GL11.glGetString(enumValue);
        } catch (Throwable t) {
            return null;
        }
    }
}
