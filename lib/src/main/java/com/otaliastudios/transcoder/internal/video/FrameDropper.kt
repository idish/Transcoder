package com.otaliastudios.transcoder.internal.video

interface FrameDropper {
    fun shouldRender(timeUs: Long): Boolean
}

/** Selects existing frames on an output-rate grid without synthesizing frames. */
fun frameDropper(inputFps: Int, outputFps: Int): FrameDropper {
    require(inputFps > 0 && outputFps > 0) { "Frame rates must be positive" }
    return object : FrameDropper {
        private var firstTimeUs: Long? = null
        private var lastRenderedTimeUs = Long.MIN_VALUE
        private var nextFrame = 1L

        override fun shouldRender(timeUs: Long): Boolean {
            val first = firstTimeUs
            if (first == null) {
                firstTimeUs = timeUs
                lastRenderedTimeUs = timeUs
                return true
            }
            if (timeUs <= lastRenderedTimeUs) return false
            // Permit microsecond quantization without accumulating floating-point error.
            val elapsedRateUnits = (timeUs - first + 1L) * outputFps
            if (elapsedRateUnits < nextFrame * 1_000_000L) return false
            lastRenderedTimeUs = timeUs
            // Skip empty intervals after a source gap instead of emitting a catch-up burst.
            nextFrame = elapsedRateUnits / 1_000_000L + 1L
            return true
        }
    }
}
