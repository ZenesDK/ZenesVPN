package com.zenes.vpn.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Size
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.zenes.vpn.R
import com.zenes.vpn.bg.CrashLogger
import com.zenes.vpn.ui.theme.ZenesTheme
import java.util.concurrent.Executors

/**
 * Result contract for [QrScanActivity].
 * Input: the prompt line shown at the bottom of the scanner.
 * Output: the decoded string, or null when the scan was cancelled.
 */
class QrScanContract : ActivityResultContract<String, String?>() {
    override fun createIntent(context: Context, input: String): Intent =
        Intent(context, QrScanActivity::class.java)
            .putExtra(QrScanActivity.EXTRA_PROMPT, input)

    override fun parseResult(resultCode: Int, intent: Intent?): String? =
        if (resultCode == Activity.RESULT_OK) {
            intent?.getStringExtra(QrScanActivity.EXTRA_RESULT)
        } else {
            null
        }
}

/**
 * In-app QR scanner on CameraX + ML Kit (bundled on-device model — works
 * without Google services and without network).
 *
 * This replaces the zxing-android-embedded CaptureActivity: after being
 * forced to portrait it never decoded anything on some devices (preview
 * rendered fine, but no result ever arrived). Here the analyzer receives
 * full camera frames and ML Kit handles sensor orientation internally,
 * so there is no manual crop/rotation math to get wrong.
 */
class QrScanActivity : ComponentActivity() {

    val promptText: String by lazy {
        intent?.getStringExtra(EXTRA_PROMPT) ?: getString(R.string.qr_prompt)
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            // The PreviewView is created by composition; the camera binds
            // from bindCamera() once the view exists. If the user grants
            // the permission after composition, retry binding.
            pendingView?.let { bindCamera(it) }
        } else {
            CrashLogger.breadcrumb("qr camera permission denied")
            Toast.makeText(this, getString(R.string.qr_permission_denied), Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private var pendingView: PreviewView? = null
    private var camera: Camera? = null

    @Volatile
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLogger.breadcrumb("qr activity created")
        setContent { ZenesTheme { QrScanScreen(this) } }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            // Composition has not created the PreviewView yet; bindCamera is
            // called from the AndroidView factory. Nothing to do here.
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    /** Called from the Compose AndroidView factory once the preview surface exists. */
    fun bindCamera(previewView: PreviewView) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            pendingView = previewView // bind later, after the permission grant
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val scanner = BarcodeScanning.getClient(
                    BarcodeScannerOptions.Builder()
                        .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                        .build(),
                )

                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    Size(1280, 720),
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER,
                                ),
                            )
                            .build(),
                    )
                    .build()
                    .also { it.setAnalyzer(analysisExecutor) { proxy -> decodeFrame(scanner, proxy) } }

                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analysis,
                )
                CrashLogger.breadcrumb("qr camera bound")
            } catch (t: Throwable) {
                CrashLogger.log("qr camera bind failed", t)
                Toast.makeText(this, getString(R.string.qr_camera_error), Toast.LENGTH_LONG).show()
                finish()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    fun setTorch(on: Boolean) {
        try {
            camera?.cameraControl?.enableTorch(on)
        } catch (_: Throwable) {
        }
    }

    @OptIn(ExperimentalGetImage::class)
    private fun decodeFrame(
        scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
        proxy: ImageProxy,
    ) {
        val mediaImage = proxy.image
        if (mediaImage == null || handled) {
            proxy.close()
            return
        }
        val rotation = proxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        scanner.process(input)
            .addOnSuccessListener { codes ->
                if (handled) return@addOnSuccessListener
                val value = codes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue
                if (!value.isNullOrEmpty()) {
                    handled = true
                    CrashLogger.breadcrumb("qr decoded: ${value.length} chars")
                    buzz()
                    runOnUiThread {
                        setResult(RESULT_OK, Intent().putExtra(EXTRA_RESULT, value))
                        finish()
                    }
                }
            }
            .addOnCompleteListener { proxy.close() }
    }

    private fun buzz() {
        try {
            val vib: Vibrator = if (Build.VERSION.SDK_INT >= 31) {
                (getSystemService(VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                getSystemService(VIBRATOR_SERVICE) as Vibrator
            }
            if (Build.VERSION.SDK_INT >= 26) {
                vib.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib.vibrate(40)
            }
        } catch (_: Throwable) {
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        analysisExecutor.shutdown()
    }

    override fun onBackPressed() {
        CrashLogger.breadcrumb("qr scan cancelled")
        super.onBackPressed()
    }

    companion object {
        const val EXTRA_PROMPT = "com.zenes.vpn.extra.QR_PROMPT"
        const val EXTRA_RESULT = "com.zenes.vpn.extra.QR_RESULT"
    }
}

@Composable
private fun QrScanScreen(activity: QrScanActivity) {
    var torchOn by remember { mutableStateOf(false) }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                PreviewView(ctx).also { activity.bindCamera(it) }
            },
        )

        // readability gradient for the top buttons
        Box(
            Modifier
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(listOf(Color(0xB3000000), Color.Transparent)),
                ),
        )

        FilledIconButton(
            onClick = { activity.finish() },
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(14.dp),
        ) {
            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel), tint = Color.White)
        }
        FilledIconButton(
            onClick = {
                torchOn = !torchOn
                activity.setTorch(torchOn)
            },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(14.dp),
        ) {
            Icon(
                if (torchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                contentDescription = stringResource(R.string.qr_torch),
                tint = Color.White,
            )
        }

        // framing hint
        Box(
            Modifier
                .align(Alignment.Center)
                .size(260.dp)
                .border(2.dp, Color(0xD9FFFFFF), RoundedCornerShape(24.dp))
                .clip(RoundedCornerShape(24.dp)),
        )

        Text(
            text = activity.promptText,
            color = Color.White,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(28.dp),
        )
    }
}
