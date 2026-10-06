package com.livetv.premium

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.os.SystemClock
import androidx.media3.common.C
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.input.key.nativeKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import java.io.File
import androidx.compose.foundation.Image
import androidx.activity.compose.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.zIndex
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit


data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String,
    val category: String
)

private enum class Screen { HOME, ABOUT }

private const val ABOUT_ID = "__about__"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installCrashLogger()
        try {
            schedulePlaylistSync(this)
        } catch (e: Throwable) {
            Log.e("HasuLiveTv", "WorkManager schedule failed", e)
        }
        setContent {
            var crash by remember { mutableStateOf(readLastCrash()) }
            val c = crash
            if (c != null) {
                CrashScreen(c) { clearLastCrash(); crash = null }
            } else {
                LiveTvApp()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        PlayerPreloader.release()
    }

    private fun crashFile() = File(filesDir, "last_crash.txt")

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                crashFile().writeText(Log.getStackTraceString(error))
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun readLastCrash(): String? = try {
        crashFile().takeIf { it.exists() }?.readText()?.take(6000)
    } catch (_: Throwable) {
        null
    }

    private fun clearLastCrash() {
        try {
            crashFile().delete()
        } catch (_: Throwable) {
        }
    }

    private fun schedulePlaylistSync(context: Context) {
        val request = PeriodicWorkRequestBuilder<PlaylistSyncWorker>(
            1, TimeUnit.HOURS
        )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            "hasu_live_tv_playlist_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
}

@Composable
private fun LiveTvApp() {
    val context = LocalContext.current
    val activity = context as Activity
    val scope = rememberCoroutineScope()
    var channels by remember { mutableStateOf(emptyList<Channel>()) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<Channel?>(null) }
    var screen by remember { mutableStateOf(Screen.HOME) }
    // Remember where the user was on Home so Back returns to the exact same spot.
    var query by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    var lastFocusedId by remember { mutableStateOf<String?>(null) }
    val restoreFocus = remember { FocusRequester() }
    var recentIds by remember { mutableStateOf(RecentStore.load(context)) }
    var showExit by remember { mutableStateOf(false) }
    var showSplash by remember { mutableStateOf(true) }
    val onlineState = rememberIsOnline()
    val offline = !onlineState.value && !showSplash
    var wasOffline by remember { mutableStateOf(false) }

    suspend fun loadPlaylist() {
        loading = true
        error = null
        try {
            val result = PlaylistRepository.load(context)
            if (result.isNotEmpty()) channels = result
            else if (channels.isEmpty()) error = "No channels found in playlist."
        } catch (e: Exception) {
            if (channels.isEmpty()) error = e.message ?: "Unable to load playlist."
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        launch { loadPlaylist() }
        delay(1200)
        showSplash = false
    }

    // When the connection comes back, reload the playlist if it is missing.
    LaunchedEffect(offline) {
        if (offline) {
            wasOffline = true
        } else if (wasOffline) {
            wasOffline = false
            if (channels.isEmpty() || error != null) loadPlaylist()
        }
    }

    LaunchedEffect(selected) {
        selected?.let {
            lastFocusedId = it.id
            recentIds = RecentStore.add(context, it.id)
        }
    }
    val recentChannels = remember(channels, recentIds) {
        recentIds.mapNotNull { id -> channels.firstOrNull { it.id == id } }.take(7)
    }

    BackHandler(enabled = selected != null) { selected = null }
    BackHandler(enabled = selected == null && screen != Screen.HOME) { screen = Screen.HOME }
    BackHandler(enabled = selected == null && screen == Screen.HOME) { showExit = true }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF06070C),
            surface = Color(0xFF111521),
            primary = Color(0xFFA56BFF)
        )
    ) {
        Crossfade(targetState = offline, animationSpec = tween(350), label = "network") { isOffline ->
            if (isOffline) {
                NoInternetScreen(
                    onRecheck = {
                        val ok = NetworkMonitor.isOnline(context)
                        onlineState.value = ok
                        ok
                    }
                )
            } else {
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
                label = "screen"
            ) { channel ->
                if (channel != null) {
                    val currentIndex = channels.indexOfFirst { it.id == channel.id }
                    PlayerScreen(
                        channel = channel,
                        nextChannel = if (currentIndex >= 0) channels.getOrNull(currentIndex + 1) else null,
                        hasPrevious = currentIndex > 0,
                        onBack = { selected = null },
                        onPrevious = {
                            val index = channels.indexOfFirst { it.id == channel.id }
                            if (index > 0) selected = channels[index - 1]
                        },
                        onNext = {
                            val index = channels.indexOfFirst { it.id == channel.id }
                            if (index >= 0 && index < channels.lastIndex) selected = channels[index + 1]
                        }
                    )
                } else if (screen == Screen.ABOUT) {
                    AboutScreen(onBack = { screen = Screen.HOME }, channelCount = channels.size)
                } else {
                    HomeScreen(
                        channels = channels,
                        loading = loading,
                        error = error,
                        onRetry = { scope.launch { loadPlaylist() } },
                        onChannel = { lastFocusedId = it.id; selected = it },
                        onAbout = { lastFocusedId = ABOUT_ID; screen = Screen.ABOUT },
                        query = query,
                        onQuery = { query = it },
                        listState = listState,
                        restoreId = lastFocusedId,
                        restoreFocus = restoreFocus,
                        recents = recentChannels
                    )
                }
            }
            }
        }

        if (showExit) {
            AlertDialog(
                onDismissRequest = { showExit = false },
                title = { Text("Exit Live TV?") },
                text = { Text("Are you sure you want to close the app?") },
                confirmButton = { TextButton(onClick = { activity.finish() }) { Text("Exit") } },
                dismissButton = { TextButton(onClick = { showExit = false }) { Text("Cancel") } }
            )
        }

        if (showSplash) SplashScreen()
    }
}

