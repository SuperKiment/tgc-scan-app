package org.tcgscan.app.ui.scan

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FlashOff
import androidx.compose.material.icons.outlined.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import org.tcgscan.app.R
import org.tcgscan.app.ui.Routes

/** Printed Pokémon card ratio (63 x 88 mm), the proportions of the guide frame. */
private const val CARD_ASPECT_RATIO = 63f / 88f

/** Share of the screen width the guide frame takes. */
private const val GUIDE_WIDTH_FRACTION = 0.72f

private const val FLASH_PEAK_ALPHA = 0.55f
private const val FLASH_DURATION_MS = 280

private val OVERLAY_COLOR = Color.Black.copy(alpha = 0.55f)

/**
 * The burst scan screen (FR-6): camera preview with a card guide, the live counter of the burst, a
 * torch toggle, transient hints, a haptic + flash tick per detection and « Terminer », which closes
 * the burst and opens its review.
 *
 * Scanning needs the camera permission (EC-4) and an installed pack (EC-1); without either, the
 * screen explains why and the rest of the app, catalogue and collection included, stays reachable
 * through the bottom bar. An unfinished session is offered for resume (EC-13).
 */
@Composable
fun ScanRoute(nav: NavController) {
    val context = LocalContext.current
    val viewModel: ScanViewModel = viewModel(factory = ScanViewModel.factory(context))
    val state by viewModel.state.collectAsStateWithLifecycle()

    var hasPermission by remember { mutableStateOf(context.hasCameraPermission()) }
    var permissionAsked by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    // The user may have changed the permission in the system settings while the screen was stopped.
    LifecycleEventEffect(Lifecycle.Event.ON_START) {
        hasPermission = context.hasCameraPermission()
    }
    LaunchedEffect(hasPermission) {
        if (!hasPermission && !permissionAsked) {
            permissionAsked = true
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // The ~34 MB of models only live while the screen is on and scanning is possible.
    val canScan = hasPermission && state.hasPack
    LifecycleStartEffect(canScan) {
        if (canScan) viewModel.onScreenStart()
        onStopOrDispose { viewModel.onScreenStop() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when {
            !state.packsLoaded -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            !state.hasPack -> BlockedMessage(
                title = stringResource(R.string.scan_no_pack_title),
                body = stringResource(R.string.scan_no_pack_body),
            ) {
                Button(onClick = { nav.navigateToTab(Routes.SETTINGS) }) {
                    Text(stringResource(R.string.scan_no_pack_action_install))
                }
            }

            !hasPermission -> BlockedMessage(
                title = stringResource(R.string.scan_permission_title),
                body = stringResource(R.string.scan_permission_body),
            ) {
                Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                    Text(stringResource(R.string.scan_permission_action_grant))
                }
                OutlinedButton(onClick = { context.openAppSettings() }) {
                    Text(stringResource(R.string.scan_permission_action_settings))
                }
            }

            else -> ScanContent(
                state = state,
                viewModel = viewModel,
                onFinished = { sessionId -> nav.navigate(Routes.review(sessionId)) },
            )
        }
    }
}

@Composable
private fun ScanContent(
    state: ScanUiState,
    viewModel: ScanViewModel,
    onFinished: (Long) -> Unit,
) {
    var torchAvailable by remember { mutableStateOf(false) }
    var cameraUnavailable by remember { mutableStateOf(false) }

    val flash = remember { Animatable(0f) }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(viewModel) {
        viewModel.detectionFeedback.collect {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            flash.snapTo(FLASH_PEAK_ALPHA)
            flash.animateTo(0f, tween(FLASH_DURATION_MS))
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (cameraUnavailable) {
            BlockedMessage(
                title = stringResource(R.string.scan_camera_unavailable),
                body = null,
            )
        } else {
            CameraPreview(
                torchOn = state.torchOn,
                onFrame = viewModel::analyze,
                onTorchAvailable = { torchAvailable = it },
                onUnavailable = { cameraUnavailable = true },
                modifier = Modifier.fillMaxSize(),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(GUIDE_WIDTH_FRACTION)
                    .aspectRatio(CARD_ASPECT_RATIO)
                    .border(2.dp, Color.White.copy(alpha = 0.85f), RoundedCornerShape(16.dp)),
            )
        }

        // White flash on each detection; never intercepts a touch.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .alpha(flash.value)
                .background(Color.White),
        )

        if (!cameraUnavailable) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.resumeOfferSessionId?.let { sessionId ->
                    ResumeBanner(
                        onResume = { viewModel.resumeSession(sessionId) },
                        onDismiss = { viewModel.dismissResumeOffer(sessionId) },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    CounterChip(count = state.count)
                    if (torchAvailable) {
                        TorchButton(on = state.torchOn, onToggle = viewModel::toggleTorch)
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val message = statusMessage(state)
                if (message != null) OverlayText(message)
                Button(
                    onClick = { viewModel.finish(onFinished) },
                    enabled = state.count > 0,
                ) {
                    Text(stringResource(R.string.scan_action_finish))
                }
            }
        }
    }
}

/** The one line shown above « Terminer »: a failure first, then the transient hint. */
@Composable
private fun statusMessage(state: ScanUiState): String? = when {
    state.engineFailed -> stringResource(R.string.scan_engine_failed)
    else -> when (state.hint) {
        ScanHint.MULTIPLE_CARDS -> stringResource(R.string.scan_hint_multiple_cards)
        ScanHint.LOW_LIGHT -> stringResource(R.string.scan_hint_low_light)
        ScanHint.TOO_BLURRY -> stringResource(R.string.scan_hint_too_blurry)
        ScanHint.ANALYSIS_FAILED -> stringResource(R.string.scan_hint_analysis_failed)
        null -> null
    }
}

@Composable
private fun CounterChip(count: Int) {
    Surface(shape = RoundedCornerShape(50), color = OVERLAY_COLOR, contentColor = Color.White) {
        Text(
            text = pluralStringResource(R.plurals.scan_counter, count, count),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun TorchButton(on: Boolean, onToggle: () -> Unit) {
    Surface(shape = RoundedCornerShape(50), color = OVERLAY_COLOR, contentColor = Color.White) {
        IconButton(onClick = onToggle) {
            Icon(
                imageVector = if (on) Icons.Outlined.FlashOn else Icons.Outlined.FlashOff,
                contentDescription = stringResource(
                    if (on) R.string.scan_torch_turn_off else R.string.scan_torch_turn_on,
                ),
            )
        }
    }
}

@Composable
private fun OverlayText(text: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = OVERLAY_COLOR, contentColor = Color.White) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Offer to continue the unfinished session found at entry (EC-13). */
@Composable
private fun ResumeBanner(onResume: () -> Unit, onDismiss: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = stringResource(R.string.scan_resume_message),
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.scan_resume_action_dismiss))
                }
                TextButton(onClick = onResume) {
                    Text(stringResource(R.string.scan_resume_action_resume))
                }
            }
        }
    }
}

/** Full-screen explanation when scanning is impossible (no pack, no permission, no camera). */
@Composable
private fun BlockedMessage(
    title: String,
    body: String?,
    actions: @Composable () -> Unit = {},
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            if (body != null) {
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
            actions()
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
        PackageManager.PERMISSION_GRANTED

/** Opens this app's page of the system settings, where the camera permission can be re-enabled. */
private fun Context.openAppSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A device without a settings app has nothing more to offer; the explanation stays on screen.
    }
}

/** Tab switch, same contract as the bottom bar: one instance per tab, state preserved. */
private fun NavController.navigateToTab(route: String) {
    if (currentDestination?.route == route) return
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}
