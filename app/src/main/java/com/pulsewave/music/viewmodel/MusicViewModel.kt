package com.pulsewave.music.viewmodel

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.pulsewave.music.PulseWaveApp
import com.pulsewave.music.data.CatalogPresets
import com.pulsewave.music.model.AccentPreset
import com.pulsewave.music.model.AppSettings
import com.pulsewave.music.model.LyricsState
import com.pulsewave.music.model.MoodCategory
import com.pulsewave.music.model.PlaylistPreview
import com.pulsewave.music.model.SearchFilter
import com.pulsewave.music.model.SongTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HomeUiState(
    val isLoading: Boolean = true,
    val trendingHits: List<SongTrack> = emptyList(),
    val quickPicks: List<SongTrack> = emptyList(),
    val chillVibes: List<SongTrack> = emptyList(),
    val activeMoodCategory: MoodCategory? = null,
    val activeMoodTracks: List<SongTrack> = emptyList(),
    val isMoodLoading: Boolean = false,
    val error: String? = null
)

data class SearchUiState(
    val query: String = "",
    val selectedFilter: SearchFilter = SearchFilter.MUSIC_SONGS,
    val suggestions: List<String> = emptyList(),
    val trackResults: List<SongTrack> = emptyList(),
    val playlistResults: List<PlaylistPreview> = emptyList(),
    val isSearching: Boolean = false,
    val error: String? = null
)

class MusicViewModel : ViewModel() {

    private val app = PulseWaveApp.instance
    val localStore = app.localStore
    val playerBridge = app.playerBridge
    val downloadManager = app.downloadManager

    val localState = localStore.state
    val playbackState = playerBridge.playbackState
    val dspState = playerBridge.audioEffects.dspState
    val ambientLevels = playerBridge.ambientMixer.levels
    val activeAmbientPreset = playerBridge.ambientMixer.activePresetName
    val downloadTasks = downloadManager.tasks

    private val _homeState = MutableStateFlow(HomeUiState())
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _searchState = MutableStateFlow(SearchUiState())
    val searchState: StateFlow<SearchUiState> = _searchState.asStateFlow()

    private val _lyricsState = MutableStateFlow(LyricsState())
    val lyricsState: StateFlow<LyricsState> = _lyricsState.asStateFlow()

    private val _dynamicTrackColor = MutableStateFlow<Color?>(null)
    val dynamicTrackColor: StateFlow<Color?> = _dynamicTrackColor.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private val _ytDlpStatus = MutableStateFlow("Embedded yt-dlp + FFmpeg Ready")
    val ytDlpStatus: StateFlow<String> = _ytDlpStatus.asStateFlow()

    private var suggestionJob: Job? = null
    private var searchJob: Job? = null

    init {
        refreshHomeFeed()
        observeCurrentTrackSideEffects()
        viewModelScope.launch {
            val v = downloadManager.getYtDlpVersion()
            _ytDlpStatus.value = "yt-dlp ($v) + NewPipe Dual-Engine"
        }
    }