@Composable
private fun HomeScreen(
    channels: List<Channel>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onChannel: (Channel) -> Unit,
    onAbout: () -> Unit,
    query: String,
    onQuery: (String) -> Unit,
    listState: LazyListState,
    restoreId: String?,
    restoreFocus: FocusRequester,
    recents: List<Channel>
) {
    val showRecents = recents.isNotEmpty() && query.isBlank()
    val grouped = remember(channels, query) {
        val q = query.trim().lowercase(Locale.US)
        channels
            .filter { q.isBlank() || it.name.lowercase(Locale.US).contains(q) || it.category.lowercase(Locale.US).contains(q) }
            .groupBy { it.category.ifBlank { "Live TV" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    // On coming back (Back from player / About): scroll to and focus the item the user left from.
    LaunchedEffect(Unit) {
        if (restoreId != null && restoreId != ABOUT_ID) {
            var index = if (showRecents) 2 else 0
            var found = -1
            grouped.forEach { (_, list) ->
                index += 1
                val rows = list.chunked(7)
                rows.forEachIndexed { r, row ->
                    if (found < 0 && row.any { it.id == restoreId }) found = index + r
                }
                index += rows.size
            }
            val visible = listState.layoutInfo.visibleItemsInfo
            if (found >= 0 && visible.isNotEmpty() && visible.none { it.index == found }) {
                listState.scrollToItem(found)
            }
        }
        if (restoreId != null) {
            delay(120)
            try {
                restoreFocus.requestFocus()
            } catch (_: Throwable) {
            }
        }
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(0xFF100B20), Color(0xFF080B14), Color(0xFF05060A))
            )
        )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 22.dp)) {
            TopBar(
                query,
                onQuery = onQuery,
                onAbout = onAbout,
                loading = loading,
                aboutFocus = if (restoreId == ABOUT_ID) restoreFocus else null
            )
            Spacer(Modifier.height(22.dp))

            when {
                loading && channels.isEmpty() -> LoadingRows()
                error != null && channels.isEmpty() -> ErrorState(error, onRetry)
                grouped.isEmpty() -> ErrorState("No matching channels found.", onRetry)
                else -> {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 34.dp)
                    ) {
                        if (showRecents) {
                            item(key = "recent_header") {
                                Box(Modifier.padding(top = 14.dp)) { CategoryRow("Recently Watched", recents.size) }
                            }
                            item(key = "recent_row") {
                                ChannelRow(recents, onChannel, 0, null, restoreFocus)
                            }
                        }
                        grouped.forEach { (category, list) ->
                            item(key = "category_$category") {
                                Box(Modifier.padding(top = 14.dp)) { CategoryRow(category, list.size) }
                            }
                            val rows = list.chunked(7)
                            itemsIndexed(rows, key = { i, _ -> "row_${category}_$i" }) { i, row ->
                                ChannelRow(row, onChannel, i, restoreId, restoreFocus)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    query: String,
    onQuery: (String) -> Unit,
    onAbout: () -> Unit,
    loading: Boolean,
    aboutFocus: FocusRequester? = null
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Logo(Modifier.size(62.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.widthIn(min = 210.dp, max = 300.dp)) {
            Text("Hasu Live Tv", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text(
                if (loading) "Syncing live channels…" else "LIVE • FAST • PREMIUM",
                color = Color(0xFFB982FF), fontSize = 12.sp, fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.weight(1f))
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            singleLine = true,
            placeholder = { Text("Search channels", color = Color(0xFF8D94A5)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", tint = Color(0xFFB982FF)) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { onQuery("") }) { Icon(Icons.Default.Close, "Clear") }
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                keyboard?.hide()
                focusManager.moveFocus(FocusDirection.Down)
            }),
            modifier = Modifier
                .width(370.dp)
                .onPreviewKeyEvent { event ->
                    // OK / Enter on the remote opens the keyboard on the first press.
                    if (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter) {
                        if (event.type == KeyEventType.KeyUp) keyboard?.show()
                        true
                    } else false
                },
            shape = RoundedCornerShape(18.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFA56BFF),
                unfocusedBorderColor = Color(0xFF353B4C),
                focusedContainerColor = Color(0x221D1634),
                unfocusedContainerColor = Color(0x161A1F2B),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White,
                cursorColor = Color(0xFFA56BFF)
            )
        )
        Spacer(Modifier.width(14.dp))
        HeaderButton(Icons.Default.Info, "About", onAbout, aboutFocus)
    }
}

@Composable
private fun HeaderButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .size(64.dp)
            .onFocusChanged { focused = it.hasFocus }
            .then(if (focused) Modifier.border(2.dp, Color(0xFFA56BFF), RoundedCornerShape(18.dp)) else Modifier),
        shape = RoundedCornerShape(18.dp),
        color = if (focused) Color(0xFF2B1850) else Color(0xFF151A27)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(27.dp))
        }
    }
}

@Composable
private fun CategoryRow(category: String, count: Int) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.width(6.dp).height(30.dp).clip(RoundedCornerShape(4.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFD36BFF), Color(0xFF6C5CFF))))
        )
        Spacer(Modifier.width(12.dp))
        Text(category.uppercase(Locale.US), fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
        Spacer(Modifier.width(12.dp))
        Text(count.toString(), color = Color(0xFF9FA7B8), fontSize = 14.sp)
    }
}

