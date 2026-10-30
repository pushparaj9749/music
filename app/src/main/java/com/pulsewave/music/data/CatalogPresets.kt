package com.pulsewave.music.data

import com.pulsewave.music.R
import com.pulsewave.music.model.AmbientStem
import com.pulsewave.music.model.MoodCategory
import com.pulsewave.music.model.SongTrack

object CatalogPresets {

    val moodCategories: List<MoodCategory> = listOf(
        MoodCategory(
            id = "bollywood",
            title = "Bollywood Hits",
            subtitle = "Arijit, Pritam, Vishal-Shekhar",
            assetCover = "file:///android_asset/covers/bollywood_hits.png",
            searchQuery = "Bollywood Latest Hit Songs Official Audio",
            accentColor = 0xFFF43F5E
        ),
        MoodCategory(
            id = "punjabi",
            title = "Punjabi Fire",
            subtitle = "Karan Aujla, Diljit, AP Dhillon",
            assetCover = "file:///android_asset/covers/punjabi_fire.png",
            searchQuery = "Punjabi Top Hit Songs Karan Aujla Diljit Dosanjh",
            accentColor = 0xFFF97316
        ),
        MoodCategory(
            id = "lofi",
            title = "Lo-Fi & Chill",
            subtitle = "Hindi Lo-Fi, Study & Late Night",
            assetCover = "file:///android_asset/covers/lofi_chill.png",
            searchQuery = "Bollywood Lofi Chill Slowed Reverb Songs",
            accentColor = 0xFF8B5CF6
        ),
        MoodCategory(
            id = "hiphop",
            title = "Desi Hip-Hop",
            subtitle = "Seedhe Maut, KR\$NA, Divine, Raftaar",
            assetCover = "file:///android_asset/covers/desi_hiphop.png",
            searchQuery = "Desi Hip Hop Rap Hits Divine KRSNA Seedhe Maut",
            accentColor = 0xFF10B981
        ),
        MoodCategory(
            id = "romantic",
            title = "Romantic Soul",
            subtitle = "Heartfelt melodies & acoustic love",
            assetCover = "file:///android_asset/covers/romantic_soul.png",
            searchQuery = "Hindi Romantic Love Songs Arijit Singh Atif Aslam",
            accentColor = 0xFFEC4899
        ),
        MoodCategory(
            id = "retro90s",
            title = "90s & 2000s Gold",
            subtitle = "KK, Sonu Nigam, Shaan, Kumar Sanu",
            assetCover = "file:///android_asset/covers/retro_90s.png",
            searchQuery = "90s 2000s Evergreen Bollywood Songs KK Sonu Nigam",
            accentColor = 0xFFF59E0B
        ),
        MoodCategory(
            id = "workout",
            title = "Gym & Beast Mode",
            subtitle = "High-energy bass & adrenaline",
            assetCover = "file:///android_asset/covers/workout_beast.png",
            searchQuery = "Gym Workout High Energy Motivation Songs Bass Boosted",
            accentColor = 0xFF84CC16
        ),
        MoodCategory(
            id = "nightdrive",
            title = "Late Night Drive",
            subtitle = "Synthwave, dark pop & highway vibes",
            assetCover = "file:///android_asset/covers/late_night_drive.png",
            searchQuery = "Night Drive Chill Songs The Weeknd Synth Pop",
            accentColor = 0xFF06B6D4
        ),
        MoodCategory(
            id = "sufi",
            title = "Sufi & Sukoon",
            subtitle = "Nusrat, Coke Studio, Kun Faya Kun",
            assetCover = "file:///android_asset/covers/sufi_peace.png",
            searchQuery = "Best Sufi Songs Coke Studio Rahat Fateh Ali Khan",
            accentColor = 0xFF14B8A6
        ),
        MoodCategory(
            id = "phonk",
            title = "Phonk & Drift Bass",
            subtitle = "Brazilian phonk & heavy sub-bass",
            assetCover = "file:///android_asset/covers/phonk_bass.png",
            searchQuery = "Best Phonk Drift Music Bass Boosted",
            accentColor = 0xFFE11D48
        ),
        MoodCategory(
            id = "indie",
            title = "Indie India",
            subtitle = "Anuv Jain, Prateek Kuhad, Local Train",
            assetCover = "file:///android_asset/covers/indie_india.png",
            searchQuery = "Indian Indie Songs Anuv Jain Prateek Kuhad Aditya Rikhari",
            accentColor = 0xFF2DD4BF
        ),
        MoodCategory(
            id = "global",
            title = "Global Pop Hits",
            subtitle = "Worldwide chartbusters & viral hits",
            assetCover = "file:///android_asset/covers/global_top.png",
            searchQuery = "Top Global English Pop Hits Songs Official Audio",
            accentColor = 0xFFA855F7
        )
    )

    val builtInStudioTracks: List<SongTrack> = listOf(
        SongTrack(
            id = "studio_offline_lofi",
            title = "Velvet Midnight Rhodes (Offline Studio Beat)",
            artist = "PulseWave Originals • Built-in Offline",
            thumbnailUrl = "file:///android_asset/covers/lofi_chill.png",
            durationSec = 12L,
            rawResName = "offline_lofi_beat",
            albumName = "PulseWave Studio Sessions"
        ),
        SongTrack(
            id = "studio_offline_synthwave",
            title = "Neon Horizon 1988 (Offline Synthwave)",
            artist = "PulseWave Originals • Built-in Offline",
            thumbnailUrl = "file:///android_asset/covers/late_night_drive.png",
            durationSec = 10L,
            rawResName = "offline_synthwave_drive",
            albumName = "PulseWave Studio Sessions"
        )
    )

    val ambientStems: List<AmbientStem> = listOf(
        AmbientStem(
            id = "rain",
            title = "Midnight Rain",
            subtitle = "Soft window rain & droplets",
            rawResId = R.raw.ambient_rain,
            accentColor = 0xFF38BDF8
        ),
        AmbientStem(
            id = "vinyl",
            title = "Warm Vinyl Crackle",
            subtitle = "Analog record needle texture",
            rawResId = R.raw.ambient_vinyl,
            accentColor = 0xFFF59E0B
        ),
        AmbientStem(
            id = "ocean",
            title = "Ocean Surf Waves",
            subtitle = "Deep rolling coastal tide",
            rawResId = R.raw.ambient_ocean,
            accentColor = 0xFF06B6D4
        ),
        AmbientStem(
            id = "thunder",
            title = "Distant Thunder",
            subtitle = "Sub-bass storm rumble",
            rawResId = R.raw.ambient_thunder,
            accentColor = 0xFF8B5CF6
        ),
        AmbientStem(
            id = "night_pad",
            title = "Lush Dream Pad",
            subtitle = "Warm Cmaj9 atmospheric drone",
            rawResId = R.raw.ambient_night_pad,
            accentColor = 0xFFEC4899
        )
    )

    val quickSearchChips: List<String> = listOf(
        "Arijit Singh Hits",
        "Karan Aujla",
        "KK Best Songs",
        "Atif Aslam",
        "Hindi Lofi Slowed",
        "Shreya Ghoshal",
        "Diljit Dosanjh",
        "Anuv Jain",
        "AR Rahman Classics",
        "The Weeknd",
        "Pritam Jukebox",
        "Coke Studio"
    )
}
