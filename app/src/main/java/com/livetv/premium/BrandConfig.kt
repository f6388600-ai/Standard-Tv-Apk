package com.livetv.premium

object BrandConfig {
    const val APP_NAME = "Hasu Live Tv"
    // App logo link (png/jpg). Used inside the app AND for the launcher icon at build time.
    // Leave "" to keep the built-in logo.
    const val REMOTE_LOGO_URL = ""

    // Developer contact buttons on the About screen. Leave "" to hide a button.
    const val DEV_NAME = "Hasan Ahmed"
    const val DEV_TELEGRAM_URL = "" // e.g. "https://t.me/yourusername"
    const val DEV_PHONE = ""        // e.g. "+8801XXXXXXXXX"
    const val DEV_WEBSITE_URL = ""  // opens in the device browser

    // Playlist 1
    const val PLAYLIST_URL =
        "https://raw.githubusercontent.com/f6388600-ai/iptv-auto-playlist/refs/heads/main/playlist.m3u"

    // Playlist 2 - paste the second playlist link here (leave "" to disable).
    const val PLAYLIST_URL_2 = ""

    // Both playlists are loaded and merged. Blank links are skipped.
    val PLAYLIST_URLS: List<String> = listOf(PLAYLIST_URL, PLAYLIST_URL_2).filter { it.isNotBlank() }

    const val REFRESH_INTERVAL_MS = 60 * 60 * 1000L
}