@Composable
private fun ChannelRow(
    row: List<Channel>,
    onChannel: (Channel) -> Unit,
    rowIndex: Int,
    restoreId: String?,
    restoreFocus: FocusRequester
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        row.forEachIndexed { index, channel ->
            ChannelCard(
                channel,
                onChannel,
                Modifier.weight(1f).then(if (channel.id == restoreId) Modifier.focusRequester(restoreFocus) else Modifier),
                rowIndex * 7 + index
            )
        }
        repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
    }
}

@Composable
private fun ChannelCard(channel: Channel, onClick: (Channel) -> Unit, weight: Modifier, position: Int) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, tween(130), label = "focusScale")
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    // Pre-buffer the stream while the card is focused (TV) or pressed (touch).
    LaunchedEffect(focused, pressed) {
        if (pressed) {
            PlayerPreloader.preload(context, channel)
        } else if (focused) {
            delay(350)
            PlayerPreloader.preload(context, channel)
        }
    }
    Surface(
        onClick = { onClick(channel) },
        interactionSource = interaction,
        modifier = weight
            .scale(scale)
            .height(148.dp)
            .onFocusChanged { focused = it.hasFocus },
        shape = RoundedCornerShape(16.dp),
        color = if (focused) Color(0xFF29164A) else Color(0xFF151A25),
        border = androidx.compose.foundation.BorderStroke(
            if (focused) 3.dp else 1.dp,
            if (focused) Color(0xFFB36BFF) else Color(0xFF2A3140)
        )
    ) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ChannelLogo(channel, Modifier.height(88.dp).fillMaxWidth(0.78f))
            Spacer(Modifier.height(7.dp))
            Text(
                channel.name,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

private enum class PlayStatus { LOADING, PLAYING, REBUFFERING, RETRYING, FAILED }

private const val MAX_RETRIES = 3
private const val LOAD_TIMEOUT_MS = 15_000L

private fun friendlyError(e: PlaybackException): String = when (e.errorCode) {
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Network problem. Check your internet connection."
    PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
    PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "This channel is offline right now."
    PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED -> "This stream format is not supported."
    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
    PlaybackException.ERROR_CODE_DECODING_FAILED -> "Your device could not decode this stream."
    else -> "Playback error (${e.errorCodeName})"
}

private const val RESUME_PROMPT_MS = 10_000L
private const val SEEK_STEP_MS = 10_000L
private const val LIVE_EDGE_MS = 3_000L

private fun fmtTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val m = total / 60L
    val sec = total % 60L
    return "$m:${if (sec < 10L) "0" else ""}$sec"
}

