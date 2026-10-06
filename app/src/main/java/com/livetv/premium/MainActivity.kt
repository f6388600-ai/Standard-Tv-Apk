package com.livetv.premium

import android.app.Activity
import android.os.Bundle
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
        setContent { LiveTvApp() }
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
                PlayerScreen(
                    channel = channel,
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
                        verticalArrangement = Arrangement.spacedBy(28.dp),
                        contentPadding = PaddingValues(bottom = 34.dp)
                    ) {
                        grouped.forEach { (category, list) ->
                            item(key = "category_$category") {
                                CategoryRow(category, list.size)
                            }
                            item(key = "channels_$category") {
                                ChannelGrid7(list, onChannel)
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
private fun ChannelGrid7(channels: List<Channel>, onChannel: (Channel) -> Unit) {
    val rows = channels.chunked(7)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                row.forEachIndexed { index, channel ->
                    ChannelCard(channel, onChannel, Modifier.weight(1f), rowIndex * 7 + index)
                }
                repeat(7 - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel, onClick: (Channel) -> Unit, weight: Modifier, position: Int) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.035f else 1f, tween(130), label = "focusScale")
    Surface(
        onClick = { onClick(channel) },
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
            AsyncImage(
                model = if (channel.logo.isBlank()) painterResource(R.drawable.app_logo) else channel.logo,
                contentDescription = channel.name,
                placeholder = painterResource(R.drawable.app_logo),
                error = painterResource(R.drawable.app_logo),
                contentScale = ContentScale.Fit,
                modifier = Modifier.height(88.dp).fillMaxWidth(0.78f)
            )
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

@Composable
private fun PlayerScreen(channel: Channel, onBack: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var retryCount by remember(channel.url) { mutableStateOf(0) }
    var showBackButton by remember { mutableStateOf(false) }
    val player = remember(channel.url) {
        ExoPlayer.Builder(context).build().apply {
            val itemBuilder = MediaItem.Builder().setUri(channel.url)
            if (channel.url.lowercase(Locale.US).contains(".m3u8")) itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            setMediaItem(itemBuilder.build())
            playWhenReady = true
            prepare()
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
            override fun onPlayerError(error: PlaybackException) {
                if (retryCount < 2) {
                    retryCount++
                    player.prepare()
                    player.playWhenReady = true
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

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerAutoShow = true
                    controllerHideOnTouch = true
                    setPlayer(player)
                    setControllerVisibilityListener { visibility ->
                        showBackButton = visibility == android.view.View.VISIBLE
                    }
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

        if (showBackButton) {
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
    if (BrandConfig.REMOTE_LOGO_URL.isNotBlank()) {
        AsyncImage(
            model = BrandConfig.REMOTE_LOGO_URL,
            contentDescription = BrandConfig.APP_NAME,
            error = painterResource(R.drawable.app_logo),
            placeholder = painterResource(R.drawable.app_logo),
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(RoundedCornerShape(18.dp))
        )
    } else {
        AsyncImage(
            model = painterResource(R.drawable.app_logo),
            contentDescription = BrandConfig.APP_NAME,
            contentScale = ContentScale.Fit,
            modifier = modifier.clip(RoundedCornerShape(18.dp))
        )
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
