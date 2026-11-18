package com.drdisagree.xmorphclock.engine.render

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.ColorUtils
import com.drdisagree.xmorphclock.domain.model.FaceData
import com.drdisagree.xmorphclock.domain.model.FaceLayer
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

internal enum class WeatherEffectKind { NONE, RAIN, SNOW, WIND, FOG, STORM }

/**
 * Lightweight, state-free weather particles. Every particle's position is derived
 * from its index and elapsed time, so no state needs to be saved between frames
 * and a face can be re-inflated at any time without visible jumps.
 */
internal object WeatherEffects {

    private const val TAU = 6.2831853f
    private val clipPath = Path()
    private val clipRect = RectF()

    /** Resolve the effect to draw. "auto" follows the live weather condition. */
    fun resolve(layer: FaceLayer, data: FaceData): Pair<WeatherEffectKind, Float> {
        val requested = layer.effectKind.trim().lowercase()
        if (requested == "none") return WeatherEffectKind.NONE to 0f

        if (requested != "auto") {
            val fixed = when (requested) {
                "rain", "drizzle", "showers" -> WeatherEffectKind.RAIN
                "snow", "sleet", "hail" -> WeatherEffectKind.SNOW
                "wind", "windy", "leaves" -> WeatherEffectKind.WIND
                "fog", "mist", "haze" -> WeatherEffectKind.FOG
                "storm", "thunder", "thunderstorm" -> WeatherEffectKind.STORM
                else -> WeatherEffectKind.NONE
            }
            val level = data.weather?.let { intensity(it.conditionCode, it.wind, 1f) } ?: 0.85f
            return fixed to level
        }

        val weather = data.weather ?: return WeatherEffectKind.NONE to 0f
        val code = weather.conditionCode
        val kind = when (code) {
            3, 4, 37, 38, 39, 45, 47 -> WeatherEffectKind.STORM
            5, 6, 7, 8, 9, 10, 11, 12, 17, 18, 35, 40 -> WeatherEffectKind.RAIN
            13, 14, 15, 16, 41, 42, 43, 46 -> WeatherEffectKind.SNOW
            23, 24 -> WeatherEffectKind.WIND
            19, 20, 21, 22 -> WeatherEffectKind.FOG
            else -> WeatherEffectKind.NONE
        }
        return kind to intensity(code, weather.wind, 1f)
    }

    private fun intensity(code: Int, wind: Int, fallback: Float): Float = when (code) {
        12, 40, 3, 4, 37, 38, 39, 45, 47 -> 1.2f
        8, 9, 5, 6, 7, 18, 19, 20, 21, 22 -> 0.6f
        23, 24 -> (wind / 20f).coerceIn(0.4f, 1.4f)
        else -> if (fallback == 1f) 0.9f else fallback
    }

    fun draw(
        canvas: Canvas,
        paint: Paint,
        kind: WeatherEffectKind,
        intensity: Float,
        width: Float,
        height: Float,
        radius: Float,
        color: Int,
        alpha: Float,
        speed: Float,
        density: Float,
        nowMs: Long
    ) {
        if (kind == WeatherEffectKind.NONE || width <= 0f || height <= 0f || alpha <= 0f) return
        val level = intensity.coerceIn(0.2f, 1.6f)
        val pace = speed.coerceIn(0.1f, 4f)
        val amount = density.coerceIn(0.2f, 3f)
        val a = alpha.coerceIn(0f, 1f)
        val t = nowMs / 1000f

        clipRect.set(0f, -height, width, 0f)
        val corner = minOf(width, height) * radius.coerceIn(0f, 0.5f)
        clipPath.rewind()
        clipPath.addRoundRect(clipRect, corner, corner, Path.Direction.CW)

        canvas.save()
        canvas.clipPath(clipPath)
        paint.isAntiAlias = true
        paint.shader = null
        when (kind) {
            WeatherEffectKind.RAIN -> drawRain(canvas, paint, width, height, color, a, pace, amount, t, false, level)
            WeatherEffectKind.STORM -> {
                drawRain(canvas, paint, width, height, color, a, pace, amount, t, true, level)
                drawFlash(canvas, paint, width, height, a, t)
            }
            WeatherEffectKind.SNOW -> drawSnow(canvas, paint, width, height, color, a, pace, amount, t)
            WeatherEffectKind.WIND -> drawWind(canvas, paint, width, height, color, a, pace, amount, t)
            WeatherEffectKind.FOG -> drawFog(canvas, paint, width, height, color, a, pace, t)
            WeatherEffectKind.NONE -> Unit
        }
        paint.shader = null
        canvas.restore()
    }

    private fun hash(n: Int): Float {
        val v = sin(n * 12.9898 + 78.233) * 43758.5453
        return (v - floor(v)).toFloat()
    }