    private fun observeCurrentTrackSideEffects() {
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack }
                .distinctUntilChanged { a, b -> a?.id == b?.id }
                .collect { track ->
                    if (track == null) {
                        _lyricsState.value = LyricsState()
                        _dynamicTrackColor.value = null
                    } else {
                        extractPaletteFromCover(app, track.thumbnailUrl)
                        _lyricsState.value = LyricsState(isLoading = true)
                        _lyricsState.value = app.lyricsRepo.fetchLyrics(track)
                    }
                }
        }
    }

    private fun extractPaletteFromCover(context: Context, url: String) {
        viewModelScope.launch {
            val extracted = runCatching {
                val loader = ImageLoader(context)
                val req = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)
                    .size(160)
                    .build()
                val res = loader.execute(req)
                val bmp = ((res as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                bmp?.let {
                    val palette = Palette.from(it).generate()
                    val rgb = palette.getVibrantColor(
                        palette.getMutedColor(0xFFF43F5E.toInt())
                    )
                    Color(rgb)
                }
            }.getOrNull()
            _dynamicTrackColor.value = extracted
        }
    }

    fun refreshHomeFeed() {
        viewModelScope.launch {
            _homeState.update { it.copy(isLoading = true, error = null) }
            runCatching {
                app.youtubeRepo.fetchHomeFeed(localState.value.settings.contentRegion)
            }.fold(
                onSuccess = { feed ->
                    _homeState.update {
                        it.copy(
                            isLoading = false,
                            trendingHits = feed.trendingHits,
                            quickPicks = feed.quickPicks,
                            chillVibes = feed.chillVibes,
                            error = if (feed.trendingHits.isEmpty() && feed.quickPicks.isEmpty()) {
                                "Could not reach YouTube right now. Built-in Offline Studio tracks are ready below!"
                            } else null
                        )
                    }
                },
                onFailure = { err ->
                    _homeState.update {
                        it.copy(
                            isLoading = false,
                            error = "Offline / Network issue (${err.message?.take(45)}). Enjoy Offline Library & Studio Beats!"
                        )
                    }
                }
            )
        }
    }

    fun openMoodCategory(category: MoodCategory) {
        viewModelScope.launch {
            _homeState.update {
                it.copy(
                    activeMoodCategory = category,
                    activeMoodTracks = emptyList(),
                    isMoodLoading = true
                )
            }
            val tracks = app.youtubeRepo.searchTracks(category.searchQuery, SearchFilter.MUSIC_SONGS)
            _homeState.update {
                it.copy(activeMoodTracks = tracks, isMoodLoading = false)
            }
        }
    }

    fun closeMoodCategory() {
        _homeState.update { it.copy(activeMoodCategory = null, activeMoodTracks = emptyList(), isMoodLoading = false) }
    }

    fun onSearchQueryChange(newQuery: String) {
        _searchState.update { it.copy(query = newQuery) }
        suggestionJob?.cancel()
        if (newQuery.trim().length < 2) {
            _searchState.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(220L)
            val list = app.youtubeRepo.fetchSuggestions(newQuery)
            _searchState.update { it.copy(suggestions = list) }
        }
    }

    fun submitSearch(queryOverride: String? = null, filterOverride: SearchFilter? = null) {
        val q = (queryOverride ?: _searchState.value.query).trim()
        val filter = filterOverride ?: _searchState.value.selectedFilter
        if (q.isBlank()) return

        localStore.addSearchQuery(q)
        suggestionJob?.cancel()
        searchJob?.cancel()
        _searchState.update {
            it.copy(
                query = q,
                selectedFilter = filter,
                suggestions = emptyList(),
                isSearching = true,
                error = null
            )
        }

        searchJob = viewModelScope.launch {
            if (filter == SearchFilter.PLAYLISTS) {
                val playlists = app.youtubeRepo.searchPlaylists(q)
                _searchState.update {
                    it.copy(
                        playlistResults = playlists,
                        trackResults = emptyList(),
                        isSearching = false,
                        error = if (playlists.isEmpty()) "No playlists found for \"$q\"" else null
                    )
                }
            } else {
                val tracks = app.youtubeRepo.searchTracks(q, filter)
                _searchState.update {
                    it.copy(
                        trackResults = tracks,
                        playlistResults = emptyList(),
                        isSearching = false,
                        error = if (tracks.isEmpty()) "No songs found for \"$q\"" else null
                    )
                }
            }
        }
    }

    fun importYoutubeUrlOrShareText(sharedText: String) {
        val urlCandidate = Regex("https?://\\S+").find(sharedText)?.value ?: sharedText.trim()
        if (urlCandidate.isBlank()) return

        viewModelScope.launch {
            val playlistId = com.pulsewave.music.youtube.YoutubeMusicRepository.extractPlaylistId(urlCandidate)
            val videoId = com.pulsewave.music.youtube.YoutubeMusicRepository.extractVideoId(urlCandidate)

            when {
                playlistId != null && videoId == null -> {
                    showToast("Importing YouTube playlist...")
                    runCatching {
                        app.youtubeRepo.fetchPlaylistTracks(urlCandidate)
                    }.fold(
                        onSuccess = { (name, tracks) ->
                            if (tracks.isNotEmpty()) {
                                localStore.createPlaylist(
                                    name = name,
                                    description = "Imported from YouTube (${tracks.size} songs)",
                                    initialTracks = tracks
                                )
                                playerBridge.playTrack(tracks.first(), tracks)
                                showToast("Imported \"$name\" (${tracks.size} songs)!")
                            } else {
                                showToast("Playlist is empty or private")
                            }
                        },
                        onFailure = { showToast("Could not import playlist") }
                    )
                }
                videoId != null -> {
                    showToast("Loading song from YouTube link...")
                    val meta = app.youtubeRepo.fetchSingleTrackMetadata(videoId)
                    if (meta != null) {
                        playerBridge.playTrack(meta, listOf(meta))
                    } else {
                        showToast("Could not load YouTube video")
                    }
                }
                else -> {
                    showToast("Not a recognized YouTube song or playlist URL")
                }
            }
        }
    }

    fun openYoutubePlaylistPreview(preview: PlaylistPreview) {
        viewModelScope.launch {
            showToast("Loading \"${preview.title}\"...")
            runCatching {
                app.youtubeRepo.fetchPlaylistTracks(preview.url)
            }.onSuccess { (title, tracks) ->
                if (tracks.isNotEmpty()) {
                    localStore.createPlaylist(
                        name = title,
                        description = "Saved from YouTube • ${preview.uploader}",
                        initialTracks = tracks
                    )
                    playerBridge.playTrack(tracks.first(), tracks)
                    showToast("Playing & saved \"$title\" to Library!")
                } else {
                    showToast("No playable tracks found in playlist")
                }
            }.onFailure {
                showToast("Unable to open playlist right now")
            }
        }
    }

    fun playStudioOfflineDemo(index: Int = 0) {
        val demos = CatalogPresets.builtInStudioTracks
        val chosen = demos.getOrElse(index) { demos.first() }
        playerBridge.playTrack(chosen, demos)
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        localStore.updateSettings(transform)
        playerBridge.applySettings(localStore.state.value.settings)
    }

    fun setAccentPreset(preset: AccentPreset) {
        updateSettings { it.copy(accentName = preset.name) }
    }

    fun updateYtDlpEngine() {
        viewModelScope.launch {
            _ytDlpStatus.value = "Updating yt-dlp Nightly Engine..."
            val msg = downloadManager.updateYtDlpEngine()
            _ytDlpStatus.value = msg
            showToast(msg)
        }
    }

    fun clearStreamCache() {
        val msg = playerBridge.clearDiskCache()
        showToast(msg)
    }

    fun showToast(msg: String) {
        _toastMessage.value = msg
    }

    fun consumeToast() {
        _toastMessage.value = null
    }
}
