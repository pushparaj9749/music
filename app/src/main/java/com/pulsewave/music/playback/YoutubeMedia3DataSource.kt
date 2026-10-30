package com.pulsewave.music.playback

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import com.pulsewave.music.youtube.NewPipeOkHttpDownloader
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * Media3 network DataSource tailored for YouTube audio streaming.
 * Uses VisionOS / Firefox User-Agents, request numbering (&rn=), byte-range query params (&range=),
 * and POST {0x78, 0} on /videoplayback URLs to prevent YouTube CDN throttling and HTTP 403s.
 */
class YoutubeMedia3DataSource(
    private val rangeParameterEnabled: Boolean,
    private val rnParameterEnabled: Boolean
) : BaseDataSource(/* isNetwork = */ true) {

    class Factory(
        private val rangeParameterEnabled: Boolean = true,
        private val rnParameterEnabled: Boolean = true
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            YoutubeMedia3DataSource(rangeParameterEnabled, rnParameterEnabled)
    }

    private var currentUri: Uri? = null
    private var connection: HttpURLConnection? = null
    private var inputStream: InputStream? = null
    private var opened = false
    private var bytesToRead: Long = 0L
    private var bytesRead: Long = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        bytesRead = 0L
        bytesToRead = 0L

        val conn = openYoutubeConnection(dataSpec)
        connection = conn
        currentUri = Uri.parse(conn.url.toString())

        val responseCode = conn.responseCode
        if (responseCode < 200 || responseCode > 299) {
            val errMsg = conn.responseMessage
            closeConnectionQuietly()
            throw IOException("YouTube stream HTTP $responseCode ($errMsg)")
        }

        val positionToSkip = if (!rangeParameterEnabled && responseCode == 200 && dataSpec.position > 0L) {
            dataSpec.position
        } else {
            0L
        }

        bytesToRead = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
            dataSpec.length
        } else {
            val contentLen = conn.contentLengthLong
            if (contentLen != -1L) (contentLen - positionToSkip).coerceAtLeast(0L)
            else C.LENGTH_UNSET.toLong()
        }

        try {
            inputStream = conn.inputStream
            if (positionToSkip > 0L) {
                skipFully(positionToSkip)
            }
        } catch (e: IOException) {
            closeConnectionQuietly()
            throw e
        }

        opened = true
        transferStarted(dataSpec)
        return bytesToRead
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesToRead != C.LENGTH_UNSET.toLong()) {
            val remaining = bytesToRead - bytesRead
            if (remaining == 0L) return C.RESULT_END_OF_INPUT
        }
        val toRead = if (bytesToRead == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            min(length.toLong(), bytesToRead - bytesRead).toInt()
        }

        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val readCount = stream.read(buffer, offset, toRead)
        if (readCount == -1) {
            return C.RESULT_END_OF_INPUT
        }
        bytesRead += readCount
        bytesTransferred(readCount)
        return readCount
    }

    override fun getUri(): Uri? = currentUri

    override fun close() {
        try {
            inputStream?.let { runCatching { it.close() } }
        } finally {
            inputStream = null
            closeConnectionQuietly()
            if (opened) {
                opened = false
                transferEnded()
            }
        }
    }

    private fun openYoutubeConnection(dataSpec: DataSpec): HttpURLConnection {
        var requestUrl = dataSpec.uri.toString()
        val parsed = URL(requestUrl)
        val isVideoPlayback = parsed.path.startsWith("/videoplayback")

        if (isVideoPlayback && rnParameterEnabled && !requestUrl.contains("&rn=")) {
            requestUrl += "&rn=${requestCounter.getAndIncrement()}"
        }

        if (isVideoPlayback && rangeParameterEnabled) {
            buildRangeQueryParam(dataSpec.position, dataSpec.length)?.let { rangeParam ->
                if (!requestUrl.contains("&range=")) {
                    requestUrl += rangeParam
                }
            }
        }

        val conn = (URL(requestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 25_000
            instanceFollowRedirects = true
        }

        dataSpec.httpRequestHeaders.forEach { (k, v) ->
            conn.setRequestProperty(k, v)
        }

        if (!rangeParameterEnabled || !isVideoPlayback) {
            if (dataSpec.position > 0L || dataSpec.length != C.LENGTH_UNSET.toLong()) {
                val endPart = if (dataSpec.length != C.LENGTH_UNSET.toLong()) {
                    (dataSpec.position + dataSpec.length - 1L).toString()
                } else ""
                conn.setRequestProperty("Range", "bytes=${dataSpec.position}-$endPart")
            }
        }

        if (YoutubeParsingHelper.isWebStreamingUrl(requestUrl)) {
            conn.setRequestProperty("Origin", YOUTUBE_ORIGIN)
            conn.setRequestProperty("Referer", YOUTUBE_ORIGIN)
            conn.setRequestProperty("Sec-Fetch-Dest", "empty")
            conn.setRequestProperty("Sec-Fetch-Mode", "cors")
            conn.setRequestProperty("Sec-Fetch-Site", "cross-site")
        }

        val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(requestUrl)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            NewPipeOkHttpDownloader.USER_AGENT
        }
        conn.setRequestProperty("User-Agent", ua)
        conn.setRequestProperty("TE", "trailers")
        conn.setRequestProperty("Accept-Encoding", "identity")

        if (isVideoPlayback) {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(POST_BODY.size)
            conn.connect()
            conn.outputStream.use { os ->
                os.write(POST_BODY)
            }
        } else {
            conn.requestMethod = "GET"
            conn.connect()
        }

        return conn
    }

    private fun buildRangeQueryParam(position: Long, length: Long): String? {
        if (position == 0L && length == C.LENGTH_UNSET.toLong()) return null
        val sb = StringBuilder("&range=").append(position).append("-")
        if (length != C.LENGTH_UNSET.toLong()) {
            sb.append(position + length - 1L)
        }
        return sb.toString()
    }

    private fun skipFully(bytesToSkip: Long) {
        val stream = inputStream ?: return
        var remaining = bytesToSkip
        val skipBuf = ByteArray(4096)
        while (remaining > 0L) {
            val readLen = min(remaining, skipBuf.size.toLong()).toInt()
            val r = stream.read(skipBuf, 0, readLen)
            if (r == -1) throw IOException("EOF while skipping stream bytes")
            remaining -= r
            bytesTransferred(r)
        }
    }

    private fun closeConnectionQuietly() {
        connection?.let { runCatching { it.disconnect() } }
        connection = null
    }

    companion object {
        private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
        private val POST_BODY = byteArrayOf(0x78, 0)
        private val requestCounter = AtomicInteger(1)
    }
}
