package com.livetv.premium

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

data class Channel(
    val id: String,
    val name: String,
    val url: String,
    val logo: String,
    val category: String
)

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
    var showExit by remember { mutableStateOf(false) }

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
        loadPlaylist()
    }

    BackHandler(enabled = selected != null) {
        selected = null
    }

    BackHandler(enabled = selected == null) {
        showExit = true
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Color(0xFF07090D),
            surface = Color(0xFF10141C),
            primary = Color.White
        )
    ) {
        AnimatedContent(
            targetState = selected,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "screen"
        ) { channel ->
            if (channel == null) {
                HomeScreen(
                    channels = channels,
                    loading = loading,
                    error = error,
                    onRetry = { scope.launch { loadPlaylist() } },
                    onChannel = { selected = it }
                )
            } else {
                PlayerScreen(
                    channel = channel,
                    onPrevious = {
                        val index = channels.indexOfFirst { it.id == channel.id }
                        if (index > 0) selected = channels[index - 1]
                    },
                    onNext = {
                        val index = channels.indexOfFirst { it.id == channel.id }
                        if (index >= 0 && index < channels.lastIndex) selected = channels[index + 1]
                    }
                )
            }
        }

        if (showExit) {
            AlertDialog(
                onDismissRequest = { showExit = false },
                title = { Text("Exit Live TV?") },
                text = { Text("Are you sure you want to close the app?") },
                confirmButton = {
                    TextButton(onClick = { activity.finish() }) { Text("Exit") }
                },
                dismissButton = {
                    TextButton(onClick = { showExit = false }) { Text("Cancel") }
                }
            )
        }
    }
}

@Composable
private fun HomeScreen(
    channels: List<Channel>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onChannel: (Channel) -> Unit
) {
    val grouped = remember(channels) {
        channels.groupBy { it.category.ifBlank { "Live TV" } }
            .toSortedMap(String.CASE_INSENSITIVE_ORDER)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF0A0D13), Color(0xFF050609))
                )
            )
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 52.dp, vertical = 28.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Logo()
                Spacer(Modifier.width(18.dp))
                Column {
                    Text(BrandConfig.APP_NAME, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    Text(
                        if (loading) "Loading live channels…" else "${channels.size} channels available",
                        color = Color(0xFF9EA5B2),
                        fontSize = 14.sp
                    )
                }
                Spacer(Modifier.weight(1f))
                Icon(Icons.Default.Search, null, tint = Color(0xFFB9C0CC), modifier = Modifier.size(30.dp))
            }

            Spacer(Modifier.height(28.dp))

            when {
                loading && channels.isEmpty() -> LoadingRows()
                error != null && channels.isEmpty() -> ErrorState(error, onRetry)
                grouped.isEmpty() -> ErrorState("No channels available.", onRetry)
                else -> {
                    LazyColumn(
                        state = rememberLazyListState(),
                        verticalArrangement = Arrangement.spacedBy(26.dp),
                        contentPadding = PaddingValues(bottom = 32.dp)
                    ) {
                        grouped.forEach { (category, list) ->
                            item(key = "header_$category") {
                                Text(
                                    category,
                                    fontSize = 23.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(start = 4.dp)
                                )
                            }
                            item(key = "row_$category") {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(18.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                                ) {
                                    itemsIndexed(list, key = { _, c -> c.id }) { _, channel ->
                                        ChannelCard(channel, onChannel)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChannelCard(channel: Channel, onClick: (Channel) -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (focused) 1.10f else 1f,
        animationSpec = tween(150),
        label = "focusScale"
    )

    Box(
        modifier = Modifier
            .width(210.dp)
            .height(132.dp)
            .scale(scale)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF121722))
            .then(
                if (focused) Modifier.border(3.dp, Color.White, RoundedCornerShape(16.dp))
                else Modifier.border(1.dp, Color(0xFF252C39), RoundedCornerShape(16.dp))
            )
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyUp &&
                    (it.key == Key.Enter || it.key == Key.DirectionCenter)
                ) {
                    onClick(channel)
                    true
                } else false
            }
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        if (channel.logo.isNotBlank()) {
            AsyncImage(
                model = channel.logo,
                contentDescription = channel.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(0.62f)
            )
        } else {
            Text(
                channel.name,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2
            )
        }

        Box(
            Modifier.align(Alignment.TopStart)
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFDA2C38))
                .padding(horizontal = 7.dp, vertical = 3.dp)
        ) {
            Text("LIVE", fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }

        Text(
            channel.name,
            fontSize = 12.sp,
            maxLines = 1,
            color = Color(0xFFE6E9EF),
            modifier = Modifier.align(Alignment.BottomStart)
        )
    }
}

@Composable
private fun PlayerScreen(
    channel: Channel,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val context = LocalContext.current
    val player = remember(channel.url) {
        ExoPlayer.Builder(context).build().apply {
            val itemBuilder = MediaItem.Builder().setUri(channel.url)
            if (channel.url.lowercase(Locale.US).contains(".m3u8")) {
                itemBuilder.setMimeType(MimeTypes.APPLICATION_M3U8)
            }
            setMediaItem(itemBuilder.build())
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(player) {
        onDispose { player.release() }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = true
                    controllerAutoShow = true
                    controllerHideOnTouch = true
                    player = player
                    requestFocus()
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (event.key) {
                        Key.ChannelUp -> { onNext(); true }
                        Key.ChannelDown -> { onPrevious(); true }
                        else -> false
                    }
                }
        )

        Row(
            Modifier
                .align(Alignment.TopStart)
                .padding(28.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x99070A0F))
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("●", color = Color(0xFFFF3B45), fontSize = 14.sp)
            Spacer(Modifier.width(8.dp))
            Text(channel.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
        }
    }
}

@Composable
private fun Logo() {
    if (BrandConfig.REMOTE_LOGO_URL.isNotBlank()) {
        AsyncImage(
            model = BrandConfig.REMOTE_LOGO_URL,
            contentDescription = "Logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(58.dp).clip(RoundedCornerShape(14.dp))
        )
    } else {
        Box(
            Modifier.size(58.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.White),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Default.Tv, null, tint = Color.Black, modifier = Modifier.size(34.dp))
        }
    }
}

@Composable
private fun LoadingRows() {
    Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
        repeat(4) {
            Row(horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                repeat(5) {
                    Box(
                        Modifier.width(210.dp).height(132.dp)
                            .clip(RoundedCornerShape(16.dp))
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
        Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(52.dp), tint = Color(0xFF9EA5B2))
        Spacer(Modifier.height(16.dp))
        Text(message, color = Color(0xFFB9C0CC), fontSize = 18.sp)
        Spacer(Modifier.height(18.dp))
        Button(onClick = retry) { Text("Retry") }
    }
}
