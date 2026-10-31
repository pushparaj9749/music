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

            // Strategy 1: High-speed 512 KB chunked stream download via redirect-safe YoutubeHttpEngine
            val fastSuccess = runCatching {
                val resolved = youtubeRepo.resolvePlaybackStream(track, preferHighQuality = true).getOrThrow()
                val directCandidate = resolved.candidates.firstOrNull { !it.isHls }
                    ?: if (!resolved.isHls) null else error("HLS stream requires yt-dlp muxer")

                val streamUrl = directCandidate?.streamUrl ?: resolved.streamUrl
                val mime = directCandidate?.mimeType ?: resolved.mimeType
                val headers = directCandidate?.headers ?: resolved.headers
                val preferPost = directCandidate?.preferPostVideoPlayback ?: resolved.preferPostVideoPlayback

                val ext = if (mime?.contains("webm") == true) "webm" else "m4a"
                val destFile = File(songsDir, "${track.id}.$ext")
                downloadChunkedAudio(
                    streamUrl = streamUrl,
                    headers = headers,
                    preferPost = preferPost,
                    destFile = destFile
                ) { pct ->
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
                        statusText = "Saved Offline (${(file.length() / 1024.0 / 1024.0).let { String.format("%.1f", it) }} MB)",
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
        headers: Map<String, String>,
        preferPost: Boolean,
        destFile: File,
        onProgress: (Float) -> Unit
    ) {
        val chunkSize = 512L * 1024L
        val tmpFile = File(destFile.parentFile, "${destFile.name}.part")
        if (tmpFile.exists()) tmpFile.delete()
        if (destFile.exists()) destFile.delete()

        var downloaded = 0L
        var totalLength = YoutubeHttpEngine.parseClenFromUrl(streamUrl)

        FileOutputStream(tmpFile).use { fos ->
            while (true) {
                val requestLen = if (totalLength > 0L) {
                    minOf(chunkSize, (totalLength - downloaded).coerceAtLeast(1L))
                } else {
                    chunkSize
                }

                YoutubeHttpEngine.openRange(
                    rawUrl = streamUrl,
                    position = downloaded,
                    length = requestLen,
                    rangeParameterEnabled = true,
                    rnParameterEnabled = true,
                    customHeaders = headers,
                    preferPost = preferPost
                ).use { opened ->
                    if (totalLength <= 0L && opened.totalResourceLength > 0L) {
                        totalLength = opened.totalResourceLength
                    }
                    val buf = ByteArray(32 * 1024)
                    var chunkBytes = 0L
                    while (true) {
                        val n = opened.bodyStream.read(buf)
                        if (n == -1) break
                        fos.write(buf, 0, n)
                        downloaded += n
                        chunkBytes += n
                    }

                    if (totalLength > 0L) {
                        onProgress((downloaded.toFloat() / totalLength.toFloat() * 100f).coerceIn(2f, 99f))
                        if (downloaded >= totalLength) {
                            fos.flush()
                            tmpFile.renameTo(destFile)
                            return
                        }
                    }

                    if (chunkBytes <= 0L || (totalLength <= 0L && chunkBytes < requestLen)) {
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
