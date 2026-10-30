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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsewave.music.data.CatalogPresets
import com.pulsewave.music.model.DownloadTaskState
import com.pulsewave.music.model.LocalAppState
import com.pulsewave.music.model.SongTrack
import com.pulsewave.music.playback.PlaybackUiState
import com.pulsewave.music.ui.components.CoverArtwork
import com.pulsewave.music.ui.components.MoodVisualCard
import com.pulsewave.music.ui.components.QuickPickCompactCard
import com.pulsewave.music.ui.components.SongRowCard
import com.pulsewave.music.ui.theme.ObsidianCard
import com.pulsewave.music.ui.theme.ObsidianSurface
import com.pulsewave.music.ui.theme.TextSecondary
import com.pulsewave.music.viewmodel.HomeUiState
import java.util.Calendar

@Composable
fun HomeScreen(
    homeState: HomeUiState,
    localState: LocalAppState,
    playbackState: PlaybackUiState,
    downloadTasks: Map<String, DownloadTaskState>,
    onPlayTrack: (SongTrack, List<SongTrack>) -> Unit,
    onToggleLike: (SongTrack) -> Unit,
    onPlayNext: (SongTrack) -> Unit,
    onAddToQueue: (SongTrack) -> Unit,
    onAddToPlaylist: (SongTrack) -> Unit,
    onDownloadTrack: (SongTrack) -> Unit,
    onOpenMood: (com.pulsewave.music.model.MoodCategory) -> Unit,
    onCloseMood: () -> Unit,
    onRefresh: () -> Unit,
    onOpenPasteUrlDialog: () -> Unit,
    onOpenAmbientStudio: () -> Unit,
    onPlayStudioOfflineBeat: (Int) -> Unit
) {
    val greeting = remember {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good Morning"
            in 12..16 -> "Good Afternoon"
            in 17..21 -> "Good Evening"
            else -> "Late Night Vibes"
        }
    }

    val likedIds = remember(localState.likedSongs) { localState.likedSongs.map { it.id }.toSet() }
    val downloadedIds = remember(localState.downloadedSongs) { localState.downloadedSongs.map { it.id }.toSet() }
    val primary = MaterialTheme.colorScheme.primary

    LazyColumn(
        contentPadding = PaddingValues(bottom = 135.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        // Header with Aurora Glow
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                primary.copy(alpha = 0.24f),
                                Color.Transparent
                            )
                        )
                    )
                    .padding(horizontal = 18.dp, vertical = 18.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Bolt,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "AD-FREE YT AUDIO • NO LOGIN",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 1.sp,
                                color = primary
                            )
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = greeting,
                            style = MaterialTheme.typography.headlineLarge
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        IconButton(
                            onClick = onOpenPasteUrlDialog,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(ObsidianCard)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Link,
                                contentDescription = "Paste YT Link",
                                tint = Color.White
                            )
                        }
                        IconButton(
                            onClick = onRefresh,
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(ObsidianCard)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Refresh,
                                contentDescription = "Refresh",
                                tint = Color.White
                            )
                        }
                    }
                }
            }
        }

        // Instant Offline Studio Hero Card
        item {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color.Transparent,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    primary.copy(alpha = 0.36f),
                                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.30f),
                                    ObsidianCard
                                )
                            ),
                            shape = RoundedCornerShape(22.dp)
                        )
                        .padding(16.dp)
                ) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.AutoAwesome,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "PulseWave Studio • Instant Offline Ready",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.9f)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Stream Millions of YT Songs or Mix Rain & Vinyl Underneath",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onPlayStudioOfflineBeat(0) },
                                colors = ButtonDefaults.buttonColors(containerColor = primary),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Lo-Fi Beat", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                            FilledTonalButton(
                                onClick = onOpenAmbientStudio,
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Icon(Icons.Rounded.GraphicEq, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(5.dp))
                                Text("Rain & Vinyl Mixer", fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }

        // Recently Played Carousel
        if (localState.recentlyPlayed.isNotEmpty()) {
            item {
                SectionHeader(title = "Continue Listening", subtitle = "Pick up right where you left off")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(localState.recentlyPlayed.take(12), key = { "recent_${it.id}" }) { track ->
                        Column(
                            modifier = Modifier
                                .width(132.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .clickable { onPlayTrack(track, localState.recentlyPlayed) }
                        ) {
                            CoverArtwork(
                                url = track.thumbnailUrl,
                                modifier = Modifier.size(132.dp),
                                cornerRadius = 16.dp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        // Moods & Genres 2-Column Visual Grid
        item {
            SectionHeader(title = "Moods & Desi Vibes", subtitle = "Tap any vibe for instant curated YT Music")
            val moods = CatalogPresets.moodCategories
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (rowMoods in moods.chunked(2)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        for (mood in rowMoods) {
                            MoodVisualCard(
                                mood = mood,
                                onClick = { onOpenMood(mood) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowMoods.size == 1) {
                            Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Loading / Error state for live YouTube feed
        if (homeState.isLoading) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 36.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = primary)
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Fetching Trending YouTube Music...",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }

        if (homeState.error != null) {
            item {
                Surface(
                    color = ObsidianCard,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = homeState.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        }

        // Quick Picks 2-Column Compact Cards
        if (homeState.quickPicks.isNotEmpty()) {
            item {
                SectionHeader(title = "Quick Picks", subtitle = "One-tap instant radio")
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (pair in homeState.quickPicks.take(8).chunked(2)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            for (song in pair) {
                                QuickPickCompactCard(
                                    track = song,
                                    isActive = playbackState.currentTrack?.id == song.id,
                                    onClick = { onPlayTrack(song, homeState.quickPicks) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Trending Chartbusters List
        if (homeState.trendingHits.isNotEmpty()) {
            item {
                SectionHeader(
                    title = "Trending Chartbusters",
                    subtitle = "Top YouTube Music hits right now"
                )
            }
            items(homeState.trendingHits, key = { "trend_${it.id}" }) { song ->
                SongRowCard(
                    track = song,
                    isActive = playbackState.currentTrack?.id == song.id,
                    isPlaying = playbackState.isPlaying,
                    isLiked = song.id in likedIds,
                    isDownloaded = song.id in downloadedIds,
                    downloadTask = downloadTasks[song.id],
                    onClick = { onPlayTrack(song, homeState.trendingHits) },
                    onToggleLike = { onToggleLike(song) },
                    onPlayNext = { onPlayNext(song) },
                    onAddToQueue = { onAddToQueue(song) },
                    onAddToPlaylist = { onAddToPlaylist(song) },
                    onDownload = { onDownloadTrack(song) },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        // Chill & Lo-Fi Horizontal Carousel
        if (homeState.chillVibes.isNotEmpty()) {
            item {
                SectionHeader(title = "Late Night Lo-Fi & Acoustic", subtitle = "Unwind with slowed & soulful mixes")
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(homeState.chillVibes, key = { "chill_${it.id}" }) { track ->
                        Column(
                            modifier = Modifier
                                .width(148.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onPlayTrack(track, homeState.chillVibes) }
                        ) {
                            CoverArtwork(
                                url = track.thumbnailUrl,
                                modifier = Modifier.size(148.dp),
                                cornerRadius = 16.dp
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleMedium,
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }

    // Mood Category Bottom Sheet
    val activeMood = homeState.activeMoodCategory
    if (activeMood != null) {
        ModalBottomSheet(
            onDismissRequest = onCloseMood,
            containerColor = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CoverArtwork(
                        url = activeMood.assetCover,
                        modifier = Modifier.size(64.dp),
                        cornerRadius = 16.dp
                    )
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = activeMood.title, style = MaterialTheme.typography.headlineMedium)
                        Text(text = activeMood.subtitle, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = onCloseMood) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close", tint = TextSecondary)
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (homeState.isMoodLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = primary)
                    }
                } else if (homeState.activeMoodTracks.isNotEmpty()) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Button(
                            onClick = {
                                onPlayTrack(homeState.activeMoodTracks.first(), homeState.activeMoodTracks)
                                onCloseMood()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Play Mix")
                        }
                        FilledTonalButton(
                            onClick = {
                                val shuffled = homeState.activeMoodTracks.shuffled()
                                onPlayTrack(shuffled.first(), shuffled)
                                onCloseMood()
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Rounded.Shuffle, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Shuffle")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.height(360.dp)
                    ) {
                        items(homeState.activeMoodTracks, key = { "mood_${it.id}" }) { song ->
                            SongRowCard(
                                track = song,
                                isActive = playbackState.currentTrack?.id == song.id,
                                isPlaying = playbackState.isPlaying,
                                isLiked = song.id in likedIds,
                                isDownloaded = song.id in downloadedIds,
                                downloadTask = downloadTasks[song.id],
                                onClick = {
                                    onPlayTrack(song, homeState.activeMoodTracks)
                                    onCloseMood()
                                },
                                onToggleLike = { onToggleLike(song) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToQueue = { onAddToQueue(song) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onDownload = { onDownloadTrack(song) }
                            )
                        }
                    }
                } else {
                    Text(
                        text = "Unable to load tracks right now. Check connection and try again.",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, subtitle: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 10.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleLarge)
        Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
    }
}