    private fun rgba(color: Int, alpha: Float, strength: Float): Int =
        ColorUtils.setAlphaComponent(color, (alpha * strength * 255f).roundToInt().coerceIn(0, 255))

    private fun drawRain(
        canvas: Canvas, paint: Paint, width: Float, height: Float, color: Int,
        alpha: Float, speed: Float, density: Float, t: Float, heavy: Boolean, level: Float
    ) {
        val count = (((if (heavy) 46 else 28) * density * level).roundToInt()).coerceIn(6, 120)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until count) {
            val h1 = hash(i + 1)
            val h2 = hash(i + 101)
            val h3 = hash(i + 211)
            val phase = t * (0.9f + h2 * 0.5f) * speed + h3
            val progress = phase - floor(phase)
            val len = (9f + h2 * 15f) * level.coerceAtMost(1.3f)
            val x = (h1 * width + progress * width * 0.1f) % width
            val y = -height + progress * (height + len)
            paint.strokeWidth = 1f + h3 * 1.3f
            paint.color = rgba(color, alpha, 0.42f + h2 * 0.4f)
            canvas.drawLine(x, y, x - len * 0.24f, y + len, paint)
        }
    }

    private fun drawSnow(
        canvas: Canvas, paint: Paint, width: Float, height: Float, color: Int,
        alpha: Float, speed: Float, density: Float, t: Float
    ) {
        val count = ((24f * density).roundToInt()).coerceIn(5, 90)
        paint.style = Paint.Style.FILL
        for (i in 0 until count) {
            val h1 = hash(i + 7)
            val h2 = hash(i + 137)
            val h3 = hash(i + 307)
            val phase = t * (0.14f + h2 * 0.16f) * speed + h3
            val progress = phase - floor(phase)
            val drift = sin(phase * TAU * 1.7f + h1 * TAU) * (7f + h3 * 9f)
            var x = h1 * width + drift
            if (x < 0f) x += width else if (x > width) x -= width
            val y = -height + progress * (height + 16f)
            paint.color = rgba(color, alpha, 0.5f + h2 * 0.4f)
            canvas.drawCircle(x, y, 1f + h2 * 2.2f, paint)
        }
    }

    private fun drawWind(
        canvas: Canvas, paint: Paint, width: Float, height: Float, color: Int,
        alpha: Float, speed: Float, density: Float, t: Float
    ) {
        val count = ((16f * density).roundToInt()).coerceIn(4, 60)
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until count) {
            val h1 = hash(i + 13)
            val h2 = hash(i + 173)
            val h3 = hash(i + 353)
            val phase = t * (0.28f + h2 * 0.32f) * speed + h3
            val progress = phase - floor(phase)
            val x = progress * (width + 70f) - 35f
            val y = -height + h1 * height + sin(phase * TAU + h2 * TAU) * 9f
            val len = 16f + h3 * 28f
            paint.strokeWidth = 0.8f + h1 * 1.4f
            paint.color = rgba(color, alpha, 0.18f + h2 * 0.3f)
            canvas.drawLine(x, y, x + len, y - len * 0.14f, paint)
        }
    }

    private fun drawFog(
        canvas: Canvas, paint: Paint, width: Float, height: Float, color: Int,
        alpha: Float, speed: Float, t: Float
    ) {
        paint.style = Paint.Style.FILL
        for (i in 0 until 4) {
            val h = hash(i + 29)
            val phase = t * 0.07f * speed + h
            val progress = phase - floor(phase)
            val x = progress * (width * 1.7f) - width * 0.35f
            val span = width * 0.85f
            val y = -height + (0.12f + h * 0.55f) * height
            val band = height * (0.16f + h * 0.1f)
            val edge = rgba(color, alpha, 0f)
            val mid = rgba(color, alpha, 0.16f + h * 0.1f)
            paint.shader = LinearGradient(
                x, 0f, x + span, 0f,
                intArrayOf(edge, mid, mid, edge),
                floatArrayOf(0f, 0.25f, 0.75f, 1f),
                Shader.TileMode.CLAMP
            )
            canvas.drawRect(RectF(x, y, x + span, y + band), paint)
            paint.shader = null
        }
    }

    private fun drawFlash(canvas: Canvas, paint: Paint, width: Float, height: Float, alpha: Float, t: Float) {
        val phase = (t / 3.6f) % 1f
        if (phase >= 0.07f) return
        val strength = (1f - phase / 0.07f) * 0.32f
        paint.style = Paint.Style.FILL
        paint.color = ColorUtils.setAlphaComponent(0xFFFFFF, (alpha * strength * 255f).roundToInt().coerceIn(0, 255))
        canvas.drawRect(clipRect, paint)
    }
}
