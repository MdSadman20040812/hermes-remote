package com.hermes.mobile.ui.connect

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.hermes.mobile.core.connection.ConnState
import java.util.concurrent.Executors

/**
 * QR pairing screen — the 5-second path (spec §C.3): point at the PC's QR,
 * paired. Manual entry is the fallback.
 */
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun ConnectScreen(
    vm: ConnectViewModel = hiltViewModel(),
) {
    val state by vm.connState.collectAsState()
    var manualMode by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Text("Hermes Remote", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Point at the QR on your PC to pair",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (val s = state) {
            is ConnState.Connecting -> ConnectingCard("Connecting to ${s.profile.label}…")
            is ConnState.Probing -> ConnectingCard("Looking for your PC…")
            is ConnState.Reconnecting -> ConnectingCard("Reconnecting (attempt ${s.attempt})…")
            is ConnState.Failed -> {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "Connection failed: ${s.message}",
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = { vm.retry() },
                            modifier = Modifier
                                .height(48.dp)
                                .semantics { contentDescription = "Retry connection" },
                        ) { Text("Retry") }
                    }
                }
            }
            else -> { /* NoProfile / Connected handled by the shell */ }
        }

        if (!manualMode) {
            QrScannerPane(onPayload = vm::onQrScanned)
            TextButton(
                onClick = { manualMode = true },
                modifier = Modifier
                    .height(48.dp)
                    .semantics { contentDescription = "Enter connection details manually" },
            ) { Text("Enter manually instead") }
        } else {
            ManualEntryPane(onSubmit = vm::onManualSubmit)
            TextButton(
                onClick = { manualMode = false },
                modifier = Modifier
                    .height(48.dp)
                    .semantics { contentDescription = "Back to QR scanning" },
            ) { Text("Scan a QR instead") }
        }
    }
}

@Composable
private fun ConnectingCard(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(
            Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(28.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun QrScannerPane(onPayload: (String) -> Unit) {
    val cameraPermission = rememberPermissionState(android.Manifest.permission.CAMERA)
    when {
        cameraPermission.status.isGranted -> CameraPreviewBox(onPayload)
        else -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (cameraPermission.status.shouldShowRationale)
                    "The camera is only used to scan the pairing QR — nothing is recorded or uploaded."
                else "Camera access is needed to scan the pairing QR.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = { cameraPermission.launchPermissionRequest() },
                modifier = Modifier
                    .height(48.dp)
                    .semantics { contentDescription = "Grant camera permission" },
            ) { Text("Allow camera") }
        }
    }
}

@SuppressLint("UnsafeOptInUsageError")
@Composable
private fun CameraPreviewBox(onPayload: (String) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose { analysisExecutor.shutdown() }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(320.dp),
    ) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val scanner = BarcodeScanning.getClient()
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        val mediaImage = imageProxy.image
                        if (mediaImage == null) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val image = InputImage.fromMediaImage(
                            mediaImage, imageProxy.imageInfo.rotationDegrees,
                        )
                        scanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                barcodes.firstOrNull { it.format == Barcode.FORMAT_QR_CODE }
                                    ?.rawValue
                                    ?.let(onPayload)
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    }
                    runCatching {
                        cameraProvider.unbindAll()
                        cameraProvider.bindToLifecycle(
                            lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis,
                        )
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier
                .fillMaxSize()
                .semantics { contentDescription = "Camera viewfinder for QR pairing" },
        )
    }
}

@Composable
private fun ManualEntryPane(onSubmit: (label: String, host: String, port: Int?, token: String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("9119") }
    var token by remember { mutableStateOf("") }

    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        OutlinedTextField(
            value = label, onValueChange = { label = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = host, onValueChange = { host = it },
            label = { Text("PC address (e.g. 100.x.y.z)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "PC address" },
        )
        OutlinedTextField(
            value = port, onValueChange = { port = it.filter(Char::isDigit) },
            label = { Text("Port") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it },
            label = { Text("Session token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Session token" },
        )
        Button(
            onClick = { onSubmit(label, host, port.toIntOrNull(), token) },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .semantics { contentDescription = "Connect to PC" },
        ) { Text("Connect") }
    }
}
