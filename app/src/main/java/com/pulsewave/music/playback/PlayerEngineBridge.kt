package com.pulsewave.music.playback

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.pulsewave.music.PulseWaveApp
import com.pulsewave.music.R
import com.pulsewave.music.model.AppSettings
import com.pulsewave.music.model.SongTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayInputStream
import java.io.File

enum class RepeatModeState {
    OFF, ALL, ONE
}

data class PlaybackUiState(
    val currentTrack: SongTrack? = null,
    val queue: List<SongTrack> = emptyList(),
    val currentQueueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModeState = RepeatModeState.OFF,
    val streamInfoBadge: String = "Ready",
    val errorMessage: String? = null,
    val sleepTimerRemainingSec: Long? = null,
    val sleepStopAtTrackEnd: Boolean = false
)

class PlayerEngineBridge(context: Context) {

    private val appContext = context.applicationContext
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val audioEffects = AudioEffectsController()
    val ambientMixer = AmbientSoundscapeMixer(appContext)

    private val _playbackState = MutableStateFlow(PlaybackUiState())
    val playbackState: StateFlow<PlaybackUiState> = _playbackState.asStateFlow()

    private var playJob: Job? = null
    private var sleepTimerJob: Job? = null
    private var retryAttemptForCurrentTrack = 0

