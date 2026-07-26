package org.lwjgl.opengl;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;

import org.junit.Test;

/**
 * Headless guards for the HiDPI pixel-scale contract.
 *
 * <p>{@link Display#getWidth()}/{@link Display#getHeight()} report <em>framebuffer
 * pixels</em>, while GLFW reports cursor positions in <em>window units</em>. On a
 * Retina display those differ by 2x, so the input backend multiplies incoming
 * cursor coordinates by {@link Display#getPixelScaleX()}/{@code Y} and divides
 * when pushing a position back out. Without that, clicks land at half the
 * intended x and always in the top half of the screen.</p>
 *
 * <p>The scale itself can only be measured against a live GLFW window, so what is
 * testable headless is the contract around it: the accessors exist, they are
 * neutral before a window is created (a 0 or NaN scale would silently zero every
 * coordinate), and {@code destroy()} does not double-free the callbacks Keyboard
 * and Mouse already released. The live 2.0 case is covered by the manual
 * round-trip check recorded in docs/macos/.</p>
 */
public class DisplayPixelScaleTest {

    /** Both accessors must exist and be public static — the input backend calls them statically. */
    @Test
    public void pixelScaleAccessorsArePublicStatic() throws Exception {
        for (String name : new String[] {"getPixelScaleX", "getPixelScaleY"}) {
            Method m = Display.class.getMethod(name);
            assertNotNull(name + " must exist", m);
            assertEquals(name + " must return float", float.class, m.getReturnType());
            assertTrue(name + " must be static",
                java.lang.reflect.Modifier.isStatic(m.getModifiers()));
        }
    }

    /**
     * With no window, the scale must be exactly 1.0 — never 0 and never NaN.
     * A 0 scale would collapse every cursor coordinate to the origin; NaN would
     * make the int cast undefined. Both fail silently rather than loudly.
     */
    @Test
    public void pixelScaleIsNeutralBeforeWindowCreation() {
        assertEquals("x scale must default to 1.0", 1.0F, Display.getPixelScaleX(), 0.0F);
        assertEquals("y scale must default to 1.0", 1.0F, Display.getPixelScaleY(), 0.0F);
    }

    /** destroy() on a never-created Display must be a no-op, not an NPE. */
    @Test
    public void destroyWithoutWindowIsANoOp() {
        Display.destroy();
        assertEquals(1.0F, Display.getPixelScaleX(), 0.0F);
    }

    /**
     * Display must free only the two callbacks it owns, one at a time.
     *
     * <p>It previously called {@code Callbacks.glfwFreeCallbacks(window)}, which
     * walks every callback still registered on the window — including the
     * key/char/mouse ones {@code Keyboard.destroy()}/{@code Mouse.destroy()} had
     * just freed a few lines above. Freeing those a second time threw
     * NullPointerException inside {@code Callback.free()} on every clean exit, on
     * all platforms. Asserting the import is gone is what keeps it gone.</p>
     */
    @Test
    public void destroyDoesNotUseGlfwFreeCallbacks() throws Exception {
        StringBuilder code = new StringBuilder();
        for (String line : java.nio.file.Files.readAllLines(
                java.nio.file.Paths.get("src/main/java/org/lwjgl/opengl/Display.java"),
                java.nio.charset.StandardCharsets.UTF_8)) {
            String trimmed = line.trim();
            // Skip comments: the explanation of why glfwFreeCallbacks is wrong
            // necessarily names it, and must not read as a call site.
            if (trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")) {
                continue;
            }
            code.append(line).append('\n');
        }
        String src = code.toString();

        assertTrue("Display must not call Callbacks.glfwFreeCallbacks — it double-frees "
            + "the input callbacks Keyboard/Mouse.destroy() already released, throwing "
            + "NPE in Callback.free() on every clean exit",
            !src.contains("glfwFreeCallbacks"));

        assertTrue("destroy() must free framebufferSizeCallback explicitly",
            src.contains("framebufferSizeCallback.free()"));
        assertTrue("destroy() must free windowFocusCallback explicitly",
            src.contains("windowFocusCallback.free()"));
    }
}
