package com.pulsewave.music.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.FileUpload
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pulsewave.music.BuildConfig
import com.pulsewave.music.model.AccentPreset
import com.pulsewave.music.model.AppSettings
import com.pulsewave.music.ui.theme.ObsidianCard

@Composable
fun SettingsScreen(
    settings: AppSettings,
    ytDlpStatus: String,
    onUpdateSettings: ((AppSettings) -> AppSettings) -> Unit,
    onSelectAccent: (AccentPreset) -> Unit,
    onUpdateYtDlp: () -> Unit,
    onClearCache: () -> Unit,
    onExportJson: () -> String,
    onImportJson: (String) -> Boolean,
    onShowToast: (String) -> Unit
) {
    val context = LocalContext.current
    val primary = MaterialTheme.colorScheme.primary

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openOutputStream(uri)?.use { os ->
                    os.write(onExportJson().toByteArray(Charsets.UTF_8))
                }
                onShowToast("Library backup exported!")
            }.onFailure { onShowToast("Export failed") }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty()
                if (onImportJson(raw)) {
                    onShowToast("Library backup restored!")
                } else {
                    onShowToast("Invalid backup file")
                }
            }.onFailure { onShowToast("Restore failed") }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text("Settings & Engine", style = MaterialTheme.typography.headlineMedium)
            Text(
                text = "Zero Login • Zero Cloud Tracking • Native Android Audio",
                style = MaterialTheme.typography.bodySmall
            )
        }

        // Theme & Dynamic Glow
        item {
            SettingsCard(title = "Visual Theme & Dynamic Glow") {
                ToggleRow(
                    title = "Dynamic Album-Art Glow",
                    subtitle = "Automatically tint player & accents from song cover art",
                    checked = settings.dynamicCoverTheme,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(dynamicCoverTheme = v) } }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Accent Palette", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(8.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(AccentPreset.entries) { preset ->
                        val selected = settings.accentName == preset.name
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { onSelectAccent(preset) }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(Color(preset.primaryHex), Color(preset.secondaryHex))
                                        )
                                    )
                                    .then(
                                        if (selected) Modifier.border(2.5.dp, Color.White, CircleShape)
                                        else Modifier
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selected) {
                                    Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White)
                                }
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(preset.label, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // Audio & Playback
        item {
            SettingsCard(title = "Audio Quality & Playback") {
                ToggleRow(
                    title = "High-Bitrate Audio Stream (160 kbps Opus/M4A)",
                    subtitle = "Turn off for Data Saver mode on slow mobile networks",
                    checked = settings.highQualityAudio,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(highQualityAudio = v) } }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    title = "Skip Silence",
                    subtitle = "Automatically trim quiet intros & outros in songs",
                    checked = settings.skipSilence,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(skipSilence = v) } }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    title = "Endless Auto-Radio Queue",
                    subtitle = "Automatically fetch related YouTube songs so music never stops",
                    checked = settings.autoRadioEnabled,
                    onCheckedChange = { v -> onUpdateSettings { it.copy(autoRadioEnabled = v) } }
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text("Discovery Charts Region", style = MaterialTheme.typography.labelMedium)
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("IN" to "India (Hindi/Punjabi)", "US" to "Global (English)").forEach { (code, label) ->
                        FilterChip(
                            selected = settings.contentRegion == code,
                            onClick = { onUpdateSettings { it.copy(contentRegion = code) } },
                            label = { Text(label) }
                        )
                    }
                }
            }
        }

        // Dual YouTube Engine Maintenance
        item {
            SettingsCard(title = "YouTube Audio Extractor Engine") {
                Text(
                    text = ytDlpStatus,
                    fontWeight = FontWeight.SemiBold,
                    color = primary,
                    fontSize = 13.sp
                )
                Text(
                    text = "Primary: NewPipe VisionOS Progressive DASH • Secondary: Embedded Python yt-dlp + FFmpeg",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onUpdateYtDlp,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.SystemUpdateAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Update yt-dlp", fontSize = 13.sp)
                    }
                    FilledTonalButton(
                        onClick = onClearCache,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.CleaningServices, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Clear Cache", fontSize = 13.sp)
                    }
                }
            }
        }

        // Local Backup & Restore
        item {
            SettingsCard(title = "Local Library Backup & Restore") {
                Text(
                    text = "Export your Liked Songs, Playlists & Settings to a portable JSON file.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { exportLauncher.launch("pulsewave_backup.json") },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.FileUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Export Backup")
                    }
                    FilledTonalButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Restore Backup")
                    }
                }
            }
        }

        // About card
        item {
            SettingsCard(title = "About PulseWave Music") {
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} • Native Kotlin + Jetpack Compose + Media3 ExoPlayer",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Built with Dual-Engine YouTube Audio Extraction, Hardware 5-Band Equalizer, Synced LRCLIB Lyrics, Offline Downloader & Studio Soundscape Mixer.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    content: @Composable () -> Unit
) {
    Surface(
        color = ObsidianCard,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
