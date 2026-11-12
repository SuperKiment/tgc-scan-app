package org.tcgscan.app.scan.detect

/**
 * What the stabiliser decided about the current frame.
 */
sealed interface StabilizerDecision {

    /** No single card-like quad to track: empty frame, or several cards (EC-5). */
    data object Idle : StabilizerDecision

    /** A quad is being tracked but has not held still long enough yet. */
    data class Settling(val quad: CardQuad, val stableFrames: Int) : StabilizerDecision

    /** The quad held still for the required number of frames: capture it now (FR-1). */
    data class Capture(val quad: CardQuad) : StabilizerDecision

    /**
     * The card captured a moment ago is still in the frame, so it is not captured again until it
     * leaves (FR-7).
     */
    data class Blocked(val quad: CardQuad) : StabilizerDecision
}

/**
 * Turns the per-frame output of [CardQuadDetector] into at most one capture per card (FR-1/FR-7).
 *
 * Two rules, both expressed relative to the frame diagonal so they hold at any resolution:
 *
 * 1. **Stability**: a capture is emitted only after [requiredStableFrames] consecutive frames whose
 *    matching corners all moved less than [maxCornerShiftFraction] of the diagonal. A hand still
 *    placing the card, or a camera still focusing, keeps resetting the streak.
 * 2. **Cooldown**: right after a capture the stabiliser is armed on that quad and answers
 *    [StabilizerDecision.Blocked] while the same card stays in the frame. It disarms when the frame
 *    no longer holds a quad — the card left — or when the tracked quad has clearly moved elsewhere,
 *    i.e. a different card was placed. That second escape never weakens FR-7: a re-presented card
 *    is caught again by the session-wide `DedupTracker` (T14), which compares embeddings and parsed
 *    numbers.
 *
 * Image quality is deliberately out of scope here: « trop flou » and « activez la lampe » are
 * decided by the pipeline from [FrameDetection.isBlurry] / [FrameDetection.isLowLight] (EC-7).
 *
 * Not thread-safe; one instance per scan session, reset through [reset].
 */
class Stabilizer(
    private val requiredStableFrames: Int = REQUIRED_STABLE_FRAMES,
    private val maxCornerShiftFraction: Double = MAX_CORNER_SHIFT_FRACTION,
    private val cooldownReleaseFraction: Double = COOLDOWN_RELEASE_FRACTION,
) {

    init {
        require(requiredStableFrames >= 1) {
            "requiredStableFrames must be >= 1, got $requiredStableFrames"
        }
        require(maxCornerShiftFraction > 0.0) {
            "maxCornerShiftFraction must be > 0, got $maxCornerShiftFraction"
        }
        require(cooldownReleaseFraction > maxCornerShiftFraction) {
            "cooldownReleaseFraction must exceed maxCornerShiftFraction"
        }
    }

    private var tracked: CardQuad? = null
    private var stableFrames = 0
    private var captured: CardQuad? = null

    /** Number of consecutive still frames accumulated so far, for the scan screen's feedback. */
    val stableFrameCount: Int get() = stableFrames

    /** True while a captured card is still blocking further captures (FR-7). */
    val isCooldownArmed: Boolean get() = captured != null

    /** Feeds one analysed frame and returns the resulting decision. */
    fun onFrame(detection: FrameDetection): StabilizerDecision {
        val quad = detection.singleQuad
        if (quad == null) {
            tracked = null
            stableFrames = 0
            // Only an empty frame proves the captured card left; with several cards in the frame
            // (EC-5) the captured one may well be one of them, so the cooldown stays armed.
            if (detection.outcome is QuadOutcome.None) captured = null
            return StabilizerDecision.Idle
        }

        val capturedQuad = captured
        if (capturedQuad != null) {
            if (capturedQuad.maxCornerShift(quad) <= detection.frameDiagonal * cooldownReleaseFraction) {
                tracked = null
                stableFrames = 0
                return StabilizerDecision.Blocked(quad)
            }
            captured = null
        }

        val previous = tracked
        val stillTolerance = detection.frameDiagonal * maxCornerShiftFraction
        stableFrames = if (previous != null && previous.maxCornerShift(quad) < stillTolerance) {
            stableFrames + 1
        } else {
            1
        }
        tracked = quad
        if (stableFrames < requiredStableFrames) {
            return StabilizerDecision.Settling(quad, stableFrames)
        }
        captured = quad
        tracked = null
        stableFrames = 0
        return StabilizerDecision.Capture(quad)
    }

    /** Forgets the tracked and captured quads; used when a session starts or the camera stops. */
    fun reset() {
        tracked = null
        stableFrames = 0
        captured = null
    }

    companion object {

        /** N of the plan: three consecutive still frames before a capture. */
        const val REQUIRED_STABLE_FRAMES = 3

        /** A corner may drift by less than 2 % of the frame diagonal between two still frames. */
        const val MAX_CORNER_SHIFT_FRACTION = 0.02

        /**
         * A quad whose corners sit more than a quarter of the diagonal away from the captured one
         * is a different placement, so the cooldown releases without waiting for an empty frame.
         */
        const val COOLDOWN_RELEASE_FRACTION = 0.25
    }
}
