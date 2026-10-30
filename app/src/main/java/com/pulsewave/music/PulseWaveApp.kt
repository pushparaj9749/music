package com.pulsewave.music

import android.app.Application
import com.pulsewave.music.data.LocalStoreRepository
import com.pulsewave.music.playback.PlayerEngineBridge
import com.pulsewave.music.youtube.LyricsRepository
import com.pulsewave.music.youtube.OfflineDownloadManager
import com.pulsewave.music.youtube.YoutubeMusicRepository
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class PulseWaveApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var localStore: LocalStoreRepository
        private set

    lateinit var youtubeRepo: YoutubeMusicRepository
        private set

    lateinit var downloadManager: OfflineDownloadManager
        private set

    lateinit var lyricsRepo: LyricsRepository
        private set

    lateinit var playerBridge: PlayerEngineBridge
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        localStore = LocalStoreRepository(this)
        val region = localStore.state.value.settings.contentRegion
        youtubeRepo = YoutubeMusicRepository().apply { ensureInitialized(region) }
        downloadManager = OfflineDownloadManager(this, youtubeRepo, localStore)
        lyricsRepo = LyricsRepository()
        playerBridge = PlayerEngineBridge(this).also {
            it.applySettings(localStore.state.value.settings)
        }

        // Initialize embedded Python + yt-dlp + FFmpeg native runtime asynchronously
        appScope.launch {
            runCatching { YoutubeDL.getInstance().init(this@PulseWaveApp) }
            runCatching { FFmpeg.getInstance().init(this@PulseWaveApp) }
        }
    }

    companion object {
        lateinit var instance: PulseWaveApp
            private set
    }
}
