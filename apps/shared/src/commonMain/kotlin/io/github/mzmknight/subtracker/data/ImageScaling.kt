package io.github.mzmknight.subtracker.data

/**
 * Decode an image, shrink it to fit [maxDimension], and re-encode it as PNG.
 *
 * Null when the bytes are not an image this platform can decode — which is a
 * normal outcome, not an error: a guessed logo URL routinely returns an HTML
 * error page, and .ico files are not decodable by either platform's stock
 * decoder. Callers treat null as "no logo found" and move on.
 *
 * Re-encoding rather than storing the original matters because these bytes are
 * synced: it caps the size and normalises a dozen possible formats down to one
 * that every device can render.
 */
expect fun scaleToPng(bytes: ByteArray, maxDimension: Int): ByteArray?
