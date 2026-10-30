package com.pulsewave.music.youtube

import android.content.Context
import com.pulsewave.music.data.LocalStoreRepository
import com.pulsewave.music.model.DownloadTaskState
import com.pulsewave.music.model.SongTrack
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import java.io.File
import java.io.FileOutputStream

class OfflineDownloadManager(
    context: Context,
    private val youtubeRepo: YoutubeMusicRepository,
    private val localStore: LocalStoreRepository
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val songsDir = File(appContext.filesDir, "offline_tracks").apply { mkdirs() }

    private val _tasks = MutableStateFlow<Map<String, DownloadTaskState>>(emptyMap())
    val tasks: StateFlow<Map<String, DownloadTaskState>> = _tasks.asStateFlow()

    fun enqueueDownload(track: SongTrack) {
        if (track.isOfflineReady) return
        val existing = _tasks.value[track.id]
        if (existing != null && !existing.isCompleted && !existing.isFailed) return

        _tasks.update { map ->
            map + (track.id to DownloadTaskState(
                trackId = track.id,
                title = track.title,
                thumbnailUrl = track.thumbnailUrl,
                progress = 2f,
                statusText = "Preparing stream..."
            ))
        }

        scope.launch {
            val ytUrl = track.youtubeUrl.ifBlank { "https://www.youtube.com/watch?v=${track.id}" }

            // Strategy 1: High-speed chunked stream download via resolved NewPipe stream
            val fastSuccess = runCatching {
                val resolved = youtubeRepo.resolvePlaybackStream(track, preferHighQuality = true).getOrThrow()
                if (resolved.isHls) error("HLS stream requires yt-dlp muxer")

                val ext = if (resolved.mimeType?.contains("webm") == true) "webm" else "m4a"
                val destFile = File(songsDir, "${track.id}.$ext")
                downloadChunkedAudio(resolved.streamUrl, destFile) { pct ->
                    _tasks.update { map ->
                        val cur = map[track.id] ?: return@update map
                        map + (track.id to cur.copy(
                            progress = pct,
                            statusText = "Downloading ${pct.toInt()}%"
                        ))
                    }
                }
                if (!destFile.exists() || destFile.length() < 16_384L) {
                    destFile.delete()
                    error("Downloaded file incomplete")
                }
                destFile
            }

            if (fastSuccess.isSuccess) {
                val file = fastSuccess.getOrThrow()
                val savedTrack = track.copy(localFilePath = file.absolutePath)
                localStore.saveDownloadedSong(savedTrack)
                _tasks.update { map ->
                    map + (track.id to DownloadTaskState(
                        trackId = track.id,
                        title = track.title,
                        thumbnailUrl = track.thumbnailUrl,
                        progress = 100f,
                        statusText = "Saved Offline (${file.length() / 1024 / 1024} MB)",
                        isCompleted = true
                    ))
                }
                return@launch
            }

            // Strategy 2: Embedded yt-dlp + FFmpeg audio extraction
            val ytdlAttempt = runCatching {
                val outTemplate = File(songsDir, "${track.id}.%(ext)s").absolutePath
                val request = YoutubeDLRequest(ytUrl).apply {
                    addOption("--no-playlist")
                    addOption("-f", "bestaudio[ext=m4a]/bestaudio")
                    addOption("-x")
                    addOption("--audio-format", "m4a")
                    addOption("-o", outTemplate)
                }
                val procId = "dl_${track.id}"
                YoutubeDL.getInstance().execute(request, procId) { progress, _, _ ->
                    val clamped = progress.coerceIn(5f, 99f)
                    _tasks.update { map ->
                        val cur = map[track.id] ?: return@update map
                        map + (track.id to cur.copy(
                            progress = clamped,
                            statusText = "yt-dlp + FFmpeg ${clamped.toInt()}%"
                        ))
                    }
                }
                songsDir.listFiles()?.firstOrNull { it.name.startsWith("${track.id}.") && it.length() > 8192L }
                    ?: error("Output file not found after yt-dlp")
            }

            ytdlAttempt.fold(
                onSuccess = { file ->
                    val savedTrack = track.copy(localFilePath = file.absolutePath)
                    localStore.saveDownloadedSong(savedTrack)
                    _tasks.update { map ->
                        map + (track.id to DownloadTaskState(
                            trackId = track.id,
                            title = track.title,
                            thumbnailUrl = track.thumbnailUrl,
                            progress = 100f,
                            statusText = "Saved Offline",
                            isCompleted = true
                        ))
                    }
                },
                onFailure = { err ->
                    _tasks.update { map ->
                        map + (track.id to DownloadTaskState(
                            trackId = track.id,
                            title = track.title,
                            thumbnailUrl = track.thumbnailUrl,
                            progress = 0f,
                            statusText = err.message?.take(60) ?: "Download failed",
                            isFailed = true
                        ))
                    }
                }
            )
        }
    }

    private fun downloadChunkedAudio(
        streamUrl: String,
        destFile: File,
        onProgress: (Float) -> Unit
    ) {
        val client = NewPipeOkHttpDownloader.getInstance().okHttpClient
        val userAgent = if (YoutubeParsingHelper.isVisionOsStreamingUrl(streamUrl)) {
            YoutubeParsingHelper.getVisionOsUserAgent(null)
        } else {
            NewPipeOkHttpDownloader.USER_AGENT
        }
        val chunkSize = 512 * 1024L // 512 KB range chunks to bypass YouTube throttling
        val tmpFile = File(destFile.parentFile, "${destFile.name}.part")
        if (tmpFile.exists()) tmpFile.delete()

        var downloaded = 0L
        var totalLength = -1L
        var rn = 1

        FileOutputStream(tmpFile).use { fos ->
            while (true) {
                val endByte = downloaded + chunkSize - 1
                val isVideoPlayback = streamUrl.contains("/videoplayback")
                val chunkUrl = if (isVideoPlayback) {
                    "$streamUrl&rn=$rn&range=$downloaded-$endByte"
                } else {
                    streamUrl
                }
                rn++

                val reqBuilder = Request.Builder()
                    .url(chunkUrl)
                    .header("User-Agent", userAgent)

                if (!isVideoPlayback) {
                    reqBuilder.header("Range", "bytes=$downloaded-$endByte")
                } else {
                    reqBuilder.post(byteArrayOf(0x78, 0).toRequestBody(null))
                }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    if (!resp.isSuccessful && resp.code != 206) {
                        error("HTTP ${resp.code}")
                    }
                    if (totalLength <= 0L) {
                        val contentRange = resp.header("Content-Range")
                        totalLength = contentRange?.substringAfter("/", "")?.toLongOrNull()
                            ?: streamUrl.substringAfter("clen=", "").substringBefore("&").toLongOrNull()
                            ?: -1L
                    }
                    val body = resp.body ?: error("Empty body")
                    val bytes = body.bytes()
                    if (bytes.isEmpty()) {
                        tmpFile.renameTo(destFile)
                        return
                    }
                    fos.write(bytes)
                    downloaded += bytes.size

                    if (totalLength > 0L) {
                        onProgress((downloaded.toFloat() / totalLength.toFloat() * 100f).coerceIn(2f, 99f))
                        if (downloaded >= totalLength) {
                            fos.flush()
                            tmpFile.renameTo(destFile)
                            return
                        }
                    }

                    if (!isVideoPlayback || bytes.size < 1024) {
                        fos.flush()
                        tmpFile.renameTo(destFile)
                        return
                    }
                }
            }
        }
    }

    suspend fun getYtDlpVersion(): String = withContext(Dispatchers.IO) {
        runCatching {
            YoutubeDL.getInstance().version(appContext) ?: "Bundled 0.17.3"
        }.getOrDefault("Bundled")
    }

    suspend fun updateYtDlpEngine(): String = withContext(Dispatchers.IO) {
        runCatching {
            val status = YoutubeDL.getInstance().updateYoutubeDL(
                appContext,
                YoutubeDL.UpdateChannel.NIGHTLY
            )
            val ver = YoutubeDL.getInstance().version(appContext) ?: "Latest"
            when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Updated yt-dlp to $ver"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "Already up-to-date ($ver)"
                else -> "Engine ready ($ver)"
            }
        }.getOrElse { "Engine active (${it.message?.take(40) ?: "offline"})" }
    }
}
