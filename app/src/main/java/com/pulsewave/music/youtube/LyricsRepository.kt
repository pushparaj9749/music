package com.pulsewave.music.youtube

import com.pulsewave.music.model.LyricLine
import com.pulsewave.music.model.LyricsState
import com.pulsewave.music.model.SongTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

class LyricsRepository {

    @Serializable
    private data class LrcLibEntry(
        val trackName: String? = null,
        val artistName: String? = null,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cache = ConcurrentHashMap<String, LyricsState>()

    suspend fun fetchLyrics(track: SongTrack): LyricsState = withContext(Dispatchers.IO) {
        cache[track.id]?.let { return@withContext it }

        if (track.rawResName != null) {
            val instrumental = LyricsState(
                isLoading = false,
                isSynced = false,
                plainText = "Instrumental Studio Session\n\nEnjoy this built-in PulseWave studio groove.\nOpen the Audio Studio button to test the 5-Band Equalizer, Bass Boost, Slowed + Reverb Pitch, or layer Midnight Rain & Vinyl Crackle underneath!"
            )
            cache[track.id] = instrumental
            return@withContext instrumental
        }

        val cleanedTitle = stripNoiseFromTitle(track.title)
        val cleanedArtist = track.artist
            .substringBefore(",")
            .substringBefore("&")
            .substringBefore("|")
            .trim()

        val queries = listOfNotNull(
            "$cleanedTitle $cleanedArtist".trim().takeIf { it.isNotBlank() },
            cleanedTitle.takeIf { it.isNotBlank() }
        ).distinct()

        for (q in queries) {
            val attempt = runCatching {
                val encoded = URLEncoder.encode(q, "UTF-8")
                val req = Request.Builder()
                    .url("https://lrclib.net/api/search?q=$encoded")
                    .header("User-Agent", "PulseWaveMusic/1.0.0 (Android)")
                    .build()
                NewPipeOkHttpDownloader.getInstance().okHttpClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val body = resp.body?.string() ?: return@use null
                    val entries = json.decodeFromString<List<LrcLibEntry>>(body)
                    val syncedHit = entries.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
                    if (syncedHit != null) {
                        val parsed = parseLrc(syncedHit.syncedLyrics!!)
                        if (parsed.isNotEmpty()) {
                            return@use LyricsState(
                                isLoading = false,
                                isSynced = true,
                                lines = parsed,
                                plainText = syncedHit.plainLyrics
                            )
                        }
                    }
                    val plainHit = entries.firstOrNull { !it.plainLyrics.isNullOrBlank() }
                    if (plainHit != null) {
                        return@use LyricsState(
                            isLoading = false,
                            isSynced = false,
                            plainText = plainHit.plainLyrics
                        )
                    }
                    null
                }
            }.getOrNull()

            if (attempt != null) {
                cache[track.id] = attempt
                return@withContext attempt
            }
        }

        LyricsState(
            isLoading = false,
            error = "Synced lyrics not found for \"${track.title}\""
        )
    }

    private fun stripNoiseFromTitle(title: String): String {
        return title
            .replace(Regex("\\(.*?\\)"), " ")
            .replace(Regex("\\[.*?]"), " ")
            .substringBefore("|")
            .substringBefore(" - ")
            .replace(Regex("(?i)\\b(full video|lyrical|video song|audio song|8d audio|slowed|reverb|lofi|remix|ft\\.|feat\\.)\\b.*"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
            .ifBlank { title }
    }

    private fun parseLrc(lrcText: String): List<LyricLine> {
        val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?](.*)")
        val list = mutableListOf<LyricLine>()
        for (rawLine in lrcText.lineSequence()) {
            val match = regex.find(rawLine.trim()) ?: continue
            val min = match.groupValues[1].toLongOrNull() ?: 0L
            val sec = match.groupValues[2].toLongOrNull() ?: 0L
            val fracRaw = match.groupValues[3]
            val millis = when (fracRaw.length) {
                1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                3 -> fracRaw.toLongOrNull() ?: 0L
                else -> 0L
            }
            val lyric = match.groupValues[4].trim()
            if (lyric.isNotEmpty()) {
                list += LyricLine(
                    timeMs = min * 60_000L + sec * 1_000L + millis,
                    text = lyric
                )
            }
        }
        return list.sortedBy { it.timeMs }
    }
}
