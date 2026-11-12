package org.tcgscan.app

import android.app.Application
import org.opencv.android.OpenCVLoader

/**
 * Application entry point.
 *
 * The only process-wide initialisation it carries today is OpenCV: card detection, perspective
 * correction, every resize of the embedding contract (D6) and the PP-OCR post-processing all go
 * through the native library, so nothing in the scan flow works until it is loaded. The hand-rolled
 * dependency container arrives with T31.
 */
class TcgScanApp : Application() {

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
}
