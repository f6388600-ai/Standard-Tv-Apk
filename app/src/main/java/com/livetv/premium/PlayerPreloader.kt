package com.livetv.premium

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

    fun create(context: Context): ExoPlayer {
        val app = context.applicationContext
        val http = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(8_000)
            .setUserAgent("HasuLiveTv/1.0")
        // Start playing after only ~1s of buffered data for a fast start.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 40_000, 1_000, 2_000)
            .setPrioritizeTimeOverSizeThresholds(true)
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
