package com.digitalsmod.digitalnotify

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi

/** Keeps references to API-33-only RuntimeShader out of the main view's field types. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal object GlassShaderApi33 {
    fun create(source: String): Shader = RuntimeShader(source)

    fun setResolution(shader: Shader?, width: Float, height: Float) {
        (shader as? RuntimeShader)?.setFloatUniform("resolution", width, height)
    }

    fun setAnimation(shader: Shader?, time: Float, progress: Float) {
        (shader as? RuntimeShader)?.apply {
            setFloatUniform("time", time)
            setFloatUniform("progress", progress)
        }
    }

    fun setTheme(shader: Shader?, themeIndex: Int) {
        (shader as? RuntimeShader)?.setFloatUniform("themeMode", themeIndex.toFloat())
    }
}