@Composable
private fun PlayerScreen(
    channel: Channel,
    nextChannel: Channel?,
    hasPrevious: Boolean,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    val dvrCap = remember { PlayerPreloader.dvrMaxMs(context) }

    // Re-use the pre-buffered player if one is ready for this channel => instant start.
    val player = remember(channel.url) {
        val reused = PlayerPreloader.take(channel.url)
        val p = reused ?: PlayerPreloader.create(context).apply {
            setMediaItem(PlayerPreloader.mediaItem(channel.url))
            prepare()
        }
        if (reused != null && p.playerError != null) {
            p.setMediaItem(PlayerPreloader.mediaItem(channel.url))
            p.prepare()
        }
        p.playWhenReady = true
        p
    }

    var status by remember(channel.url) {
        mutableStateOf(if (player.playbackState == Player.STATE_READY) PlayStatus.PLAYING else PlayStatus.LOADING)
    }
    var attempt by remember(channel.url) { mutableStateOf(0) }
    var retryNonce by remember(channel.url) { mutableStateOf(0) }
    var errorText by remember(channel.url) { mutableStateOf("") }
    var controlsVisible by remember(channel.url) { mutableStateOf(true) }
    var interaction by remember(channel.url) { mutableStateOf(0) }
    var paused by remember(channel.url) { mutableStateOf(!player.playWhenReady) }

    // Time-shift bookkeeping: how far behind the live edge the viewer currently is.
    var behindBase by remember(channel.url) { mutableStateOf(0L) }
    var pausedAt by remember(channel.url) { mutableStateOf(0L) }
    var tick by remember(channel.url) { mutableStateOf(0L) }
    var pos by remember(channel.url) { mutableStateOf(0L) }
    var resumePrompt by remember(channel.url) { mutableStateOf(false) }
    var flash by remember(channel.url) { mutableStateOf(0) }
    var flashNonce by remember(channel.url) { mutableStateOf(0) }
    var noticeText by remember(channel.url) { mutableStateOf("") }
    var noticeVisible by remember(channel.url) { mutableStateOf(false) }

    val behind = (behindBase + (if (pausedAt > 0L) (tick - pausedAt).coerceAtLeast(0L) else 0L)).coerceIn(0L, dvrCap)
    val overlayFocus = status == PlayStatus.FAILED || resumePrompt
    val showControls = controlsVisible && !overlayFocus

    fun nowMs(): Long = SystemClock.elapsedRealtime()

    fun currentBehind(): Long =
        (behindBase + (if (pausedAt > 0L) (nowMs() - pausedAt).coerceAtLeast(0L) else 0L)).coerceIn(0L, dvrCap)

    fun commitPause() {
        if (pausedAt > 0L) {
            behindBase = (behindBase + (nowMs() - pausedAt)).coerceIn(0L, dvrCap)
            pausedAt = 0L
        }
    }

    fun restart() {
        player.setMediaItem(PlayerPreloader.mediaItem(channel.url))
        player.prepare()
        player.playWhenReady = true
    }

    fun goLive() {
        pausedAt = 0L
        behindBase = 0L
        player.seekToDefaultPosition()
        player.playWhenReady = true
    }

    fun resumeNow() {
        commitPause()
        player.playWhenReady = true
    }

    // After a longer pause we ask: Continue Watching or Watch Live.
    fun requestPlay() {
        val pausedFor = if (pausedAt > 0L) nowMs() - pausedAt else 0L
        if (pausedFor > RESUME_PROMPT_MS) resumePrompt = true else resumeNow()
    }

    fun togglePlay() {
        if (player.playWhenReady) player.playWhenReady = false else requestPlay()
    }

    /** Seek relative to now. Returns true if the position actually changed. */
    fun seekBy(deltaMs: Long): Boolean {
        val b = currentBehind()
        if (deltaMs > 0L && b <= LIVE_EDGE_MS) return false
        if (deltaMs > 0L && b <= deltaMs) {
            goLive()
            return true
        }
        val dur = player.duration
        val cur = player.currentPosition.coerceAtLeast(0L)
        val maxPos = if (dur == C.TIME_UNSET || dur <= 0L) Long.MAX_VALUE else dur
        val target = (cur + deltaMs).coerceIn(0L, maxPos)
        val actual = target - cur
        if (actual == 0L) return false
        if (pausedAt > 0L) {
            behindBase = (behindBase + (nowMs() - pausedAt)).coerceIn(0L, dvrCap)
            pausedAt = nowMs()
        }
        player.seekTo(target)
        behindBase = (behindBase - actual).coerceIn(0L, dvrCap)
        pos = target
        return true
    }

    fun handleFailure(message: String) {
        errorText = message
        if (attempt < MAX_RETRIES) {
            attempt += 1
            status = PlayStatus.RETRYING
            retryNonce += 1
        } else {
            status = PlayStatus.FAILED
        }
    }

    // Auto retry with a growing delay.
    LaunchedEffect(retryNonce) {
        if (retryNonce > 0 && status == PlayStatus.RETRYING) {
            delay(1_200L * attempt)
            if (status == PlayStatus.RETRYING) restart()
        }
    }

    // If a stream never starts, treat it as a failure instead of spinning forever.
    LaunchedEffect(status, retryNonce) {
        if (status == PlayStatus.LOADING || status == PlayStatus.RETRYING) {
            delay(LOAD_TIMEOUT_MS)
            if (status == PlayStatus.LOADING || status == PlayStatus.RETRYING) {
                handleFailure("The stream is taking too long to respond.")
            }
        }
    }

    // Keep the controls up while loading or paused; auto-hide 5s after the last key press while playing.
    LaunchedEffect(status) {
        if (status == PlayStatus.LOADING || status == PlayStatus.RETRYING) controlsVisible = true
    }
    LaunchedEffect(controlsVisible, interaction, status, paused) {
        if (controlsVisible && status == PlayStatus.PLAYING && !paused) {
            delay(5_000)
            controlsVisible = false
        }
    }
    // Move remote focus between the control panel, the dialogs and the screen itself.
    LaunchedEffect(showControls, overlayFocus) {
        delay(60)
        try {
            if (showControls) playFocus.requestFocus()
            else if (!overlayFocus) rootFocus.requestFocus()
        } catch (_: Throwable) {
        }
    }

    // Clock for the "-m:ss behind live" label while paused.
    LaunchedEffect(pausedAt) {
        if (pausedAt > 0L) {
            while (true) {
                tick = SystemClock.elapsedRealtime()
                delay(500)
            }
        }
    }
    // How much video exists behind the playhead (what the bar can reach).
    LaunchedEffect(showControls) {
        if (showControls) {
            while (true) {
                pos = player.currentPosition.coerceAtLeast(0L)
                delay(500)
            }
        }
    }
    LaunchedEffect(flashNonce) {
        if (flashNonce > 0) {
            delay(650)
            flash = 0
        }
    }
    LaunchedEffect(noticeVisible) {
        if (noticeVisible) {
            delay(3_500)
            noticeVisible = false
        }
    }

    // Warm up the next channel so channel up/down is fast too.
    LaunchedEffect(channel.url, nextChannel?.url, status) {
        if (status == PlayStatus.PLAYING && nextChannel != null) {
            delay(3_000)
            PlayerPreloader.preload(context, nextChannel)
        }
    }

    DisposableEffect(player, lifecycleOwner) {
        val lifecycleObserver = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                player.pause()
            }
        }
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        status = PlayStatus.PLAYING
                        attempt = 0
                    }
                    Player.STATE_BUFFERING -> {
                        if (status == PlayStatus.PLAYING) status = PlayStatus.REBUFFERING
                    }
                    Player.STATE_ENDED -> handleFailure("The stream ended unexpectedly.")
                    else -> Unit
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                // Paused so long that the server dropped our position: jump back to live.
                if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && attempt < MAX_RETRIES) {
                    attempt += 1
                    pausedAt = 0L
                    behindBase = 0L
                    noticeText = "You were too far behind - back to live"
                    noticeVisible = true
                    player.seekToDefaultPosition()
                    player.prepare()
                    player.playWhenReady = true
                    return
                }
                handleFailure(friendlyError(error))
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
                if (!playWhenReady) {
                    if (pausedAt == 0L) {
                        pausedAt = SystemClock.elapsedRealtime()
                        tick = pausedAt
                    }
                } else {
                    commitPause()
                }
            }
        }
        player.addListener(listener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                interaction += 1
                when (event.key) {
                    Key.ChannelUp, Key.MediaNext -> { if (nextChannel != null) onNext(); true }
                    Key.ChannelDown, Key.MediaPrevious -> { if (hasPrevious) onPrevious(); true }
                    Key.MediaPlayPause -> { togglePlay(); true }
                    Key.MediaPlay -> { requestPlay(); true }
                    Key.MediaPause -> { player.playWhenReady = false; true }
                    Key.MediaRewind -> { if (seekBy(-SEEK_STEP_MS)) flash = -1; flashNonce += 1; true }
                    Key.MediaFastForward -> { if (seekBy(SEEK_STEP_MS)) flash = 1; flashNonce += 1; true }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        // Any D-pad key brings the control panel up.
                        if (!showControls && !overlayFocus) {
                            controlsVisible = true
                            true
                        } else false
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable()
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    setPlayer(player)
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Touch: tap = show/hide controls, double tap left/right half = -10s / +10s.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { controlsVisible = !controlsVisible },
                        onDoubleTap = { offset ->
                            val left = offset.x < size.width / 2f
                            if (seekBy(if (left) -SEEK_STEP_MS else SEEK_STEP_MS)) {
                                flash = if (left) -1 else 1
                                flashNonce += 1
                            }
                        }
                    )
                }
        )

        SeekFlashOverlay(flash)

        PlayerStatusOverlay(
            channel = channel,
            status = status,
            attempt = attempt,
            errorText = errorText,
            hasNext = nextChannel != null,
            onRetry = {
                attempt = 0
                errorText = ""
                status = PlayStatus.LOADING
                retryNonce += 1
                restart()
            },
            onBack = onBack,
            onNext = onNext
        )

        PlayerControls(
            visible = showControls,
            channel = channel,
            paused = paused,
            hasPrevious = hasPrevious,
            hasNext = nextChannel != null,
            behindMs = behind,
            availableMs = (behind + pos).coerceAtMost(dvrCap),
            capMs = dvrCap,
            playFocus = playFocus,
            onBack = onBack,
            onPrevious = onPrevious,
            onNext = onNext,
            onToggle = { togglePlay() },
            onSeekBy = { delta ->
                if (seekBy(delta)) {
                    flash = if (delta < 0L) -1 else 1
                    flashNonce += 1
                }
            },
            onGoLive = { goLive() }
        )

        ResumePrompt(
            visible = resumePrompt,
            behindMs = behind,
            onContinue = {
                resumePrompt = false
                resumeNow()
                controlsVisible = true
            },
            onLive = {
                resumePrompt = false
                goLive()
                controlsVisible = true
            }
        )

        AnimatedVisibility(
            visible = noticeVisible,
            modifier = Modifier.fillMaxSize().zIndex(9f),
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(300))
        ) {
            Box(Modifier.fillMaxSize().padding(top = 76.dp), contentAlignment = Alignment.TopCenter) {
                Surface(shape = RoundedCornerShape(20.dp), color = Color(0xE6141826)) {
                    Text(
                        noticeText,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SeekFlashOverlay(flash: Int) {
    Row(Modifier.fillMaxSize().zIndex(4f)) {
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(visible = flash < 0, enter = fadeIn(tween(80)), exit = fadeOut(tween(300))) {
                SeekBadge(Icons.Default.FastRewind)
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
            AnimatedVisibility(visible = flash > 0, enter = fadeIn(tween(80)), exit = fadeOut(tween(300))) {
                SeekBadge(Icons.Default.FastForward)
            }
        }
    }
}

@Composable
private fun SeekBadge(icon: ImageVector) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(72.dp).clip(CircleShape).background(Color(0x99000000)),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(38.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text("10 seconds", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ResumePrompt(
    visible: Boolean,
    behindMs: Long,
    onContinue: () -> Unit,
    onLive: () -> Unit
) {
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize().zIndex(7f),
        enter = fadeIn(tween(250)) + scaleIn(initialScale = 0.94f),
        exit = fadeOut(tween(200))
    ) {
        val focus = remember { FocusRequester() }
        LaunchedEffect(Unit) {
            delay(80)
            try {
                focus.requestFocus()
            } catch (_: Throwable) {
            }
        }
        Box(
            Modifier.fillMaxSize().background(Color(0xCC05060A)),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xF2141826),
                border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF343B4E))
            ) {
                Column(
                    Modifier.padding(horizontal = 40.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Default.PauseCircle, null, tint = Color(0xFFB982FF), modifier = Modifier.size(50.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("Welcome back", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "You are ${fmtTime(behindMs)} behind the live broadcast.\nContinue where you left off, or jump to live.",
                        color = Color(0xFFC4CAD6),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(24.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Button(
                            onClick = onContinue,
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA56BFF), contentColor = Color.White),
                            modifier = Modifier.focusRequester(focus)
                        ) {
                            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Continue Watching", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(onClick = onLive, shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Default.LiveTv, null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Watch Live", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    visible: Boolean,
    channel: Channel,
    paused: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    behindMs: Long,
    availableMs: Long,
    capMs: Long,
    playFocus: FocusRequester,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggle: () -> Unit,
    onSeekBy: (Long) -> Unit,
    onGoLive: () -> Unit
) {
    val atLive = behindMs <= LIVE_EDGE_MS
    AnimatedVisibility(
        visible = visible,
        modifier = Modifier.fillMaxSize().zIndex(8f),
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(300))
    ) {
        Box(Modifier.fillMaxSize()) {
            Row(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent)))
                    .padding(horizontal = 28.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BackPill(channel.name, onBack)
                Spacer(Modifier.weight(1f))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (atLive) Color(0xFFE5254B) else Color(0xFF3A3F52))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (atLive) "LIVE" else "-${fmtTime(behindMs)}",
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.ExtraBold
                    )
                }
            }
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xEE000000))))
                    .padding(start = 40.dp, end = 40.dp, top = 40.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                DvrBar(behindMs, availableMs, capMs, onSeekBy, Modifier.fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(22.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (hasPrevious) ControlButton(Icons.Default.SkipPrevious, "Previous channel", 52.dp, onClick = onPrevious)
                    ControlButton(Icons.Default.Replay10, "Back 10 seconds", 52.dp, onClick = { onSeekBy(-SEEK_STEP_MS) })
                    ControlButton(
                        if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        if (paused) "Play" else "Pause",
                        74.dp,
                        modifier = Modifier.focusRequester(playFocus),
                        onClick = onToggle
                    )
                    ControlButton(Icons.Default.Forward10, "Forward 10 seconds", 52.dp, onClick = { onSeekBy(SEEK_STEP_MS) })
                    if (hasNext) ControlButton(Icons.Default.SkipNext, "Next channel", 52.dp, onClick = onNext)
                    LivePill(atLive, onGoLive)
                }
            }
        }
    }
}

