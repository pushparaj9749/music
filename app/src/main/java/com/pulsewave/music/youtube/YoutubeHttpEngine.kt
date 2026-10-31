package com.pulsewave.music.youtube

import com.pulsewave.music.model.StreamCandidate
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class OpenedYoutubeStream(
    val response: Response,
    val bodyStream: InputStream,
    val finalUrl: String,
    val responseCode: Int,
    val contentLength: Long,
    val totalResourceLength: Long
) : AutoCloseable {
    override fun close() {
        runCatching { bodyStream.close() }
        runCatching { response.close() }
    }
}

object YoutubeHttpEngine {

    private const val MAX_REDIRECTS = 15
    private const val YOUTUBE_ORIGIN = "https://www.youtube.com"
    private val POST_BODY = byteArrayOf(0x78, 0x00)
    private val requestCounter = AtomicInteger(1)

    const val ANDROID_VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)"
    const val IOS_UA =
        "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"
    const val VISIONOS_1_02_UA =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

    val streamClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(18, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .writeTimeout(18, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(true)
            .connectionPool(ConnectionPool(12, 3, TimeUnit.MINUTES))
            .build()
    }

    fun selectUserAgent(url: String, customHeaders: Map<String, String> = emptyMap()): String {
        customHeaders["User-Agent"]?.takeIf { it.isNotBlank() }?.let { return it }
        customHeaders["user-agent"]?.takeIf { it.isNotBlank() }?.let { return it }
        return when {
            YoutubeParsingHelper.isVisionOsStreamingUrl(url) ->
                YoutubeParsingHelper.getVisionOsUserAgent(null)
            url.contains("c=ANDROID_VR", ignoreCase = true) -> ANDROID_VR_UA
            url.contains("c=IOS", ignoreCase = true) -> IOS_UA
            else -> NewPipeOkHttpDownloader.USER_AGENT
        }
    }

    fun parseClenFromUrl(url: String): Long {
        val match = Regex("[?&]clen=(\\d+)").find(url)
            ?: Regex("/clen/(\\d+)").find(url)
        return match?.groupValues?.getOrNull(1)?.toLongOrNull() ?: -1L
    }

    fun openRange(
        rawUrl: String,
        position: Long = 0L,
        length: Long = -1L,
        rangeParameterEnabled: Boolean = true,
        rnParameterEnabled: Boolean = true,
        customHeaders: Map<String, String> = emptyMap(),
        preferPost: Boolean = true
    ): OpenedYoutubeStream {
        val parsed = URL(rawUrl)
        val isVideoPlayback = parsed.path.startsWith("/videoplayback")

        val primaryUsePost = isVideoPlayback && preferPost
        val primaryResp = runCatching {
            executeWithRedirects(
                baseUrl = rawUrl,
                position = position,
                length = length,
                isVideoPlayback = isVideoPlayback,
                usePost = primaryUsePost,
                useRangeParam = isVideoPlayback && rangeParameterEnabled,
                rnParameterEnabled = rnParameterEnabled,
                customHeaders = customHeaders
            )
        }

        val finalResp: Response = if (
            primaryResp.isSuccess &&
            primaryResp.getOrNull()?.code in 200..299
        ) {
            primaryResp.getOrThrow()
        } else {
            val firstCode = primaryResp.getOrNull()?.code ?: -1
            primaryResp.getOrNull()?.close()

            // Automatic fallback between POST (&range=) and GET (Range: bytes=) if CDN rejects first mode
            if (isVideoPlayback && (firstCode in listOf(-1, 400, 403, 404, 405, 411, 416))) {
                val altUsePost = !primaryUsePost
                val altResp = executeWithRedirects(
                    baseUrl = rawUrl,
                    position = position,
                    length = length,
                    isVideoPlayback = true,
                    usePost = altUsePost,
                    useRangeParam = altUsePost && rangeParameterEnabled,
                    rnParameterEnabled = rnParameterEnabled,
                    customHeaders = customHeaders
                )
                if (altResp.code !in 200..299) {
                    val code = altResp.code
                    val msg = altResp.message
                    altResp.close()
                    throw IOException("YouTube CDN HTTP $code ($msg) after fallback [first=$firstCode]")
                }
                altResp
            } else {
                val err = primaryResp.exceptionOrNull()
                if (err != null) throw err
                throw IOException("YouTube CDN HTTP $firstCode")
            }
        }

        val body = finalResp.body ?: run {
            finalResp.close()
            throw IOException("Empty response body from YouTube CDN")
        }

        val contentRangeHeader = finalResp.header("Content-Range")
        val totalFromHeader = contentRangeHeader
            ?.substringAfter("/", "")
            ?.takeIf { it.isNotBlank() && it != "*" }
            ?.toLongOrNull()
            ?: -1L

        val clenFromUrl = parseClenFromUrl(rawUrl)
        val bodyContentLen = body.contentLength()
        val totalLen = when {
            totalFromHeader > 0L -> totalFromHeader
            clenFromUrl > 0L -> clenFromUrl
            position == 0L && length < 0L && bodyContentLen > 0L -> bodyContentLen
            else -> -1L
        }

        val resolvedSliceLength = when {
            length >= 0L && bodyContentLen > 0L -> minOf(length, bodyContentLen)
            length >= 0L -> length
            bodyContentLen > 0L -> bodyContentLen
            totalLen > 0L && position < totalLen -> totalLen - position
            else -> -1L
        }

        return OpenedYoutubeStream(
            response = finalResp,
            bodyStream = body.byteStream(),
            finalUrl = finalResp.request.url.toString(),
            responseCode = finalResp.code,
            contentLength = resolvedSliceLength,
            totalResourceLength = totalLen
        )
    }

    private fun executeWithRedirects(
        baseUrl: String,
        position: Long,
        length: Long,
        isVideoPlayback: Boolean,
        usePost: Boolean,
        useRangeParam: Boolean,
        rnParameterEnabled: Boolean,
        customHeaders: Map<String, String>
    ): Response {
        var currentUrl = decorateVideoPlaybackUrl(
            rawUrl = baseUrl,
            position = position,
            length = length,
            isVideoPlayback = isVideoPlayback,
            useRangeParam = useRangeParam,
            rnParameterEnabled = rnParameterEnabled
        )
        var currentUsePost = usePost
        val userAgent = selectUserAgent(baseUrl, customHeaders)

        var redirects = 0
        while (redirects <= MAX_REDIRECTS) {
            val reqBuilder = Request.Builder().url(currentUrl)

            customHeaders.forEach { (k, v) ->
                reqBuilder.header(k, v)
            }

            reqBuilder.header("User-Agent", userAgent)
            reqBuilder.header("Accept-Encoding", "identity")
            reqBuilder.header("TE", "trailers")

            if (!useRangeParam && (position > 0L || length >= 0L)) {
                val endByte = if (length >= 0L) (position + length - 1L).toString() else ""
                reqBuilder.header("Range", "bytes=$position-$endByte")
            }

            if (YoutubeParsingHelper.isWebStreamingUrl(currentUrl) &&
                customHeaders["Origin"] == null && customHeaders["origin"] == null
            ) {
                reqBuilder.header("Origin", YOUTUBE_ORIGIN)
                reqBuilder.header("Referer", "$YOUTUBE_ORIGIN/")
                reqBuilder.header("Sec-Fetch-Dest", "empty")
                reqBuilder.header("Sec-Fetch-Mode", "cors")
                reqBuilder.header("Sec-Fetch-Site", "cross-site")
            }

            if (currentUsePost) {
                reqBuilder.post(POST_BODY.toRequestBody(null))
            } else {
                reqBuilder.get()
            }

            val resp = streamClient.newCall(reqBuilder.build()).execute()
            val code = resp.code
            if (code in listOf(301, 302, 303, 307, 308)) {
                val location = resp.header("Location")
                resp.close()
                if (location.isNullOrBlank()) {
                    throw IOException("Redirect $code missing Location header")
                }
                val resolvedNext = URL(URL(currentUrl), location).toString()
                // Keep POST {0x78, 0} across 301/302/307/308 on /videoplayback just like NewPipe
                if (code == 303 || !URL(resolvedNext).path.startsWith("/videoplayback")) {
                    currentUsePost = false
                }
                currentUrl = resolvedNext
                redirects++
                continue
            }

            return resp
        }

        throw IOException("Too many YouTube CDN redirects ($redirects)")
    }

    private fun decorateVideoPlaybackUrl(
        rawUrl: String,
        position: Long,
        length: Long,
        isVideoPlayback: Boolean,
        useRangeParam: Boolean,
        rnParameterEnabled: Boolean
    ): String {
        if (!isVideoPlayback) return rawUrl
        var result = rawUrl
        val sep = if (result.contains("?")) "&" else "?"
        if (rnParameterEnabled && !result.contains("&rn=") && !result.contains("?rn=")) {
            result = "$result${sep}rn=${requestCounter.getAndIncrement()}"
        }
        if (useRangeParam && !result.contains("&range=") && !result.contains("?range=")) {
            if (position > 0L || length >= 0L) {
                val endPart = if (length >= 0L) (position + length - 1L).toString() else ""
                val rSep = if (result.contains("?")) "&" else "?"
                result = "$result${rSep}range=$position-$endPart"
            }
        }
        return result
    }

    fun probeCandidate(candidate: StreamCandidate): Boolean {
        return runCatching {
            if (candidate.isHls) {
                openRange(
                    rawUrl = candidate.streamUrl,
                    position = 0L,
                    length = -1L,
                    rangeParameterEnabled = false,
                    rnParameterEnabled = false,
                    customHeaders = candidate.headers,
                    preferPost = false
                ).use { opened ->
                    if (opened.responseCode !in 200..299) return@runCatching false
                    val buf = ByteArray(512)
                    val n = opened.bodyStream.read(buf)
                    n > 7 && String(buf, 0, n).contains("#EXTM3U")
                }
            } else {
                openRange(
                    rawUrl = candidate.streamUrl,
                    position = 0L,
                    length = 2048L,
                    rangeParameterEnabled = true,
                    rnParameterEnabled = true,
                    customHeaders = candidate.headers,
                    preferPost = candidate.preferPostVideoPlayback
                ).use { opened ->
                    if (opened.responseCode !in 200..299) return@runCatching false
                    val buf = ByteArray(256)
                    val n = opened.bodyStream.read(buf)
                    n >= 32
                }
            }
        }.getOrDefault(false)
    }
}
