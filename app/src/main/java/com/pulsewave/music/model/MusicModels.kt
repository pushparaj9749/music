package com.pulsewave.music.model

import kotlinx.serialization.Serializable

@Serializable
data class SongTrack(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String,
    val durationSec: Long = 0L,
    val youtubeUrl: String = "",
    val localFilePath: String? = null,
    val rawResName: String? = null,
    val albumName: String? = null,
    val viewCountText: String? = null,
    val addedAt: Long = System.currentTimeMillis()
) {
    val isOfflineReady: Boolean
        get() = !localFilePath.isNullOrBlank() || !rawResName.isNullOrBlank()
}

@Serializable
data class UserPlaylist(
    val id: String,
    val name: String,
    val description: String = "",
    val coverUrl: String = "",
    val tracks: List<SongTrack> = emptyList(),
    val createdAt: Long = System.currentTimeMillis()
)

@Serializable
data class PlaylistPreview(
    val id: String,
    val title: String,
    val uploader: String,
    val thumbnailUrl: String,
    val streamCount: Long = 0L,
    val url: String
)

@Serializable
data class LyricLine(
    val timeMs: Long,
    val text: String
)

data class LyricsState(
    val isLoading: Boolean = false,
    val isSynced: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    val plainText: String? = null,
    val error: String? = null
)

enum class SearchFilter(val label: String, val npFilter: String) {
    MUSIC_SONGS("Songs", "music_songs"),
    VIDEOS("Videos", "videos"),
    MUSIC_ALBUMS("Albums", "music_albums"),
    PLAYLISTS("Playlists", "playlists")
}

enum class AccentPreset(
    val label: String,
    val primaryHex: Long,
    val secondaryHex: Long
) {
    NEON_ROSE("Neon Rose", 0xFFF43F5E, 0xFF8B5CF6),
    CYBER_VIOLET("Cyber Violet", 0xFFA855F7, 0xFFEC4899),
    EMERALD_GLOW("Emerald Pulse", 0xFF10B981, 0xFF06B6D4),
    SUNSET_AMBER("Sunset Gold", 0xFFF59E0B, 0xFFEF4444),
    OCEAN_CYAN("Ocean Cyan", 0xFF06B6D4, 0xFF3B82F6),
    CRIMSON_FIRE("Crimson Surge", 0xFFEF4444, 0xFFF97316);

    companion object {
        fun fromName(name: String): AccentPreset =
            entries.firstOrNull { it.name == name } ?: NEON_ROSE
    }
}

@Serializable
data class AppSettings(
    val accentName: String = AccentPreset.NEON_ROSE.name,
    val dynamicCoverTheme: Boolean = true,
    val highQualityAudio: Boolean = true,
    val skipSilence: Boolean = false,
    val autoRadioEnabled: Boolean = true,
    val eqEnabled: Boolean = true,
    val eqPresetName: String = "Bass & Clarity",
    val customBandLevelsMb: List<Int> = emptyList(),
    val bassBoostStrength: Int = 350,
    val virtualizerStrength: Int = 250,
    val loudnessGainMb: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val playbackPitch: Float = 1.0f,
    val contentRegion: String = "IN"
)

@Serializable
data class LocalAppState(
    val likedSongs: List<SongTrack> = emptyList(),
    val recentlyPlayed: List<SongTrack> = emptyList(),
    val downloadedSongs: List<SongTrack> = emptyList(),
    val playlists: List<UserPlaylist> = emptyList(),
    val searchHistory: List<String> = emptyList(),
    val settings: AppSettings = AppSettings()
)

data class MoodCategory(
    val id: String,
    val title: String,
    val subtitle: String,
    val assetCover: String,
    val searchQuery: String,
    val accentColor: Long
)

data class AmbientStem(
    val id: String,
    val title: String,
    val subtitle: String,
    val rawResId: Int,
    val accentColor: Long
)

data class DownloadTaskState(
    val trackId: String,
    val title: String,
    val thumbnailUrl: String,
    val progress: Float = 0f,
    val statusText: String = "Starting...",
    val isCompleted: Boolean = false,
    val isFailed: Boolean = false
)

data class ResolvedPlaybackStream(
    val streamUrl: String,
    val isHls: Boolean = false,
    val dashManifestXml: String? = null,
    val mimeType: String? = null,
    val bitrate: Int = 0,
    val durationSec: Long = 0L,
    val relatedTracks: List<SongTrack> = emptyList(),
    val resolvedViaEngine: String = "NewPipe VisionOS"
)
