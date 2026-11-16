@file:Suppress("MagicNumber", "UnusedPrivateMember")

package com.otaliastudios.transcoder.internal

import com.otaliastudios.transcoder.common.TrackType
import com.otaliastudios.transcoder.internal.utils.Logger
import com.otaliastudios.transcoder.internal.utils.TrackMap
import com.otaliastudios.transcoder.source.DataSource
import com.otaliastudios.transcoder.time.TimeInterpolator

class Timer(
    private val interpolator: TimeInterpolator,
    private val sources: DataSources,
    private val tracks: Tracks,
    private val current: TrackMap<Int>
) {

    private val log = Logger("Timer")

    private fun List<DataSource>.durationUs(current: Int) = foldIndexed(0L) { index, acc, source ->
        // If source has been drained, readUs can be more precise than durationUs
        acc + if (index < current) source.positionUs else source.durationUs
    }

    private fun List<DataSource>.positionUs(current: Int) = foldIndexed(0L) { index, acc, source ->
        if (index <= current) acc + source.positionUs else acc
    }

    val positionUs = object : TrackMap<Long> {
        override fun has(type: TrackType) = true
        override fun get(type: TrackType): Long {
            return if (!tracks.active.has(type)) 0L
            else sources[type].positionUs(current = current[type])
        }
    }

    val durationUs = object : TrackMap<Long> {
        override fun has(type: TrackType) = true
        override fun get(type: TrackType): Long {
            return if (!tracks.active.has(type)) 0L
            else sources[type].durationUs(current = current[type])
        }
    }

    val totalDurationUs: Long get() {
        val video = if (tracks.active.hasVideo) durationUs.video else Long.MAX_VALUE
        val audio = if (tracks.active.hasAudio) durationUs.audio else Long.MAX_VALUE
        return minOf(video, audio)
    }

    val progress = object : TrackMap<Double> {
        override fun has(type: TrackType) = true
        override fun get(type: TrackType): Double {
            val read = positionUs[type] // 0L if not active
            val total = durationUs[type] // 0L if not active
            return if (total == 0L) 0.0 else read.toDouble() / total
        }
    }

    private val interpolators = mutableMapOf<Pair<TrackType, Int>, SourceInterpolator>()

    /** Lays one source's timestamps after the previous source's. */
    private inner class SourceInterpolator(private val index: Int, private val type: TrackType) : TimeInterpolator {
        var lastOut = 0L
            private set

        /** Time between this source's last two frames (or audio buffers); 0 until there are two. */
        var lastStepOut = 0L
            private set

        private var firstIn = Long.MAX_VALUE
        private var count = 0

        // A source's first frame comes one frame after the previous source's last one, as the next
        // frame of a single source would. It used to come 10 us after it, so the last frame was
        // never on screen and a frame-rate limit (fresh for each source) dropped the frame after:
        // a visible skip where a boomerang's forward clip hands over to the reversed one.
        private val firstOut = when (index) {
            0 -> 0L
            else -> {
                val previous = interpolators[type to index - 1]!!
                previous.lastOut + previous.lastStepOut.coerceAtLeast(MIN_SOURCE_GAP_US)
            }
        }

        override fun interpolate(type: TrackType, time: Long): Long = when (time) {
            Long.MAX_VALUE -> lastOut
            else -> {
                if (firstIn == Long.MAX_VALUE) firstIn = time
                val out = firstOut + (time - firstIn)
                if (count++ > 0 && out > lastOut) lastStepOut = out - lastOut
                lastOut = out
                interpolator.interpolate(type, out)
            }
        }
    }

    fun localize(type: TrackType, index: Int, positionUs: Long): Long? {
        if (!tracks.active.has(type)) return null
        val behindUs = sources[type]
            .filterIndexed { i, _ -> i < index }
            .durationUs(-1)
        val localizedUs = positionUs - behindUs
        return if (localizedUs < 0L || localizedUs > sources[type][index].durationUs) null else localizedUs
    }

    fun interpolator(type: TrackType, index: Int): TimeInterpolator =
        interpolators.getOrPut(type to index) { SourceInterpolator(index, type) }

    private companion object {
        /** Without a step to go by (a one-frame source): just so the timestamps aren't identical. */
        const val MIN_SOURCE_GAP_US = 10L
    }
}
