package io.github.mzmknight.subtracker.sync

/**
 * What a pairing QR code contains.
 *
 * Everything the scanning device needs to complete a pair in one step:
 *
 *     subtracker://pair?h=192.0.2.42&p=47913&c=636710&n=DESKTOP-01
 *
 * The address is the part people get wrong when typing it by hand — they have
 * to go and look it up — so it is the main reason this exists. The code is
 * still single-use and still expires; scanning replaces the typing, not the
 * security.
 *
 * A custom scheme rather than an https link on purpose: nothing here should
 * ever be resolvable on the internet, and a QR that opens a browser would be
 * both confusing and a phishing shape.
 */
data class PairingLink(
    val host: String,
    val port: Int,
    val code: String,
    val deviceName: String = "",
) {
    fun encode(): String = buildString {
        append(SCHEME)
        append("://pair?h=")
        append(encodeComponent(host))
        append("&p=")
        append(port)
        append("&c=")
        append(encodeComponent(code))
        if (deviceName.isNotBlank()) {
            append("&n=")
            // Capped: a long name pushes the QR to a high version whose modules
            // are too small to decode reliably at on-screen sizes — the code
            // looks perfectly fine and simply never scans. Nothing is lost,
            // because the full name comes back in the pairing response anyway.
            append(encodeComponent(deviceName.take(MAX_NAME_IN_QR)))
        }
    }

    companion object {
        const val SCHEME = "subtracker"

        /** Keeps the payload short enough to stay comfortably scannable. */
        const val MAX_NAME_IN_QR = 24

        /**
         * Percent-encodes anything outside an unreserved set. Device names are
         * user-supplied and routinely contain spaces and apostrophes.
         */
        private fun encodeComponent(value: String): String = buildString {
            for (byte in value.encodeToByteArray()) {
                val ch = byte.toInt().toChar()
                if (ch.isLetterOrDigit() && ch.code < 128 || ch in "-_.~") {
                    append(ch)
                } else {
                    append('%')
                    append(((byte.toInt() and 0xFF) shr 4).toString(16).uppercase())
                    append((byte.toInt() and 0x0F).toString(16).uppercase())
                }
            }
        }

        private fun decodeComponent(value: String): String {
            val bytes = mutableListOf<Byte>()
            var index = 0
            while (index < value.length) {
                val ch = value[index]
                when {
                    ch == '%' && index + 2 < value.length -> {
                        val hex = value.substring(index + 1, index + 3).toIntOrNull(16)
                        if (hex == null) {
                            bytes += ch.code.toByte()
                            index++
                        } else {
                            bytes += hex.toByte()
                            index += 3
                        }
                    }
                    ch == '+' -> {
                        bytes += ' '.code.toByte()
                        index++
                    }
                    else -> {
                        for (b in ch.toString().encodeToByteArray()) bytes += b
                        index++
                    }
                }
            }
            return bytes.toByteArray().decodeToString()
        }

        /** Returns null for anything that isn't one of our pairing links. */
        fun parse(raw: String): PairingLink? {
            val text = raw.trim()
            val prefix = "$SCHEME://pair?"
            if (!text.startsWith(prefix, ignoreCase = true)) return null

            val params = text.substring(prefix.length)
                .split("&")
                .mapNotNull { pair ->
                    val index = pair.indexOf('=')
                    if (index <= 0) null
                    else pair.substring(0, index) to decodeComponent(pair.substring(index + 1))
                }
                .toMap()

            val host = params["h"]?.takeIf { it.isNotBlank() } ?: return null
            val port = params["p"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
            val code = params["c"]?.takeIf { it.isNotBlank() } ?: return null

            return PairingLink(host, port, code, params["n"].orEmpty())
        }
    }
}
