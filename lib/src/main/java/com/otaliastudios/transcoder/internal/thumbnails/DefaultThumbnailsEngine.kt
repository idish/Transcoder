@file:Suppress("MagicNumber", "UnusedPrivateMember")

package com.otaliastudios.transcoder.internal.thumbnails

import android.media.MediaFormat
import com.otaliastudios.transcoder.common.TrackType
import com.otaliastudios.transcoder.internal.CustomSegments
import com.otaliastudios.transcoder.internal.DataSources
import com.otaliastudios.transcoder.internal.Tracks
import com.otaliastudios.transcoder.internal.codec.Decoder
import com.otaliastudios.transcoder.internal.codec.TranscoderEventsListener
import com.otaliastudios.transcoder.internal.data.Reader
import com.otaliastudios.transcoder.internal.data.Seeker
import com.otaliastudios.transcoder.internal.pipeline.Pipeline
import com.otaliastudios.transcoder.internal.pipeline.plus
import com.otaliastudios.transcoder.internal.utils.Logger
import com.otaliastudios.transcoder.internal.utils.trackMapOf
import com.otaliastudios.transcoder.internal.video.VideoRenderer
import com.otaliastudios.transcoder.internal.video.VideoSnapshots
import com.otaliastudios.transcoder.resize.Resizer
import com.otaliastudios.transcoder.source.DataSource
import com.otaliastudios.transcoder.strategy.DefaultVideoStrategy
import com.otaliastudios.transcoder.strategy.RemoveTrackStrategy
import com.otaliastudios.transcoder.thumbnail.SingleThumbnailRequest
import com.otaliastudios.transcoder.thumbnail.Thumbnail
import com.otaliastudios.transcoder.thumbnail.ThumbnailRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import java.util.ArrayList

