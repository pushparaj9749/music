package com.pulsewave.music

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.pulsewave.music.model.AccentPreset
import com.pulsewave.music.model.SongTrack
import com.pulsewave.music.ui.components.MiniPlayerBar
import com.pulsewave.music.ui.screens.AmbientStudioScreen
import com.pulsewave.music.ui.screens.HomeScreen
import com.pulsewave.music.ui.screens.LibraryScreen
import com.pulsewave.music.ui.screens.NowPlayingFullScreen
import com.pulsewave.music.ui.screens.SearchScreen
import com.pulsewave.music.ui.screens.SettingsScreen
import com.pulsewave.music.ui.theme.ObsidianBg
import com.pulsewave.music.ui.theme.ObsidianCard
import com.pulsewave.music.ui.theme.ObsidianSurface
import com.pulsewave.music.ui.theme.PulseWaveTheme
import com.pulsewave.music.ui.theme.TextSecondary
import com.pulsewave.music.viewmodel.MusicViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MusicViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIncomingYoutubeIntent(intent)

        setContent {
            val localState by viewModel.localState.collectAsStateWithLifecycle()
            val dynamicColor by viewModel.dynamicTrackColor.collectAsStateWithLifecycle()
            val accent = AccentPreset.fromName(localState.settings.accentName)

            PulseWaveTheme(
                accentPreset = accent,
                dynamicTrackColor = dynamicColor,
                useDynamicCover = localState.settings.dynamicCoverTheme
            ) {
                PulseWaveRootApp(viewModel = viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIncomingYoutubeIntent(intent)
    }

    private fun handleIncomingYoutubeIntent(intent: Intent?) {
        if (intent == null) return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val shared = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!shared.isNullOrBlank()) {
                    viewModel.importYoutubeUrlOrShareText(shared)
                }
            }
            Intent.ACTION_VIEW -> {
                val url = intent.dataString
                if (!url.isNullOrBlank()) {
                    viewModel.importYoutubeUrlOrShareText(url)
                }
            }
        }
    }
}

