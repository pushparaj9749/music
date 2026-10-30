package com.pulsewave.music.data

import android.content.Context
import com.pulsewave.music.model.AppSettings
import com.pulsewave.music.model.LocalAppState
import com.pulsewave.music.model.SongTrack
import com.pulsewave.music.model.UserPlaylist
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Lightweight file-backed state store (Zero SQL/Room database, Zero login).
 * Keeps Liked Songs, Custom Playlists, Offline Downloads, History & Audio Settings locally on device.
 */
class LocalStoreRepository(context: Context) {

    private val appContext = context.applicationContext
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val writeMutex = Mutex()
    private val stateFile = File(appContext.filesDir, "pulsewave_local_store.json")

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        encodeDefaults = true
    }

    private val _state = MutableStateFlow(loadInitial())
    val state: StateFlow<LocalAppState> = _state.asStateFlow()

    private fun loadInitial(): LocalAppState {
        return try {
            if (stateFile.exists()) {
                val raw = stateFile.readText()
                val loaded = json.decodeFromString<LocalAppState>(raw)
                // Filter out any downloaded song entries whose local file was manually deleted
                val validDownloads = loaded.downloadedSongs.filter { track ->
                    val path = track.localFilePath
                    path != null && File(path).exists()
                }
                loaded.copy(downloadedSongs = validDownloads)
            } else {
                LocalAppState()
            }
        } catch (_: Throwable) {
            LocalAppState()
        }
    }

    private fun updateState(transform: (LocalAppState) -> LocalAppState) {
        val updated = transform(_state.value)
        _state.value = updated
        ioScope.launch {
            writeMutex.withLock {
                runCatching {
                    val tmp = File(appContext.filesDir, "pulsewave_local_store.tmp")
                    tmp.writeText(json.encodeToString(updated))
                    tmp.renameTo(stateFile)
                }
            }
        }
    }

    fun isLiked(trackId: String): Boolean =
        _state.value.likedSongs.any { it.id == trackId }

    fun toggleLike(track: SongTrack) {
        updateState { current ->
            val exists = current.likedSongs.any { it.id == track.id }
            val next = if (exists) {
                current.likedSongs.filterNot { it.id == track.id }
            } else {
                listOf(track.copy(addedAt = System.currentTimeMillis())) + current.likedSongs
            }
            current.copy(likedSongs = next)
        }
    }

    fun recordRecentlyPlayed(track: SongTrack) {
        updateState { current ->
            val filtered = current.recentlyPlayed.filterNot { it.id == track.id }
            val next = (listOf(track.copy(addedAt = System.currentTimeMillis())) + filtered).take(60)
            current.copy(recentlyPlayed = next)
        }
    }

    fun clearHistory() {
        updateState { it.copy(recentlyPlayed = emptyList()) }
    }

    fun addSearchQuery(query: String) {
        val clean = query.trim()
        if (clean.isBlank()) return
        updateState { current ->
            val next = (listOf(clean) + current.searchHistory.filterNot {
                it.equals(clean, ignoreCase = true)
            }).take(18)
            current.copy(searchHistory = next)
        }
    }

    fun removeSearchQuery(query: String) {
        updateState { current ->
            current.copy(searchHistory = current.searchHistory.filterNot { it == query })
        }
    }

    fun createPlaylist(name: String, description: String = "", initialTracks: List<SongTrack> = emptyList()): UserPlaylist {
        val playlist = UserPlaylist(
            id = UUID.randomUUID().toString(),
            name = name.trim().ifBlank { "My Playlist" },
            description = description.trim(),
            coverUrl = initialTracks.firstOrNull()?.thumbnailUrl.orEmpty(),
            tracks = initialTracks
        )
        updateState { current ->
            current.copy(playlists = listOf(playlist) + current.playlists)
        }
        return playlist
    }

    fun deletePlaylist(playlistId: String) {
        updateState { current ->
            current.copy(playlists = current.playlists.filterNot { it.id == playlistId })
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: SongTrack) {
        updateState { current ->
            val next = current.playlists.map { pl ->
                if (pl.id == playlistId) {
                    if (pl.tracks.any { it.id == track.id }) pl
                    else {
                        val updatedTracks = pl.tracks + track
                        pl.copy(
                            tracks = updatedTracks,
                            coverUrl = pl.coverUrl.ifBlank { track.thumbnailUrl }
                        )
                    }
                } else pl
            }
            current.copy(playlists = next)
        }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        updateState { current ->
            val next = current.playlists.map { pl ->
                if (pl.id == playlistId) {
                    pl.copy(tracks = pl.tracks.filterNot { it.id == trackId })
                } else pl
            }
            current.copy(playlists = next)
        }
    }

    fun saveDownloadedSong(track: SongTrack) {
        updateState { current ->
            val filtered = current.downloadedSongs.filterNot { it.id == track.id }
            current.copy(downloadedSongs = listOf(track) + filtered)
        }
    }

    fun removeDownloadedSong(trackId: String) {
        val existing = _state.value.downloadedSongs.firstOrNull { it.id == trackId }
        existing?.localFilePath?.let { path ->
            runCatching { File(path).delete() }
        }
        updateState { current ->
            current.copy(downloadedSongs = current.downloadedSongs.filterNot { it.id == trackId })
        }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        updateState { current ->
            current.copy(settings = transform(current.settings))
        }
    }

    fun exportBackupJson(): String = json.encodeToString(_state.value)

    fun importBackupJson(rawJson: String): Boolean {
        return try {
            val imported = json.decodeFromString<LocalAppState>(rawJson)
            updateState { imported }
            true
        } catch (_: Throwable) {
            false
        }
    }
}
