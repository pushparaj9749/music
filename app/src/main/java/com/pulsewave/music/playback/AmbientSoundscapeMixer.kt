package com.pulsewave.music.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.pulsewave.music.data.CatalogPresets
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Multi-stem Ambient Studio Soundscape Mixer.
 * Can play standalone 100% offline OR blend seamlessly underneath any YouTube song.
 */
class AmbientSoundscapeMixer(context: Context) {

    private val appContext = context.applicationContext
    private val players = mutableMapOf<String, MediaPlayer>()

    private val _levels = MutableStateFlow<Map<String, Float>>(
        CatalogPresets.ambientStems.associate { it.id to 0f }
    )
    val levels: StateFlow<Map<String, Float>> = _levels.asStateFlow()

    private val _activePresetName = MutableStateFlow<String?>(null)
    val activePresetName: StateFlow<String?> = _activePresetName.asStateFlow()

    fun setStemVolume(stemId: String, volume: Float) {
        val clamped = volume.coerceIn(0f, 1f)
        _activePresetName.value = null
        applyStemInternal(stemId, clamped)
    }

    fun applyMixPreset(presetName: String, mix: Map<String, Float>) {
        if (_activePresetName.value == presetName) {
            stopAll()
            return
        }
        CatalogPresets.ambientStems.forEach { stem ->
            val target = mix[stem.id] ?: 0f
            applyStemInternal(stem.id, target)
        }
        _activePresetName.value = presetName
    }

    private fun applyStemInternal(stemId: String, volume: Float) {
        val stem = CatalogPresets.ambientStems.firstOrNull { it.id == stemId } ?: return
        _levels.update { it + (stemId to volume) }

        if (volume <= 0.01f) {
            players.remove(stemId)?.let { mp ->
                runCatching {
                    if (mp.isPlaying) mp.stop()
                    mp.release()
                }
            }
            return
        }

        val existing = players[stemId]
        if (existing != null) {
            runCatching {
                existing.setVolume(volume, volume)
                if (!existing.isPlaying) existing.start()
            }
        } else {
            runCatching {
                val afd = appContext.resources.openRawResourceFd(stem.rawResId) ?: return
                val mp = MediaPlayer()
                mp.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build()
                )
                mp.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                mp.isLooping = true
                mp.prepare()
                mp.setVolume(volume, volume)
                mp.start()
                players[stemId] = mp
            }
        }
    }

    fun stopAll() {
        _activePresetName.value = null
        players.values.forEach { mp ->
            runCatching {
                if (mp.isPlaying) mp.stop()
                mp.release()
            }
        }
        players.clear()
        _levels.value = CatalogPresets.ambientStems.associate { it.id to 0f }
    }
}
