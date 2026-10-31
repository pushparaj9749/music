package com.pulsewave.music

import com.pulsewave.music.model.SearchFilter
import com.pulsewave.music.model.SongTrack
import com.pulsewave.music.youtube.YoutubeHttpEngine
import com.pulsewave.music.youtube.YoutubeMusicRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

class YoutubeLiveVerificationTest {

    @Test
    fun verifyMultipleYoutubeSongsSearchResolveAndStreamDistinctBytes() = runBlocking {
        val repo = YoutubeMusicRepository()
        repo.ensureInitialized("IN")

        val home = repo.fetchHomeFeed("IN")
        println("LIVE TEST -> Home Trending=${home.trendingHits.size}, QuickPicks=${home.quickPicks.size}, Chill=${home.chillVibes.size}")
        assertTrue(
            "Expected Home feed to return multiple songs, got trending=${home.trendingHits.size}, quick=${home.quickPicks.size}",
            home.trendingHits.size + home.quickPicks.size >= 8
        )

        val arijit = repo.searchTracks("Arijit Singh", SearchFilter.MUSIC_SONGS)
        val aujla = repo.searchTracks("Karan Aujla", SearchFilter.MUSIC_SONGS)
        println("LIVE TEST -> Search 'Arijit Singh'=${arijit.size} tracks, 'Karan Aujla'=${aujla.size} tracks")
        assertTrue("Expected >= 5 Arijit Singh songs, got ${arijit.size}", arijit.size >= 5)
        assertTrue("Expected >= 5 Karan Aujla songs, got ${aujla.size}", aujla.size >= 5)

        // Assemble 6 diverse real tracks from search + home feed (skip very long >15 min mixes)
        val pool: List<SongTrack> = (arijit.take(3) + aujla.take(3) + home.trendingHits.take(4))
            .distinctBy { it.id }
            .filter { it.durationSec in 60L..900L || it.durationSec == 0L }
            .take(6)

        assertTrue("Expected at least 4 distinct candidate songs to test, got ${pool.size}", pool.size >= 4)

        var streamedSuccessCount = 0
        val audioHashes = mutableSetOf<String>()
        val failureDetails = mutableListOf<String>()

        for ((idx, track) in pool.withIndex()) {
            val resolvedRes = repo.resolvePlaybackStream(
                track = track,
                preferHighQuality = true,
                allowYtDlp = false
            )
            if (resolvedRes.isFailure) {
                val msg = "Track[$idx] ${track.id} (${track.title}) resolve failed: ${resolvedRes.exceptionOrNull()?.message}"
                println("LIVE TEST WARN -> $msg")
                failureDetails.add(msg)
                continue
            }

            val resolved = resolvedRes.getOrThrow()
            println("LIVE TEST -> Track[$idx] '${track.title}' (${track.id}) resolved via [${resolved.resolvedViaEngine}] candidates=${resolved.candidates.size}")

            val streamAttempt = runCatching {
                if (resolved.isHls) {
                    YoutubeHttpEngine.openRange(
                        rawUrl = resolved.streamUrl,
                        position = 0L,
                        length = -1L,
                        rangeParameterEnabled = false,
                        rnParameterEnabled = false,
                        customHeaders = resolved.headers,
                        preferPost = false
                    ).use { opened ->
                        val manifestText = opened.bodyStream.bufferedReader().readText()
                        assertTrue("HLS manifest missing #EXTM3U", manifestText.contains("#EXTM3U"))
                        audioHashes.add(sha256Hex(manifestText.toByteArray()))
                    }
                } else {
                    // 1. Read initial 64 KB audio bytes (verifying 302 redirect handling & audio start)
                    val firstChunk = readExactUpTo(
                        url = resolved.streamUrl,
                        position = 0L,
                        length = 65_536L,
                        headers = resolved.headers,
                        preferPost = resolved.preferPostVideoPlayback
                    )
                    assertTrue(
                        "Expected >= 16 KB initial audio for ${track.id}, got ${firstChunk.size}",
                        firstChunk.size >= 16_384
                    )

                    // 2. Read mid-song seek chunk at 128 KB offset (verifying range seeking works)
                    val seekChunk = readExactUpTo(
                        url = resolved.streamUrl,
                        position = 131_072L,
                        length = 32_768L,
                        headers = resolved.headers,
                        preferPost = resolved.preferPostVideoPlayback
                    )
                    assertTrue(
                        "Expected >= 8 KB seek audio for ${track.id}, got ${seekChunk.size}",
                        seekChunk.size >= 8_192
                    )

                    val digest = sha256Hex(firstChunk)
                    println("LIVE TEST OK -> '${track.title}' streamed ${firstChunk.size + seekChunk.size} bytes! sha256=${digest.take(12)}")
                    audioHashes.add(digest)
                }
            }

            if (streamAttempt.isSuccess) {
                streamedSuccessCount++
            } else {
                val err = "Track[$idx] ${track.id} stream read failed: ${streamAttempt.exceptionOrNull()?.message}"
                println("LIVE TEST WARN -> $err")
                failureDetails.add(err)
            }

            if (streamedSuccessCount >= 4) break
        }

        assertTrue(
            "Expected at least 3 distinct songs to stream live audio bytes, got $streamedSuccessCount (hashes=${audioHashes.size}). Errors: ${failureDetails.joinToString(" || ")}",
            streamedSuccessCount >= 3 && audioHashes.size >= 3
        )
    }

    private fun readExactUpTo(
        url: String,
        position: Long,
        length: Long,
        headers: Map<String, String>,
        preferPost: Boolean
    ): ByteArray {
        YoutubeHttpEngine.openRange(
            rawUrl = url,
            position = position,
            length = length,
            rangeParameterEnabled = true,
            rnParameterEnabled = true,
            customHeaders = headers,
            preferPost = preferPost
        ).use { opened ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var remaining = length.toInt()
            while (remaining > 0) {
                val n = opened.bodyStream.read(buf, 0, minOf(buf.size, remaining))
                if (n == -1) break
                out.write(buf, 0, n)
                remaining -= n
            }
            return out.toByteArray()
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
