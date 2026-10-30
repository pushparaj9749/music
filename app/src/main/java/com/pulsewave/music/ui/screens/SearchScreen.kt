package com.pulsewave.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NorthWest
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TrendingUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsewave.music.data.CatalogPresets
import com.pulsewave.music.model.DownloadTaskState
import com.pulsewave.music.model.LocalAppState
import com.pulsewave.music.model.PlaylistPreview
import com.pulsewave.music.model.SearchFilter
import com.pulsewave.music.model.SongTrack
import com.pulsewave.music.playback.PlaybackUiState
import com.pulsewave.music.ui.components.CoverArtwork
import com.pulsewave.music.ui.components.SongRowCard
import com.pulsewave.music.ui.theme.ObsidianCard
import com.pulsewave.music.ui.theme.TextSecondary
import com.pulsewave.music.viewmodel.SearchUiState

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    searchState: SearchUiState,
    localState: LocalAppState,
    playbackState: PlaybackUiState,
    downloadTasks: Map<String, DownloadTaskState>,
    onQueryChange: (String) -> Unit,
    onSubmitSearch: (String?, SearchFilter?) -> Unit,
    onRemoveHistoryItem: (String) -> Unit,
    onPlayTrack: (SongTrack, List<SongTrack>) -> Unit,
    onOpenPlaylistPreview: (PlaylistPreview) -> Unit,
    onToggleLike: (SongTrack) -> Unit,
    onPlayNext: (SongTrack) -> Unit,
    onAddToQueue: (SongTrack) -> Unit,
    onAddToPlaylist: (SongTrack) -> Unit,
    onDownloadTrack: (SongTrack) -> Unit,
    onOpenPasteUrlDialog: () -> Unit
) {
    val focusManager = LocalFocusManager.current
    val primary = MaterialTheme.colorScheme.primary
    val likedIds = remember(localState.likedSongs) { localState.likedSongs.map { it.id }.toSet() }
    val downloadedIds = remember(localState.downloadedSongs) { localState.downloadedSongs.map { it.id }.toSet() }

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
            Text(
                text = "Explore YouTube Music",
                style = MaterialTheme.typography.headlineMedium
            )
            AssistChip(
                onClick = onOpenPasteUrlDialog,
                label = { Text("Paste Link", fontSize = 12.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Rounded.Link,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                }
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Search input field
        OutlinedTextField(
            value = searchState.query,
            onValueChange = onQueryChange,
            placeholder = { Text("Songs, artists, albums or paste YT URL...", color = TextSecondary) },
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Rounded.Search, contentDescription = null, tint = primary)
            },
            trailingIcon = {
                if (searchState.query.isNotEmpty()) {
                    IconButton(onClick = { onQueryChange("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "Clear", tint = TextSecondary)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(
                onSearch = {
                    focusManager.clearFocus()
                    onSubmitSearch(null, null)
                }
            ),
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = ObsidianCard,
                unfocusedContainerColor = ObsidianCard,
                focusedBorderColor = primary,
                unfocusedBorderColor = Color.White.copy(alpha = 0.08f)
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        // Filter Chips
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(SearchFilter.entries) { filter ->
                val selected = searchState.selectedFilter == filter
                FilterChip(
                    selected = selected,
                    onClick = {
                        focusManager.clearFocus()
                        onSubmitSearch(searchState.query.ifBlank { null }, filter)
                    },
                    label = { Text(filter.label, fontWeight = FontWeight.SemiBold) },
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
            // Live Autocomplete Suggestions
            if (searchState.suggestions.isNotEmpty()) {
                items(searchState.suggestions, key = { "sug_$it" }) { suggestion ->
                    Surface(
                        color = ObsidianCard.copy(alpha = 0.65f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                focusManager.clearFocus()
                                onSubmitSearch(suggestion, null)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = suggestion,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                imageVector = Icons.Rounded.NorthWest,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (searchState.isSearching) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = primary)
                            Spacer(modifier = Modifier.height(10.dp))
                            Text("Searching YouTube...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            if (searchState.error != null && !searchState.isSearching) {
                item {
                    Text(
                        text = searchState.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(vertical = 18.dp)
                    )
                }
            }

            // Song / Video / Album results
            if (!searchState.isSearching && searchState.trackResults.isNotEmpty()) {
                items(searchState.trackResults, key = { "sr_${it.id}" }) { song ->
                    SongRowCard(
                        track = song,
                        isActive = playbackState.currentTrack?.id == song.id,
                        isPlaying = playbackState.isPlaying,
                        isLiked = song.id in likedIds,
                        isDownloaded = song.id in downloadedIds,
                        downloadTask = downloadTasks[song.id],
                        onClick = {
                            focusManager.clearFocus()
                            onPlayTrack(song, searchState.trackResults)
                        },
                        onToggleLike = { onToggleLike(song) },
                        onPlayNext = { onPlayNext(song) },
                        onAddToQueue = { onAddToQueue(song) },
                        onAddToPlaylist = { onAddToPlaylist(song) },
                        onDownload = { onDownloadTrack(song) }
                    )
                }
            }

            // Playlist results
            if (!searchState.isSearching && searchState.playlistResults.isNotEmpty()) {
                items(searchState.playlistResults, key = { "pl_${it.id}" }) { pl ->
                    Surface(
                        color = ObsidianCard,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .clickable { onOpenPlaylistPreview(pl) }
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CoverArtwork(
                                url = pl.thumbnailUrl,
                                modifier = Modifier.size(60.dp),
                                cornerRadius = 12.dp
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = pl.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${pl.uploader} • ${if (pl.streamCount > 0) "${pl.streamCount} songs" else "Playlist"}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                            Icon(
                                imageVector = Icons.Rounded.PlaylistPlay,
                                contentDescription = null,
                                tint = primary,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }
                }
            }

            // Empty search state: show Recent Searches + Trending Artist Chips
            if (!searchState.isSearching && searchState.trackResults.isEmpty() && searchState.playlistResults.isEmpty()) {
                if (localState.searchHistory.isNotEmpty()) {
                    item {
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("Recent Searches", style = MaterialTheme.typography.titleLarge)
                    }
                    items(localState.searchHistory.take(8), key = { "hist_$it" }) { item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    focusManager.clearFocus()
                                    onSubmitSearch(item, null)
                                }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.History,
                                contentDescription = null,
                                tint = TextSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = item,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { onRemoveHistoryItem(item) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.Clear,
                                    contentDescription = "Remove",
                                    tint = TextSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.TrendingUp,
                            contentDescription = null,
                            tint = primary,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Popular Artists & Mixes", style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CatalogPresets.quickSearchChips.forEach { chip ->
                            AssistChip(
                                onClick = {
                                    focusManager.clearFocus()
                                    onSubmitSearch(chip, SearchFilter.MUSIC_SONGS)
                                },
                                label = { Text(chip) }
                            )
                        }
                    }
                }
            }
        }
    }
}