/** YouTube-style time bar: right end = live. Left/Right on the remote moves it by 10s. */
@Composable
private fun DvrBar(
    behindMs: Long,
    availableMs: Long,
    capMs: Long,
    onSeekBy: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    var focused by remember { mutableStateOf(false) }
    val behindState by rememberUpdatedState(behindMs)
    val seek by rememberUpdatedState(onSeekBy)
    val cap = capMs.coerceAtLeast(1L)
    val frac = (1f - behindMs.toFloat() / cap.toFloat()).coerceIn(0f, 1f)
    val availFrac = (1f - availableMs.toFloat() / cap.toFloat()).coerceIn(0f, 1f)
    val atLive = behindMs <= LIVE_EDGE_MS
    var lastDragX by remember { mutableStateOf(-1f) }

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("-${fmtTime(cap)}", color = Color(0xFF9FA7B8), fontSize = 12.sp)
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier
                .weight(1f)
                .height(40.dp)
                .onFocusChanged { focused = it.hasFocus }
                .onKeyEvent { event ->
                    if (event.key == Key.DirectionLeft || event.key == Key.DirectionRight) {
                        if (event.type == KeyEventType.KeyDown) {
                            val repeat = event.nativeKeyEvent.repeatCount
                            // Holding the key keeps seeking, but at a calmer pace.
                            if (repeat == 0 || repeat % 5 == 0) {
                                seek(if (event.key == Key.DirectionLeft) -SEEK_STEP_MS else SEEK_STEP_MS)
                            }
                        }
                        true
                    } else false
                }
                .focusable()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { offset ->
                        val targetBehind = ((1f - offset.x / size.width).coerceIn(0f, 1f) * cap).toLong()
                        seek(behindState - targetBehind)
                    })
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { lastDragX = it.x },
                        onDragEnd = {
                            if (lastDragX >= 0f) {
                                val targetBehind = ((1f - lastDragX / size.width).coerceIn(0f, 1f) * cap).toLong()
                                seek(behindState - targetBehind)
                                lastDragX = -1f
                            }
                        },
                        onDragCancel = { lastDragX = -1f },
                        onHorizontalDrag = { change, _ -> lastDragX = change.position.x }
                    )
                }
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val cy = size.height / 2f
                val th = 6.dp.toPx()
                val radius = CornerRadius(th / 2f, th / 2f)
                val a = availFrac * w
                val k = frac * w
                drawRoundRect(Color(0x33FFFFFF), Offset(0f, cy - th / 2f), Size(w, th), radius)
                drawRoundRect(Color(0x66B66CFF), Offset(a, cy - th / 2f), Size(w - a, th), radius)
                if (k > a) drawRoundRect(Color(0xFFA56BFF), Offset(a, cy - th / 2f), Size(k - a, th), radius)
                if (focused) drawCircle(Color(0x66B66CFF), 16.dp.toPx(), Offset(k, cy))
                drawCircle(Color.White, (if (focused) 10.dp else 7.dp).toPx(), Offset(k, cy))
            }
        }
        Spacer(Modifier.width(12.dp))
        Text(
            if (atLive) "LIVE" else "-${fmtTime(behindMs)}",
            color = if (atLive) Color(0xFFFF6B8A) else Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LivePill(atLive: Boolean, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.hasFocus },
        shape = RoundedCornerShape(24.dp),
        color = when {
            focused -> Color(0xFFA56BFF)
            atLive -> Color(0xFFE5254B)
            else -> Color(0xAA0C0F18)
        },
        border = androidx.compose.foundation.BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) Color.White else Color(0x44FFFFFF)
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(Color.White))
            Spacer(Modifier.width(8.dp))
            Text(if (atLive) "LIVE" else "Go Live", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    size: Dp,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.14f else 1f, tween(120), label = "controlScale")
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(size)
            .scale(scale)
            .onFocusChanged { focused = it.hasFocus },
        shape = CircleShape,
        color = if (focused) Color(0xFFA56BFF) else Color(0xAA0C0F18),
        border = androidx.compose.foundation.BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) Color.White else Color(0x44FFFFFF)
        )
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, label, tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
    }
}

