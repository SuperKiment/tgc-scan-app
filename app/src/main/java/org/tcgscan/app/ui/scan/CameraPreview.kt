package org.tcgscan.app.ui.scan

import android.content.Context
import android.util.Log
import android.util.Size
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "CameraPreview"

/** Analysis target (T15): 720p is plenty to find and read a card and keeps the frame cost low. */
private val ANALYSIS_SIZE = Size(1280, 720)

/**
 * The live camera: a CameraX [Preview] and an [ImageAnalysis] bound to the lifecycle of the screen.
 *
 * Frames go to [onFrame] on a dedicated single analysis thread, with
 * `STRATEGY_KEEP_ONLY_LATEST`: the next frame is delivered only once the previous one is closed, so
 * [onFrame] **must close** the [ImageProxy] it is given. The binding is dropped with the lifecycle
 * (or when this composable leaves the composition), which also turns the torch off.
 *
 * @param torchOn desired torch state; applied whenever it changes and once the camera is bound.
 * @param onTorchAvailable called once per binding: whether the device has a flash unit.
 * @param onUnavailable called when the camera cannot be bound (no back camera, camera in use).
 */
@Composable
fun CameraPreview(
    torchOn: Boolean,
    onFrame: (ImageProxy) -> Unit,
    onTorchAvailable: (Boolean) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(analysisExecutor) {
        // shutdown() lets the frame in flight finish and close its image.
        onDispose { analysisExecutor.shutdown() }
    }

    val currentOnFrame by rememberUpdatedState(onFrame)
    val currentOnTorchAvailable by rememberUpdatedState(onTorchAvailable)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(lifecycleOwner, previewView, analysisExecutor) {
        val provider = try {
            ProcessCameraProvider.getInstance(context).await(context)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "The camera provider is unavailable", e)
            currentOnUnavailable()
            return@LaunchedEffect
        }

        val preview = Preview.Builder().build().also {
            it.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            ANALYSIS_SIZE,
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .build()
            .also { it.setAnalyzer(analysisExecutor) { image -> currentOnFrame(image) } }

        try {
            val bound = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
            camera = bound
            currentOnTorchAvailable(bound.cameraInfo.hasFlashUnit())
        } catch (e: IllegalArgumentException) {
            // No back camera, or no use-case combination the device supports.
            Log.w(TAG, "The camera cannot be bound", e)
            currentOnUnavailable()
            return@LaunchedEffect
        } catch (e: IllegalStateException) {
            Log.w(TAG, "The camera cannot be bound", e)
            currentOnUnavailable()
            return@LaunchedEffect
        }

        try {
            awaitCancellation()
        } finally {
            camera = null
            analysis.clearAnalyzer()
            provider.unbind(preview, analysis)
        }
    }

    LaunchedEffect(camera, torchOn) {
        // The result only reports completion; a device without flash simply ignores the request.
        camera?.cameraControl?.enableTorch(torchOn)
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Suspends until the CameraX future completes, on the main executor. */
private suspend fun <T> ListenableFuture<T>.await(context: Context): T =
    suspendCancellableCoroutine { continuation ->
        addListener(
            {
                try {
                    continuation.resume(get())
                } catch (e: ExecutionException) {
                    continuation.resumeWithException(e.cause ?: e)
                }
            },
            ContextCompat.getMainExecutor(context),
        )
        continuation.invokeOnCancellation { cancel(false) }
    }
