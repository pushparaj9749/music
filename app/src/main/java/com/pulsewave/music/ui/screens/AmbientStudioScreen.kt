package com.pulsewave.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Cloud
import androidx.compose.material.icons.rounded.NightsStay
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.StopCircle
import androidx.compose.material.icons.rounded.Thunderstorm
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsewave.music.data.CatalogPresets
import com.pulsewave.music.model.SongTrack
import com.pulsewave.music.playback.PlaybackUiState
import com.pulsewave.music.ui.components.CoverArtwork
import com.pulsewave.music.ui.theme.ObsidianCard
import com.pulsewave.music.ui.theme.TextSecondary

@Composable
fun AmbientStudioScreen(
    ambientLevels: Map<String, Float>,
    activePresetName: String?,
    playbackState: PlaybackUiState,
    onSetStemVolume: (String, Float) -> Unit,
    onApplyPreset: (String, Map<String, Float>) -> Unit,
    onStopAllAmbient: () -> Unit,
    onPlayOfflineBeat: (SongTrack, List<SongTrack>) -> Unit
) {
    val primary = MaterialTheme.colorScheme.primary
    val anyActive = ambientLevels.values.any { it > 0.01f }

    val signatureMixes = listOf(
        Triple("Rainy Lo-Fi Cafe", "Rain 55% + Warm Vinyl 35%", mapOf("rain" to 0.55f, "vinyl" to 0.35f)),
        Triple("Midnight Storm", "Rain 50% + Thunder 45% + Pad 25%", mapOf("rain" to 0.50f, "thunder" to 0.45f, "night_pad" to 0.25f)),
        Triple("Coastal Sunset", "Ocean Waves 65% + Dream Pad 35%", mapOf("ocean" to 0.65f, "night_pad" to 0.35f)),
        Triple("Pure Vinyl Dust", "Warm Analog Crackle 45%", mapOf("vinyl" to 0.45f))
    )

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Studio Soundscapes", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        text = "100% Offline Ready • Layer under any YT song!",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                if (anyActive) {
                    FilledTonalButton(
                        onClick = onStopAllAmbient,
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(5.dp))
                        Text("Mute Mix")
                    }
                }
            }
        }

        // Signature One-Tap Mixes
        item {
            Text(
                text = "One-Tap Atmosphere Presets",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 6.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                signatureMixes.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        rowItems.forEach { (name, desc, mixMap) ->
                            val isSelected = activePresetName == name
                            Surface(
                                color = if (isSelected) primary.copy(alpha = 0.26f) else ObsidianCard,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(16.dp))
                                    .clickable { onApplyPreset(name, mixMap) }
                            ) {
                                Column(modifier = Modifier.padding(14.dp)) {
                                    Text(
                                        text = name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp,
                                        color = if (isSelected) primary else Color.White
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = desc,
                                        fontSize = 11.sp,
                                        color = TextSecondary,
                                        maxLines = 2
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // Individual Studio Stem Sliders
        item {
            Text(
                text = "Individual Soundscape Faders",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        items(CatalogPresets.ambientStems, key = { it.id }) { stem ->
            val vol = ambientLevels[stem.id] ?: 0f
            val stemColor = Color(stem.accentColor)
            val icon = when (stem.id) {
                "rain" -> Icons.Rounded.Cloud
                "vinyl" -> Icons.Rounded.Album
                "ocean" -> Icons.Rounded.Waves
                "thunder" -> Icons.Rounded.Thunderstorm
                else -> Icons.Rounded.NightsStay
            }

            Surface(
                color = ObsidianCard,
                shape = RoundedCornerShape(18.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(stemColor.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(icon, contentDescription = null, tint = stemColor)
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(stem.title, style = MaterialTheme.typography.titleMedium)
                            Text(stem.subtitle, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            text = "${(vol * 100).toInt()}%",
                            fontWeight = FontWeight.Bold,
                            color = if (vol > 0.01f) stemColor else TextSecondary
                        )
                    }
                    Slider(
                        value = vol,
                        onValueChange = { onSetStemVolume(stem.id, it) },
                        colors = SliderDefaults.colors(
                            thumbColor = stemColor,
                            activeTrackColor = stemColor
                        )
                    )
                }
            }
        }

        // Built-in Offline Studio Beats
        item {
            Text(
                text = "Built-in Offline Studio Grooves",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(top = 10.dp)
            )
            Text(
                text = "Play zero-internet synthesized tracks to test EQ, Bass Boost & Slowed+Reverb",
                style = MaterialTheme.typography.bodySmall
            )
        }

        items(CatalogPresets.builtInStudioTracks, key = { it.id }) { beat ->
            val active = playbackState.currentTrack?.id == beat.id
            Surface(
                color = if (active) primary.copy(alpha = 0.2f) else ObsidianCard,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onPlayOfflineBeat(beat, CatalogPresets.builtInStudioTracks) }
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CoverArtwork(
                        url = beat.thumbnailUrl,
                        modifier = Modifier.size(56.dp),
                        cornerRadius = 12.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(beat.title, style = MaterialTheme.typography.titleMedium)
                        Text(beat.artist, style = MaterialTheme.typography.bodySmall)
                    }
                    Box(
                        modifier = Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(Brush.linearGradient(listOf(primary, MaterialTheme.colorScheme.secondary))),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White)
                    }
                }
            }
        }
    }
}
