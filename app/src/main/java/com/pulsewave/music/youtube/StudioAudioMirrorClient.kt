package com.pulsewave.music.youtube

import com.pulsewave.music.model.SongTrack
import com.pulsewave.music.model.StreamCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Zero-login 320 kbps Studio AAC M4A audio mirror resolver.
 * Activates automatically whenever YouTube blocks anonymous watch access on CGNAT/datacenter IPs
 * ("Sign in to confirm that you're not a bot") or restricts explicit/label tracks.
 */
object StudioAudioMirrorClient {

    private const val API_URL = "https://www.jiosaavn.com/api.php"
    private const val OPEN_MEDIA_HOST = "aac.saavncdn.com"
    private const val MOBILE_UA =
        "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/139.0.0.0 Mobile Safari/537.36"

    private val desKey = SecretKeySpec("38346591".toByteArray(Charsets.US_ASCII), "DES")
    private val random = SecureRandom()

    private val jsonParser = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val indianPrefixes = listOf(
        "49.36",
        "49.37",
        "152.58",
        "157.48",
        "117.98"
    )

    private val noisePatterns = listOf(
        Regex("\\((?:official|full|lyric|lyrics|video|audio|hd|4k|hq|visualizer|music video|from\\s+[^)]*)[^)]*\\)", RegexOption.IGNORE_CASE),
        Regex("\\[(?:official|full|lyric|lyrics|video|audio|hd|4k|hq|visualizer|music video|from\\s+[^\\]]*)[^\\]]*\\]", RegexOption.IGNORE_CASE),
        Regex("\\|.*$"),
        Regex("(?i)\\b(official music video|official video|official audio|full video|lyrical video|lyrics|4k video|hd video)\\b")
    )

    data class MirrorMatch(
        val candidates: List<StreamCandidate>,
        val durationSec: Long
    )

    fun resolveMirrorCandidates(
        track: SongTrack,
        preferHighQuality: Boolean = true
    ): MirrorMatch {
        val cleanTitle = cleanSongTitle(track.title)
        val cleanArtist = cleanArtistName(track.artist)

        val queries = buildList {
            if (cleanTitle.isNotBlank() && cleanArtist.isNotBlank() &&
                !cleanTitle.contains(cleanArtist, ignoreCase = true)
            ) {
                add("$cleanTitle $cleanArtist")
            }
            if (cleanTitle.isNotBlank()) {
                add(cleanTitle)
            }
            val rawShort = track.title.substringBefore("(").substringBefore("[").substringBefore("|").trim()
            if (rawShort.isNotBlank() && rawShort !in this) {
                add(rawShort)
            }
        }

        val forwardedIp = randomIndianIp()

        for (query in queries.take(2)) {
            val parsedSongs = runCatching { searchCatalog(query, forwardedIp) }.getOrDefault(emptyList())
            if (parsedSongs.isEmpty()) continue

            val ranked = parsedSongs
                .map { item -> item to scoreCandidate(item, cleanTitle, cleanArtist, track.durationSec) }
                .filter { (_, score) -> score >= 25 }
                .sortedByDescending { (_, score) -> score }

            val best = ranked.firstOrNull()?.first ?: continue
            val urls = buildMediaUrls(best, preferHighQuality, forwardedIp)
            if (urls.isNotEmpty()) {
                val headers = mapOf(
                    "User-Agent" to MOBILE_UA,
                    "Accept" to "*/*",
                    "Accept-Encoding" to "identity"
                )
                val candidates = urls.map { (url, kbps) ->
                    StreamCandidate(
                        streamUrl = url,
                        isHls = false,
                        dashManifestXml = null,
                        mimeType = "audio/mp4",
                        bitrate = kbps,
                        contentLength = -1L,
                        engineLabel = "Hi-Fi Studio AAC (${kbps}kbps)",
                        headers = headers,
                        preferPostVideoPlayback = false
                    )
                }
                return MirrorMatch(
                    candidates = candidates,
                    durationSec = best.durationSec
                )
            }
        }

        return MirrorMatch(emptyList(), 0L)
    }

    private data class CatalogSong(
        val id: String,
        val title: String,
        val artists: String,
        val durationSec: Long,
        val offers320: Boolean,
        val mediaToken: String
    )

