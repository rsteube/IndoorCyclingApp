/*
 * Copyright (C) 2026 Juhani Lehtimäki
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3 of the License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package app.indoorbike.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.indoorbike.LocalAppContainer
import app.indoorbike.library.HttpFetcher
import app.indoorbike.library.ImportResult
import app.indoorbike.library.SubscribeResult
import app.indoorbike.library.WorkoutLinkParser
import app.indoorbike.ui.components.AppBar
import app.indoorbike.ui.components.IconBtn
import app.indoorbike.ui.components.PrimaryBtn
import app.indoorbike.ui.theme.CadenceColors
import app.indoorbike.ui.theme.CadenceType
import app.indoorbike.ui.theme.Spacing
import com.google.zxing.BinaryBitmap
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executors
import kotlinx.coroutines.launch

/**
 * Camera-backed QR scanner. Decodes any URL (the gravel-friendly handoff path
 * for the future web editor — generates a QR you point your bars-mounted phone
 * at without paddling around in the file system).
 *
 *   web editor            QR  →  app QR scanner          import flow
 *   ─────────────────       ────────────────────────       ──────────
 *   "Send to phone"  ──┐                              ┌──  fetch + classify
 *   button generates    │       camera frame    →    │       (workout vs.
 *   a URL like          │       luminance source     │        manifest)
 *   indoorbike://...   ─┘    →  ZXing decode      ───┘
 *                                                          subscribe / save
 *                                                          → toast → back
 *
 * The scanner accepts any HTTPS URL (passes through HttpFetcher) **and** the
 * `indoorbike://import?url=…` deep-link form (delegates to the same flow).
 */
@Composable
fun QrScannerScreen(
    onBack: () -> Unit,
    onNavigateToWorkout: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val container = LocalAppContainer.current
    val scope = rememberCoroutineScope()

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> permissionGranted = granted }

    LaunchedEffect(Unit) {
        if (!permissionGranted) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    var lastDetected by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(CadenceColors.Bg0)) {
        AppBar(
            title = "Scan QR",
            eyebrow = "IMPORT FROM WEB EDITOR",
            leading = {
                IconBtn(
                    icon = Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "Back",
                    onClick = onBack
                )
            }
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (permissionGranted) {
                CameraPreviewWithDecoder(
                    onScanned = { url ->
                        if (busy || url == lastDetected) return@CameraPreviewWithDecoder
                        lastDetected = url
                        busy = true
                        scope.launch {
                            val openedId = handleScannedUrl(context, container, url)
                            busy = false
                            if (openedId != null) onNavigateToWorkout(openedId) else onBack()
                        }
                    }
                )
                ScannerOverlay()
            } else {
                NoPermission(
                    onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                )
            }
        }

        Text(
            text = "Point at a QR code from the workout editor.\n" +
                "We'll fetch the workout (or repository) it points to.",
            style = CadenceType.small,
            color = CadenceColors.Fg3,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.s5, vertical = Spacing.s5)
        )
    }
}

@Composable
private fun CameraPreviewWithDecoder(onScanned: (String) -> Unit) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val executor = remember { Executors.newSingleThreadExecutor() }
    val previewView = remember { PreviewView(context) }
    val reader = remember { MultiFormatReader() }
    // Hold the latest onScanned through a State ref so the analyzer set up
    // once below doesn't capture a stale lambda when callers recompose.
    val onScannedRef = androidx.compose.runtime.rememberUpdatedState(onScanned)

    DisposableEffect(Unit) {
        // Bind CameraX exactly once per screen entry. Previously this lived in
        // AndroidView.update which fires on every recomposition, calling
        // unbindAll() + bindToLifecycle() and accumulating Future listeners.
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().apply {
                surfaceProvider = previewView.surfaceProvider
            }
            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analyzer.setAnalyzer(executor) { proxy ->
                decodeAndForward(proxy, reader) { url -> onScannedRef.value(url) }
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    analyzer
                )
            } catch (_: Throwable) {
                // Camera bind can fail if the screen is being torn down.
                // Swallow — the user just sees a black preview and can retry.
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            // Release camera before shutting down the analyzer thread so any
            // in-flight frame is delivered first.
            try {
                cameraProviderFuture.get().unbindAll()
            } catch (_: Throwable) {}
            executor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { previewView },
    )
}

