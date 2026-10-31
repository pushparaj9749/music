package com.pulsewave.music.youtube

import com.pulsewave.music.model.PlaylistPreview
import com.pulsewave.music.model.ResolvedPlaybackStream
import com.pulsewave.music.model.SearchFilter
import com.pulsewave.music.model.SongTrack
import com.pulsewave.music.model.StreamCandidate
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.ConcurrentHashMap

data class HomeFeedResult(
    val trendingHits: List<SongTrack>,
    val quickPicks: List<SongTrack>,
    val chillVibes: List<SongTrack>
)

class YoutubeMusicRepository {

    private data class CachedStream(
        val resolved: ResolvedPlaybackStream,
        val expiresAtMs: Long
    )

    private val streamCache = ConcurrentHashMap<String, CachedStream>()

    @Volatile
    private var activeCountryCode: String = "IN"

    init {
        ensureInitialized("IN")
    }

    fun ensureInitialized(countryCode: String = "IN") {
        activeCountryCode = countryCode
        runCatching {
            NewPipe.init(
                NewPipeOkHttpDownloader.getInstance(),
                Localization("en", countryCode),
                ContentCountry(countryCode)
            )
            YoutubeProgressiveDashManifestCreator.getCache().setMaximumSize(300)
        }
    }

    suspend fun fetchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        runCatching {
            ServiceList.YouTube.suggestionExtractor
                .suggestionList(query.trim())
                .take(8)
        }.getOrDefault(emptyList())
    }

    suspend fun searchTracks(
        query: String,
        filter: SearchFilter = SearchFilter.MUSIC_SONGS
    ): List<SongTrack> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        // Direct YouTube video link pasted into search
        extractVideoId(cleanQuery)?.let { directId ->
            val single = fetchSingleTrackMetadata(directId)
            if (single != null) return@withContext listOf(single)
        }

        val primary = runCatching {
            val extractor = ServiceList.YouTube.getSearchExtractor(
                cleanQuery,
                listOf(filter.npFilter),
                null
            )
            extractor.fetchPage()
            extractor.initialPage.items
                .mapNotNull { it.toSongTrackOrNull() }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())

        if (primary.size >= 4 || filter != SearchFilter.MUSIC_SONGS) {
            return@withContext primary
        }

        // Merge with standard VIDEOS search if MUSIC_SONGS returned fewer than 4 items
        val fallback = runCatching {
            val fallbackExtractor = ServiceList.YouTube.getSearchExtractor(
                cleanQuery,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                null
            )
            fallbackExtractor.fetchPage()
            fallbackExtractor.initialPage.items
                .mapNotNull { it.toSongTrackOrNull() }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())

        (primary + fallback).distinctBy { it.id }
    }

    suspend fun searchPlaylists(query: String): List<PlaylistPreview> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        runCatching {
            val extractor = ServiceList.YouTube.getSearchExtractor(
                cleanQuery,
                listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS),
                null
            )
            extractor.fetchPage()
            extractor.initialPage.items
                .filterIsInstance<PlaylistInfoItem>()
                .map { item ->
                    val id = item.url.substringAfter("list=", item.url).substringBefore("&")
                    PlaylistPreview(
                        id = id,
                        title = item.name ?: "YouTube Playlist",
                        uploader = item.uploaderName ?: "YouTube Music",
                        thumbnailUrl = pickBestThumbnail(item.thumbnails, null),
                        streamCount = item.streamCount.coerceAtLeast(0L),
                        url = item.url
                    )
                }
        }.getOrDefault(emptyList())
    }

    suspend fun fetchPlaylistTracks(playlistUrlOrId: String): Pair<String, List<SongTrack>> =
        withContext(Dispatchers.IO) {
            val url = when {
                playlistUrlOrId.startsWith("http") -> playlistUrlOrId
                else -> "https://www.youtube.com/playlist?list=${playlistUrlOrId.trim()}"
            }
            val info = PlaylistInfo.getInfo(ServiceList.YouTube, url)
            val tracks = info.relatedItems
                .mapNotNull { it.toSongTrackOrNull() }
                .distinctBy { it.id }
            (info.name ?: "Imported YouTube Playlist") to tracks
        }

    suspend fun fetchHomeFeed(region: String = "IN"): HomeFeedResult = coroutineScope {
        ensureInitialized(region)
        val trendingDeferred = async(Dispatchers.IO) {
            if (region.equals("IN", ignoreCase = true)) {
                searchTracks("Trending Hindi Punjabi Songs 2026", SearchFilter.MUSIC_SONGS).take(16)
            } else {
                searchTracks("Global Top Hits Songs 2026", SearchFilter.MUSIC_SONGS).take(16)
            }
        }
        val quickPicksDeferred = async(Dispatchers.IO) {
            if (region.equals("IN", ignoreCase = true)) {
                searchTracks("Arijit Singh Karan Aujla Diljit Hits", SearchFilter.MUSIC_SONGS).take(12)
            } else {
                searchTracks("Popular Pop R&B Hits", SearchFilter.MUSIC_SONGS).take(12)
            }
        }
        val chillDeferred = async(Dispatchers.IO) {
            searchTracks("Bollywood Lofi Chill Mix Audio", SearchFilter.VIDEOS).take(12)
        }

        HomeFeedResult(
            trendingHits = trendingDeferred.await(),
            quickPicks = quickPicksDeferred.await(),
            chillVibes = chillDeferred.await()
        )
    }

    suspend fun fetchSingleTrackMetadata(videoIdOrUrl: String): SongTrack? = withContext(Dispatchers.IO) {
        val vid = extractVideoId(videoIdOrUrl) ?: return@withContext null
        val url = "https://www.youtube.com/watch?v=$vid"
        runCatching {
            val info = StreamInfo.getInfo(ServiceList.YouTube, url)
            SongTrack(
                id = vid,
                title = info.name ?: "YouTube Audio",
                artist = info.uploaderName?.removeSuffix(" - Topic")?.trim() ?: "YouTube Artist",
                thumbnailUrl = pickBestThumbnail(info.thumbnails, vid),
                durationSec = info.duration.coerceAtLeast(0L),
                youtubeUrl = url
            )
        }.getOrNull()
    }

    fun invalidateStreamCache(trackId: String) {
        streamCache.remove(trackId)
    }

    suspend fun resolvePlaybackStream(
        track: SongTrack,
        preferHighQuality: Boolean = true,
        allowYtDlp: Boolean = true
    ): Result<ResolvedPlaybackStream> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        streamCache[track.id]?.let { cached ->
            if (now < cached.expiresAtMs) {
                return@withContext Result.success(cached.resolved)
            } else {
                streamCache.remove(track.id)
            }
        }

        val ytUrl = track.youtubeUrl.ifBlank { "https://www.youtube.com/watch?v=${track.id}" }
        val allCandidates = mutableListOf<StreamCandidate>()
        var resolvedDurationSec = track.durationSec
        var relatedTracks: List<SongTrack> = emptyList()
        val errors = mutableListOf<String>()

        // Engine 1: NewPipeExtractor (VisionOS + Mozilla Rhino JS Cipher + DASH/Progressive/HLS)
        runCatching {
            val info = StreamInfo.getInfo(ServiceList.YouTube, ytUrl)
            if (info.duration > 0L) {
                resolvedDurationSec = info.duration
            }
            relatedTracks = info.relatedItems
                .mapNotNull { it.toSongTrackOrNull() }
                .filterNot { it.id == track.id }
                .distinctBy { it.id }
                .take(18)

            val npCandidates = buildNewPipeCandidates(info, preferHighQuality)
            allCandidates.addAll(npCandidates)
        }.onFailure { e ->
            errors.add("NewPipe: ${e.message}")
        }

        // Check if Engine 1 already produced a live-verified HTTP 200/206 stream
        var verifiedPrimary: StreamCandidate? = allCandidates
            .take(3)
            .firstOrNull { YoutubeHttpEngine.probeCandidate(it) }

        // Engine 2: Direct Multi-Client InnerTube (VisionOS 1.02 Music, VisionOS 0.1 Music, Android VR, iOS HLS, Piped)
        if (verifiedPrimary == null) {
            runCatching {
                val fb = InnerTubeFallbackClient.extractCandidates(
                    videoId = track.id,
                    countryCode = activeCountryCode,
                    preferHighQuality = preferHighQuality
                )
                if (fb.durationSec > 0L && resolvedDurationSec <= 0L) {
                    resolvedDurationSec = fb.durationSec
                }
                val newCandidates = fb.candidates.filterNot { c ->
                    allCandidates.any { existing -> existing.streamUrl == c.streamUrl }
                }
                allCandidates.addAll(newCandidates)
                verifiedPrimary = newCandidates
                    .take(4)
                    .firstOrNull { YoutubeHttpEngine.probeCandidate(it) }
            }.onFailure { e ->
                errors.add("InnerTubeFallback: ${e.message}")
            }
        }

        // Engine 3: Embedded yt-dlp Python runtime fallback (Android runtime)
        if (verifiedPrimary == null && allowYtDlp) {
            runCatching {
                val ytdlCandidate = extractYtDlpCandidate(ytUrl, preferHighQuality)
                if (ytdlCandidate != null) {
                    allCandidates.add(ytdlCandidate)
                    if (YoutubeHttpEngine.probeCandidate(ytdlCandidate)) {
                        verifiedPrimary = ytdlCandidate
                    }
                }
            }.onFailure { e ->
                errors.add("yt-dlp: ${e.message}")
            }
        }

        val chosen = verifiedPrimary ?: allCandidates.firstOrNull()
        if (chosen != null) {
            val orderedCandidates = listOf(chosen) + allCandidates.filterNot {
                it.streamUrl == chosen.streamUrl && it.dashManifestXml == chosen.dashManifestXml
            }
            val result = ResolvedPlaybackStream(
                streamUrl = chosen.streamUrl,
                isHls = chosen.isHls,
                dashManifestXml = chosen.dashManifestXml,
                mimeType = chosen.mimeType,
                bitrate = chosen.bitrate,
                contentLength = chosen.contentLength,
                durationSec = resolvedDurationSec,
                relatedTracks = relatedTracks,
                resolvedViaEngine = chosen.engineLabel,
                headers = chosen.headers,
                preferPostVideoPlayback = chosen.preferPostVideoPlayback,
                candidates = orderedCandidates
            )
            // Cache resolved URLs for 20 minutes (YouTube URLs remain valid for ~6 hours)
            streamCache[track.id] = CachedStream(
                resolved = result,
                expiresAtMs = now + 20 * 60 * 1000L
            )
            return@withContext Result.success(result)
        }

        Result.failure(
            IllegalStateException(
                "Could not extract playable audio for ${track.id}: ${errors.joinToString(" | ")}"
            )
        )
    }

    private fun buildNewPipeCandidates(
        info: StreamInfo,
        preferHighQuality: Boolean
    ): List<StreamCandidate> {
        val list = mutableListOf<StreamCandidate>()
        val validAudio = info.audioStreams.filter { it.content.isNotBlank() && it.isUrl }

        val sortedAudio: List<AudioStream> = if (preferHighQuality) {
            validAudio.sortedWith(
                compareByDescending<AudioStream> { it.averageBitrate }
                    .thenByDescending { it.format?.mimeType?.contains("webm") == true }
            )
        } else {
            validAudio.sortedBy { if (it.averageBitrate <= 0) 999 else it.averageBitrate }
        }

        // Top 2 distinct audio formats (e.g. Opus 251 + M4A 140)
        for (audio in sortedAudio.take(2)) {
            val br = audio.averageBitrate.coerceAtLeast(128)
            val mime = audio.format?.mimeType
            val clen = YoutubeHttpEngine.parseClenFromUrl(audio.content)
            val itag = audio.itagItem
            val dashXml = if (
                audio.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                itag != null &&
                info.duration > 0L
            ) {
                runCatching {
                    YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                        audio.content,
                        itag,
                        info.duration
                    )
                }.getOrNull()
            } else null

            if (!dashXml.isNullOrBlank()) {
                list.add(
                    StreamCandidate(
                        streamUrl = audio.content,
                        isHls = false,
                        dashManifestXml = dashXml,
                        mimeType = mime,
                        bitrate = br,
                        contentLength = clen,
                        engineLabel = "YT Hi-Fi (${br}kbps)",
                        preferPostVideoPlayback = true
                    )
                )
            }

            // Also add chunked Progressive fallback for the exact same audio URL
            list.add(
                StreamCandidate(
                    streamUrl = audio.content,
                    isHls = false,
                    dashManifestXml = null,
                    mimeType = mime,
                    bitrate = br,
                    contentLength = clen,
                    engineLabel = "YT Direct (${br}kbps)",
                    preferPostVideoPlayback = true
                )
            )
        }

        // Muxed progressive stream (itag 18 AAC) backup
        val muxed = info.videoStreams.firstOrNull { it.content.isNotBlank() && it.isUrl }
        if (muxed != null) {
            list.add(
                StreamCandidate(
                    streamUrl = muxed.content,
                    isHls = false,
                    dashManifestXml = null,
                    mimeType = muxed.format?.mimeType ?: "video/mp4",
                    bitrate = 128,
                    contentLength = YoutubeHttpEngine.parseClenFromUrl(muxed.content),
                    engineLabel = "YT Progressive AAC",
                    preferPostVideoPlayback = true
                )
            )
        }

        // HLS manifest backup
        val hls = info.hlsUrl
        if (!hls.isNullOrBlank()) {
            list.add(
                StreamCandidate(
                    streamUrl = hls,
                    isHls = true,
                    mimeType = "application/x-mpegURL",
                    bitrate = 128,
                    engineLabel = "YT Adaptive HLS",
                    preferPostVideoPlayback = false
                )
            )
        }

        return list
    }

    private fun extractYtDlpCandidate(
        ytUrl: String,
        preferHighQuality: Boolean
    ): StreamCandidate? {
        val formatSelector = if (preferHighQuality) {
            "bestaudio[ext=webm]/bestaudio[ext=m4a]/bestaudio/best"
        } else {
            "worstaudio[ext=m4a]/worstaudio/bestaudio"
        }
        val req = YoutubeDLRequest(ytUrl).apply {
            addOption("--no-playlist")
            addOption("-f", formatSelector)
            addOption("--get-url")
        }
        val resp = YoutubeDL.getInstance().execute(req)
        val directUrl = resp.out
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("http") }
            ?: return null

        val isM3u8 = directUrl.contains(".m3u8") || directUrl.contains("manifest.googlevideo.com")
        return StreamCandidate(
            streamUrl = directUrl,
            isHls = isM3u8,
            mimeType = if (isM3u8) "application/x-mpegURL" else "audio/mp4",
            bitrate = 160,
            contentLength = YoutubeHttpEngine.parseClenFromUrl(directUrl),
            engineLabel = "Embedded yt-dlp Engine",
            preferPostVideoPlayback = false
        )
    }

    private fun InfoItem.toSongTrackOrNull(): SongTrack? {
        if (this !is StreamInfoItem) return null
        val rawUrl = url ?: return null
        val videoId = extractVideoId(rawUrl) ?: return null
        val cleanTitle = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cleanArtist = uploaderName
            ?.removeSuffix(" - Topic")
            ?.trim()
            ?.ifBlank { "YouTube Music" }
            ?: "YouTube Music"

        val dur = if (duration > 0L) duration else 0L
        val views = if (viewCount > 0L) formatCompactViews(viewCount) else null

        return SongTrack(
            id = videoId,
            title = cleanTitle,
            artist = cleanArtist,
            thumbnailUrl = pickBestThumbnail(thumbnails, videoId),
            durationSec = dur,
            youtubeUrl = "https://www.youtube.com/watch?v=$videoId",
            viewCountText = views
        )
    }

    companion object {
        fun extractVideoId(urlOrId: String): String? {
            val trimmed = urlOrId.trim()
            if (trimmed.matches(Regex("^[a-zA-Z0-9_-]{11}$"))) return trimmed

            val patterns = listOf(
                Regex("[?&]v=([a-zA-Z0-9_-]{11})"),
                Regex("youtu\\.be/([a-zA-Z0-9_-]{11})"),
                Regex("youtube\\.com/shorts/([a-zA-Z0-9_-]{11})"),
                Regex("youtube\\.com/embed/([a-zA-Z0-9_-]{11})"),
                Regex("youtube\\.com/live/([a-zA-Z0-9_-]{11})")
            )
            for (regex in patterns) {
                regex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
            }
            return null
        }

        fun extractPlaylistId(url: String): String? {
            val m = Regex("[?&]list=([a-zA-Z0-9_-]+)").find(url.trim())
            return m?.groupValues?.getOrNull(1)
        }

        fun pickBestThumbnail(images: List<Image>, fallbackVideoId: String?): String {
            val best = images
                .filter { it.url.isNotBlank() }
                .maxByOrNull { it.width * it.height }
                ?.url
            if (!best.isNullOrBlank()) {
                return best.replace("http://", "https://")
            }
            return if (!fallbackVideoId.isNullOrBlank()) {
                "https://i.ytimg.com/vi/$fallbackVideoId/hqdefault.jpg"
            } else {
                ""
            }
        }

        private fun formatCompactViews(count: Long): String {
            return when {
                count >= 1_000_000_000L -> String.format("%.1fB plays", count / 1_000_000_000.0)
                count >= 1_000_000L -> String.format("%.1fM plays", count / 1_000_000.0)
                count >= 1_000L -> String.format("%.1fK plays", count / 1_000.0)
                else -> "$count plays"
            }
        }
    }
}
