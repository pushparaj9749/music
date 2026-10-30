package com.pulsewave.music.ui.screens

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.pulsewave.music.ui.components.SongRowCard
import com.pulsewave.music.ui.theme.ObsidianCard
import com.pulsewave.music.ui.theme.TextSecondary

@Composable
fun LibraryScreen(
    localState: LocalAppState,
    playbackState: PlaybackUiState,
    downloadTasks: Map<String, DownloadTaskState>,
    onPlayTrack: (SongTrack, List<SongTrack>) -> Unit,
    onToggleLike: (SongTrack) -> Unit,
    onPlayNext: (SongTrack) -> Unit,
    onAddToQueue: (SongTrack) -> Unit,
    onAddToPlaylist: (SongTrack) -> Unit,
    onDownloadTrack: (SongTrack) -> Unit,
    onRemoveDownloadedTrack: (String) -> Unit,
    onCreatePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onRemoveTrackFromPlaylist: (String, String) -> Unit,
    onClearHistory: () -> Unit,
    onOpenPasteUrlDialog: () -> Unit
) {
    var selectedSection by remember { mutableIntStateOf(0) }
    var openPlaylistId by remember { mutableStateOf<String?>(null) }
    var showCreateDialog by remember { mutableStateOf(false) }

    val primary = MaterialTheme.colorScheme.primary
    val likedIds = remember(localState.likedSongs) { localState.likedSongs.map { it.id }.toSet() }
    val downloadedIds = remember(localState.downloadedSongs) { localState.downloadedSongs.map { it.id }.toSet() }

    val activePlaylist = localState.playlists.firstOrNull { it.id == openPlaylistId }

    if (activePlaylist != null) {
        // Inside a specific playlist
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { openPlaylistId = null }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(activePlaylist.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
                    Text(
                        text = "${activePlaylist.tracks.size} songs ${if (activePlaylist.description.isNotBlank()) "• ${activePlaylist.description}" else ""}",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1
                    )
                }
                IconButton(onClick = {
                    onDeletePlaylist(activePlaylist.id)
                    openPlaylistId = null
                }) {
                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete Playlist", tint = TextSecondary)
                }
            }

            if (activePlaylist.tracks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { onPlayTrack(activePlaylist.tracks.first(), activePlaylist.tracks) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Play All")
                    }
                    FilledTonalButton(
                        onClick = {
                            val shuf = activePlaylist.tracks.shuffled()
                            onPlayTrack(shuf.first(), shuf)
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Rounded.Shuffle, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Shuffle")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            LazyColumn(
                contentPadding = PaddingValues(bottom = 140.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                if (activePlaylist.tracks.isEmpty()) {
                    item {
                        EmptyHintBox("Playlist is empty. Tap the 3-dot menu on any song to add it here!")
                    }
                }
                items(activePlaylist.tracks, key = { "plitem_${it.id}" }) { song ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SongRowCard(
                            track = song,
                            isActive = playbackState.currentTrack?.id == song.id,
                            isPlaying = playbackState.isPlaying,
                            isLiked = song.id in likedIds,
                            isDownloaded = song.id in downloadedIds,
                            downloadTask = downloadTasks[song.id],
                            onClick = { onPlayTrack(song, activePlaylist.tracks) },
                            onToggleLike = { onToggleLike(song) },
                            onPlayNext = { onPlayNext(song) },
                            onAddToQueue = { onAddToQueue(song) },
                            onAddToPlaylist = { onAddToPlaylist(song) },
                            onDownload = { onDownloadTrack(song) },
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemoveTrackFromPlaylist(activePlaylist.id, song.id) }) {
                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "Remove", tint = TextSecondary)
                        }
                    }
                }
            }
        }
        return
    }

    val sections = listOf(
        "Liked (${localState.likedSongs.size})",
        "Offline (${localState.downloadedSongs.size + CatalogPresets.builtInStudioTracks.size})",
        "Playlists (${localState.playlists.size})",
        "History (${localState.recentlyPlayed.size})"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = 14.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Your Library", style = MaterialTheme.typography.headlineMedium)
                Text("Stored 100% locally • Zero account required", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(
                    onClick = onOpenPasteUrlDialog,
                    label = { Text("Import YT", fontSize = 12.sp) },
                    leadingIcon = { Icon(Icons.Rounded.Link, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(sections.indices.toList()) { idx ->
                FilterChip(
                    selected = selectedSection == idx,
                    onClick = { selectedSection = idx },
                    label = { Text(sections[idx], fontWeight = FontWeight.SemiBold) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = primary,
                        selectedLabelColor = Color.White
                    )
                )
            }
        }

        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 140.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            when (selectedSection) {
                0 -> {
                    if (localState.likedSongs.isEmpty()) {
                        item { EmptyHintBox("Tap the Heart icon on any YouTube song to save it to Liked Songs.") }
                    } else {
                        item {
                            PlayShuffleBar(
                                onPlayAll = { onPlayTrack(localState.likedSongs.first(), localState.likedSongs) },
                                onShuffle = {
                                    val s = localState.likedSongs.shuffled()
                                    onPlayTrack(s.first(), s)
                                }
                            )
                        }
                        items(localState.likedSongs, key = { "liked_${it.id}" }) { song ->
                            SongRowCard(
                                track = song,
                                isActive = playbackState.currentTrack?.id == song.id,
                                isPlaying = playbackState.isPlaying,
                                isLiked = true,
                                isDownloaded = song.id in downloadedIds,
                                downloadTask = downloadTasks[song.id],
                                onClick = { onPlayTrack(song, localState.likedSongs) },
                                onToggleLike = { onToggleLike(song) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToQueue = { onAddToQueue(song) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onDownload = { onDownloadTrack(song) }
                            )
                        }
                    }
                }

                1 -> {
                    val activeDownloads = downloadTasks.values.filter { !it.isCompleted }
                    if (activeDownloads.isNotEmpty()) {
                        item {
                            Text("Active Downloads", style = MaterialTheme.typography.titleLarge)
                        }
                        items(activeDownloads, key = { "dl_act_${it.trackId}" }) { dl ->
                            Surface(
                                color = ObsidianCard,
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(dl.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                                    Text(dl.statusText, style = MaterialTheme.typography.bodySmall)
                                    Spacer(modifier = Modifier.height(6.dp))
                                    LinearProgressIndicator(
                                        progress = { (dl.progress / 100f).coerceIn(0f, 1f) },
                                        color = primary,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }

                    val allOffline = localState.downloadedSongs + CatalogPresets.builtInStudioTracks
                    item {
                        PlayShuffleBar(
                            onPlayAll = { onPlayTrack(allOffline.first(), allOffline) },
                            onShuffle = {
                                val s = allOffline.shuffled()
                                onPlayTrack(s.first(), s)
                            }
                        )
                    }

                    items(allOffline, key = { "off_${it.id}" }) { song ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SongRowCard(
                                track = song,
                                isActive = playbackState.currentTrack?.id == song.id,
                                isPlaying = playbackState.isPlaying,
                                isLiked = song.id in likedIds,
                                isDownloaded = true,
                                downloadTask = null,
                                onClick = { onPlayTrack(song, allOffline) },
                                onToggleLike = { onToggleLike(song) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToQueue = { onAddToQueue(song) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onDownload = {},
                                modifier = Modifier.weight(1f)
                            )
                            if (song.rawResName == null) {
                                IconButton(onClick = { onRemoveDownloadedTrack(song.id) }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "Delete File", tint = TextSecondary)
                                }
                            }
                        }
                    }
                }

                2 -> {
                    item {
                        Button(
                            onClick = { showCreateDialog = true },
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Create New Playlist")
                        }
                    }
                    if (localState.playlists.isEmpty()) {
                        item {
                            EmptyHintBox("Create a custom playlist or import any YouTube playlist link!")
                        }
                    }
                    items(localState.playlists, key = { "userpl_${it.id}" }) { pl ->
                        Surface(
                            color = ObsidianCard,
                            shape = RoundedCornerShape(16.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { openPlaylistId = pl.id }
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CoverArtwork(
                                    url = pl.coverUrl,
                                    modifier = Modifier.size(58.dp),
                                    cornerRadius = 12.dp
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(pl.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        text = "${pl.tracks.size} songs ${if (pl.description.isNotBlank()) "• ${pl.description}" else ""}",
                                        style = MaterialTheme.typography.bodySmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                }

                3 -> {
                    if (localState.recentlyPlayed.isEmpty()) {
                        item { EmptyHintBox("Songs you play will appear here.") }
                    } else {
                        item {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("Listening History", style = MaterialTheme.typography.titleLarge)
                                TextButton(onClick = onClearHistory) {
                                    Text("Clear All")
                                }
                            }
                        }
                        items(localState.recentlyPlayed, key = { "hist_song_${it.id}" }) { song ->
                            SongRowCard(
                                track = song,
                                isActive = playbackState.currentTrack?.id == song.id,
                                isPlaying = playbackState.isPlaying,
                                isLiked = song.id in likedIds,
                                isDownloaded = song.id in downloadedIds,
                                downloadTask = downloadTasks[song.id],
                                onClick = { onPlayTrack(song, localState.recentlyPlayed) },
                                onToggleLike = { onToggleLike(song) },
                                onPlayNext = { onPlayNext(song) },
                                onAddToQueue = { onAddToQueue(song) },
                                onAddToPlaylist = { onAddToPlaylist(song) },
                                onDownload = { onDownloadTrack(song) }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        var name by remember { mutableStateOf("") }
        var desc by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("New Playlist") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Playlist Name") },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = desc,
                        onValueChange = { desc = it },
                        label = { Text("Description (optional)") },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    onCreatePlaylist(name, desc)
                    showCreateDialog = false
                }) {
                    Text("Create")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PlayShuffleBar(onPlayAll: () -> Unit, onShuffle: () -> Unit) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
    ) {
        Button(
            onClick = onPlayAll,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Play All")
        }
        FilledTonalButton(
            onClick = onShuffle,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(Icons.Rounded.Shuffle, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Shuffle")
        }
    }
}

@Composable
private fun EmptyHintBox(text: String) {
    Surface(
        color = ObsidianCard.copy(alpha = 0.7f),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
    ) {
        Box(
            modifier = Modifier.padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = text, style = MaterialTheme.typography.bodySmall)
        }
    }
}
