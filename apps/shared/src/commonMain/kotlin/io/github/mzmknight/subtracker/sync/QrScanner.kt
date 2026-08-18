package io.github.mzmknight.subtracker.sync

import androidx.compose.runtime.Composable

/**
 * Live camera QR scanning.
 *
 * Android only — a desktop has no camera worth assuming, and the desktop is
 * normally the device *showing* the code anyway. The desktop implementation
 * reports that it is unavailable rather than pretending, so the UI can offer
 * typing instead.
 */
@Composable
expect fun QrScannerView(
    onScanned: (PairingLink) -> Unit,
    onUnavailable: (String) -> Unit,
)

expect fun isQrScanningSupported(): Boolean
