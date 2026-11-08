package org.tcgscan.app

import android.app.Application

/**
 * Application entry point.
 *
 * Process-wide initialisation is added here by later batches: `OpenCVLoader.initLocal()` (T9) and
 * the hand-rolled dependency container (T31). It stays deliberately empty for now so the skeleton
 * builds without pulling any of that in.
 */
class TcgScanApp : Application()