@Composable
private fun PulseWaveRootApp(viewModel: MusicViewModel) {
    val homeState by viewModel.homeState.collectAsStateWithLifecycle()
    val searchState by viewModel.searchState.collectAsStateWithLifecycle()
    val localState by viewModel.localState.collectAsStateWithLifecycle()
    val playbackState by viewModel.playbackState.collectAsStateWithLifecycle()
    val lyricsState by viewModel.lyricsState.collectAsStateWithLifecycle()
    val dspState by viewModel.dspState.collectAsStateWithLifecycle()
    val ambientLevels by viewModel.ambientLevels.collectAsStateWithLifecycle()
    val activeAmbientPreset by viewModel.activeAmbientPreset.collectAsStateWithLifecycle()
    val downloadTasks by viewModel.downloadTasks.collectAsStateWithLifecycle()
    val ytDlpStatus by viewModel.ytDlpStatus.collectAsStateWithLifecycle()
    val toastMsg by viewModel.toastMessage.collectAsStateWithLifecycle()

    var currentTab by rememberSaveable { mutableIntStateOf(0) }
    var fullPlayerOpen by rememberSaveable { mutableStateOf(false) }
    var trackForPlaylistSheet by remember { mutableStateOf<SongTrack?>(null) }
    var showPasteLinkDialog by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Request notification permission for lockscreen media controls on Android 13+
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        val notifLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) {}
        LaunchedEffect(Unit) {
            runCatching { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    LaunchedEffect(toastMsg) {
        val m = toastMsg ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(m)
        viewModel.consumeToast()
    }

    BackHandler(enabled = fullPlayerOpen) {
        fullPlayerOpen = false
    }

    val currentTrack = playbackState.currentTrack
    val isCurrentLiked = remember(currentTrack, localState.likedSongs) {
        currentTrack != null && localState.likedSongs.any { it.id == currentTrack.id }
    }
    val isCurrentDownloaded = remember(currentTrack, localState.downloadedSongs) {
        currentTrack != null && localState.downloadedSongs.any { it.id == currentTrack.id }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = ObsidianBg,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                Column {
                    if (currentTrack != null) {
                        MiniPlayerBar(
                            playbackState = playbackState,
                            isLiked = isCurrentLiked,
                            onToggleLike = { viewModel.localStore.toggleLike(currentTrack) },
                            onTogglePlayPause = { viewModel.playerBridge.togglePlayPause() },
                            onSkipNext = { viewModel.playerBridge.skipNext() },
                            onOpenFullPlayer = { fullPlayerOpen = true }
                        )
                    }

                    NavigationBar(
                        containerColor = ObsidianSurface,
                        tonalElevation = 0.dp
                    ) {
                        val navItems = listOf(
                            Triple(0, "Home", Icons.Rounded.Home),
                            Triple(1, "Explore", Icons.Rounded.Search),
                            Triple(2, "Studio", Icons.Rounded.GraphicEq),
                            Triple(3, "Library", Icons.Rounded.LibraryMusic),
                            Triple(4, "Settings", Icons.Rounded.Settings)
                        )
                        navItems.forEach { (idx, label, icon) ->
                            NavigationBarItem(
                                selected = currentTab == idx,
                                onClick = { currentTab = idx },
                                icon = { Icon(icon, contentDescription = label) },
                                label = { Text(label) },
                                colors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = MaterialTheme.colorScheme.primary,
                                    selectedTextColor = MaterialTheme.colorScheme.primary,
                                    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f),
                                    unselectedIconColor = TextSecondary,
                                    unselectedTextColor = TextSecondary
                                )
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
            ) {
                when (currentTab) {
                    0 -> HomeScreen(
                        homeState = homeState,
                        localState = localState,
                        playbackState = playbackState,
                        downloadTasks = downloadTasks,
                        onPlayTrack = { song, list -> viewModel.playerBridge.playTrack(song, list) },
                        onToggleLike = { viewModel.localStore.toggleLike(it) },
                        onPlayNext = {
                            viewModel.playerBridge.addPlayNext(it)
                            viewModel.showToast("Playing next: ${it.title}")
                        },
                        onAddToQueue = {
                            viewModel.playerBridge.addToQueueEnd(it)
                            viewModel.showToast("Added to queue")
                        },
                        onAddToPlaylist = { trackForPlaylistSheet = it },
                        onDownloadTrack = {
                            viewModel.downloadManager.enqueueDownload(it)
                            viewModel.showToast("Downloading \"${it.title}\" offline...")
                        },
                        onOpenMood = { viewModel.openMoodCategory(it) },
                        onCloseMood = { viewModel.closeMoodCategory() },
                        onRefresh = { viewModel.refreshHomeFeed() },
                        onOpenPasteUrlDialog = { showPasteLinkDialog = true },
                        onOpenAmbientStudio = { currentTab = 2 },
                        onPlayStudioOfflineBeat = { viewModel.playStudioOfflineDemo(it) }
                    )

                    1 -> SearchScreen(
                        searchState = searchState,
                        localState = localState,
                        playbackState = playbackState,
                        downloadTasks = downloadTasks,
                        onQueryChange = { viewModel.onSearchQueryChange(it) },
                        onSubmitSearch = { q, f -> viewModel.submitSearch(q, f) },
                        onRemoveHistoryItem = { viewModel.localStore.removeSearchQuery(it) },
                        onPlayTrack = { song, list -> viewModel.playerBridge.playTrack(song, list) },
                        onOpenPlaylistPreview = { viewModel.openYoutubePlaylistPreview(it) },
                        onToggleLike = { viewModel.localStore.toggleLike(it) },
                        onPlayNext = {
                            viewModel.playerBridge.addPlayNext(it)
                            viewModel.showToast("Playing next: ${it.title}")
                        },
                        onAddToQueue = {
                            viewModel.playerBridge.addToQueueEnd(it)
                            viewModel.showToast("Added to queue")
                        },
                        onAddToPlaylist = { trackForPlaylistSheet = it },
                        onDownloadTrack = {
                            viewModel.downloadManager.enqueueDownload(it)
                            viewModel.showToast("Downloading \"${it.title}\" offline...")
                        },
                        onOpenPasteUrlDialog = { showPasteLinkDialog = true }
                    )

                    2 -> AmbientStudioScreen(
                        ambientLevels = ambientLevels,
                        activePresetName = activeAmbientPreset,
                        playbackState = playbackState,
                        onSetStemVolume = { id, vol -> viewModel.playerBridge.ambientMixer.setStemVolume(id, vol) },
                        onApplyPreset = { name, map -> viewModel.playerBridge.ambientMixer.applyMixPreset(name, map) },
                        onStopAllAmbient = { viewModel.playerBridge.ambientMixer.stopAll() },
                        onPlayOfflineBeat = { song, list -> viewModel.playerBridge.playTrack(song, list) }
                    )

                    3 -> LibraryScreen(
                        localState = localState,
                        playbackState = playbackState,
                        downloadTasks = downloadTasks,
                        onPlayTrack = { song, list -> viewModel.playerBridge.playTrack(song, list) },
                        onToggleLike = { viewModel.localStore.toggleLike(it) },
                        onPlayNext = {
                            viewModel.playerBridge.addPlayNext(it)
                            viewModel.showToast("Playing next: ${it.title}")
                        },
                        onAddToQueue = {
                            viewModel.playerBridge.addToQueueEnd(it)
                            viewModel.showToast("Added to queue")
                        },
                        onAddToPlaylist = { trackForPlaylistSheet = it },
                        onDownloadTrack = {
                            viewModel.downloadManager.enqueueDownload(it)
                            viewModel.showToast("Downloading \"${it.title}\" offline...")
                        },
                        onRemoveDownloadedTrack = {
                            viewModel.localStore.removeDownloadedSong(it)
                            viewModel.showToast("Removed offline copy")
                        },
                        onCreatePlaylist = { name, desc ->
                            viewModel.localStore.createPlaylist(name, desc)
                            viewModel.showToast("Playlist created!")
                        },
                        onDeletePlaylist = { viewModel.localStore.deletePlaylist(it) },
                        onRemoveTrackFromPlaylist = { plId, trId ->
                            viewModel.localStore.removeTrackFromPlaylist(plId, trId)
                        },
                        onClearHistory = { viewModel.localStore.clearHistory() },
                        onOpenPasteUrlDialog = { showPasteLinkDialog = true }
                    )

                    4 -> SettingsScreen(
                        settings = localState.settings,
                        ytDlpStatus = ytDlpStatus,
                        onUpdateSettings = { viewModel.updateSettings(it) },
                        onSelectAccent = { viewModel.setAccentPreset(it) },
                        onUpdateYtDlp = { viewModel.updateYtDlpEngine() },
                        onClearCache = { viewModel.clearStreamCache() },
                        onExportJson = { viewModel.localStore.exportBackupJson() },
                        onImportJson = { viewModel.localStore.importBackupJson(it) },
                        onShowToast = { viewModel.showToast(it) }
                    )
                }
            }
        }

        // Full-Screen Immersive Now Playing Overlay
        AnimatedVisibility(
            visible = fullPlayerOpen && currentTrack != null,
            enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
        ) {
            if (currentTrack != null) {
                NowPlayingFullScreen(
                    playbackState = playbackState,
                    lyricsState = lyricsState,
                    dspState = dspState,
                    settings = localState.settings,
                    isLiked = isCurrentLiked,
                    isDownloaded = isCurrentDownloaded,
                    downloadTask = downloadTasks[currentTrack.id],
                    onDismiss = { fullPlayerOpen = false },
                    onTogglePlayPause = { viewModel.playerBridge.togglePlayPause() },
                    onSkipNext = { viewModel.playerBridge.skipNext() },
                    onSkipPrevious = { viewModel.playerBridge.skipPrevious() },
                    onSeekTo = { viewModel.playerBridge.seekTo(it) },
                    onToggleShuffle = { viewModel.playerBridge.toggleShuffle() },
                    onCycleRepeat = { viewModel.playerBridge.cycleRepeatMode() },
                    onToggleLike = { viewModel.localStore.toggleLike(currentTrack) },
                    onAddToPlaylist = { trackForPlaylistSheet = currentTrack },
                    onDownload = {
                        if (!isCurrentDownloaded && !currentTrack.isOfflineReady) {
                            viewModel.downloadManager.enqueueDownload(currentTrack)
                            viewModel.showToast("Downloading \"${currentTrack.title}\" offline...")
                        }
                    },
                    onSelectQueueTrack = { chosen ->
                        viewModel.playerBridge.playTrack(chosen, playbackState.queue)
                    },
                    onRemoveFromQueue = { viewModel.playerBridge.removeFromQueue(it) },
                    onUpdateSettings = { viewModel.updateSettings(it) },
                    onSetSleepMinutes = { mins ->
                        viewModel.playerBridge.setSleepTimerMinutes(mins)
                        viewModel.showToast(if (mins != null) "Sleep timer set for $mins min" else "Sleep timer off")
                    },
                    onSetSleepEndOfTrack = { enabled ->
                        viewModel.playerBridge.setSleepAtEndOfTrack(enabled)
                        viewModel.showToast(if (enabled) "Will stop after current song" else "Sleep timer off")
                    }
                )
            }
        }
    }

    // Add Track to Playlist Bottom Sheet
    val targetTrack = trackForPlaylistSheet
    if (targetTrack != null) {
        var newPlName by remember { mutableStateOf("") }
        ModalBottomSheet(
            onDismissRequest = { trackForPlaylistSheet = null },
            containerColor = ObsidianSurface
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Add to Playlist", style = MaterialTheme.typography.titleLarge)
                Text(targetTrack.title, style = MaterialTheme.typography.bodySmall, maxLines = 1)

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newPlName,
                        onValueChange = { newPlName = it },
                        placeholder = { Text("New playlist name...") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val created = viewModel.localStore.createPlaylist(
                                name = newPlName.ifBlank { "My Favorites" },
                                initialTracks = listOf(targetTrack)
                            )
                            viewModel.showToast("Added to \"${created.name}\"")
                            trackForPlaylistSheet = null
                        }
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Create")
                    }
                }

                localState.playlists.forEach { pl ->
                    Surface(
                        color = ObsidianCard,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .clickable {
                                viewModel.localStore.addTrackToPlaylist(pl.id, targetTrack)
                                viewModel.showToast("Added to \"${pl.name}\"")
                                trackForPlaylistSheet = null
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(pl.name, color = Color.White)
                            Text("${pl.tracks.size} songs", color = TextSecondary)
                        }
                    }
                }
            }
        }
    }

    // Paste YouTube Song / Playlist URL Dialog
    if (showPasteLinkDialog) {
        var linkText by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showPasteLinkDialog = false },
            title = { Text("Play or Import YouTube Link") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste any YouTube or YT Music song URL to play immediately, or a playlist URL to import all tracks into your Library!",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = linkText,
                        onValueChange = { linkText = it },
                        placeholder = { Text("https://youtu.be/... or playlist?list=...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.importYoutubeUrlOrShareText(linkText)
                    showPasteLinkDialog = false
                }) {
                    Text("Open")
                }
            },
            dismissButton = {
                TextButton(onClick = { showPasteLinkDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
