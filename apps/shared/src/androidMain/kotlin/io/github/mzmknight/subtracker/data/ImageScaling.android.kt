package io.github.mzmknight.subtracker.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

actual fun scaleToPng(bytes: ByteArray, maxDimension: Int): ByteArray? {
    if (bytes.isEmpty()) return null
    val source = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        ?: return null

    return try {
        val longestSide = max(source.width, source.height)
        // Only ever shrink. Upscaling a 16px favicon to 144px produces a blurry
        // square that looks worse than the letter it would have replaced.
        val scaled = if (longestSide <= maxDimension || longestSide == 0) {
            source
        } else {
            val ratio = maxDimension.toFloat() / longestSide
            Bitmap.createScaledBitmap(
                source,
                (source.width * ratio).roundToInt().coerceAtLeast(1),
                (source.height * ratio).roundToInt().coerceAtLeast(1),
                true,
            )
        }
        ByteArrayOutputStream().use { out ->
            scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
            if (scaled !== source) scaled.recycle()
            out.toByteArray()
        }
    } finally {
        source.recycle()
    }
}
