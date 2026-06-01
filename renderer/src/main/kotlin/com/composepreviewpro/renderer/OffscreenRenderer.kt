package com.composepreviewpro.renderer

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.composepreviewpro.ipc.PreviewTheme
import org.jetbrains.skia.EncodedImageFormat
import java.util.Base64
import kotlin.reflect.KFunction
import kotlin.reflect.KParameter
import kotlin.reflect.jvm.javaMethod

/**
 * Performs the actual off-screen render of a @Composable function.
 *
 * Uses [ImageComposeScene] — Compose Multiplatform's headless rendering
 * surface backed by Skiko. The scene executes composition synchronously,
 * applies layout + draw, then returns a Skia Image we encode as PNG.
 *
 * Invocation strategy: the Compose compiler rewrites every @Composable
 * function to take two synthetic parameters — `Composer` and `Int $changed`
 * — appended after the source-level parameters. Kotlin reflection
 * (`KFunction`) hides these from `.parameters`, but the *Java* method
 * (`javaMethod`) exposes them at JVM level. We therefore:
 *
 *   1. Take the mocked source-level args from [ArgumentBinder]
 *   2. Append `currentComposer` (a Compose intrinsic giving us the active
 *      Composer inside composition) and `0` for $changed
 *   3. Invoke through `java.lang.reflect.Method.invoke`
 */
class OffscreenRenderer(
    private val widthPx: Int,
    private val heightPx: Int,
    private val theme: PreviewTheme = PreviewTheme.LIGHT,
    private val density: Density = Density(2f),
) {

    fun renderToBase64Png(fn: KFunction<*>, args: Map<KParameter, Any?>): String {
        val scene = ImageComposeScene(
            width = widthPx,
            height = heightPx,
            density = density,
        )
        try {
            scene.setContent {
                // Outer Material theme — overridden if the user's code
                // sets its own MaterialTheme inside. Default colorScheme
                // gives sensible foreground/background for plain Text().
                MaterialTheme(
                    colorScheme = when (theme) {
                        PreviewTheme.LIGHT -> lightColorScheme()
                        PreviewTheme.DARK -> darkColorScheme()
                    },
                ) {
                    InvokeComposable(fn, args)
                }
            }
            val image = scene.render()
            val pngData = image.encodeToData(EncodedImageFormat.PNG)
                ?: error("Skia failed to encode PNG from rendered scene")
            return Base64.getEncoder().encodeToString(pngData.bytes)
        } finally {
            scene.close()
        }
    }

    @Composable
    private fun InvokeComposable(fn: KFunction<*>, args: Map<KParameter, Any?>) {
        val javaMethod = fn.javaMethod
            ?: error("No JVM method for ${fn.name} — inline or intrinsic function?")
        // Composables declared `private` compile to `private static final`
        // JVM methods. Reflection from outside their declaring class would
        // raise IllegalAccessException without this opt-in. Useful — users
        // routinely want to preview private helper composables (StatCard,
        // RoleChip, etc.) without exposing them publicly just for the
        // preview tool.
        if (!javaMethod.canAccess(null)) {
            javaMethod.isAccessible = true
        }

        // currentComposer is a Compose intrinsic — the compiler rewrites this
        // reference to fetch the active Composer from the surrounding
        // composition. Outside @Composable scope it would throw.
        val composer = currentComposer

        // Build the positional JVM args with a correctly-computed $default
        // bitmask so omitted (defaulted) parameters use their author-declared
        // defaults rather than null/garbage. See [buildComposableJvmArgs].
        val jvmArgs = buildComposableJvmArgs(fn, args, composer)
        javaMethod.invoke(/* static target */ null, *jvmArgs)
    }
}