@Composable
private fun BackPill(title: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.hasFocus },
        shape = RoundedCornerShape(14.dp),
        color = if (focused) Color(0xFFA56BFF) else Color(0xAA0C0F18),
        border = androidx.compose.foundation.BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused) Color.White else Color(0x44FFFFFF)
        )
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.ArrowBack, "Back", tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                title,
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 320.dp)
            )
        }
    }
}

@Composable
private fun PlayerStatusOverlay(
    channel: Channel,
    status: PlayStatus,
    attempt: Int,
    errorText: String,
    hasNext: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val busy = status == PlayStatus.LOADING || status == PlayStatus.RETRYING

    AnimatedVisibility(
        visible = busy,
        modifier = Modifier.fillMaxSize().zIndex(5f),
        enter = fadeIn(tween(250)),
        exit = fadeOut(tween(450))
    ) {
        LoadingPanel(channel, status == PlayStatus.RETRYING, attempt, errorText)
    }

    AnimatedVisibility(
        visible = status == PlayStatus.REBUFFERING,
        modifier = Modifier.fillMaxSize().zIndex(5f),
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(300))
    ) {
        Box(Modifier.fillMaxSize().padding(18.dp), contentAlignment = Alignment.TopEnd) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC080A10))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color(0xFFB66CFF)
                )
                Spacer(Modifier.width(8.dp))
                Text("Buffering…", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    AnimatedVisibility(
        visible = status == PlayStatus.FAILED,
        modifier = Modifier.fillMaxSize().zIndex(6f),
        enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.92f),
        exit = fadeOut(tween(250))
    ) {
        FailedPanel(errorText, hasNext, onRetry, onBack, onNext)
    }
}