private fun decodeAndForward(
    proxy: ImageProxy,
    reader: MultiFormatReader,
    onScanned: (String) -> Unit
) {
    try {
        val plane = proxy.planes.firstOrNull() ?: return
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        val source = PlanarYUVLuminanceSource(
            bytes,
            proxy.width,
            proxy.height,
            0, 0,
            proxy.width,
            proxy.height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = try {
            reader.decode(bitmap)
        } catch (_: NotFoundException) {
            null
        }
        result?.text?.let { text ->
            android.os.Handler(android.os.Looper.getMainLooper()).post { onScanned(text) }
        }
    } catch (_: Throwable) {
        // Per-frame analyzer errors are noisy; ignore.
    } finally {
        proxy.close()
    }
}

@Composable
private fun ScannerOverlay() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(20.dp))
                .border(2.dp, CadenceColors.Brand500, RoundedCornerShape(20.dp))
        )
    }
}

@Composable
private fun NoPermission(onRequest: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(Spacing.s6),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "Camera access needed",
            style = CadenceType.h2,
            color = CadenceColors.Fg1
        )
        Spacer(Modifier.height(Spacing.s2))
        Text(
            text = "We use the camera only to read QR codes for workout import. Frames are processed locally and never stored.",
            style = CadenceType.body,
            color = CadenceColors.Fg3,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(Spacing.s5))
        PrimaryBtn(text = "Allow camera", leadingIcon = null, onClick = onRequest)
    }
}

/**
 * Returns the id of a freshly imported single workout when the caller should
 * navigate straight into its detail page, or null for everything else
 * (manifests, multi-workout payloads, fetch failures, parse errors). The
 * library is awaited before returning so the caller's navigation finds the
 * new entry already present.
 */
private suspend fun handleScannedUrl(
    context: android.content.Context,
    container: app.indoorbike.AppContainer,
    raw: String
): String? {
    // 1) Try to decode an embedded workout straight from the URL — the
    //    indoorbike.app/share#w=… and indoorbike://import?data=… forms.
    //    No HTTP, no backend.
    val embedded = WorkoutLinkParser.extractEmbeddedJson(raw)

    // sourceUrl: only meaningful when we fetched from a known address. For
    // embedded payloads it stays null because the URL itself *is* the data —
    // future "refresh" against it wouldn't fetch anything new.
    var sourceUrl: String? = null

    val text: String = if (embedded != null) {
        embedded
    } else {
        // 2) Otherwise treat it as either a bare HTTPS URL or
        //    indoorbike://import?url=… and fetch.
        val url = if (raw.startsWith("indoorbike://", ignoreCase = true)) {
            try { android.net.Uri.parse(raw).getQueryParameter("url") } catch (_: Throwable) { null }
        } else raw

        if (url.isNullOrBlank() || !url.startsWith("https://", ignoreCase = true)) {
            Toast.makeText(context, "QR didn't contain a workout or an HTTPS URL", Toast.LENGTH_LONG).show()
            return null
        }
        sourceUrl = url
        try {
            HttpFetcher.getString(url)
        } catch (t: Throwable) {
            Toast.makeText(context, "Couldn't fetch: ${t.message}", Toast.LENGTH_LONG).show()
            return null
        }
    }

    return when (val result = container.workoutImporter.importJsonText(text)) {
        is ImportResult.Workouts -> {
            // Await the rebuild so the caller's navigation finds the new entry
            // — the alternative (fire-and-forget refreshImports) races the
            // detail-screen lookup and bounces straight back.
            container.workoutLibrary.refreshImportsAwait()
            val n = result.workouts.size
            Toast.makeText(
                context,
                if (n == 1) "Imported \"${result.workouts.first().name}\"" else "Imported $n workouts",
                Toast.LENGTH_SHORT
            ).show()
            // Single-workout imports route straight into the detail page so
            // the user can hit Start without hunting for the new card.
            if (n == 1) result.workouts.first().id else null
        }
        is ImportResult.Manifest -> {
            val outcome = container.repositoryManager.subscribeFromManifest(result.manifest, sourceUrl = sourceUrl)
            when (outcome) {
                is SubscribeResult.Ok -> Toast.makeText(
                    context,
                    "Subscribed to \"${result.manifest.name}\" (${result.manifest.workouts.size} workouts)",
                    Toast.LENGTH_SHORT
                ).show()
                is SubscribeResult.Failure -> Toast.makeText(context, outcome.message, Toast.LENGTH_LONG).show()
            }
            null
        }
        is ImportResult.Failure -> {
            Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
            null
        }
    }
}
