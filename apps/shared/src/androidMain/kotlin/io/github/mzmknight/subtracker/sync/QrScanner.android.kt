package io.github.mzmknight.subtracker.sync

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors

actual fun isQrScanningSupported(): Boolean = true

/**
 * Camera preview with QR decoding, using CameraX for the pipeline and ZXing for
 * the decode.
 *
 * ZXing rather than ML Kit deliberately: ML Kit's scanner needs Google Play
 * Services, and this app is otherwise entirely Google-free — no FCM, no
 * analytics, a self-hosted backend. Requiring Play Services just to scan a
 * square would be the only thing stopping it running on a de-Googled phone.
 */
@Composable
actual fun QrScannerView(
    onScanned: (PairingLink) -> Unit,
    onUnavailable: (String) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var granted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val requestPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { allowed ->
        granted = allowed
        if (!allowed) {
            onUnavailable("Camera access is needed to scan. You can still pair by typing the address.")
        }
    }

    LaunchedEffect(Unit) {
        if (!granted) requestPermission.launch(Manifest.permission.CAMERA)
    }

    if (!granted) return

    // Only the first successful decode counts: the analyser runs on every frame
    // and would otherwise fire the callback dozens of times.
    val handled = remember { java.util.concurrent.atomic.AtomicBoolean(false) }
    val executor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(Unit) {
        onDispose { executor.shutdown() }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
            }
            val providerFuture = ProcessCameraProvider.getInstance(ctx)

            providerFuture.addListener({
                runCatching {
                    val provider = providerFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.surfaceProvider = previewView.surfaceProvider
                    }

                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .also { it.setAnalyzer(executor) { image -> decode(image, handled, onScanned) } }

                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                }.onFailure {
                    onUnavailable("Couldn't start the camera — ${it.message ?: "unknown error"}")
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        },
    )
}

private val reader = MultiFormatReader().apply {
    setHints(
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(com.google.zxing.BarcodeFormat.QR_CODE),
            DecodeHintType.TRY_HARDER to true,
        )
    )
}

private fun decode(
    image: ImageProxy,
    handled: java.util.concurrent.atomic.AtomicBoolean,
    onScanned: (PairingLink) -> Unit,
) {
    if (handled.get()) {
        image.close()
        return
    }
    try {
        // The Y plane of YUV_420_888 is the luminance ZXing wants, so no colour
        // conversion is needed.
        val buffer = image.planes[0].buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }

        val source = PlanarYUVLuminanceSource(
            bytes,
            image.planes[0].rowStride,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false,
        )
        val result = runCatching {
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull()

        val link = result?.text?.let(PairingLink::parse)
        if (link != null && handled.compareAndSet(false, true)) {
            onScanned(link)
        }
    } catch (_: Exception) {
        // A frame that fails to decode is the normal case, not an error.
    } finally {
        reader.reset()
        image.close()
    }
}
