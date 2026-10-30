package com.pulsewave.music.playback

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.media.audiofx.Virtualizer
import com.pulsewave.music.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqBandInfo(
    val index: Int,
    val centerFreqHz: Int,
    val label: String,
    val levelMb: Int
)

data class AudioDspUiState(
    val isAvailable: Boolean = false,
    val minLevelMb: Int = -1500,
    val maxLevelMb: Int = 1500,
    val bands: List<EqBandInfo> = emptyList(),
    val presets: List<String> = AudioEffectsController.BUILT_IN_PRESETS.keys.toList()
)

class AudioEffectsController {

    private var currentSessionId: Int = 0
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null

    private val _dspState = MutableStateFlow(AudioDspUiState())
    val dspState: StateFlow<AudioDspUiState> = _dspState.asStateFlow()

    fun attachToSession(audioSessionId: Int, settings: AppSettings) {
        if (audioSessionId <= 0) return
        if (audioSessionId != currentSessionId) {
            release()
            currentSessionId = audioSessionId
            runCatching {
                equalizer = Equalizer(0, audioSessionId).apply { enabled = settings.eqEnabled }
            }
            runCatching {
                bassBoost = BassBoost(0, audioSessionId).apply { enabled = settings.eqEnabled }
            }
            runCatching {
                virtualizer = Virtualizer(0, audioSessionId).apply { enabled = settings.eqEnabled }
            }
            runCatching {
                loudnessEnhancer = LoudnessEnhancer(audioSessionId).apply {
                    enabled = settings.loudnessGainMb > 0
                }
            }
        }
        applySettings(settings)
    }

    fun applySettings(settings: AppSettings) {
        val eq = equalizer
        if (eq != null) {
            runCatching {
                eq.enabled = settings.eqEnabled
                val range = eq.bandLevelRange
                val minMb = range.getOrElse(0) { -1500 }.toInt()
                val maxMb = range.getOrElse(1) { 1500 }.toInt()
                val numBands = eq.numberOfBands.toInt()

                val curve = if (settings.eqPresetName == "Custom" && settings.customBandLevelsMb.size == numBands) {
                    settings.customBandLevelsMb
                } else {
                    val template = BUILT_IN_PRESETS[settings.eqPresetName] ?: BUILT_IN_PRESETS.getValue("Bass & Clarity")
                    List(numBands) { idx ->
                        val ratio = if (numBands <= 1) 0f else idx.toFloat() / (numBands - 1).toFloat()
                        val tIdx = (ratio * (template.size - 1)).toInt().coerceIn(0, template.lastIndex)
                        template[tIdx].coerceIn(minMb, maxMb)
                    }
                }

                val bandsInfo = mutableListOf<EqBandInfo>()
                for (b in 0 until numBands) {
                    val target = curve.getOrElse(b) { 0 }.coerceIn(minMb, maxMb)
                    eq.setBandLevel(b.toShort(), target.toShort())
                    val centerHz = (eq.getCenterFreq(b.toShort()) / 1000)
                    val label = if (centerHz >= 1000) "${centerHz / 1000}kHz" else "${centerHz}Hz"
                    bandsInfo += EqBandInfo(
                        index = b,
                        centerFreqHz = centerHz,
                        label = label,
                        levelMb = target
                    )
                }

                _dspState.value = AudioDspUiState(
                    isAvailable = true,
                    minLevelMb = minMb,
                    maxLevelMb = maxMb,
                    bands = bandsInfo
                )
            }
        }

        bassBoost?.let { bb ->
            runCatching {
                bb.enabled = settings.eqEnabled && settings.bassBoostStrength > 0
                if (bb.strengthSupported) {
                    bb.setStrength(settings.bassBoostStrength.coerceIn(0, 1000).toShort())
                }
            }
        }

        virtualizer?.let { virt ->
            runCatching {
                virt.enabled = settings.eqEnabled && settings.virtualizerStrength > 0
                if (virt.strengthSupported) {
                    virt.setStrength(settings.virtualizerStrength.coerceIn(0, 1000).toShort())
                }
            }
        }

        loudnessEnhancer?.let { le ->
            runCatching {
                le.enabled = settings.loudnessGainMb > 0
                le.setTargetGain(settings.loudnessGainMb.coerceIn(0, 1500))
            }
        }
    }

    fun release() {
        runCatching { equalizer?.release() }
        runCatching { bassBoost?.release() }
        runCatching { virtualizer?.release() }
        runCatching { loudnessEnhancer?.release() }
        equalizer = null
        bassBoost = null
        virtualizer = null
        loudnessEnhancer = null
        currentSessionId = 0
    }

    companion object {
        // 5-band target curves in millibels (-1500 to +1500 mB)
        val BUILT_IN_PRESETS: LinkedHashMap<String, List<Int>> = linkedMapOf(
            "Bass & Clarity" to listOf(650, 350, 50, 300, 550),
            "Pure Flat" to listOf(0, 0, 0, 0, 0),
            "Heavy Sub-Bass" to listOf(1050, 700, 0, 150, 350),
            "Bollywood Vocal" to listOf(250, 150, 550, 650, 400),
            "Lo-Fi Warmth" to listOf(550, 450, 100, -350, -650),
            "Acoustic & Sufi" to listOf(300, 200, 400, 500, 450),
            "Gym Adrenaline" to listOf(900, 500, -100, 450, 750)
        )
    }
}
