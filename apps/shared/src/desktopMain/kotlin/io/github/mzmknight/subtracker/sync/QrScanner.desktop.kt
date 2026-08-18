package io.github.mzmknight.subtracker.sync

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

@Composable
actual fun QrScannerView(
    onScanned: (PairingLink) -> Unit,
    onUnavailable: (String) -> Unit,
) {
    LaunchedEffect(Unit) {
        onUnavailable("This computer can't scan — show the code here and scan it with your phone.")
    }
}

actual fun isQrScanningSupported(): Boolean = false