class DefaultThumbnailsEngine(
    private val dataSources: DataSources,
    private val rotation: Int,
    resizer: Resizer,
    private val eventListener: TranscoderEventsListener?
) : ThumbnailsEngine() {

    private var shouldSeek = true
    private var shouldFlush = false
    private var finish = false
    private val log = Logger("ThumbnailsEngine")
    private var previousSnapshotUs = 0L
    // Huge framerate triks the VideoRenderer into not dropping frames, which is important
    // for thumbnail requests that want to catch the very last frame.
    private val tracks = Tracks(
        trackMapOf(
            video = DefaultVideoStrategy.Builder()
                .frameRate(120)
                .addResizer(resizer)
                .build(),
            audio = RemoveTrackStrategy()
        ),
        dataSources,
        rotation,
        true
    )

    private val segments = CustomSegments(dataSources, tracks, ::createPipeline)

    init {
        log.i("Created Tracks, Segments, Timer...")
    }

    private class Stub(
        val request: ThumbnailRequest,
        val positionUs: Long,
        val localizedUs: Long
    ) {
        var actualLocalizedUs: Long = localizedUs
        override fun toString(): String {
            return request.sourcePath() + ":" + positionUs.toString()
        }
    }

    private val stubs = ArrayDeque<Stub>()

    private inner class IgnoringEosDataSource(
        private val source: DataSource,
    ) : DataSource by source {

        override fun requestKeyFrameTimestamps() = source.requestKeyFrameTimestamps()

        override fun getKeyFrameTimestamps() = source.keyFrameTimestamps

        override fun getSeekThreshold() = source.seekThreshold

        override fun mediaId() = source.mediaId()

        override fun isDrained(): Boolean {
            if (source.isDrained) {
                source.seekTo(stubs.firstOrNull()?.positionUs ?: -1)
            }
            return source.isDrained
        }
    }
    private fun DataSource.ignoringEOS(): DataSource = IgnoringEosDataSource(this)

    private fun createPipeline(
        type: TrackType,
        source: DataSource,
        outputFormat: MediaFormat
    ): Pipeline {
        val source = source.ignoringEOS()
        if(VERBOSE) {
            log.i("Creating pipeline for $source. absoluteUs=${stubs.joinToString { it.toString() }}")
        }
        shouldSeek = true
        shouldFlush = false
        return Pipeline.build("Thumbnails") {
            Seeker(source) {
                var seek = false
                val requested = stubs.firstOrNull()?.positionUs ?: -1

                if (!shouldSeek || requested == -1L)
                    return@Seeker Pair(requested, seek)

                val seekUs: Long
                val current = source.positionUs
                val threshold = stubs.firstOrNull()?.request?.threshold() ?: 0L
                val nextKeyFrameIndex = source.search(requested)

                val nextKeyFrameUs = source.keyFrameAt(nextKeyFrameIndex) { Long.MAX_VALUE }
                val previousKeyFrameUs = source.keyFrameAt(nextKeyFrameIndex - 1) { source.lastKeyFrame() }


                val rightGap = nextKeyFrameUs - requested
                val nextKeyFrameInThreshold = rightGap <= threshold
                seek = nextKeyFrameInThreshold || previousKeyFrameUs > current || (current - requested > threshold)
                seekUs =
                    (if (nextKeyFrameInThreshold) nextKeyFrameUs else previousKeyFrameUs) + source.seekThreshold

                if (VERBOSE) {
                    log.i(
                        "seek: current ${source.positionUs}," +
                                " requested $requested, threshold $threshold, nextKeyFrameUs $nextKeyFrameUs," +
                                " nextKeyFrameInThreshold:$nextKeyFrameInThreshold, seekUs: $seekUs, flushing : $seek"
                    )
                }

                shouldFlush = seek
                shouldSeek = false
                Pair(seekUs, seek)
            } +
                Reader(source, type) +
                Decoder(source.getTrackFormat(type)!!, continuous = false, useSwFor4K = true, eventListener) {
                    shouldFlush.also {
                        shouldFlush = false
                    }
                } +
                VideoRenderer(source.orientation, rotation, outputFormat, flipY = true, true) {
                    stubs.firstOrNull()?.positionUs ?: -1
                } +
                VideoSnapshots(outputFormat, fetchPosition) { pos, bitmap ->
                    val stub = stubs.removeFirstOrNull()
                    if (stub != null) {
                        shouldSeek = true
                        stub.actualLocalizedUs = pos
                        previousSnapshotUs = pos
                        log.i(
                            "Got snapshot. positionUs=${stub.positionUs} " +
                                "localizedUs=${stub.localizedUs} " +
                                "actualLocalizedUs=${stub.actualLocalizedUs} " +
                                "deltaUs=${stub.localizedUs - stub.actualLocalizedUs}"
                        )
                        val thumbnail = Thumbnail(stub.request, stub.positionUs, bitmap)
                        val callbackStatus = progress.trySend(thumbnail)
                        if (VERBOSE) {
                            log.i("Callback Send Status ${callbackStatus.isSuccess}")
                        }
                    }
                }
        }
    }

    private val progress = Channel<Thumbnail>(Channel.BUFFERED)


    private fun DataSource.lastKeyFrame() = keyFrameAt(keyFrameTimestamps.size - 1)

    override val progressFlow: Flow<Thumbnail> = progress.receiveAsFlow()

    private inline fun DataSource.keyFrameAt(index: Int, defaultValue: ((Int)-> Long) = {_ -> -1}) =
        keyFrameTimestamps.getOrElse(index, defaultValue)

    private fun DataSource.search(timestampUs: Long): Int {
        if (keyFrameTimestamps.isEmpty())
            requestKeyFrameTimestamps()

        val searchIndex = keyFrameTimestamps.binarySearch(timestampUs)

        val nextKeyFrameIndex = when {
            searchIndex >= 0 -> searchIndex
            else -> {
                val index = -searchIndex - 1
                when {
                    index >= keyFrameTimestamps.size -> {
                        val ts = requestKeyFrameTimestamps()
                        if (ts == -1L) {
                            -1
                        } else {
                            search(timestampUs)
                        }
                    }
                    index < keyFrameTimestamps.size -> index
                    else -> {
                        -1 // will never reach here. kotlin is stupid
                    }
                }
            }
        }

        return nextKeyFrameIndex
    }

    override fun addDataSource(dataSource: DataSource) {
        if (dataSources.getVideoSources().find { it.mediaId() == dataSource.mediaId() } != null) {
            return // dataSource already exists
        }
        dataSources.addVideoDataSource(dataSource)
        tracks.updateTracksInfo()
        if (tracks.active.has(TrackType.VIDEO) && dataSource.getTrackFormat(TrackType.VIDEO) != null) {
            dataSource.selectTrack(TrackType.VIDEO)
        }
    }

    override fun removeDataSource(dataSourceId: String) {
        segments.releaseSegment(dataSourceId)
        dataSources.removeVideoDataSource(dataSourceId)
        tracks.updateTracksInfo()
    }

    override fun updateDataSources(dataSourcesNew: List<DataSource>) {
        val currentVideoIds = dataSources.videoOrNull()?.map { it.mediaId() }
        val newSourceIds = dataSourcesNew.map { it.mediaId() }.distinct()
        val toAdd = newSourceIds - currentVideoIds
        val toRemove = currentVideoIds?.minus(newSourceIds)
        toAdd.forEach { id ->
            val source = dataSourcesNew.first { it.mediaId() == id }
            addDataSource(source)
        }
        toRemove?.forEach { id ->
            removeDataSource(id)
        }
    }

    override suspend fun queueThumbnails(list: List<ThumbnailRequest>) {

        val map = list.groupBy { it.sourcePath() }

        map.forEach { entry ->
            val dataSource = getDataSourceByPath(entry.key)
            if (dataSource != null) {
                val duration = dataSource.getVideoTrackDuration()
                val positions = entry.value.flatMap { request ->
                    request.locate(duration).map { it to request }
                }.sortedBy { it.first }
                stubs.addAll(
                    positions.map { (positionUs, request) ->
                        Stub(request, positionUs, positionUs)
                    }.toMutableList().reorder(dataSource)
                )
                if (VERBOSE) {
                    log.i("Updating pipeline positions for segment source#$dataSource absoluteUs=${positions.joinToString { it.first.toString() }}, and stubs $stubs")
                }
            }

        }

        while (currentCoroutineContext().isActive && stubs.isNotEmpty()) {
            var advanced = false
            val stub = stubs.firstOrNull()
            try {
                val segment = stub?.request?.sourcePath()?.let { segments.getSegment(it) }

                if (VERBOSE) {
                    log.i("loop advancing for $segment")
                }
                advanced = try {
                    segment?.advance() ?: false
                } catch (e: Exception) {
                    if (e !is CancellationException) {
                        currentCoroutineContext().ensureActive()
                    }
                    throw e
                }
            } catch (e: Exception) {
                val path = stub?.request?.sourcePath()
                if (path != null) {
                    val dataSource = getDataSourceByPath(path)
                    val bitmap = dataSource?.getFrameAtPosition(stub.positionUs, 150, 150)
                    if (bitmap != null) {
                        val thumbnail = Thumbnail(stub.request, stub.positionUs, bitmap)
                        progress.trySend(thumbnail)
                        stubs.removeFirst()
                        advanced = true
                    }
                }
            }

            // avoid calling hasNext if we advanced.
            val completed = !advanced && !segments.hasNext()
            if (completed || stubs.isEmpty()) {
                log.i("loop broken $stubs $hasMoreRequestsIncoming")
                if (!hasMoreRequestsIncoming) {
                    try {
                        segments.release()
                    } catch (e: IllegalStateException) {

                    }
                }
                break
            } else if (!advanced) {
                delay(WAIT_MS)
            }
        }

    }

    private fun DataSource.getVideoTrackDuration() =
        getTrackFormat(TrackType.VIDEO)?.getLong(MediaFormat.KEY_DURATION)
            ?: durationUs

    fun finish() {
        this.finish = true
        segments.release()
    }

    override fun removePosition(sourcePath: String, sourceId: String, positionUs: Long) {
        if (positionUs < 0) {
//            val activeStub = stubs.firstOrNull()?.takeIf { it.request.sourceId() == sourceId }
            stubs.removeAll {
                it.request.sourceId() == sourceId
            }
//            if (activeStub != null) {
//                stubs.addFirst(activeStub)
//            }
            shouldSeek = true
            return
        }
        val isStubActive =
            stubs.firstOrNull()?.request?.sourceId() == sourceId && positionUs == stubs.firstOrNull()?.positionUs && positionUs > 0
        if (isStubActive) {
            return
        }

        val dataSource = getDataSourceByPath(sourcePath)
        if (dataSource != null) {
            val duration = dataSource.getVideoTrackDuration()
            val locatedTimestampUs = SingleThumbnailRequest(positionUs).locate(duration)[0]
            val stub =
                stubs.find { it.request.sourceId() == sourceId && it.positionUs == locatedTimestampUs }
            if (stub != null) {
                log.i("removePosition Match: $positionUs :$stubs")
                stubs.remove(stub)
                shouldSeek = true
            }
        }
    }


    override fun getDataSourceByPath(source: String): DataSource? {
        return dataSources[TrackType.VIDEO].firstOrNull { it.mediaId() == source }
    }

    private fun  List<Stub>.reorder(source: DataSource): Collection<Stub> {
        val bucketListMap = LinkedHashMap<Long, ArrayList<Stub>>()
        val finalList = ArrayList<Stub>()

        forEach {
            val nextKeyFrameIndex = source.search(it.positionUs)
            val previousKeyFrameUs = source.keyFrameAt(nextKeyFrameIndex - 1) { source.lastKeyFrame() }

            val list = bucketListMap.getOrPut(previousKeyFrameUs) { ArrayList<Stub>() }
            list.add(it)
        }
        bucketListMap.forEach {
            finalList.addAll(it.value.sortedBy { it.positionUs })
        }
        return finalList
    }

    private val fetchPosition: () -> VideoSnapshots.Request? = {
        if (stubs.isEmpty()) null
        else VideoSnapshots.Request(stubs.first().localizedUs, stubs.first().request.threshold())
    }

    override fun cleanup() {
        runCatching { stubs.clear() }
        runCatching { segments.release() }
        runCatching { dataSources.release() }
    }

    companion object {
        private const val WAIT_MS = 5L
        private const val VERBOSE = false
    }
}
