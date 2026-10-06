package com.livetv.premium

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory

/**
 * Keeps one pre-buffered ExoPlayer ready so a channel starts instantly when opened.
 * All calls must happen on the main thread.
 */
object PlayerPreloader {
    private const val MAX_AGE_MS = 90_000L

    private var held: ExoPlayer? = null
    private var heldUrl: String? = null
    private var heldAt = 0L

    fun mediaItem(url: String): MediaItem {
        val builder = MediaItem.Builder().setUri(url)
        if (url.lowercase().contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
        return builder.build()
    }

    /** How far ahead we keep buffering: 5 min on normal devices, 2 min on low-memory ones. */
    fun dvrMaxMs(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val low = am == null || am.isLowRamDevice || am.largeMemoryClass < 192
        return if (low) 2 * 60_000L else 5 * 60_000L
    }

    private fun bufferBytes(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val mb = am?.largeMemoryClass ?: 128
        return (mb * 1024L * 1024L * 35L / 100L).coerceIn(24L * 1024 * 1024, 160L * 1024 * 1024).toInt()
    }

    fun create(context: Context): ExoPlayer {
        val app = context.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setUserAgent("HasuLiveTv/1.0")
        // Start after ~1s of data (fast start), but keep downloading ahead - also while paused -
        // up to dvrMaxMs / the memory cap, so resuming after a pause never stalls.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(30_000, dvrMaxMs(app).toInt(), 1_000, 2_000)
            .setTargetBufferBytes(bufferBytes(app))
            .build()
        val audio = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        return ExoPlayer.Builder(app)
            .setMediaSourceFactory(DefaultMediaSourceFactory(app).setDataSourceFactory(http))
            .setLoadControl(loadControl)
            .setAudioAttributes(audio, true)
            .build()
    }

    fun preload(context: Context, channel: Channel) {
        val now = SystemClock.elapsedRealtime()
        if (held != null && heldUrl == channel.url && now - heldAt < MAX_AGE_MS) return
        release()
        try {
            val player = create(context)
            player.setMediaItem(mediaItem(channel.url))
            player.playWhenReady = false
            player.prepare()
            held = player
            heldUrl = channel.url
            heldAt = now
        } catch (_: Throwable) {
            release()
        }
    }

    fun take(url: String): ExoPlayer? {
        val player = held ?: return null
        val fresh = heldUrl == url && SystemClock.elapsedRealtime() - heldAt < MAX_AGE_MS
        held = null
        heldUrl = null
        if (!fresh) {
            player.release()
            return null
        }
        return player
    }

    fun release() {
        try {
            held?.release()
        } catch (_: Throwable) {
        }
        held = null
        heldUrl = null
    }
}
