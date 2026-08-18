package io.github.mzmknight.subtracker.sync

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Renders [content] as a QR code, or null if it cannot be encoded.
 *
 * Both platforms can *show* a code — pairing works in either direction, so
 * whichever device is more convenient to hold up can be the one displaying.
 */
expect fun encodeQr(content: String, sizePx: Int = 512): ImageBitmap?

/**
 * Addresses another device on the local network could reach this one at.
 *
 * Loopback and link-local are excluded: a QR containing 127.0.0.1 is worse than
 * no QR, because it looks like it worked. Ordered so the most likely LAN
 * address comes first.
 */
expect fun localAddresses(): List<String>
