package com.pulsewave.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.pulsewave.music.youtube.OpenedYoutubeStream
import com.pulsewave.music.youtube.YoutubeHttpEngine
import java.io.IOException
import java.net.URL
import kotlin.math.min

/**
 * Bulletproof Media3 network DataSource for YouTube audio streaming.
 * - Handles HTTP 301/302/303/307/308 ISP edge redirects on POST /videoplayback requests
 * - Automatically falls back between POST {0x78, 0} (&range=) and GET (Range: bytes=)
 * - Transparently chunks unbounded /videoplayback streams into 512 KB range requests
 *   so YouTube CDNs never throttle bandwidth or cut off Android VR / VisionOS streams at 1 MiB.
 */
class YoutubeMedia3DataSource(
    private val rangeParameterEnabled: Boolean,
    private val rnParameterEnabled: Boolean,
    private val chunkedUnboundedEnabled: Boolean,
    private val preferPostVideoPlayback: Boolean,
    private val defaultHeaders: Map<String, String>
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val rangeParameterEnabled: Boolean = true,
        private val rnParameterEnabled: Boolean = true,
        private val chunkedUnboundedEnabled: Boolean = false,
        private val preferPostVideoPlayback: Boolean = true,
        private val defaultHeaders: Map<String, String> = emptyMap()
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource = YoutubeMedia3DataSource(
            rangeParameterEnabled = rangeParameterEnabled,
            rnParameterEnabled = rnParameterEnabled,
            chunkedUnboundedEnabled = chunkedUnboundedEnabled,
            preferPostVideoPlayback = preferPostVideoPlayback,
            defaultHeaders = defaultHeaders
        )
    }

    private var baseStreamUrl: String = ""
    private var mergedHeaders: Map<String, String> = emptyMap()
    private var currentUri: Uri? = null
    private var activeStream: OpenedYoutubeStream? = null

    private var opened = false
    private var isChunkedMode = false
    private var startPosition: Long = 0L
    private var bytesToRead: Long = C.LENGTH_UNSET.toLong()
    private var bytesRead: Long = 0L
    private var currentChunkRemaining: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        baseStreamUrl = dataSpec.uri.toString()
        mergedHeaders = defaultHeaders + dataSpec.httpRequestHeaders
        startPosition = dataSpec.position
        bytesRead = 0L
        bytesToRead = C.LENGTH_UNSET.toLong()
        currentChunkRemaining = 0L

        val isVideoPlayback = runCatching {
            URL(baseStreamUrl).path.startsWith("/videoplayback")
        }.getOrDefault(false)

        val useChunkedProgressive =
            isVideoPlayback &&
                chunkedUnboundedEnabled &&
                dataSpec.length == C.LENGTH_UNSET.toLong()

        try {
            if (useChunkedProgressive) {
                val knownClen = YoutubeHttpEngine.parseClenFromUrl(baseStreamUrl)
                val firstChunkLen = if (knownClen > 0L) {
                    min(CHUNK_SIZE_BYTES, (knownClen - startPosition).coerceAtLeast(1L))
                } else {
                    CHUNK_SIZE_BYTES
                }

                val firstOpened = YoutubeHttpEngine.openRange(
                    rawUrl = baseStreamUrl,
                    position = startPosition,
                    length = firstChunkLen,
                    rangeParameterEnabled = true,
                    rnParameterEnabled = rnParameterEnabled,
                    customHeaders = mergedHeaders,
                    preferPost = preferPostVideoPlayback
                )
                activeStream = firstOpened
                currentUri = Uri.parse(firstOpened.finalUrl)

                val totalLen = firstOpened.totalResourceLength
                if (totalLen > startPosition) {
                    isChunkedMode = true
                    bytesToRead = totalLen - startPosition
                    currentChunkRemaining = if (firstOpened.contentLength > 0L) {
                        min(firstOpened.contentLength, bytesToRead)
                    } else {
                        min(firstChunkLen, bytesToRead)
                    }
                } else {
                    // Server did not report total size; read stream normally
                    isChunkedMode = false
                    bytesToRead = if (firstOpened.contentLength > 0L) {
                        firstOpened.contentLength
                    } else {
                        C.LENGTH_UNSET.toLong()
                    }
                }
            } else {
                isChunkedMode = false
                val reqLength = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    dataSpec.length
                } else {
                    -1L
                }
                val stream = YoutubeHttpEngine.openRange(
                    rawUrl = baseStreamUrl,
                    position = startPosition,
                    length = reqLength,
                    rangeParameterEnabled = rangeParameterEnabled,
                    rnParameterEnabled = rnParameterEnabled,
                    customHeaders = mergedHeaders,
                    preferPost = preferPostVideoPlayback
                )
                activeStream = stream
                currentUri = Uri.parse(stream.finalUrl)
                bytesToRead = when {
                    dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                    stream.contentLength > 0L -> stream.contentLength
                    else -> C.LENGTH_UNSET.toLong()
                }
            }
        } catch (e: Exception) {
            closeActiveStreamQuietly()
            throw if (e is IOException) e else IOException("Stream open error: ${e.message}", e)
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong() && bytesRead >= bytesToRead) {
            return C.RESULT_END_OF_INPUT
        }

        if (isChunkedMode && currentChunkRemaining <= 0L) {
            openNextChunk()
        }

        val stream = activeStream?.bodyStream ?: return C.RESULT_END_OF_INPUT
        val maxWanted = when {
            isChunkedMode -> min(length.toLong(), currentChunkRemaining).toInt()
            bytesToRead != C.LENGTH_UNSET.toLong() -> min(length.toLong(), bytesToRead - bytesRead).toInt()
            else -> length
        }

        val count = stream.read(buffer, offset, maxWanted)
        if (count == -1) {
            if (isChunkedMode && bytesToRead != C.LENGTH_UNSET.toLong() && bytesRead < bytesToRead) {
                currentChunkRemaining = 0L
                openNextChunk()
                val retryStream = activeStream?.bodyStream ?: return C.RESULT_END_OF_INPUT
                val retryMax = min(length.toLong(), currentChunkRemaining).toInt()
                val retryCount = retryStream.read(buffer, offset, retryMax)
                if (retryCount == -1) return C.RESULT_END_OF_INPUT
                bytesRead += retryCount
                currentChunkRemaining -= retryCount
                bytesTransferred(retryCount)
                return retryCount
            }
            return C.RESULT_END_OF_INPUT
        }

        bytesRead += count
        if (isChunkedMode) {
            currentChunkRemaining -= count
        }
        bytesTransferred(count)
        return count
    }

    private fun openNextChunk() {
        closeActiveStreamQuietly()
        val nextOffset = startPosition + bytesRead
        val remainingTotal = (bytesToRead - bytesRead).coerceAtLeast(0L)
        if (remainingTotal <= 0L) return

        val nextLen = min(CHUNK_SIZE_BYTES, remainingTotal)
        val nextOpened = YoutubeHttpEngine.openRange(
            rawUrl = baseStreamUrl,
            position = nextOffset,
            length = nextLen,
            rangeParameterEnabled = true,
            rnParameterEnabled = rnParameterEnabled,
            customHeaders = mergedHeaders,
            preferPost = preferPostVideoPlayback
        )
        activeStream = nextOpened
        currentUri = Uri.parse(nextOpened.finalUrl)
        currentChunkRemaining = if (nextOpened.contentLength > 0L) {
            min(nextOpened.contentLength, remainingTotal)
        } else {
            nextLen
        }
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            closeActiveStreamQuietly()
        } finally {
            currentUri = null
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun closeActiveStreamQuietly() {
        activeStream?.close()
        activeStream = null
    }

    companion object {
        private const val CHUNK_SIZE_BYTES = 512L * 1024L // 512 KB fast anti-throttle chunks
    }
}
