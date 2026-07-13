package net.marcloud.mcp.dwm.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The Material 3 tree the overlay renders in-game. A minimal, transparent-background
 * card-with-button — enough to prove "a real M3 surface appears over the running game"
 * without depending on any game state. The background is transparent so only the M3
 * content composites over MC's frame, not an opaque quad.
 */
object OverlayContent {

    @Composable
    fun Root() {
        MaterialTheme {
            // Transparent root so the game shows through; the M3 button is the overlay.
            Box(
                Modifier.background(Color.Transparent).wrapContentSize(Alignment.TopStart)
                    .padding(12.dp)
            ) {
                Button(onClick = {}) {
                    Text("DWM overlay")
                }
            }
        }
    }
}
