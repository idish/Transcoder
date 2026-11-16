package com.otaliastudios.transcoder.internal.video

interface FrameDropper {
    fun shouldRender(timeUs: Long): Boolean
}

/**
 * Selects existing frames for the output rate without synthesizing frames.
 *
 * Two rules, both in integer "rate units" (microseconds times output fps, one output frame is
 * [FRAME_UNITS]) so nothing accumulates:
 * - Rate: the output never runs more than one frame ahead of [outputFps] (a leaky bucket).
 * - Spacing: a frame at least [MIN_SPACING_UNITS] after the last one is kept while the rate allows
 *   it. Closer frames (60 fps, strongly sped up) go by a grid anchored at the first frame, which
 *   spreads the dropped frames evenly.
 *
 * A grid alone dropped frames that were never surplus: camera clips at 30.01 fps land microseconds
 * before their slots (the second frame was dropped, a stutter at the clip's start), and a clip that's
 * already 30 fps on average but uneven (a boomerang sped up to 1.2x, re-encoded for sharing) lost
 * frames where the grid's phase differed, leaving gaps of two frames.
 */
fun frameDropper(inputFps: Int, outputFps: Int): FrameDropper {
    require(inputFps > 0 && outputFps > 0) { "Frame rates must be positive" }
    return object : FrameDropper {
        private var firstTimeUs: Long? = null
        private var lastRenderedTimeUs = Long.MIN_VALUE
        private var nextFrame = 1L
        private var nextAllowedUnits = 0L

        override fun shouldRender(timeUs: Long): Boolean {
            val first = firstTimeUs
            if (first == null) {
                firstTimeUs = timeUs
                lastRenderedTimeUs = timeUs
                return true
            }
            if (timeUs <= lastRenderedTimeUs) return false
            val elapsedUnits = (timeUs - first) * outputFps
            if (elapsedUnits < nextAllowedUnits) return false
            // Permit microsecond quantization on the grid.
            val onGrid = elapsedUnits + outputFps >= nextFrame * FRAME_UNITS
            val spaced = (timeUs - lastRenderedTimeUs) * outputFps >= MIN_SPACING_UNITS
            if (!onGrid && !spaced) return false
            lastRenderedTimeUs = timeUs
            // Skip empty intervals after a source gap instead of emitting a catch-up burst.
            nextFrame = maxOf(nextFrame, (elapsedUnits + outputFps) / FRAME_UNITS + 1L)
            nextAllowedUnits = maxOf(nextAllowedUnits, elapsedUnits - FRAME_UNITS) + FRAME_UNITS
            return true
        }
    }
}

private const val FRAME_UNITS = 1_000_000L

/** Three quarters of an output frame. */
private const val MIN_SPACING_UNITS = 750_000L
