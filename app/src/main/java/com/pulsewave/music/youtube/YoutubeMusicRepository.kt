package com.pulsewave.music.youtube

import com.pulsewave.music.model.PlaylistPreview
import com.pulsewave.music.model.ResolvedPlaybackStream
import com.pulsewave.music.model.SearchFilter
import com.pulsewave.music.model.SongTrack
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

    init {
        ensureInitialized("IN")
    }

    fun ensureInitialized(countryCode: String = "IN") {
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

        // If user pasted a direct YouTube video link, resolve it directly
        extractVideoId(cleanQuery)?.let { directId ->
            val single = fetchSingleTrackMetadata(directId)
            if (single != null) return@withContext listOf(single)
        }

        val primary = runCatching {
            val extractor = ServiceList.YouTube.getSearchExtractor(
                cleanQuery,
                listOf(filter.npFilter),
                ""
            )
            extractor.fetchPage()
            extractor.initialPage.items
                .mapNotNull { it.toSongTrackOrNull() }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())

        if (primary.isNotEmpty() || filter != SearchFilter.MUSIC_SONGS) {
            return@withContext primary
        }

        // Fallback to standard videos filter if YT Music filter returned 0
        runCatching {
            val fallbackExtractor = ServiceList.YouTube.getSearchExtractor(
                cleanQuery,
                listOf(YoutubeSearchQueryHandlerFactory.VIDEOS),
                ""
            )
            fallbackExtractor.fetchPage()
            fallbackExtractor.initialPage.items
                .mapNotNull { it.toSongTrackOrNull() }
                .distinctBy { it.id }
        }.getOrDefault(emptyList())
    }

    suspend fun searchPlaylists(query: String): List<PlaylistPreview> = withContext(Dispatchers.IO) {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return@withContext emptyList()

        runCatching {
            val extractor = ServiceList.YouTube.getSearchExtractor(
                cleanQuery,
                listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS),
                ""
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
            (info.name ?: "Imported Playlist") to tracks
        }

    suspend fun fetchHomeFeed(region: String = "IN"): HomeFeedResult = coroutineScope {
        ensureInitialized(region)
        val trendingDeferred = async(Dispatchers.IO) {
            val q = if (region == "IN") {
                "Trending Hindi Punjabi Songs 2026 Official Audio"
            } else {
                "Top Global Trending Hits Songs Official Audio"
            }
            searchTracks(q, SearchFilter.MUSIC_SONGS).ifEmpty {
                searchTracks("Bollywood Top Songs Arijit Singh", SearchFilter.VIDEOS)
            }.take(16)
        }
        val quickPicksDeferred = async(Dispatchers.IO) {
            val q = if (region == "IN") {
                "Bollywood Evergreen & Latest Hit Songs Pritam Arijit"
            } else {
                "Pop & R&B Hit Songs The Weeknd Dua Lipa"
            }
            searchTracks(q, SearchFilter.MUSIC_SONGS).take(14)
        }
        val chillDeferred = async(Dispatchers.IO) {
            searchTracks("Hindi Lofi Chill Acoustic Slowed Songs", SearchFilter.VIDEOS).take(12)
        }
        HomeFeedResult(
            trendingHits = trendingDeferred.await(),
            quickPicks = quickPicksDeferred.await(),
            chillVibes = chillDeferred.await()
        )
    }

    suspend fun fetchSingleTrackMetadata(videoIdOrUrl: String): SongTrack? =
        withContext(Dispatchers.IO) {
            runCatching {
                val id = extractVideoId(videoIdOrUrl) ?: videoIdOrUrl
                val url = "https://www.youtube.com/watch?v=$id"
                val info = StreamInfo.getInfo(ServiceList.YouTube, url)
                SongTrack(
                    id = id,
                    title = cleanSongTitle(info.name ?: "YouTube Track"),
                    artist = cleanArtistName(info.uploaderName ?: "YouTube Artist"),
                    thumbnailUrl = pickBestThumbnail(info.thumbnails, id),
                    durationSec = info.duration.coerceAtLeast(0L),
                    youtubeUrl = url
                )
            }.getOrNull()
        }

    suspend fun resolvePlaybackStream(
        track: SongTrack,
        preferHighQuality: Boolean = true
    ): Result<ResolvedPlaybackStream> = withContext(Dispatchers.IO) {
        val cacheKey = "${track.id}_$preferHighQuality"
        val now = System.currentTimeMillis()
        streamCache[cacheKey]?.let { cached ->
            if (cached.expiresAtMs > now) {
                return@withContext Result.success(cached.resolved)
            } else {
                streamCache.remove(cacheKey)
            }
        }

        val ytUrl = track.youtubeUrl.ifBlank { "https://www.youtube.com/watch?v=${track.id}" }

        // Engine 1: NewPipeExtractor (VisionOS + Web metadata + Progressive DASH anti-throttle)
        val primaryAttempt = runCatching {
            val streamInfo = StreamInfo.getInfo(ServiceList.YouTube, ytUrl)
            val related = streamInfo.relatedItems
                .mapNotNull { it.toSongTrackOrNull() }
                .filterNot { it.id == track.id }
                .distinctBy { it.id }
                .take(18)

            val audioStreams = streamInfo.audioStreams
                .filter { it.content.isNotBlank() }

            val bestAudio = selectPreferredAudioStream(audioStreams, preferHighQuality)
            if (bestAudio != null) {
                val dashXml = if (
                    bestAudio.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP &&
                    bestAudio.itagItem != null &&
                    streamInfo.duration > 0
                ) {
                    runCatching {
                        YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(
                            bestAudio.content,
                            bestAudio.itagItem!!,
                            streamInfo.duration
                        )
                    }.getOrNull()
                } else null

                val isHlsStream = bestAudio.deliveryMethod == DeliveryMethod.HLS
                return@runCatching ResolvedPlaybackStream(
                    streamUrl = bestAudio.content,
                    isHls = isHlsStream,
                    dashManifestXml = dashXml,
                    mimeType = bestAudio.format?.mimeType,
                    bitrate = bestAudio.averageBitrate.coerceAtLeast(0),
                    durationSec = streamInfo.duration.coerceAtLeast(track.durationSec),
                    relatedTracks = related,
                    resolvedViaEngine = "NewPipe Audio (${bestAudio.averageBitrate.coerceAtLeast(128)} kbps)"
                )
            }

            // Secondary inside Engine 1: Apple VisionOS HLS manifest
            val hlsUrl = runCatching { streamInfo.hlsUrl }.getOrNull().orEmpty()
            if (hlsUrl.isNotBlank()) {
                return@runCatching ResolvedPlaybackStream(
                    streamUrl = hlsUrl,
                    isHls = true,
                    durationSec = streamInfo.duration.coerceAtLeast(track.durationSec),
                    relatedTracks = related,
                    resolvedViaEngine = "NewPipe VisionOS HLS"
                )
            }

            error("No playable audio stream returned by primary extractor")
        }

        if (primaryAttempt.isSuccess) {
            val resolved = primaryAttempt.getOrThrow()
            streamCache[cacheKey] = CachedStream(resolved, now + 15 * 60 * 1000L)
            return@withContext Result.success(resolved)
        }

        // Engine 2 Fallback: Embedded yt-dlp engine (youtubedl-android)
        val fallbackAttempt = runCatching {
            val formatSelector = if (preferHighQuality) {
                "bestaudio[ext=m4a]/bestaudio/best"
            } else {
                "bestaudio[abr<=96]/bestaudio[ext=m4a]/bestaudio"
            }
            val request = YoutubeDLRequest(ytUrl).apply {
                addOption("-f", formatSelector)
                addOption("--no-playlist")
                addOption("-g")
            }
            val response = YoutubeDL.getInstance().execute(request, null, null)
            val directUrl = response.out
                .lineSequence()
                .map { it.trim() }
                .firstOrNull { it.startsWith("http") }
                ?: error("yt-dlp did not return a stream URL")

            ResolvedPlaybackStream(
                streamUrl = directUrl,
                isHls = directUrl.contains(".m3u8"),
                durationSec = track.durationSec,
                relatedTracks = emptyList(),
                resolvedViaEngine = "yt-dlp Native Engine"
            )
        }

        fallbackAttempt.onSuccess { resolved ->
            streamCache[cacheKey] = CachedStream(resolved, now + 12 * 60 * 1000L)
        }
        fallbackAttempt
    }

    fun invalidateStreamCache(trackId: String) {
        streamCache.keys.filter { it.startsWith("${trackId}_") }.forEach { streamCache.remove(it) }
    }

    private fun selectPreferredAudioStream(
        streams: List<AudioStream>,
        preferHighQuality: Boolean
    ): AudioStream? {
        if (streams.isEmpty()) return null
        val progressive = streams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val pool = progressive.ifEmpty { streams }
        return if (preferHighQuality) {
            pool.maxWithOrNull(
                compareBy<AudioStream> { it.averageBitrate }
                    .thenBy { if (it.format?.suffix == "webm" || it.format?.suffix == "m4a") 1 else 0 }
            )
        } else {
            pool.filter { it.averageBitrate in 32..96 }
                .maxByOrNull { it.averageBitrate }
                ?: pool.minByOrNull { it.averageBitrate.takeIf { b -> b > 0 } ?: 128 }
        }
    }

    private fun InfoItem.toSongTrackOrNull(): SongTrack? {
        if (this !is StreamInfoItem) return null
        val rawUrl = url ?: return null
        val videoId = extractVideoId(rawUrl) ?: return null
        // Skip live streams without duration if possible unless duration == -1 (YT Music tracks sometimes report >0)
        val dur = duration.coerceAtLeast(0L)
        val viewsText = if (viewCount > 0) formatCompactCount(viewCount) else null
        return SongTrack(
            id = videoId,
            title = cleanSongTitle(name ?: "Unknown Song"),
            artist = cleanArtistName(uploaderName ?: "YouTube Music"),
            thumbnailUrl = pickBestThumbnail(thumbnails, videoId),
            durationSec = dur,
            youtubeUrl = "https://www.youtube.com/watch?v=$videoId",
            viewCountText = viewsText
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
                Regex("youtube\\.com/embed/([a-zA-Z0-9_-]{11})")
            )
            for (regex in patterns) {
                regex.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
            }
            return null
        }

        fun extractPlaylistId(url: String): String? {
            val match = Regex("[?&]list=([a-zA-Z0-9_-]+)").find(url.trim())
            return match?.groupValues?.getOrNull(1)
        }

        private fun pickBestThumbnail(thumbnails: List<Image>, videoId: String?): String {
            val best = thumbnails
                .filter { it.url.isNotBlank() }
                .maxByOrNull { it.width * it.height.coerceAtLeast(1) }
                ?.url
            if (!best.isNullOrBlank()) {
                return best.replace("http://", "https://")
            }
            return if (!videoId.isNullOrBlank()) {
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
            } else {
                "file:///android_asset/covers/bollywood_hits.png"
            }
        }

        private fun cleanSongTitle(raw: String): String {
            return raw
                .replace(Regex("\\s*\\((Official\\s*(Music\\s*)?Video|Official\\s*Audio|Lyric\\s*Video|Full\\s*Video|HD|4K)\\)", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s*\\[(Official\\s*(Music\\s*)?Video|Official\\s*Audio|Full\\s*Song|HD|4K)\\]", RegexOption.IGNORE_CASE), "")
                .trim()
                .ifBlank { raw }
        }

        private fun cleanArtistName(raw: String): String {
            return raw
                .removeSuffix(" - Topic")
                .removeSuffix("VEVO")
                .trim()
                .ifBlank { "YouTube Artist" }
        }

        private fun formatCompactCount(count: Long): String {
            return when {
                count >= 1_000_000_000L -> String.format("%.1fB plays", count / 1_000_000_000.0)
                count >= 1_000_000L -> String.format("%.1fM plays", count / 1_000_000.0)
                count >= 1_000L -> String.format("%.1fK plays", count / 1_000.0)
                else -> "$count plays"
            }
        }
    }
}
