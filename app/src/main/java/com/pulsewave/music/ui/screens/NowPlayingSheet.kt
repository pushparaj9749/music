package com.pulsewave.music.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsewave.music.model.AppSettings
import com.pulsewave.music.model.DownloadTaskState
import com.pulsewave.music.model.LyricsState
import com.pulsewave.music.model.SongTrack
import com.pulsewave.music.playback.AudioDspUiState
import com.pulsewave.music.playback.PlaybackUiState
import com.pulsewave.music.playback.RepeatModeState
import com.pulsewave.music.ui.components.AnimatedSpectrumBars
import com.pulsewave.music.ui.components.CoverArtwork
import com.pulsewave.music.ui.theme.ObsidianBg
import com.pulsewave.music.ui.theme.ObsidianCard
import com.pulsewave.music.ui.theme.ObsidianSurface
import com.pulsewave.music.ui.theme.TextSecondary
import com.pulsewave.music.ui.theme.formatDurationSec
import com.pulsewave.music.ui.theme.formatMillis

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowPlayingFullScreen(
    playbackState: PlaybackUiState,
    lyricsState: LyricsState,
    dspState: AudioDspUiState,
    settings: AppSettings,
    isLiked: Boolean,
    isDownloaded: Boolean,
    downloadTask: DownloadTaskState?,
    onDismiss: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleLike: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onDownload: () -> Unit,
    onSelectQueueTrack: (SongTrack) -> Unit,
    onRemoveFromQueue: (String) -> Unit,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSetSleepMinutes: (Int?) -> Unit,
    onSetSleepEndOfTrack: (Boolean) -> Unit
) {
    val track = playbackState.currentTrack ?: return
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    var showLyricsInline by remember { mutableStateOf(false) }
    var showAudioStudioSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }

    var isScrubbing by remember { mutableStateOf(false) }
    var scrubFraction by remember { mutableFloatStateOf(0f) }

    val liveFraction = if (playbackState.durationMs > 0L) {
        (playbackState.positionMs.toFloat() / playbackState.durationMs.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        primary.copy(alpha = 0.42f),
                        secondary.copy(alpha = 0.18f),
                        ObsidianBg,
                        ObsidianBg
                    )
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 22.dp, vertical = 10.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = "Minimize",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                Surface(
                    color = Color.White.copy(alpha = 0.10f),
                    shape = RoundedCornerShape(50)
                ) {
                    val badgeText = when {
                        playbackState.sleepTimerRemainingSec != null ->
                            "${playbackState.streamInfoBadge} • ⏱ ${formatDurationSec(playbackState.sleepTimerRemainingSec)}"
                        playbackState.sleepStopAtTrackEnd ->
                            "${playbackState.streamInfoBadge} • ⏱ End of Song"
                        else -> playbackState.streamInfoBadge
                    }
                    Text(
                        text = badgeText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.92f),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp)
                    )
                }

                IconButton(
                    onClick = {
                        if (track.youtubeUrl.isNotBlank()) {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, "${track.title} - ${track.youtubeUrl}")
                            }
                            context.startActivity(Intent.createChooser(send, "Share Song"))
                        }
                    }
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share",
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Centerpiece: Cover Art + Live Spectrum OR Synced Lyrics
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                if (showLyricsInline) {
                    SyncedLyricsPanel(
                        lyricsState = lyricsState,
                        positionMs = playbackState.positionMs,
                        primaryColor = primary,
                        onSeekTo = onSeekTo
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        CoverArtwork(
                            url = track.thumbnailUrl,
                            cornerRadius = 28.dp,
                            modifier = Modifier
                                .fillMaxWidth(0.84f)
                                .aspectRatio(1f)
                                .shadow(28.dp, RoundedCornerShape(28.dp))
                        )
                        Spacer(modifier = Modifier.height(18.dp))
                        AnimatedSpectrumBars(
                            isPlaying = playbackState.isPlaying,
                            barCount = 26,
                            color = primary.copy(alpha = 0.88f),
                            modifier = Modifier
                                .fillMaxWidth(0.76f)
                                .height(28.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Track Title, Artist, Like & Add-to-Playlist
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color.White,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = track.artist,
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onAddToPlaylist) {
                    Icon(
                        imageVector = Icons.Rounded.PlaylistAdd,
                        contentDescription = "Add to Playlist",
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
                IconButton(onClick = onToggleLike) {
                    Icon(
                        imageVector = if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = "Like",
                        tint = if (isLiked) primary else Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }

            if (playbackState.errorMessage != null) {
                Text(
                    text = playbackState.errorMessage,
                    color = Color(0xFFFCA5A5),
                    fontSize = 12.sp,
                    maxLines = 2,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            // Seek Slider + Timestamps
            Slider(
                value = if (isScrubbing) scrubFraction else liveFraction,
                onValueChange = {
                    isScrubbing = true
                    scrubFraction = it
                },
                onValueChangeFinished = {
                    val targetMs = (scrubFraction * playbackState.durationMs.coerceAtLeast(1L)).toLong()
                    onSeekTo(targetMs)
                    isScrubbing = false
                },
                colors = SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.18f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                val shownPos = if (isScrubbing) {
                    (scrubFraction * playbackState.durationMs).toLong()
                } else playbackState.positionMs
                Text(formatMillis(shownPos), fontSize = 12.sp, color = TextSecondary)
                Text(formatMillis(playbackState.durationMs), fontSize = 12.sp, color = TextSecondary)
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Primary Playback Transport Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Rounded.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (playbackState.shuffleEnabled) primary else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(24.dp)
                    )
                }

                IconButton(onClick = onSkipPrevious, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(16.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(listOf(primary, secondary))
                        )
                        .clickable(onClick = onTogglePlayPause),
                    contentAlignment = Alignment.Center
                ) {
                    if (playbackState.isBuffering) {
                        CircularProgressIndicator(
                            color = Color.White,
                            strokeWidth = 3.dp,
                            modifier = Modifier.size(32.dp)
                        )
                    } else {
                        Icon(
                            imageVector = if (playbackState.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "Play/Pause",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                IconButton(onClick = onSkipNext, modifier = Modifier.size(52.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                }

                IconButton(onClick = onCycleRepeat) {
                    val repeatIcon = if (playbackState.repeatMode == RepeatModeState.ONE) {
                        Icons.Rounded.RepeatOne
                    } else {
                        Icons.Rounded.Repeat
                    }
                    val repeatActive = playbackState.repeatMode != RepeatModeState.OFF
                    Icon(
                        imageVector = repeatIcon,
                        contentDescription = "Repeat",
                        tint = if (repeatActive) primary else Color.White.copy(alpha = 0.65f),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Studio Feature Action Dock
            Surface(
                color = ObsidianCard.copy(alpha = 0.85f),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp, horizontal = 6.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    DockButton(
                        icon = Icons.Rounded.Lyrics,
                        label = "Lyrics",
                        active = showLyricsInline,
                        onClick = { showLyricsInline = !showLyricsInline }
                    )
                    DockButton(
                        icon = Icons.Rounded.Equalizer,
                        label = if (settings.playbackSpeed != 1f) "${settings.playbackSpeed}x EQ" else "EQ & FX",
                        active = settings.eqEnabled || settings.playbackSpeed != 1f,
                        onClick = { showAudioStudioSheet = true }
                    )
                    DockButton(
                        icon = Icons.Rounded.Bedtime,
                        label = "Sleep",
                        active = playbackState.sleepTimerRemainingSec != null || playbackState.sleepStopAtTrackEnd,
                        onClick = { showSleepSheet = true }
                    )
                    DockButton(
                        icon = if (isDownloaded || track.isOfflineReady) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                        label = when {
                            isDownloaded || track.isOfflineReady -> "Saved"
                            downloadTask != null && !downloadTask.isCompleted && !downloadTask.isFailed ->
                                "${downloadTask.progress.toInt()}%"
                            else -> "Offline"
                        },
                        active = isDownloaded || track.isOfflineReady,
                        onClick = onDownload
                    )
                    DockButton(
                        icon = Icons.Rounded.QueueMusic,
                        label = "Queue (${playbackState.queue.size})",
                        active = false,
                        onClick = { showQueueSheet = true }
                    )
                }
            }
        }
    }

    // 5-Band EQ + Bass Boost + Slowed/Reverb Speed & Pitch Studio Sheet
    if (showAudioStudioSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAudioStudioSheet = false },
            containerColor = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 32.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Pro Audio & Slowed+Reverb Studio", style = MaterialTheme.typography.titleLarge)
                    Switch(
                        checked = settings.eqEnabled,
                        onCheckedChange = { v -> onUpdateSettings { it.copy(eqEnabled = v) } }
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                Text("Speed & Pitch Vibe Presets", style = MaterialTheme.typography.labelMedium, color = primary)
                Spacer(modifier = Modifier.height(6.dp))

                val vibePresets = listOf(
                    Triple("Normal 1.0x", 1.0f, 1.0f),
                    Triple("Slowed Aesthetic", 0.85f, 0.86f),
                    Triple("Deep Slowed", 0.76f, 0.78f),
                    Triple("Nightcore 1.2x", 1.20f, 1.18f),
                    Triple("Fast 1.35x", 1.35f, 1.0f)
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    vibePresets.forEach { (label, spd, ptch) ->
                        val active = kotlin.math.abs(settings.playbackSpeed - spd) < 0.02f &&
                            kotlin.math.abs(settings.playbackPitch - ptch) < 0.02f
                        FilterChip(
                            selected = active,
                            onClick = {
                                onUpdateSettings { it.copy(playbackSpeed = spd, playbackPitch = ptch) }
                            },
                            label = { Text(label, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = primary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text("Speed: ${String.format("%.2fx", settings.playbackSpeed)}", fontSize = 12.sp)
                Slider(
                    value = settings.playbackSpeed,
                    onValueChange = { v -> onUpdateSettings { it.copy(playbackSpeed = v) } },
                    valueRange = 0.5f..1.75f
                )
                Text("Pitch: ${String.format("%.2fx", settings.playbackPitch)}", fontSize = 12.sp)
                Slider(
                    value = settings.playbackPitch,
                    onValueChange = { v -> onUpdateSettings { it.copy(playbackPitch = v) } },
                    valueRange = 0.5f..1.75f
                )

                Spacer(modifier = Modifier.height(10.dp))
                Text("Equalizer Presets", style = MaterialTheme.typography.labelMedium, color = primary)
                Spacer(modifier = Modifier.height(6.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    dspState.presets.forEach { preset ->
                        FilterChip(
                            selected = settings.eqPresetName == preset,
                            onClick = { onUpdateSettings { it.copy(eqPresetName = preset) } },
                            label = { Text(preset, fontSize = 12.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = primary,
                                selectedLabelColor = Color.White
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Text("Sub-Bass Boost: ${(settings.bassBoostStrength / 10)}%", fontSize = 12.sp)
                Slider(
                    value = settings.bassBoostStrength.toFloat(),
                    onValueChange = { v -> onUpdateSettings { it.copy(bassBoostStrength = v.toInt()) } },
                    valueRange = 0f..1000f
                )

                Text("3D Surround Virtualizer: ${(settings.virtualizerStrength / 10)}%", fontSize = 12.sp)
                Slider(
                    value = settings.virtualizerStrength.toFloat(),
                    onValueChange = { v -> onUpdateSettings { it.copy(virtualizerStrength = v.toInt()) } },
                    valueRange = 0f..1000f
                )

                Text("Loudness Gain Booster: +${settings.loudnessGainMb / 100} dB", fontSize = 12.sp)
                Slider(
                    value = settings.loudnessGainMb.toFloat(),
                    onValueChange = { v -> onUpdateSettings { it.copy(loudnessGainMb = v.toInt()) } },
                    valueRange = 0f..1200f
                )

                if (dspState.bands.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("Hardware Frequency Bands", style = MaterialTheme.typography.labelMedium, color = primary)
                    dspState.bands.forEach { band ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(band.label, fontSize = 12.sp, modifier = Modifier.width(54.dp))
                            Slider(
                                value = band.levelMb.toFloat(),
                                onValueChange = { newMb ->
                                    val currentLevels = dspState.bands.map { b ->
                                        if (b.index == band.index) newMb.toInt() else b.levelMb
                                    }
                                    onUpdateSettings {
                                        it.copy(
                                            eqPresetName = "Custom",
                                            customBandLevelsMb = currentLevels
                                        )
                                    }
                                },
                                valueRange = dspState.minLevelMb.toFloat()..dspState.maxLevelMb.toFloat(),
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${band.levelMb / 100}dB",
                                fontSize = 11.sp,
                                color = TextSecondary,
                                modifier = Modifier.width(42.dp),
                                textAlign = TextAlign.End
                            )
                        }
                    }
                }
            }
        }
    }

    // Sleep Timer Sheet
    if (showSleepSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSleepSheet = false },
            containerColor = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Sleep Timer", style = MaterialTheme.typography.titleLarge)
                Text("Automatically pause music & ambient soundscapes when you fall asleep", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(6.dp))

                val options = listOf(
                    "Turn Off Sleep Timer" to null,
                    "15 Minutes" to 15,
                    "30 Minutes" to 30,
                    "45 Minutes" to 45,
                    "60 Minutes" to 60
                )
                options.forEach { (label, mins) ->
                    Surface(
                        color = ObsidianCard,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                onSetSleepMinutes(mins)
                                showSleepSheet = false
                            }
                    ) {
                        Text(label, modifier = Modifier.padding(14.dp), fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    color = if (playbackState.sleepStopAtTrackEnd) primary.copy(alpha = 0.25f) else ObsidianCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable {
                            onSetSleepEndOfTrack(!playbackState.sleepStopAtTrackEnd)
                            showSleepSheet = false
                        }
                ) {
                    Text(
                        text = "Stop at End of Current Song",
                        modifier = Modifier.padding(14.dp),
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    // Up Next Queue Sheet
    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            containerColor = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
            ) {
                Text("Up Next Queue (${playbackState.queue.size})", style = MaterialTheme.typography.titleLarge)
                Text("Auto-Radio adds related YouTube tracks automatically", style = MaterialTheme.typography.bodySmall)
                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.height(380.dp)
                ) {
                    items(playbackState.queue, key = { "q_${it.id}" }) { item ->
                        val active = item.id == track.id
                        Surface(
                            color = if (active) primary.copy(alpha = 0.2f) else ObsidianCard,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .clickable {
                                    onSelectQueueTrack(item)
                                    showQueueSheet = false
                                }
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverArtwork(
                                    url = item.thumbnailUrl,
                                    modifier = Modifier.size(44.dp),
                                    cornerRadius = 10.dp
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        color = if (active) primary else Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(item.artist, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                                }
                                if (!active) {
                                    IconButton(onClick = { onRemoveFromQueue(item.id) }) {
                                        Icon(Icons.Rounded.Close, contentDescription = "Remove", tint = TextSecondary, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncedLyricsPanel(
    lyricsState: LyricsState,
    positionMs: Long,
    primaryColor: Color,
    onSeekTo: (Long) -> Unit
) {
    val listState = rememberLazyListState()
    val activeIdx = remember(lyricsState.lines, positionMs) {
        lyricsState.lines.indexOfLast { it.timeMs <= positionMs + 300L }.coerceAtLeast(0)
    }

    LaunchedEffect(activeIdx, lyricsState.isSynced) {
        if (lyricsState.isSynced && lyricsState.lines.isNotEmpty()) {
            runCatching {
                listState.animateScrollToItem((activeIdx - 2).coerceAtLeast(0))
            }
        }
    }

    Surface(
        color = Color.Black.copy(alpha = 0.36f),
        shape = RoundedCornerShape(24.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        when {
            lyricsState.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = primaryColor)
                }
            }
            lyricsState.isSynced && lyricsState.lines.isNotEmpty() -> {
                LazyColumn(
                    state = listState,
                    contentPadding = PaddingValues(vertical = 48.dp, horizontal = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    itemsIndexed(lyricsState.lines) { index, line ->
                        val isCurrent = index == activeIdx
                        Text(
                            text = line.text,
                            fontSize = if (isCurrent) 21.sp else 16.sp,
                            fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.Medium,
                            color = if (isCurrent) Color.White else Color.White.copy(alpha = 0.42f),
                            lineHeight = 27.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onSeekTo(line.timeMs) }
                                .padding(vertical = 2.dp)
                        )
                    }
                }
            }
            !lyricsState.plainText.isNullOrBlank() -> {
                LazyColumn(
                    contentPadding = PaddingValues(22.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    item {
                        Text(
                            text = lyricsState.plainText,
                            fontSize = 16.sp,
                            lineHeight = 26.sp,
                            color = Color.White.copy(alpha = 0.9f)
                        )
                    }
                }
            }
            else -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = lyricsState.error ?: "Lyrics unavailable for this track",
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun DockButton(
    icon: ImageVector,
    label: String,
    active: Boolean,
    onClick: () -> Unit
) {
    val tint = if (active) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.82f)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(21.dp))
        Spacer(modifier = Modifier.height(2.dp))
        Text(label, fontSize = 10.sp, color = tint, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}
