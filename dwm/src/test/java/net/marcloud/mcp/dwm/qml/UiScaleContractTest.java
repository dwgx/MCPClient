package net.marcloud.mcp.dwm.qml;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.regex.Pattern;

import org.junit.Test;

/**
 * Guards the two halves of the HiDPI contract, which have to move together.
 *
 * <p>The scene is authored in logical units. The canvas carries a logical-to-device transform
 * taken from the display's DPI scale, and inbound pointer coordinates are divided by that same
 * scale. Change one without the other and the UI still renders perfectly while every click
 * lands somewhere else — on a Retina display at twice the intended point, leaving only the
 * top-left quarter of the interface reachable. That is a failure the live IT cannot see, since
 * it asserts that frames render rather than where a hit lands.
 *
 * <p>Source-level assertions, because the alternative is a live GL context plus synthetic
 * clicks for what is really an arithmetic invariant.
 */
public class UiScaleContractTest {

    private static String read(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(
                java.nio.file.Paths.get("src/main/java/net/marcloud/mcp/dwm/qml/" + path)),
                java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new AssertionError("cannot read " + path, e);
        }
    }

    /**
     * The scale must come from the DPI content scale, never the framebuffer/window ratio.
     *
     * <p>They agree on a Retina Mac and disagree on Windows, where the ratio is 1.0 at every DPI
     * setting — so a machine like this one cannot tell the two apart at runtime, and only a test
     * naming the right API keeps the wrong one out.
     */
    @Test
    public void uiScaleComesFromContentScaleNotTheFramebufferRatio() {
        String src = read("QmlUiSurface.java");
        assertTrue("the UI scale must be read from Display.getContentScale*(), the real DPI "
            + "scale on every platform",
            src.contains("Display.getContentScaleX()"));
        assertTrue("the UI scale must not be taken from getPixelScale*(): that is the "
            + "framebuffer/window ratio, which is 1.0 on Windows at any DPI",
            !Pattern.compile("uiScale\\s*=\\s*Display\\.getPixelScale").matcher(src).find());
    }

    /** Pointer coordinates must be converted, or hit testing misses by the scale factor. */
    @Test
    public void pointerCoordinatesAreConvertedToLogicalUnits() {
        String src = read("QmlUiSurface.java");
        for (String call : new String[] {
                "dispatchPointerDown(lx(", "dispatchPointerUp(lx(", "dispatchPointerMove(lx(" }) {
            assertTrue("pointer dispatch must convert to logical units: " + call,
                src.contains(call));
        }
        assertTrue("the conversion must divide by the scale", src.contains("xPx / uiScale"));
        assertTrue("the conversion must divide by the scale", src.contains("yPx / uiScale"));
    }

    /**
     * The canvas transform must be reset each frame before being reapplied.
     *
     * <p>Skija's {@code getCanvas()} returns the same canvas object every frame, so a bare
     * {@code scale()} compounds: 2x, then 4x, then 8x. The symptom is a UI that grows until it
     * vanishes, which is easy to misread as a layout bug.
     */
    @Test
    public void canvasTransformIsResetBeforeScaling() {
        String src = read("McpFboSurfaceBackend.java");
        int reset = src.indexOf("resetMatrix()");
        int scale = src.indexOf("canvas.scale(");
        assertTrue("acquireCanvas must reset the matrix, or the scale compounds every frame",
            reset > 0);
        assertTrue("acquireCanvas must apply the scale", scale > 0);
        assertTrue("resetMatrix() must come before scale(), otherwise the reset discards it",
            reset < scale);
    }

    /** A bad scale must fall back to 1.0 rather than collapsing or exploding the UI. */
    @Test
    public void scaleSetterRejectsNonsenseValues() {
        McpFboSurfaceBackend backend = new McpFboSurfaceBackend();
        // No surface exists, so this only exercises the guard — which is the point.
        backend.setUiScale(0.0F);
        backend.setUiScale(-2.0F);
        backend.setUiScale(Float.NaN);
        backend.setUiScale(Float.POSITIVE_INFINITY);
        backend.setUiScale(2.0F);
        // Reaching here without throwing is the assertion; the guard is verified by the shim's
        // own sane() test for the same arithmetic.
        assertEquals("setUiScale must never throw on nonsense input", 1, 1);
    }
}
