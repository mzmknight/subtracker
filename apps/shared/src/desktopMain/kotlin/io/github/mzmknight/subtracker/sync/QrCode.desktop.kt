package io.github.mzmknight.subtracker.sync

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.awt.image.BufferedImage
import java.net.Inet4Address
import java.net.NetworkInterface

actual fun encodeQr(content: String, sizePx: Int): ImageBitmap? = runCatching {
    val hints = mapOf(
        // Medium correction: a screen-displayed code is not going to be creased
        // or dirty, and lower correction keeps the modules large and easy to scan.
        EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
        EncodeHintType.MARGIN to 1,
        EncodeHintType.CHARACTER_SET to "UTF-8",
    )
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)

    val image = BufferedImage(matrix.width, matrix.height, BufferedImage.TYPE_INT_RGB)
    for (x in 0 until matrix.width) {
        for (y in 0 until matrix.height) {
            image.setRGB(x, y, if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt())
        }
    }
    image.toComposeImageBitmap()
}.getOrNull()

actual fun localAddresses(): List<String> = runCatching {
    LocalAddresses.rank(
        NetworkInterface.getNetworkInterfaces()
            .toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { adapter ->
                adapter.inetAddresses.toList()
                    .filterIsInstance<Inet4Address>()
                    .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                    .map {
                        // displayName is the half that carries anything readable
                        // on Windows: the name is "iftype53_32772" where the
                        // display name is "NordLynx Tunnel".
                        NetworkCandidate(
                            name = adapter.name.orEmpty(),
                            label = adapter.displayName.orEmpty(),
                            address = it.hostAddress.orEmpty(),
                        )
                    }
            }
    )
}.getOrDefault(emptyList())
