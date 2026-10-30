# PulseWave Music — Native Android YouTube Music Player

**PulseWave Music** is a 100% native Android music player built with **Kotlin**, **Jetpack Compose (Material 3)**, and **AndroidX Media3 (ExoPlayer)** — requiring **zero login**, **zero external database**, and **zero ads**.

## Highlights

- **Dual-Engine YouTube Audio Streaming**
  - **Primary Engine:** `NewPipeExtractor` (VisionOS + Web metadata + anti-throttle chunked `YoutubeProgressiveDashManifestCreator`) for sub-second song startup.
  - **Secondary Engine:** Embedded native Python `yt-dlp` + `FFmpeg` (`youtubedl-android`) with 1-tap Nightly engine updater inside Settings.
- **Background & Lockscreen Media Session**
  - Full `MediaSessionService` foreground playback with Android 13–15 notification seek bar, artwork, and Bluetooth headphone Next/Previous/Play/Pause support.
- **Offline Song Downloader & Zero-Internet Studio**
  - Download any YouTube song locally for offline playback, plus 2 built-in studio grooves and 5 offline stereo ambient soundscapes (*Midnight Rain, Warm Vinyl Crackle, Ocean Surf, Distant Thunder, Lush Dream Pad*) that can even be layered underneath any YouTube track!
- **Pro Audio & Slowed+Reverb Studio**
  - Hardware 5-Band Equalizer, Sub-Bass Boost, 3D Surround Virtualizer, Loudness Booster, Skip Silence, Sleep Timer, and real-time Speed + Pitch presets (*Slowed Aesthetic, Deep Slowed, Nightcore*).
- **Time-Synced Lyrics & Dynamic Cover Glow**
  - Auto-scrolling synced `.lrc` lyrics via LRCLIB (tap any line to seek) + dynamic album-art `Palette` color theming.
- **Zero Login, Local JSON Storage**
  - Liked Songs, Custom Playlists, Imported YouTube Playlists, History, and Settings live locally on your device with 1-tap JSON Backup & Restore.

## Download Ready-to-Install Signed APK

Check the **[GitHub Releases](../../releases)** page and download:
- **`PulseWave-v1.0.0-arm64-v8a-release.apk`** *(Recommended — ~50 MB, for modern 64-bit Android phones)*
- **`PulseWave-v1.0.0-armeabi-v7a-release.apk`** *(For 32-bit Android phones)*
- **`PulseWave-v1.0.0-universal-release.apk`** *(Universal multi-ABI package)*

## Build Locally

```bash
./gradlew assembleRelease
```

APKs are output to `app/build/outputs/apk/release/`.
