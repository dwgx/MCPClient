package net.marcloud.mcp.dwm.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf

/**
 * Phase-0 toolchain proof: the smallest possible `@Composable`. If this compiles, it
 * proves the Maven wiring works end to end — kotlin-maven-plugin 2.4.0 + the Compose
 * compiler plugin (-Xplugin) + the Compose runtime 1.11.1 (built against Kotlin
 * 2.2.20 metadata) are mutually compatible under Maven, and jvmTarget=25 is accepted.
 * No GL, no Skiko rendering yet — just the compiler transform on a `@Composable`.
 */
object ComposeSmoke {

    /** A trivial composable: exercises the Compose compiler's function transform. */
    @Composable
    fun Counter(): Int {
        // remember + mutableStateOf force the compiler plugin's slot-table rewrite,
        // so a mis-wired compiler plugin fails to compile this, not just silently pass.
        val state = remember { mutableStateOf(0) }
        return state.value
    }
}
