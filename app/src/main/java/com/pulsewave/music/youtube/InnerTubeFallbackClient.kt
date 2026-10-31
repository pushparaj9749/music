package com.pulsewave.music.youtube

import com.grack.nanojson.JsonObject
import com.grack.nanojson.JsonParser
import com.grack.nanojson.JsonWriter
import com.pulsewave.music.model.StreamCandidate
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom

object InnerTubeFallbackClient {

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private val NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val random = SecureRandom()

    @Volatile
    private var cachedVisitorData: String? = null

    @Volatile
    private var visitorDataExpiresAtMs: Long = 0L

    private data class ClientProfile(
        val label: String,
        val endpointUrl: String,
        val clientName: String,
        val clientVersion: String,
        val clientIdHeader: String,
        val userAgent: String,
        val osName: String,
        val osVersion: String,
        val deviceMake: String,
        val deviceModel: String,
        val androidSdkVersion: Int? = null,
        val platform: String? = null,
        val origin: String? = null,
        val preferPostPlayback: Boolean = true
    )

    private val profiles = listOf(
        ClientProfile(
            label = "InnerTube VisionOS 1.02",
            endpointUrl = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName = "VISIONOS",
            clientVersion = "1.02",
            clientIdHeader = "101",
            userAgent = YoutubeHttpEngine.VISIONOS_1_02_UA,
            osName = "visionOS",
            osVersion = "26.5.23O471",
            deviceMake = "Apple",
            deviceModel = "RealityDevice17,1",
            origin = "https://music.youtube.com",
            preferPostPlayback = true
        ),
        ClientProfile(
            label = "InnerTube VisionOS 0.1 Music",
            endpointUrl = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName = "VISIONOS",
            clientVersion = "0.1",
            clientIdHeader = "101",
            userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_6) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15",
            osName = "VISION_OS",
            osVersion = "1.3",
            deviceMake = "Apple",
            deviceModel = "RealityDevice14,1",
            platform = "MOBILE",
            origin = "https://music.youtube.com",
            preferPostPlayback = true
        ),
        ClientProfile(
            label = "InnerTube Android VR",
            endpointUrl = "https://music.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName = "ANDROID_VR",
            clientVersion = "1.61.48",
            clientIdHeader = "28",
            userAgent = YoutubeHttpEngine.ANDROID_VR_UA,
            osName = "Android",
            osVersion = "12",
            deviceMake = "Oculus",
            deviceModel = "Quest 3",
            androidSdkVersion = 32,
            origin = "https://music.youtube.com",
            preferPostPlayback = false
        ),
        ClientProfile(
            label = "InnerTube iOS HLS",
            endpointUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false",
            clientName = "IOS",
            clientVersion = "21.26.4",
            clientIdHeader = "5",
            userAgent = YoutubeHttpEngine.IOS_UA,
            osName = "iPhone",
            osVersion = "18.3.2.22D82",
            deviceMake = "Apple",
            deviceModel = "iPhone16,2",
            preferPostPlayback = false
        )
    )

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi-libre.kavin.rocks"
    )

    data class FallbackExtractResult(
        val candidates: List<StreamCandidate>,
        val durationSec: Long
    )

    fun extractCandidates(
        videoId: String,
        countryCode: String = "IN",
        preferHighQuality: Boolean = true
    ): FallbackExtractResult {
        val visitorData = fetchVisitorData(countryCode)
        val collected = mutableListOf<StreamCandidate>()
        var detectedDurationSec = 0L

        for (profile in profiles) {
            runCatching {
                val respObj = queryProfile(profile, videoId, countryCode, visitorData) ?: return@runCatching
                val status = respObj.getObject("playabilityStatus")?.getString("status")
                if (!status.equals("OK", ignoreCase = true)) return@runCatching

                val videoDetails = respObj.getObject("videoDetails")
                if (videoDetails != null) {
                    val returnedId = videoDetails.getString("videoId")
                    if (!returnedId.isNullOrBlank() && returnedId != videoId) return@runCatching
                    val lenSec = videoDetails.getString("lengthSeconds")?.toLongOrNull() ?: 0L
                    if (lenSec > 0L && detectedDurationSec == 0L) {
                        detectedDurationSec = lenSec
                    }
                }

                val streamingData = respObj.getObject("streamingData") ?: return@runCatching
                val headers = buildMap {
                    put("User-Agent", profile.userAgent)
                    profile.origin?.let {
                        put("Origin", it)
                        put("Referer", "$it/")
                    }
                }

                val profileAudioCandidates = mutableListOf<StreamCandidate>()

                val adaptive = streamingData.getArray("adaptiveFormats")
                if (adaptive != null) {
                    for (i in 0 until adaptive.size) {
                        val fmt = adaptive.getObject(i) ?: continue
                        val url = fmt.getString("url")?.takeIf { it.startsWith("http") } ?: continue
                        val mimeType = fmt.getString("mimeType") ?: ""
                        if (!mimeType.startsWith("audio/")) continue
                        val bitrate = fmt.getInt("averageBitrate", fmt.getInt("bitrate", 128_000)) / 1000
                        val clen = fmt.getString("contentLength")?.toLongOrNull() ?: -1L
                        profileAudioCandidates.add(
                            StreamCandidate(
                                streamUrl = url,
                                isHls = false,
                                dashManifestXml = null,
                                mimeType = mimeType.substringBefore(";"),
                                bitrate = bitrate,
                                contentLength = clen,
                                engineLabel = "${profile.label} (${bitrate}kbps)",
                                headers = headers,
                                preferPostVideoPlayback = profile.preferPostPlayback
                            )
                        )
                    }
                }

                val sortedAudio = if (preferHighQuality) {
                    profileAudioCandidates.sortedByDescending { it.bitrate }
                } else {
                    profileAudioCandidates.sortedBy { if (it.bitrate <= 0) 999 else it.bitrate }
                }
                collected.addAll(sortedAudio.take(2))

                // Also grab muxed progressive audio/video (itag 18) as backup if adaptive fails
                val muxed = streamingData.getArray("formats")
                if (muxed != null) {
                    for (i in 0 until muxed.size) {
                        val fmt = muxed.getObject(i) ?: continue
                        val url = fmt.getString("url")?.takeIf { it.startsWith("http") } ?: continue
                        val mimeType = fmt.getString("mimeType") ?: "video/mp4"
                        val clen = fmt.getString("contentLength")?.toLongOrNull() ?: -1L
                        collected.add(
                            StreamCandidate(
                                streamUrl = url,
                                isHls = false,
                                dashManifestXml = null,
                                mimeType = mimeType.substringBefore(";"),
                                bitrate = 128,
                                contentLength = clen,
                                engineLabel = "${profile.label} (Muxed AAC)",
                                headers = headers,
                                preferPostVideoPlayback = profile.preferPostPlayback
                            )
                        )
                    }
                }

                // Also grab HLS manifest if present
                val hlsUrl = streamingData.getString("hlsManifestUrl")
                if (!hlsUrl.isNullOrBlank()) {
                    collected.add(
                        StreamCandidate(
                            streamUrl = hlsUrl,
                            isHls = true,
                            mimeType = "application/x-mpegURL",
                            bitrate = 128,
                            engineLabel = "${profile.label} (HLS)",
                            headers = headers,
                            preferPostVideoPlayback = false
                        )
                    )
                }
            }

            // Stop querying additional profiles early once we already have playable direct candidates
            if (collected.size >= 3) break
        }

        // If direct InnerTube yielded nothing, check Piped public mirrors as final emergency backup
        if (collected.isEmpty()) {
            for (mirror in pipedInstances) {
                runCatching {
                    val req = Request.Builder()
                        .url("$mirror/streams/$videoId")
                        .header("User-Agent", NewPipeOkHttpDownloader.USER_AGENT)
                        .get()
                        .build()
                    NewPipeOkHttpDownloader.getInstance().okHttpClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use
                        val raw = resp.body?.string() ?: return@use
                        val obj = JsonParser.`object`().from(raw)
                        if (detectedDurationSec == 0L) {
                            detectedDurationSec = obj.getLong("duration", 0L)
                        }
                        val audioArr = obj.getArray("audioStreams")
                        if (audioArr != null) {
                            for (i in 0 until audioArr.size) {
                                val a = audioArr.getObject(i) ?: continue
                                val u = a.getString("url")?.takeIf { it.startsWith("http") } ?: continue
                                val br = a.getInt("bitrate", 128_000) / 1000
                                val mt = a.getString("mimeType") ?: "audio/mp4"
                                collected.add(
                                    StreamCandidate(
                                        streamUrl = u,
                                        isHls = false,
                                        mimeType = mt,
                                        bitrate = br.coerceAtLeast(64),
                                        engineLabel = "Piped Cloud (${br.coerceAtLeast(64)}kbps)",
                                        preferPostVideoPlayback = false
                                    )
                                )
                            }
                        }
                        val hls = obj.getString("hls")
                        if (!hls.isNullOrBlank() && hls.startsWith("http")) {
                            collected.add(
                                StreamCandidate(
                                    streamUrl = hls,
                                    isHls = true,
                                    mimeType = "application/x-mpegURL",
                                    bitrate = 128,
                                    engineLabel = "Piped HLS",
                                    preferPostVideoPlayback = false
                                )
                            )
                        }
                    }
                }
                if (collected.isNotEmpty()) break
            }
        }

        return FallbackExtractResult(
            candidates = collected.distinctBy { it.streamUrl },
            durationSec = detectedDurationSec
        )
    }

    private fun queryProfile(
        profile: ClientProfile,
        videoId: String,
        countryCode: String,
        visitorData: String?
    ): JsonObject? {
        val cpn = generateCpn()
        val clientBuilder = JsonWriter.string()
            .`object`()
            .`object`("context")
            .`object`("client")
            .value("clientName", profile.clientName)
            .value("clientVersion", profile.clientVersion)
            .value("hl", "en")
            .value("gl", countryCode)
            .value("osName", profile.osName)
            .value("osVersion", profile.osVersion)
            .value("deviceMake", profile.deviceMake)
            .value("deviceModel", profile.deviceModel)
            .value("userAgent", profile.userAgent)

        if (profile.androidSdkVersion != null) {
            clientBuilder.value("androidSdkVersion", profile.androidSdkVersion)
        }
        if (profile.platform != null) {
            clientBuilder.value("platform", profile.platform)
        }
        if (!visitorData.isNullOrBlank()) {
            clientBuilder.value("visitorData", visitorData)
        }

        val jsonPayload = clientBuilder
            .end() // client
            .end() // context
            .value("videoId", videoId)
            .value("cpn", cpn)
            .value("contentCheckOk", true)
            .value("racyCheckOk", true)
            .end()
            .done()

        val reqBuilder = Request.Builder()
            .url(profile.endpointUrl)
            .header("User-Agent", profile.userAgent)
            .header("X-YouTube-Client-Name", profile.clientIdHeader)
            .header("X-YouTube-Client-Version", profile.clientVersion)
            .header("X-Goog-Api-Format-Version", "2")
            .post(jsonPayload.toRequestBody(JSON_MEDIA_TYPE))

        if (!visitorData.isNullOrBlank()) {
            reqBuilder.header("X-Goog-Visitor-Id", visitorData)
        }
        if (profile.origin != null) {
            reqBuilder.header("Origin", profile.origin)
            reqBuilder.header("Referer", "${profile.origin}/")
        }

        NewPipeOkHttpDownloader.getInstance().okHttpClient.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val body = resp.body?.string() ?: return null
            return JsonParser.`object`().from(body)
        }
    }

    private fun fetchVisitorData(countryCode: String): String? {
        val now = System.currentTimeMillis()
        cachedVisitorData?.takeIf { now < visitorDataExpiresAtMs }?.let { return it }

        // Fast method 1: sw.js_data
        val fromSw = runCatching {
            val req = Request.Builder()
                .url("https://www.youtube.com/sw.js_data")
                .header("User-Agent", NewPipeOkHttpDownloader.USER_AGENT)
                .get()
                .build()
            NewPipeOkHttpDownloader.getInstance().okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val raw = resp.body?.string()?.removePrefix(")]}'\n") ?: return@use null
                Regex("\"(Cgt[A-Za-z0-9_\\-%=]+)\"").find(raw)?.groupValues?.getOrNull(1)
            }
        }.getOrNull()

        if (!fromSw.isNullOrBlank()) {
            cachedVisitorData = fromSw
            visitorDataExpiresAtMs = now + 20 * 60 * 1000L
            return fromSw
        }

        // Method 2: youtubei/v1/guide responseContext.visitorData
        val fromGuide = runCatching {
            val payload = JsonWriter.string()
                .`object`()
                .`object`("context")
                .`object`("client")
                .value("clientName", "WEB")
                .value("clientVersion", "2.20260708.01.00")
                .value("hl", "en")
                .value("gl", countryCode)
                .end()
                .end()
                .end()
                .done()
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/guide?prettyPrint=false")
                .header("User-Agent", NewPipeOkHttpDownloader.USER_AGENT)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            NewPipeOkHttpDownloader.getInstance().okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val obj = JsonParser.`object`().from(body)
                obj.getObject("responseContext")?.getString("visitorData")
            }
        }.getOrNull()

        if (!fromGuide.isNullOrBlank()) {
            cachedVisitorData = fromGuide
            visitorDataExpiresAtMs = now + 20 * 60 * 1000L
        }
        return fromGuide
    }

    private fun generateCpn(): String {
        val sb = StringBuilder(16)
        repeat(16) {
            sb.append(NONCE_CHARS[random.nextInt(NONCE_CHARS.length)])
        }
        return sb.toString()
    }
}
