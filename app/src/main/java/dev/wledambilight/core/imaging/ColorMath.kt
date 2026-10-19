package dev.wledambilight.core.imaging

import dev.wledambilight.core.model.AmbilightSettings
import dev.wledambilight.core.model.ColorBalance
import kotlin.math.pow

/**
 * Colour pipeline from captured sRGB pixels to LED output values.
 *
 * Processing order: sRGB -> linear light -> channel balance -> saturation -> brightness ->
 * user gamma -> sRGB encoding -> minimum brightness -> 8 bit.
 */
object ColorMath {

    const val NEUTRAL_GAMMA = 2.2f

    fun srgbToLinear(channel: Float): Float {
        val v = channel.coerceIn(0f, 1f)
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    fun linearToSrgb(channel: Float): Float {
        val v = channel.coerceIn(0f, 1f)
        return if (v <= 0.0031308f) v * 12.92f else 1.055f * v.pow(1f / 2.4f) - 0.055f
    }

    fun luma(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b

    fun lumaOf(rgb: FloatArray): Float = luma(rgb[0], rgb[1], rgb[2])

    /**
     * Applies the user settings to one sRGB colour (channels clamped to 0..1) in place and returns
     * the encoded output channels. Allocation-free apart from nothing: [rgb] is mutated.
     */
    fun adjust(rgb: FloatArray, settings: AmbilightSettings, balance: ColorBalance = settings.colorBalance) {
        var r = srgbToLinear(rgb[0]) * balance.red
        var g = srgbToLinear(rgb[1]) * balance.green
        var b = srgbToLinear(rgb[2]) * balance.blue

        val lum = luma(r, g, b)
        val saturation = settings.saturation
        r = lum + (r - lum) * saturation
        g = lum + (g - lum) * saturation
        b = lum + (b - lum) * saturation

        val scale = settings.brightness / 100f
        r = (r * scale).coerceIn(0f, 1f)
        g = (g * scale).coerceIn(0f, 1f)
        b = (b * scale).coerceIn(0f, 1f)

        // gamma 2.2 is neutral; lower gamma brightens, higher darkens
        val exponent = settings.gamma.coerceIn(1.4f, 3f) / NEUTRAL_GAMMA
        r = linearToSrgb(r.pow(exponent))
        g = linearToSrgb(g.pow(exponent))
        b = linearToSrgb(b.pow(exponent))

        val floor = settings.minBrightness / 100f
        if (floor > 0f) {
            val peak = maxOf(r, g, b)
            if (peak > 0.0001f && peak < floor) {
                val lift = floor / peak
                r = (r * lift).coerceIn(0f, 1f)
                g = (g * lift).coerceIn(0f, 1f)
                b = (b * lift).coerceIn(0f, 1f)
            }
        }

        rgb[0] = r
        rgb[1] = g
        rgb[2] = b
    }

    fun toArgb(r: Float, g: Float, b: Float): Int {
        val ri = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val gi = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val bi = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
    }

    fun red(argb: Int): Int = (argb shr 16) and 0xFF
    fun green(argb: Int): Int = (argb shr 8) and 0xFF
    fun blue(argb: Int): Int = argb and 0xFF

    fun toFloatRgb(argb: Int): FloatArray = floatArrayOf(
        red(argb) / 255f,
        green(argb) / 255f,
        blue(argb) / 255f,
    )
}
