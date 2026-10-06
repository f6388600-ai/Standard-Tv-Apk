package com.livetv.premium

object BrandConfig {
    const val APP_NAME = "Hasu Live Tv"
    const val REMOTE_LOGO_URL = ""

    // Playlist 1
    const val PLAYLIST_URL =
        "https://raw.githubusercontent.com/f6388600-ai/Gvvgh/refs/heads/main/playlist.m3u"

    // Playlist 2 - paste the second playlist link here (leave "" to disable).
    const val PLAYLIST_URL_2 = "https://raw.githubusercontent.com/f6388600-ai/iptv-auto-playlist/refs/heads/main/playlist.m3u"

    // Both playlists are loaded and merged. Blank links are skipped.
    val PLAYLIST_URLS: List<String> = listOf(PLAYLIST_URL, PLAYLIST_URL_2).filter { it.isNotBlank() }

    const val REFRESH_INTERVAL_MS = 60 * 60 * 1000L
}
