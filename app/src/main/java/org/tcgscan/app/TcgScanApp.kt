package org.tcgscan.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import org.opencv.android.OpenCVLoader
import org.tcgscan.app.di.AppContainer

/**
 * Application entry point.
 *
 * It loads OpenCV — card detection, perspective correction, every resize of the embedding contract
 * (D6) and the PP-OCR post-processing all go through the native library, so nothing in the scan
 * flow works until it is loaded — and owns the [AppContainer]. It is also Coil's
 * [ImageLoaderFactory], so every `AsyncImage` shares the container's bounded loader (FR-25).
 */
class TcgScanApp : Application(), ImageLoaderFactory {

    val container: AppContainer by lazy { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // initLocal() loads the `opencv_java4` library packed in this ABI's APK — no OpenCV Manager
        // and no download (D4). A false return means the APK was installed for the wrong ABI or the
        // native library was stripped: there is no degraded mode to fall back to, so fail loudly
        // rather than crash later inside a frame callback.
        check(OpenCVLoader.initLocal()) {
            "OpenCV native library (opencv_java4) failed to load; the APK does not match this device's ABI"
        }
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}