@Composable
private fun ChannelLogo(channel: Channel, modifier: Modifier) {
    if (channel.logo.isBlank()) {
        Image(
            painter = painterResource(R.drawable.app_logo),
            contentDescription = channel.name,
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    } else {
        AsyncImage(
            model = channel.logo,
            contentDescription = channel.name,
            placeholder = painterResource(R.drawable.app_logo),
            error = painterResource(R.drawable.app_logo),
            contentScale = ContentScale.Fit,
            modifier = modifier
        )
    }
}

@Composable
private fun LoadingPanel(channel: Channel, retrying: Boolean, attempt: Int, detail: String) {
    val transition = rememberInfiniteTransition(label = "loading")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "angle"
    )
    val pulse by transition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF230F42), Color(0xFF05060A)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(150.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 5.dp.toPx()
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(stroke / 2f, stroke / 2f)
                    drawArc(
                        color = Color(0xFFB66CFF),
                        startAngle = angle,
                        sweepAngle = 110f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                    drawArc(
                        color = Color(0x666C5CFF),
                        startAngle = angle + 180f,
                        sweepAngle = 70f,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(stroke, cap = StrokeCap.Round)
                    )
                }
                ChannelLogo(
                    channel,
                    Modifier.size(84.dp).scale(pulse).clip(RoundedCornerShape(14.dp))
                )
            }
            Spacer(Modifier.height(22.dp))
            Text(
                channel.name,
                color = Color.White,
                fontSize = 22.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(6.dp))
            Text(
                if (retrying) "Reconnecting… attempt $attempt of $MAX_RETRIES" else "Loading stream…",
                color = Color(0xFFB982FF),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold
            )
            if (retrying && detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(detail, color = Color(0xFF9FA7B8), fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            Spacer(Modifier.height(18.dp))
            LinearProgressIndicator(
                modifier = Modifier.width(200.dp).height(3.dp).clip(RoundedCornerShape(2.dp)),
                color = Color(0xFFB66CFF),
                trackColor = Color(0x334A3A63)
            )
        }
    }
}

@Composable
private fun FailedPanel(
    message: String,
    hasNext: Boolean,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    onNext: () -> Unit
) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (_: Throwable) {
        }
    }
    Box(
        Modifier.fillMaxSize().background(Color(0xF205060A)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Icon(Icons.Default.ErrorOutline, null, tint = Color(0xFFFF8A80), modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(14.dp))
            Text("Stream unavailable", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text(message, color = Color(0xFFC4CAD6), fontSize = 14.sp, textAlign = TextAlign.Center)
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = onRetry, modifier = Modifier.focusRequester(focus)) {
                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Retry")
                }
                if (hasNext) {
                    OutlinedButton(onClick = onNext) { Text("Next channel") }
                }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

@Composable
private fun AboutScreen(onBack: () -> Unit, channelCount: Int) {
    val context = LocalContext.current
    val lastSync = remember { PlaylistRepository.lastSync(context) }
    val syncText = if (lastSync > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync)) else "Not updated yet"
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            backFocus.requestFocus()
        } catch (_: Throwable) {
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF130B24), Color(0xFF07090E))))
            .onPreviewKeyEvent { event ->
                // D-pad Up / Down scrolls the page; OK on the Back button goes back.
                if (event.type == KeyEventType.KeyDown) {
                    when (event.key) {
                        Key.DirectionDown -> { scope.launch { scroll.animateScrollBy(280f) }; true }
                        Key.DirectionUp -> { scope.launch { scroll.animateScrollBy(-280f) }; true }
                        else -> false
                    }
                } else false
            }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 30.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(Icons.Default.ArrowBack, "Back", onBack, backFocus)
                Spacer(Modifier.width(20.dp))
                Text("About", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(top = 24.dp, bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Logo(Modifier.size(100.dp))
                Spacer(Modifier.height(16.dp))
                Text(BrandConfig.APP_NAME, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Text("Premium Live TV", color = Color(0xFFB982FF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(30.dp))
                AboutCard("APK Details", listOf("App: ${BrandConfig.APP_NAME}", "Version: 1.0.0", "Total Live Channels: $channelCount", "Last Update: $syncText"))
                Spacer(Modifier.height(18.dp))
                AboutCard("Developer Details", listOf("Developer: Hasan Ahmed", "App: ${BrandConfig.APP_NAME}"))
            }
        }
    }
}

