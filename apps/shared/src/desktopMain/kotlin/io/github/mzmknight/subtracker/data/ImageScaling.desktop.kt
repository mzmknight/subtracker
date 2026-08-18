package io.github.mzmknight.subtracker.data

import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

actual fun scaleToPng(bytes: ByteArray, maxDimension: Int): ByteArray? {
    if (bytes.isEmpty()) return null
    // Returns null for anything ImageIO has no reader for, which includes .ico —
    // exactly the "not a usable logo" case the caller expects to handle.
    val source = runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull() ?: return null

    val longestSide = max(source.width, source.height)
    if (longestSide == 0) return null

    val target = if (longestSide <= maxDimension) {
        source
    } else {
        val ratio = maxDimension.toFloat() / longestSide
        val width = (source.width * ratio).roundToInt().coerceAtLeast(1)
        val height = (source.height * ratio).roundToInt().coerceAtLeast(1)

        // ARGB, not the source's type: many favicons are indexed or greyscale,
        // and drawing those into a matching type throws away the alpha channel,
        // leaving a black box behind a transparent logo.
        BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).also { scaled ->
            val graphics = scaled.createGraphics()
            graphics.setRenderingHint(
                RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            graphics.drawImage(source, 0, 0, width, height, null)
            graphics.dispose()
        }
    }

    val out = ByteArrayOutputStream()
    return if (runCatching { ImageIO.write(target, "png", out) }.getOrDefault(false)) {
        out.toByteArray()
    } else {
        null
    }
}
