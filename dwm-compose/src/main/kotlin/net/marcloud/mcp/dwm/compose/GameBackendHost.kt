package net.marcloud.mcp.dwm.compose

import net.marcloud.mcp.dwm.backend.BackendHost

/**
 * [BackendHost] backed by the live game, resolving the GLFW window handle and MC's
 * framebuffer facts REFLECTIVELY so this module hard-links neither the shim nor MC at
 * compile time (they are on the runtime classpath via the game jars). All lookups are
 * fault-isolated: an unresolved handle degrades to the sentinel the adapter treats as
 * "use the default framebuffer / unknown", never a throw on the render thread.
 */
class GameBackendHost(private val windowHint: Long) : BackendHost {

    override fun windowHandle(): Long {
        if (windowHint != 0L) return windowHint
        // org.lwjgl.opengl.Display.getWindow() (the shim) returns the GLFW window handle.
        return try {
            val display = Class.forName("org.lwjgl.opengl.Display")
            (display.getMethod("getWindow").invoke(null) as? Long) ?: 0L
        } catch (t: Throwable) {
            0L
        }
    }

    // The overlay runs from the render-frame advice, which fires on the game render
    // thread with GL current — so by construction the caller IS on the render thread.
    override fun onRenderThread(): Boolean = true

    /**
     * The FBO MC is drawing into. At the updateCameraAndRender exit seam, MC's own
     * framebufferMc is still bound (unbind/blit is downstream), so glGetInteger of the
     * live binding is the correct target; -1 lets the adapter fall back to 0.
     */
    override fun currentFramebufferId(): Int {
        return try {
            val gl11 = Class.forName("org.lwjgl.opengl.GL11")
            // GL_FRAMEBUFFER_BINDING = 0x8CA6
            gl11.getMethod("glGetInteger", Int::class.javaPrimitiveType)
                .invoke(null, 0x8CA6) as? Int ?: -1
        } catch (t: Throwable) {
            -1
        }
    }

    override fun framebufferWidthPx(): Int = mcDisplayField("displayWidth")
    override fun framebufferHeightPx(): Int = mcDisplayField("displayHeight")

    /** Read a private int field off the live Minecraft singleton (e.g. displayWidth). */
    private fun mcDisplayField(name: String): Int {
        return try {
            val mc = Class.forName("net.minecraft.client.Minecraft")
            val instance = mc.getMethod("getMinecraft").invoke(null)
            mc.getDeclaredField(name).apply { isAccessible = true }.getInt(instance)
        } catch (t: Throwable) {
            0
        }
    }
}
