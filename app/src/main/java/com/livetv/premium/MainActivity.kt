package com.livetv.premium

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.compose.animation.AnimatedVisibility
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
    var showExit by remember { mutableStateOf(false) }
    var showSplash by remember { mutableStateOf(true) }

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
                    onChannel = { selected = it },
                    onAbout = { screen = Screen.ABOUT }
                )
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
    onAbout: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val grouped = remember(channels, query) {
        val q = query.trim().lowercase(Locale.US)
        channels
            .filter { q.isBlank() || it.name.lowercase(Locale.US).contains(q) || it.category.lowercase(Locale.US).contains(q) }
            .groupBy { it.category.ifBlank { "Live TV" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(Color(0xFF100B20), Color(0xFF080B14), Color(0xFF05060A))
            )
        )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 34.dp, vertical = 22.dp)) {
            TopBar(query, onQuery = { query = it }, onAbout = onAbout, loading = loading)
            Spacer(Modifier.height(22.dp))

            when {
                loading && channels.isEmpty() -> LoadingRows()
                error != null && channels.isEmpty() -> ErrorState(error, onRetry)
                grouped.isEmpty() -> ErrorState("No matching channels found.", onRetry)
                else -> {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                        contentPadding = PaddingValues(bottom = 34.dp)
                    ) {
                        grouped.forEach { (category, list) ->
                            item(key = "category_$category") {
                                Box(Modifier.padding(top = 14.dp)) { CategoryRow(category, list.size) }
                            }
                            val rows = list.chunked(7)
                            itemsIndexed(rows, key = { i, _ -> "row_${category}_$i" }) { i, row ->
                                ChannelRow(row, onChannel, i)
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
    loading: Boolean
) {
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
            modifier = Modifier
                .width(370.dp)
                .focusable()
                .onPreviewKeyEvent { event ->
                    event.type == KeyEventType.KeyUp && event.key == Key.Enter
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
        HeaderButton(Icons.Default.Info, "About", onAbout)
    }
}

@Composable
private fun HeaderButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier
            .size(64.dp)
            .onFocusChanged { focused = it.isFocused }
            .focusable()
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
private fun ChannelRow(row: List<Channel>, onChannel: (Channel) -> Unit, rowIndex: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        row.forEachIndexed { index, channel ->
            ChannelCard(channel, onChannel, Modifier.weight(1f), rowIndex * 7 + index)
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
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyUp && (event.key == Key.Enter || event.key == Key.DirectionCenter)) {
                    onClick(channel); true
                } else false
            },
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
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var showBackButton by remember { mutableStateOf(false) }

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
        }
        player.addListener(listener)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerAutoShow = true
                    controllerHideOnTouch = true
                    setPlayer(player)
                    setControllerVisibilityListener(
                        object : PlayerView.ControllerVisibilityListener {
                            override fun onVisibilityChanged(visibility: Int) {
                                showBackButton = visibility == android.view.View.VISIBLE
                            }
                        }
                    )
                    requestFocus()
                }
            },
            modifier = Modifier.fillMaxSize().onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    Key.ChannelUp -> { onNext(); true }
                    Key.ChannelDown -> { onPrevious(); true }
                    else -> false
                }
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

        if (showBackButton || status == PlayStatus.LOADING || status == PlayStatus.RETRYING) {
            Surface(
                onClick = onBack,
                modifier = Modifier
                    .padding(14.dp)
                    .align(Alignment.TopStart)
                    .zIndex(10f)
                    .focusable(),
                shape = RoundedCornerShape(10.dp),
                color = Color(0xE6080A10),
                tonalElevation = 2.dp
            ) {
                Row(
                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.ArrowBack,
                        "Back",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        channel.name,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
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

    Box(
        Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF130B24), Color(0xFF07090E))))
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(44.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(Icons.Default.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(20.dp))
                Text("About", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(38.dp))
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
