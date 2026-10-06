package com.livetv.premium

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
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
        selected?.let { lastFocusedId = it.id }
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
                    AboutScreen(onBack = { screen = Screen.HOME })
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
                        restoreFocus = restoreFocus
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
    restoreFocus: FocusRequester
) {
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
            var index = 0
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
    val showControls = controlsVisible && status != PlayStatus.FAILED

    fun restart() {
        player.setMediaItem(PlayerPreloader.mediaItem(channel.url))
        player.prepare()
        player.playWhenReady = true
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

    // Keep the controls up while loading, auto-hide 5s after the last key press once playing.
    LaunchedEffect(status) {
        if (status == PlayStatus.LOADING || status == PlayStatus.RETRYING) controlsVisible = true
    }
    LaunchedEffect(controlsVisible, interaction, status) {
        if (controlsVisible && status == PlayStatus.PLAYING) {
            delay(5_000)
            controlsVisible = false
        }
    }
    // Move remote focus between the control panel and the screen itself.
    LaunchedEffect(showControls) {
        delay(60)
        try {
            if (showControls) playFocus.requestFocus()
            else if (status != PlayStatus.FAILED) rootFocus.requestFocus()
        } catch (_: Throwable) {
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
                handleFailure(friendlyError(error))
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                paused = !playWhenReady
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
                    Key.MediaPlayPause -> { player.playWhenReady = !player.playWhenReady; true }
                    Key.MediaPlay -> { player.playWhenReady = true; true }
                    Key.MediaPause -> { player.playWhenReady = false; true }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight,
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        // Any D-pad key brings the control panel up.
                        if (!showControls && status != PlayStatus.FAILED) {
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

        // Tap anywhere on the video (touch) to toggle the controls.
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { controlsVisible = !controlsVisible })
                }
        )

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
            playFocus = playFocus,
            onBack = onBack,
            onPrevious = onPrevious,
            onNext = onNext,
            onToggle = { player.playWhenReady = !player.playWhenReady }
        )
    }
}

@Composable
private fun PlayerControls(
    visible: Boolean,
    channel: Channel,
    paused: Boolean,
    hasPrevious: Boolean,
    hasNext: Boolean,
    playFocus: FocusRequester,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToggle: () -> Unit
) {
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
                        .background(Color(0xFFE5254B))
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(Color.White))
                    Spacer(Modifier.width(6.dp))
                    Text("LIVE", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.ExtraBold)
                }
            }
            Row(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xDD000000))))
                    .padding(top = 36.dp, bottom = 30.dp),
                horizontalArrangement = Arrangement.spacedBy(30.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasPrevious) ControlButton(Icons.Default.SkipPrevious, "Previous channel", 58.dp, onClick = onPrevious)
                ControlButton(
                    if (paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                    if (paused) "Play" else "Pause",
                    78.dp,
                    modifier = Modifier.focusRequester(playFocus),
                    onClick = onToggle
                )
                if (hasNext) ControlButton(Icons.Default.SkipNext, "Next channel", 58.dp, onClick = onNext)
            }
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
private fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lastSync = remember { PlaylistRepository.lastSync(context) }
    val syncText = if (lastSync > 0L) DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync)) else "Not synced yet"
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
                AboutCard("APK Details", listOf("App: ${BrandConfig.APP_NAME}", "Version: 1.0.0", "Last playlist sync: $syncText", "Automatic playlist sync: Every 1 hour"))
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
