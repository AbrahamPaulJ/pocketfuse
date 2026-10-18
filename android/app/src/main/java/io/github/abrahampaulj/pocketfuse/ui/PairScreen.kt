package io.github.abrahampaulj.pocketfuse.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import io.github.abrahampaulj.pocketfuse.R
import io.github.abrahampaulj.pocketfuse.pair.Pairing
import io.github.abrahampaulj.pocketfuse.pair.ServerConfig
import java.util.concurrent.Executors

@Composable
fun PairScreen(failure: String?, onPaired: (ServerConfig) -> Unit, saved: ServerConfig? = null, onRetry: () -> Unit = {}) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var scanning by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(failure) }
    // A saved PC that was unreachable once (asleep, Tailscale off, or the app started in the background,
    // where Android cuts its sockets) is tried again whenever the app comes to the front, so the user is
    // not asked to pair again.
    DisposableEffect(lifecycleOwner, saved) {
        // Adding the observer to a screen already in front replays ON_RESUME: that one is not a return.
        var replayed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME || saved == null) return@LifecycleEventObserver
            if (replayed) replayed = false else onRetry()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        scanning = granted
        if (!granted) error = context.getString(R.string.pair_camera_denied)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Gap(24.dp)
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineSmall)
        Hint(stringResource(R.string.pair_intro))

        if (saved != null) {
            Section(stringResource(R.string.pair_saved_title, saved.label)) {
                failure?.let { Hint(it, color = Bad) }
                Hint(stringResource(R.string.pair_saved_hint))
                Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.pair_retry)) }
            }
        }

        Section(stringResource(R.string.pair_step_server)) {
            Hint(stringResource(R.string.pair_run_companion))
            Text(
                "python pocketfuse.py serve",
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).padding(vertical = 4.dp),
                color = Violet,
            )
            Hint(stringResource(R.string.pair_companion_explains))
        }

        Section(stringResource(R.string.pair_step_scan)) {
            if (scanning) {
                QrScanner(
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)),
                    onCode = { text ->
                        val config = Pairing.parse(text)
                        if (config != null) {
                            scanning = false
                            onPaired(config)
                        } else {
                            error = context.getString(R.string.pair_not_a_code)
                        }
                    },
                )
            }
            Button(
                onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) scanning = !scanning
                    else permission.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(if (scanning) R.string.pair_stop_scan else R.string.pair_scan)) }
        }

        Section(stringResource(R.string.pair_step_manual)) {
            OutlinedTextField(
                value = address,
                onValueChange = { address = it },
                label = { Text(stringResource(R.string.pair_address)) },
                placeholder = { Text("100.64.0.2:8000") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text(stringResource(R.string.pair_api_key)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(
                onClick = {
                    val config = Pairing.parseManual(address, apiKey)
                    if (config == null) error = context.getString(R.string.pair_bad_address) else onPaired(config)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.pair_connect)) }
        }

        if (saved == null || error != failure) error?.let { Hint(it, color = Bad) }
        Hint(stringResource(R.string.pair_security_note))
    }
}

/** CameraX preview + ML Kit QR detection; reports the first decoded value it sees. */
@Composable
private fun QrScanner(modifier: Modifier, onCode: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val scanner = remember { BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()) }

    DisposableEffect(Unit) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var reported = false
        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
            analysis.setAnalyzer(executor) { proxy ->
                val image = proxy.image
                if (image == null || reported) {
                    proxy.close()
                    return@setAnalyzer
                }
                scanner.process(InputImage.fromMediaImage(image, proxy.imageInfo.rotationDegrees))
                    .addOnSuccessListener { codes ->
                        codes.firstNotNullOfOrNull { it.rawValue }?.let { value ->
                            if (!reported) {
                                reported = true
                                onCode(value)
                            }
                        }
                    }
                    .addOnCompleteListener { proxy.close() }
            }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { providerFuture.get().unbindAll() }
            scanner.close()
            executor.shutdown()
        }
    }
    Box(modifier) { AndroidView({ previewView }, Modifier.fillMaxSize()) }
}