    private fun searchCatalog(query: String, forwardedIp: String): List<CatalogSong> {
        val url = API_URL.toHttpUrl().newBuilder()
            .addQueryParameter("__call", "search.getResults")
            .addQueryParameter("_format", "json")
            .addQueryParameter("_marker", "0")
            .addQueryParameter("api_version", "4")
            .addQueryParameter("ctx", "web6dot0")
            .addQueryParameter("q", query)
            .addQueryParameter("n", "10")
            .addQueryParameter("p", "1")
            .build()

        val req = Request.Builder()
            .url(url)
            .header("User-Agent", MOBILE_UA)
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "en-IN,en;q=0.9")
            .header("Referer", "https://www.jiosaavn.com/")
            .header("X-Forwarded-For", forwardedIp)
            .header("X-Real-IP", forwardedIp)
            .get()
            .build()

        NewPipeOkHttpDownloader.getInstance().okHttpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val root = runCatching { jsonParser.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return emptyList()
            val results = runCatching { root["results"]?.jsonArray }.getOrNull() ?: return emptyList()

            val out = mutableListOf<CatalogSong>()
            for (el in results) {
                val obj = runCatching { el.jsonObject }.getOrNull() ?: continue
                val type = obj.str("type") ?: ""
                if (type.isNotEmpty() && type != "song") continue

                val moreInfo = obj.obj("more_info")
                val id = obj.str("id").orEmpty()
                val title = decodeHtml(obj.str("title") ?: obj.str("song") ?: "")
                val token = (moreInfo?.str("encrypted_media_url") ?: obj.str("encrypted_media_url")).orEmpty().trim()
                if (id.isBlank() || title.isBlank() || token.isBlank()) continue

                val dur = (moreInfo?.str("duration") ?: obj.str("duration"))?.toLongOrNull() ?: 0L
                val has320 = (moreInfo?.str("320kbps") ?: obj.str("320kbps"))
                    ?.equals("true", ignoreCase = true) == true

                val primaryArtistNames = extractArtistNames(moreInfo, obj)
                out.add(
                    CatalogSong(
                        id = id,
                        title = title,
                        artists = primaryArtistNames,
                        durationSec = dur,
                        offers320 = has320,
                        mediaToken = token
                    )
                )
            }
            return out
        }
    }

    private fun buildMediaUrls(
        song: CatalogSong,
        preferHighQuality: Boolean,
        forwardedIp: String
    ): List<Pair<String, Int>> {
        val out = mutableListOf<Pair<String, Int>>()
        val decrypted = decryptMediaToken(song.mediaToken)
        if (decrypted != null) {
            val parsed = decrypted.toHttpUrlOrNull()
            if (parsed != null) {
                val path = parsed.encodedPath
                val qualityRegex = Regex("_(\\d{2,3})\\.(mp4|m4a)$", RegexOption.IGNORE_CASE)
                val match = qualityRegex.find(path)
                if (match != null) {
                    val ext = match.groupValues[2]
                    val prefixPath = path.substring(0, match.range.first)
                    val bitrates = if (preferHighQuality) {
                        if (song.offers320) listOf(320, 160, 96) else listOf(160, 320, 96)
                    } else {
                        listOf(96, 160)
                    }
                    for (kbps in bitrates) {
                        out.add("https://$OPEN_MEDIA_HOST${prefixPath}_$kbps.$ext" to kbps)
                    }
                } else {
                    out.add(decrypted.replace("http://", "https://") to 160)
                }
            }
        }

        // Also request signed auth_url as backup if direct open CDN needs token
        runCatching {
            val targetBr = if (preferHighQuality && song.offers320) "320" else "160"
            val authApiUrl = API_URL.toHttpUrl().newBuilder()
                .addQueryParameter("__call", "song.generateAuthToken")
                .addQueryParameter("_format", "json")
                .addQueryParameter("_marker", "0")
                .addQueryParameter("api_version", "4")
                .addQueryParameter("ctx", "web6dot0")
                .addQueryParameter("url", song.mediaToken)
                .addQueryParameter("bitrate", targetBr)
                .build()

            val req = Request.Builder()
                .url(authApiUrl)
                .header("User-Agent", MOBILE_UA)
                .header("Referer", "https://www.jiosaavn.com/")
                .header("X-Forwarded-For", forwardedIp)
                .header("X-Real-IP", forwardedIp)
                .get()
                .build()

            NewPipeOkHttpDownloader.getInstance().okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use
                val body = resp.body?.string() ?: return@use
                val obj = jsonParser.parseToJsonElement(body).jsonObject
                val authUrl = obj.str("auth_url")
                if (!authUrl.isNullOrBlank() && authUrl.startsWith("https://")) {
                    out.add(authUrl to targetBr.toInt())
                }
            }
        }

        return out.distinctBy { it.first }
    }

    private fun decryptMediaToken(token: String): String? {
        return runCatching {
            val cipher = Cipher.getInstance("DES/ECB/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, desKey)
            val raw = Base64.getDecoder().decode(token.trim())
            String(cipher.doFinal(raw), Charsets.UTF_8)
                .trim()
                .takeIf { it.startsWith("http://") || it.startsWith("https://") }
        }.getOrNull()
    }

    private fun scoreCandidate(
        song: CatalogSong,
        targetTitle: String,
        targetArtist: String,
        targetDurationSec: Long
    ): Int {
        var score = 0
        val normCandidateTitle = normalizeWords(song.title)
        val normTargetTitle = normalizeWords(targetTitle)

        if (normCandidateTitle == normTargetTitle && normTargetTitle.isNotEmpty()) {
            score += 70
        } else {
            val targetTokens = normTargetTitle.split(" ").filter { it.length >= 2 }.toSet()
            val candTokens = normCandidateTitle.split(" ").filter { it.length >= 2 }.toSet()
            if (targetTokens.isNotEmpty()) {
                val overlap = targetTokens.intersect(candTokens).size
                score += (overlap * 55) / targetTokens.size
            }
        }

        val normArtists = normalizeWords(song.artists)
        val artistTokens = normalizeWords(targetArtist).split(" ").filter { it.length >= 3 }
        if (artistTokens.any { token -> normArtists.contains(token) }) {
            score += 25
        }

        if (targetDurationSec > 30L && song.durationSec > 30L) {
            val diff = kotlin.math.abs(targetDurationSec - song.durationSec)
            when {
                diff <= 8L -> score += 20
                diff <= 25L -> score += 10
                diff > 120L -> score -= 35
            }
        }
        return score
    }

    private fun extractArtistNames(moreInfo: JsonObject?, root: JsonObject): String {
        val artistMap = moreInfo?.obj("artistMap")
        val primaryArr = runCatching { artistMap?.get("primary_artists")?.jsonArray }.getOrNull()
        if (primaryArr != null && primaryArr.isNotEmpty()) {
            val joined = primaryArr.mapNotNull { el ->
                runCatching { el.jsonObject.str("name") }.getOrNull()
            }.joinToString(", ")
            if (joined.isNotBlank()) return decodeHtml(joined)
        }
        return decodeHtml(moreInfo?.str("primary_artists") ?: root.str("primary_artists") ?: "")
    }

    private fun cleanSongTitle(raw: String): String {
        var s = decodeHtml(raw)
        for (rx in noisePatterns) {
            s = rx.replace(s, " ")
        }
        return s.replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanArtistName(raw: String): String {
        return raw
            .removeSuffix("- Topic")
            .replace("Official", "", ignoreCase = true)
            .replace("VEVO", "", ignoreCase = true)
            .trim()
    }

    private fun normalizeWords(input: String): String {
        return input.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun decodeHtml(input: String): String {
        return input
            .replace("&quot;", "\"")
            .replace("&#039;", "'")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
    }

    private fun randomIndianIp(): String {
        val prefix = indianPrefixes[random.nextInt(indianPrefixes.size)]
        val third = 10 + random.nextInt(230)
        val fourth = 11 + random.nextInt(230)
        return "$prefix.$third.$fourth"
    }

    private fun JsonObject.str(key: String): String? =
        runCatching { this[key]?.jsonPrimitive?.contentOrNull }.getOrNull()

    private fun JsonObject.obj(key: String): JsonObject? =
        runCatching { this[key]?.jsonObject }.getOrNull()
}