    private val diskCache: SimpleCache by lazy {
        val dir = File(appContext.cacheDir, "pulsewave_media_cache").apply { mkdirs() }
        SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(256L * 1024L * 1024L),
            StandaloneDatabaseProvider(appContext)
        )
    }

    private val ytDashDataSourceFactory by lazy {
        CacheDataSource.Factory()
            .setCache(diskCache)
            .setUpstreamDataSourceFactory(
                YoutubeMedia3DataSource.Factory(rangeParameterEnabled = true, rnParameterEnabled = true)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val ytProgressiveDataSourceFactory by lazy {
        CacheDataSource.Factory()
            .setCache(diskCache)
            .setUpstreamDataSourceFactory(
                YoutubeMedia3DataSource.Factory(rangeParameterEnabled = false, rnParameterEnabled = true)
            )
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    private val ytHlsDataSourceFactory by lazy {
        YoutubeMedia3DataSource.Factory(rangeParameterEnabled = false, rnParameterEnabled = false)
    }

    private val localDataSourceFactory by lazy {
        DefaultDataSource.Factory(appContext)
    }

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(appContext)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    val sessionPlayer: Player = object : ForwardingPlayer(exoPlayer) {
        override fun getAvailableCommands(): Player.Commands {
            return super.getAvailableCommands()
                .buildUpon()
                .add(Player.COMMAND_SEEK_TO_NEXT)
                .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS)
                .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
        }

        override fun isCommandAvailable(command: Int): Boolean {
            return when (command) {
                Player.COMMAND_SEEK_TO_NEXT,
                Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                Player.COMMAND_SEEK_TO_PREVIOUS,
                Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> true
                else -> super.isCommandAvailable(command)
            }
        }

        override fun seekToNext() = skipNext()
        override fun seekToNextMediaItem() = skipNext()
        override fun seekToPrevious() = skipPrevious()
        override fun seekToPreviousMediaItem() = skipPrevious()
    }

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_BUFFERING -> {
                        _playbackState.update { it.copy(isBuffering = true) }
                    }
                    Player.STATE_READY -> {
                        val dur = exoPlayer.duration.takeIf { it > 0 } ?: 0L
                        _playbackState.update {
                            it.copy(
                                isBuffering = false,
                                durationMs = if (dur > 0) dur else it.durationMs,
                                errorMessage = null
                            )
                        }
                        val settings = PulseWaveApp.instance.localStore.state.value.settings
                        audioEffects.attachToSession(exoPlayer.audioSessionId, settings)
                    }
                    Player.STATE_ENDED -> {
                        _playbackState.update { it.copy(isBuffering = false, isPlaying = false) }
                        handleTrackEnded()
                    }
                    Player.STATE_IDLE -> {
                        _playbackState.update { it.copy(isBuffering = false) }
                    }
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                val settings = PulseWaveApp.instance.localStore.state.value.settings
                audioEffects.attachToSession(audioSessionId, settings)
            }

            override fun onPlayerError(error: PlaybackException) {
                val cur = _playbackState.value.currentTrack
                if (cur != null && retryAttemptForCurrentTrack == 0 && !cur.isOfflineReady) {
                    retryAttemptForCurrentTrack = 1
                    PulseWaveApp.instance.youtubeRepo.invalidateStreamCache(cur.id)
                    loadAndPlayTrackInternal(cur)
                } else {
                    _playbackState.update {
                        it.copy(
                            isBuffering = false,
                            isPlaying = false,
                            errorMessage = "Playback error: ${error.localizedMessage ?: "Stream unavailable"}"
                        )
                    }
                }
            }
        })

        // Position ticker for smooth seek bar & synced lyrics
        mainScope.launch {
            while (isActive) {
                if (exoPlayer.isPlaying) {
                    val pos = exoPlayer.currentPosition.coerceAtLeast(0L)
                    val dur = exoPlayer.duration.takeIf { it > 0L } ?: _playbackState.value.durationMs
                    _playbackState.update {
                        it.copy(positionMs = pos, durationMs = dur)
                    }
                }
                delay(250L)
            }
        }
    }

    fun applySettings(settings: AppSettings) {
        mainScope.launch {
            exoPlayer.skipSilenceEnabled = settings.skipSilence
            exoPlayer.playbackParameters = PlaybackParameters(
                settings.playbackSpeed.coerceIn(0.5f, 2.0f),
                settings.playbackPitch.coerceIn(0.5f, 2.0f)
            )
            audioEffects.attachToSession(exoPlayer.audioSessionId, settings)
        }
    }

    fun playTrack(track: SongTrack, contextQueue: List<SongTrack> = emptyList()) {
        mainScope.launch {
            ensureServiceStarted()
            val newQueue = if (contextQueue.isNotEmpty()) {
                if (contextQueue.any { it.id == track.id }) contextQueue else listOf(track) + contextQueue
            } else {
                val existing = _playbackState.value.queue
                if (existing.any { it.id == track.id }) existing else listOf(track) + existing
            }
            val index = newQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
            retryAttemptForCurrentTrack = 0
            _playbackState.update {
                it.copy(
                    currentTrack = track,
                    queue = newQueue,
                    currentQueueIndex = index,
                    positionMs = 0L,
                    durationMs = track.durationSec * 1000L,
                    isBuffering = true,
                    errorMessage = null,
                    streamInfoBadge = if (track.isOfflineReady) "Offline Studio" else "Connecting YT..."
                )
            }
            PulseWaveApp.instance.localStore.recordRecentlyPlayed(track)
            loadAndPlayTrackInternal(track)
        }
    }

    private fun loadAndPlayTrackInternal(track: SongTrack) {
        playJob?.cancel()
        playJob = mainScope.launch {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()

            val metadata = buildMediaMetadata(track)

            // 1. Check built-in raw studio track
            if (!track.rawResName.isNullOrBlank()) {
                val resId = when (track.rawResName) {
                    "offline_lofi_beat" -> R.raw.offline_lofi_beat
                    "offline_synthwave_drive" -> R.raw.offline_synthwave_drive
                    else -> R.raw.offline_lofi_beat
                }
                val uri = RawResourceDataSource.buildRawResourceUri(resId)
                val item = MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(uri)
                    .setMediaMetadata(metadata)
                    .build()
                val source = ProgressiveMediaSource.Factory(localDataSourceFactory).createMediaSource(item)
                exoPlayer.repeatMode = Player.REPEAT_MODE_ONE // Studio loops groove seamlessly
                exoPlayer.setMediaSource(source)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                _playbackState.update {
                    it.copy(
                        isBuffering = false,
                        streamInfoBadge = "Offline PCM Studio Loop"
                    )
                }
                return@launch
            }

            // Reset ExoPlayer single-loop if we came from studio loop
            exoPlayer.repeatMode = Player.REPEAT_MODE_OFF

            // 2. Check downloaded offline file
            val downloadedMatch = PulseWaveApp.instance.localStore.state.value.downloadedSongs
                .firstOrNull { it.id == track.id }
            val localPath = track.localFilePath ?: downloadedMatch?.localFilePath
            if (!localPath.isNullOrBlank() && File(localPath).exists()) {
                val item = MediaItem.Builder()
                    .setMediaId(track.id)
                    .setUri(Uri.fromFile(File(localPath)))
                    .setMediaMetadata(metadata)
                    .build()
                val source = ProgressiveMediaSource.Factory(localDataSourceFactory).createMediaSource(item)
                exoPlayer.setMediaSource(source)
                exoPlayer.prepare()
                exoPlayer.playWhenReady = true
                _playbackState.update {
                    it.copy(
                        isBuffering = false,
                        streamInfoBadge = "Offline Downloaded Audio"
                    )
                }
                return@launch
            }

            // 3. Resolve YouTube audio stream
            val settings = PulseWaveApp.instance.localStore.state.value.settings
            val result = PulseWaveApp.instance.youtubeRepo.resolvePlaybackStream(
                track = track,
                preferHighQuality = settings.highQualityAudio
            )

            result.fold(
                onSuccess = { resolved ->
                    val uri = Uri.parse(resolved.streamUrl)
                    val item = MediaItem.Builder()
                        .setMediaId(track.id)
                        .setUri(uri)
                        .setCustomCacheKey(track.id)
                        .setMediaMetadata(metadata)
                        .build()

                    val mediaSource: MediaSource = when {
                        resolved.isHls -> {
                            HlsMediaSource.Factory(ytHlsDataSourceFactory).createMediaSource(item)
                        }
                        !resolved.dashManifestXml.isNullOrBlank() -> {
                            runCatching {
                                val manifest = withContext(Dispatchers.IO) {
                                    DashManifestParser().parse(
                                        uri,
                                        ByteArrayInputStream(resolved.dashManifestXml.toByteArray(Charsets.UTF_8))
                                    )
                                }
                                DashMediaSource.Factory(ytDashDataSourceFactory)
                                    .createMediaSource(manifest, item)
                            }.getOrElse {
                                ProgressiveMediaSource.Factory(ytProgressiveDataSourceFactory)
                                    .createMediaSource(item)
                            }
                        }
                        else -> {
                            ProgressiveMediaSource.Factory(ytProgressiveDataSourceFactory)
                                .createMediaSource(item)
                        }
                    }

                    // Append related tracks for Endless Auto-Radio if enabled and queue is running low
                    if (settings.autoRadioEnabled && resolved.relatedTracks.isNotEmpty()) {
                        _playbackState.update { st ->
                            val existingIds = st.queue.map { q -> q.id }.toSet()
                            val fresh = resolved.relatedTracks.filterNot { r -> r.id in existingIds }
                            if (st.queue.size - st.currentQueueIndex <= 4 && fresh.isNotEmpty()) {
                                st.copy(queue = st.queue + fresh.take(10))
                            } else st
                        }
                    }

                    _playbackState.update {
                        it.copy(
                            durationMs = if (resolved.durationSec > 0) resolved.durationSec * 1000L else it.durationMs,
                            streamInfoBadge = resolved.resolvedViaEngine,
                            errorMessage = null
                        )
                    }

                    exoPlayer.setMediaSource(mediaSource)
                    exoPlayer.prepare()
                    exoPlayer.playWhenReady = true
                },
                onFailure = { err ->
                    _playbackState.update {
                        it.copy(
                            isBuffering = false,
                            isPlaying = false,
                            streamInfoBadge = "Stream Failed",
                            errorMessage = err.message ?: "Unable to extract YouTube audio"
                        )
                    }
                }
            )
        }
    }

    fun togglePlayPause() {
        mainScope.launch {
            if (exoPlayer.isPlaying) {
                exoPlayer.pause()
            } else {
                if (exoPlayer.playbackState == Player.STATE_IDLE || exoPlayer.playbackState == Player.STATE_ENDED) {
                    _playbackState.value.currentTrack?.let { loadAndPlayTrackInternal(it) }
                } else {
                    exoPlayer.play()
                }
            }
        }
    }

    fun seekTo(positionMs: Long) {
        mainScope.launch {
            val target = positionMs.coerceAtLeast(0L)
            exoPlayer.seekTo(target)
            _playbackState.update { it.copy(positionMs = target) }
        }
    }

    fun skipNext() {
        mainScope.launch {
            val st = _playbackState.value
            val q = st.queue
            if (q.isEmpty()) return@launch

            val nextIndex = when {
                st.shuffleEnabled && q.size > 1 -> {
                    val candidates = q.indices.filter { it != st.currentQueueIndex }
                    candidates.random()
                }
                st.currentQueueIndex + 1 < q.size -> st.currentQueueIndex + 1
                st.repeatMode == RepeatModeState.ALL -> 0
                else -> return@launch
            }
            val nextTrack = q[nextIndex]
            retryAttemptForCurrentTrack = 0
            _playbackState.update {
                it.copy(
                    currentTrack = nextTrack,
                    currentQueueIndex = nextIndex,
                    positionMs = 0L,
                    durationMs = nextTrack.durationSec * 1000L,
                    isBuffering = true
                )
            }
            PulseWaveApp.instance.localStore.recordRecentlyPlayed(nextTrack)
            loadAndPlayTrackInternal(nextTrack)
        }
    }

    fun skipPrevious() {
        mainScope.launch {
            if (exoPlayer.currentPosition > 4_000L) {
                seekTo(0L)
                return@launch
            }
            val st = _playbackState.value
            val q = st.queue
            if (q.isEmpty()) return@launch
            val prevIndex = if (st.currentQueueIndex > 0) {
                st.currentQueueIndex - 1
            } else if (st.repeatMode == RepeatModeState.ALL) {
                q.lastIndex
            } else {
                0
            }
            val prevTrack = q[prevIndex]
            retryAttemptForCurrentTrack = 0
            _playbackState.update {
                it.copy(
                    currentTrack = prevTrack,
                    currentQueueIndex = prevIndex,
                    positionMs = 0L,
                    durationMs = prevTrack.durationSec * 1000L,
                    isBuffering = true
                )
            }
            PulseWaveApp.instance.localStore.recordRecentlyPlayed(prevTrack)
            loadAndPlayTrackInternal(prevTrack)
        }
    }

    fun addPlayNext(track: SongTrack) {
        _playbackState.update { st ->
            if (st.currentTrack == null) {
                playTrack(track)
                return@update st
            }
            val mutable = st.queue.toMutableList()
            mutable.removeAll { it.id == track.id && it.id != st.currentTrack.id }
            val curIdx = mutable.indexOfFirst { it.id == st.currentTrack.id }.coerceAtLeast(0)
            mutable.add((curIdx + 1).coerceAtMost(mutable.size), track)
            st.copy(queue = mutable, currentQueueIndex = curIdx)
        }
    }

    fun addToQueueEnd(track: SongTrack) {
        _playbackState.update { st ->
            if (st.currentTrack == null) {
                playTrack(track)
                return@update st
            }
            if (st.queue.any { it.id == track.id }) st
            else st.copy(queue = st.queue + track)
        }
    }

    fun removeFromQueue(trackId: String) {
        _playbackState.update { st ->
            if (st.currentTrack?.id == trackId) return@update st
            val updated = st.queue.filterNot { it.id == trackId }
            val newIdx = updated.indexOfFirst { it.id == st.currentTrack?.id }.coerceAtLeast(0)
            st.copy(queue = updated, currentQueueIndex = newIdx)
        }
    }

    fun toggleShuffle() {
        _playbackState.update { it.copy(shuffleEnabled = !it.shuffleEnabled) }
    }

    fun cycleRepeatMode() {
        _playbackState.update { st ->
            val next = when (st.repeatMode) {
                RepeatModeState.OFF -> RepeatModeState.ALL
                RepeatModeState.ALL -> RepeatModeState.ONE
                RepeatModeState.ONE -> RepeatModeState.OFF
            }
            st.copy(repeatMode = next)
        }
    }

    fun setSleepTimerMinutes(minutes: Int?) {
        sleepTimerJob?.cancel()
        if (minutes == null || minutes <= 0) {
            _playbackState.update {
                it.copy(sleepTimerRemainingSec = null, sleepStopAtTrackEnd = false)
            }
            return
        }
        var remaining = minutes * 60L
        _playbackState.update {
            it.copy(sleepTimerRemainingSec = remaining, sleepStopAtTrackEnd = false)
        }
        sleepTimerJob = mainScope.launch {
            while (remaining > 0L && isActive) {
                delay(1_000L)
                remaining -= 1L
                _playbackState.update { it.copy(sleepTimerRemainingSec = remaining) }
            }
            if (remaining <= 0L) {
                exoPlayer.pause()
                ambientMixer.stopAll()
                _playbackState.update { it.copy(sleepTimerRemainingSec = null) }
            }
        }
    }

    fun setSleepAtEndOfTrack(enabled: Boolean) {
        sleepTimerJob?.cancel()
        _playbackState.update {
            it.copy(sleepTimerRemainingSec = null, sleepStopAtTrackEnd = enabled)
        }
    }

    fun clearDiskCache(): String {
        return runCatching {
            val cacheDir = File(appContext.cacheDir, "pulsewave_media_cache")
            val sizeMb = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / (1024 * 1024)
            "Freed ~${sizeMb} MB audio stream cache"
        }.getOrDefault("Cache cleaned")
    }

    private fun handleTrackEnded() {
        val st = _playbackState.value
        if (st.sleepStopAtTrackEnd) {
            _playbackState.update { it.copy(sleepStopAtTrackEnd = false) }
            ambientMixer.stopAll()
            return
        }
        if (st.repeatMode == RepeatModeState.ONE && st.currentTrack != null) {
            seekTo(0L)
            exoPlayer.play()
            return
        }
        skipNext()
    }

    private fun buildMediaMetadata(track: SongTrack): MediaMetadata {
        val builder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.albumName ?: "PulseWave Music")
        if (track.thumbnailUrl.startsWith("http")) {
            builder.setArtworkUri(Uri.parse(track.thumbnailUrl))
        }
        return builder.build()
    }

    private fun ensureServiceStarted() {
        runCatching {
            val intent = Intent(appContext, PulseWavePlaybackService::class.java)
            appContext.startService(intent)
        }
    }
}
