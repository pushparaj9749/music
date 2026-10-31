package com.pulsewave.music.youtube

import com.pulsewave.music.model.StreamCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.SecureRandom

object InnerTubeFallbackClient {

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    private const val NONCE_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
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

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull

    private fun JsonObject.obj(key: String): JsonObject? =
        runCatching { this[key]?.jsonObject }.getOrNull()

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
                val status = respObj.obj("playabilityStatus")?.str("status")
                if (!status.equals("OK", ignoreCase = true)) return@runCatching

                val videoDetails = respObj.obj("videoDetails")
                if (videoDetails != null) {
                    val returnedId = videoDetails.str("videoId")
                    if (!returnedId.isNullOrBlank() && returnedId != videoId) return@runCatching
                    val lenSec = videoDetails.str("lengthSeconds")?.toLongOrNull() ?: 0L
                    if (lenSec > 0L && detectedDurationSec == 0L) {
                        detectedDurationSec = lenSec
                    }
                }

                val streamingData = respObj.obj("streamingData") ?: return@runCatching
                val headers = buildMap {
                    put("User-Agent", profile.userAgent)
                    profile.origin?.let {
                        put("Origin", it)
                        put("Referer", "$it/")
                    }
                }

                val profileAudioCandidates = mutableListOf<StreamCandidate>()

                val adaptive = runCatching { streamingData["adaptiveFormats"]?.jsonArray }.getOrNull()
                if (adaptive != null) {
                    for (element in adaptive) {
                        val fmt = runCatching { element.jsonObject }.getOrNull() ?: continue
                        val url = fmt.str("url")?.takeIf { u -> u.startsWith("http") } ?: continue
                        val mimeType = fmt.str("mimeType") ?: ""
                        if (!mimeType.startsWith("audio/")) continue
                        val avgBr = fmt["averageBitrate"]?.jsonPrimitive?.intOrNull
                            ?: fmt["bitrate"]?.jsonPrimitive?.intOrNull
                            ?: 128_000
                        val bitrate = (avgBr / 1000).coerceAtLeast(48)
                        val clen = fmt.str("contentLength")?.toLongOrNull() ?: -1L
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

                // Muxed progressive backup (itag 18 AAC)
                val muxed = runCatching { streamingData["formats"]?.jsonArray }.getOrNull()
                if (muxed != null) {
                    for (element in muxed) {
                        val fmt = runCatching { element.jsonObject }.getOrNull() ?: continue
                        val url = fmt.str("url")?.takeIf { u -> u.startsWith("http") } ?: continue
                        val mimeType = fmt.str("mimeType") ?: "video/mp4"
                        val clen = fmt.str("contentLength")?.toLongOrNull() ?: -1L
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

                // HLS manifest backup
                val hlsUrl = streamingData.str("hlsManifestUrl")
                if (!hlsUrl.isNullOrBlank() && hlsUrl.startsWith("http")) {
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

            if (collected.size >= 3) break
        }

        // Emergency backup via Piped public mirrors
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
                        val obj = jsonParser.parseToJsonElement(raw).jsonObject
                        if (detectedDurationSec == 0L) {
                            detectedDurationSec = obj["duration"]?.jsonPrimitive?.longOrNull ?: 0L
                        }
                        val audioArr = runCatching { obj["audioStreams"]?.jsonArray }.getOrNull()
                        if (audioArr != null) {
                            for (el in audioArr) {
                                val a = runCatching { el.jsonObject }.getOrNull() ?: continue
                                val u = a.str("url")?.takeIf { s -> s.startsWith("http") } ?: continue
                                val br = ((a["bitrate"]?.jsonPrimitive?.intOrNull ?: 128_000) / 1000).coerceAtLeast(64)
                                val mt = a.str("mimeType") ?: "audio/mp4"
                                collected.add(
                                    StreamCandidate(
                                        streamUrl = u,
                                        isHls = false,
                                        mimeType = mt,
                                        bitrate = br,
                                        engineLabel = "Piped Cloud (${br}kbps)",
                                        preferPostVideoPlayback = false
                                    )
                                )
                            }
                        }
                        val hls = obj.str("hls")
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
        val payloadObj = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", profile.clientName)
                    put("clientVersion", profile.clientVersion)
                    put("hl", "en")
                    put("gl", countryCode)
                    put("osName", profile.osName)
                    put("osVersion", profile.osVersion)
                    put("deviceMake", profile.deviceMake)
                    put("deviceModel", profile.deviceModel)
                    put("userAgent", profile.userAgent)
                    if (profile.androidSdkVersion != null) {
                        put("androidSdkVersion", profile.androidSdkVersion)
                    }
                    if (profile.platform != null) {
                        put("platform", profile.platform)
                    }
                    if (!visitorData.isNullOrBlank()) {
                        put("visitorData", visitorData)
                    }
                }
            }
            put("videoId", videoId)
            put("cpn", cpn)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }

        val reqBuilder = Request.Builder()
            .url(profile.endpointUrl)
            .header("User-Agent", profile.userAgent)
            .header("X-YouTube-Client-Name", profile.clientIdHeader)
            .header("X-YouTube-Client-Version", profile.clientVersion)
            .header("X-Goog-Api-Format-Version", "2")
            .post(payloadObj.toString().toRequestBody(JSON_MEDIA_TYPE))

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
            return jsonParser.parseToJsonElement(body).jsonObject
        }
    }

    private fun fetchVisitorData(countryCode: String): String? {
        val now = System.currentTimeMillis()
        cachedVisitorData?.takeIf { now < visitorDataExpiresAtMs }?.let { return it }

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

        val fromGuide = runCatching {
            val payload = buildJsonObject {
                putJsonObject("context") {
                    putJsonObject("client") {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20260708.01.00")
                        put("hl", "en")
                        put("gl", countryCode)
                    }
                }
            }.toString()
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/guide?prettyPrint=false")
                .header("User-Agent", NewPipeOkHttpDownloader.USER_AGENT)
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            NewPipeOkHttpDownloader.getInstance().okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string() ?: return@use null
                val obj = jsonParser.parseToJsonElement(body).jsonObject
                obj.obj("responseContext")?.str("visitorData")
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
