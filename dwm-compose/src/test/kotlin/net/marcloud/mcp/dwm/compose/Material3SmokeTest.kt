package net.marcloud.mcp.dwm.compose

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Material 3 smoke: render a real M3 [Button] + [Text] inside a [MaterialTheme] to a
 * PNG, no GL. Distinct from [ComposePhase0SmokeTest] (which uses only foundation) —
 * this proves the material3-desktop artifact actually LINKS and paints against the
 * 1.11.1 core, not just that the renderer works.
 *
 * <p>material3-desktop is on the 1.11.0-alpha07 line (no stable 1.11.x); the core is
 * pinned to 1.11.1 via dependencyManagement so material3 resolves against it. If this
 * ever fails with NoSuchMethodError / AbstractMethodError on androidx.compose internal
 * symbols, that is the cross-version ABI skew — drop material3 and keep the foundation
 * smoke (the renderer proof does not need M3).
 */
class Material3SmokeTest {

    @Test
    fun material3ButtonRendersToPng() {
        val w = 160
        val h = 64
        val scene = ImageComposeScene(width = w, height = h, density = Density(1f)) {
            MaterialTheme {
                Button(onClick = {}) {
                    Text("DWM M3")
                }
            }
        }
        try {
            val image = scene.render(0L)
            assertEquals(w, image.width)
            assertEquals(h, image.height)
            val bytes = image.encodeToData(EncodedImageFormat.PNG)
                ?.bytes ?: error("Skia failed to encode PNG")
            assertTrue("PNG non-empty", bytes.size > 8)
            assertEquals(0x89.toByte(), bytes[0])
            assertEquals('P'.code.toByte(), bytes[1])
            runCatching {
                val out = File("target/dwm-compose-m3-smoke.png")
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
            }
        } finally {
            scene.close()
        }
    }
}