@Composable
private fun AboutCard(title: String, lines: List<String>) {
    Surface(
        modifier = Modifier.fillMaxWidth(0.72f),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF121622),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF343B4E))
    ) {
        Column(Modifier.padding(24.dp)) {
            Text(title, color = Color(0xFFC58CFF), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(12.dp))
            lines.forEach { Text(it, color = Color(0xFFE6E8EE), fontSize = 15.sp, modifier = Modifier.padding(vertical = 3.dp)) }
        }
    }
}

@Composable
private fun Logo(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.app_logo),
        contentDescription = BrandConfig.APP_NAME,
        contentScale = ContentScale.Fit,
        modifier = modifier.clip(RoundedCornerShape(18.dp))
    )
}

@Composable
private fun CrashScreen(trace: String, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().background(Color.Black).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("App crashed last time - screenshot this and send", color = Color(0xFFFF8A80), fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Text(
            trace,
            color = Color.White,
            fontSize = 10.sp,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState())
        )
        Button(onClick = onContinue) { Text("Continue") }
    }
}

@Composable
private fun NoInternetScreen(onRecheck: () -> Boolean) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            focus.requestFocus()
        } catch (_: Throwable) {
        }
    }

    val transition = rememberInfiniteTransition(label = "offline")
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2400, easing = LinearEasing)),
        label = "wave"
    )
    val bob by transition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "bob"
    )
    val dotAlpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
        label = "dot"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF14092B), Color(0xFF07080D), Color(0xFF05060A)))),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 48.dp)) {
            Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    for (i in 0..2) {
                        val p = (wave + i / 3f) % 1f
                        drawCircle(
                            color = Color(0xFFB66CFF).copy(alpha = (1f - p) * 0.4f),
                            radius = size.minDimension / 2f * (0.4f + 0.6f * p),
                            style = Stroke(2.dp.toPx())
                        )
                    }
                }
                Box(
                    Modifier
                        .offset(y = bob.dp)
                        .size(104.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF3A1D6E), Color(0xFF1B1235))))
                        .border(1.5.dp, Color(0x66B66CFF), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.WifiOff, null, tint = Color(0xFFD9B8FF), modifier = Modifier.size(50.dp))
                }
            }
            Spacer(Modifier.width(44.dp))
            Column(Modifier.widthIn(max = 440.dp)) {
                Text("CONNECTION LOST", color = Color(0xFFB982FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("No Internet Connection", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    "We can't reach the network. We'll reconnect automatically as soon as you're back online.",
                    color = Color(0xFFC4CAD6),
                    fontSize = 15.sp
                )
                Spacer(Modifier.height(18.dp))
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFF121622),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF343B4E))
                ) {
                    Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Make sure Wi-Fi or mobile data is turned on", "Restart your router if other devices are offline too").forEach { tip ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(0xFFB66CFF)))
                                Spacer(Modifier.width(10.dp))
                                Text(tip, color = Color(0xFFE6E8EE), fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(22.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = {
                            if (!checking) {
                                checking = true
                                hint = ""
                                scope.launch {
                                    delay(900)
                                    val ok = onRecheck()
                                    checking = false
                                    if (!ok) hint = "Still offline. Please check your connection."
                                }
                            }
                        },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA56BFF), contentColor = Color.White),
                        modifier = Modifier.focusRequester(focus)
                    ) {
                        if (checking) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                        } else {
                            Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(if (checking) "Checking…" else "Retry", fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(18.dp))
                    Box(Modifier.size(8.dp).clip(CircleShape).background(Color(0xFFB66CFF).copy(alpha = dotAlpha)))
                    Spacer(Modifier.width(8.dp))
                    Text("Waiting for connection…", color = Color(0xFF9FA7B8), fontSize = 13.sp)
                }
                if (hint.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Text(hint, color = Color(0xFFFF8A80), fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun SplashScreen() {
    var visible by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (visible) 1f else 0.72f, tween(700), label = "splashScale")
    val alpha by animateFloatAsState(if (visible) 1f else 0f, tween(700), label = "splashAlpha")
    LaunchedEffect(Unit) { visible = true }

    Box(
        Modifier.fillMaxSize().background(Brush.radialGradient(listOf(Color(0xFF2A124D), Color(0xFF07080D)))),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.scale(scale)) {
            Logo(Modifier.size(128.dp))
            Spacer(Modifier.height(18.dp))
            Text("Hasu Live Tv", color = Color.White.copy(alpha = alpha), fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(14.dp))
            LinearProgressIndicator(
                modifier = Modifier.width(180.dp).height(3.dp),
                color = Color(0xFFB66CFF),
                trackColor = Color(0x334A3A63)
            )
        }
    }
}

@Composable
private fun LoadingRows() {
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        repeat(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                repeat(7) {
                    Box(
                        Modifier.weight(1f).height(148.dp).clip(RoundedCornerShape(16.dp))
                            .background(Color(0xFF121722))
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorState(message: String, retry: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(top = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(58.dp), tint = Color(0xFFB982FF))
        Spacer(Modifier.height(16.dp))
        Text(message, color = Color(0xFFC4CAD6), fontSize = 18.sp)
        Spacer(Modifier.height(18.dp))
        Button(onClick = retry, shape = RoundedCornerShape(14.dp)) { Text("Try Again") }
    }
}
