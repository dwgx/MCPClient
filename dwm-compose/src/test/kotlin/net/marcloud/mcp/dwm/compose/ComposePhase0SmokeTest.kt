package net.marcloud.mcp.dwm.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Phase-0 toolchain smoke: render a `@Composable` tree to a PNG with NO GL context and
 * NO AWT window, via [ImageComposeScene] (a CPU raster Skia surface). Green here proves
 * the FULL toolchain end to end:
 *
 *  1. kotlin-maven-plugin 2.4.0 + the Compose compiler plugin compiled `@Composable`;
 *  2. the Compose runtime/ui/foundation 1.11.1 libraries load and compose a tree;
 *  3. **Skiko native (skiko-awt-runtime-windows-x64 0.144.6) loads in-process** and
 *     rasterizes — the DLL-load risk the design brief flagged.
 *
 * This is a NON-VACUOUS regression test: it fails on any of the three break modes
 * (compiler-plugin skew, missing/incompatible Compose libs, Skiko native load failure).
 * It is the honest boundary marker — it validates the TOOLCHAIN, not the GL/FBO path,
 * which still requires a live in-game test later.
 */
class ComposePhase0SmokeTest {

    @Test
    fun composableRendersToPng() {
        val w = 64
        val h = 48
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) {
            // A tiny foundation tree: a red box filling a blue background. Exercises the
            // compose compiler transform + foundation layout + Skiko raster draw.
            Box(Modifier.fillMaxSize().background(Color.Blue)) {
                Box(Modifier.size(16.dp).background(Color.Red))
            }
        }
        try {
            val image = scene.render(0L)
            assertEquals("rendered image width matches scene", w, image.width)
            assertEquals("rendered image height matches scene", h, image.height)

            val data = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia failed to encode PNG (native likely not loaded)")
            val bytes = data.bytes
            assertTrue("PNG bytes non-empty", bytes.size > 8)
            // PNG magic: 0x89 'P' 'N' 'G'
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals('P'.code.toByte(), bytes[1])
            assertEquals('N'.code.toByte(), bytes[2])
            assertEquals('G'.code.toByte(), bytes[3])

            // Write a viewable artifact for manual inspection (best-effort).
            runCatching {
                val out = File("target/dwm-compose-phase0-smoke.png")
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }
        } finally {
            scene.close()
        }
    }
}
