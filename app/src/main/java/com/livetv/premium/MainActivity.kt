import androidx.activity.OnBackPressedCallback

package com.livetv.premium

import android.app.Activity
import android.os.Bundle
import androidx.activity.BackHandler
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.input.key.*
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
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity() {\n    private fun runOnBackPressed(action: () -> Unit) {\n        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {\n            override fun handleOnBackPressed() {\n                remove()\n                action()\n            }\n        })\n    }\n
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
    var showSettings by remember { mutableStateOf(false) }

    suspend fun loadPlaylist() {
        loading = true; error = null
        try {
            val result = PlaylistRepository.load(context)
            if (result.isNotEmpty()) channels = result else if (channels.isEmpty()) error = "No channels found."
        } catch (e: Exception) {
            if (channels.isEmpty()) error = e.message ?: "Unable to load playlist."
        } finally { loading = false }
    }

    LaunchedEffect(Unit) { loadPlaylist() }
    BackHandler(enabled = selected != null) { selected = null }
    BackHandler(enabled = selected == null && !showSettings) { showExit = true }

    MaterialTheme(colorScheme = darkColorScheme(
        background = Color(0xFF06080D), surface = Color(0xFF10151F), surfaceVariant = Color(0xFF181F2B), primary = Color.White
    )) {
        AnimatedContent(targetState = selected, transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(130)) }, label = "screen") { channel ->
            if (channel == null) HomeScreen(channels, loading, error, { scope.launch { loadPlaylist() } }, { selected = it }, { showSettings = true })
            else PlayerScreen(channel, channels, onBack = { selected = null }, onChannel = { selected = it })
        }

        if (showSettings) SettingsDialog(loading, { scope.launch { loadPlaylist() } }, { showSettings = false })
        if (showExit) AlertDialog(
            onDismissRequest = { showExit = false },
            title = { Text("Exit Live TV?") }, text = { Text("Are you sure you want to close the app?") },
            confirmButton = { TextButton(onClick = { activity.finish() }) { Text("Exit") } },
            dismissButton = { TextButton(onClick = { showExit = false }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun HomeScreen(channels: List<Channel>, loading: Boolean, error: String?, onRetry: () -> Unit, onChannel: (Channel) -> Unit, onSettings: () -> Unit) {
    var query by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("All") }
    val categories = remember(channels) { listOf("All") + channels.map { it.category.ifBlank { "Live TV" } }.distinct().sorted() }
    val filtered = remember(channels, query, selectedCategory) {
        channels.filter { c ->
            (selectedCategory == "All" || c.category.ifBlank { "Live TV" } == selectedCategory) &&
                (query.isBlank() || c.name.contains(query, true) || c.category.contains(query, true))
        }
    }
    val grouped = remember(filtered) { filtered.groupBy { it.category.ifBlank { "Live TV" } }.toSortedMap(String.CASE_INSENSITIVE_ORDER) }

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF0B0F18), Color(0xFF05070B))))) {
        Column(Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 26.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Logo(); Spacer(Modifier.width(16.dp))
                Column { Text(BrandConfig.APP_NAME, fontSize = 29.sp, fontWeight = FontWeight.ExtraBold); Text("Live channels • ${channels.size} available", color = Color(0xFF8993A3), fontSize = 13.sp) }
                Spacer(Modifier.weight(1f))
                FocusIconButton(Icons.Default.Settings, "Settings", onSettings)
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search channels…") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton({ query = "" }) { Icon(Icons.Default.Close, null) } },
                    modifier = Modifier.width(430.dp).height(58.dp).focusable(), shape = RoundedCornerShape(18.dp),
                    colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Color.White, unfocusedBorderColor = Color(0xFF303848))
                )
                Spacer(Modifier.width(18.dp))
                Text("${filtered.size} channels", color = Color(0xFF929AAA), fontSize = 14.sp)
            }
            Spacer(Modifier.height(17.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                categories.forEach { category ->
                    FilterChip(selected = selectedCategory == category, onClick = { selectedCategory = category }, label = { Text(category) })
                }
            }
            Spacer(Modifier.height(18.dp))

            when {
                loading && channels.isEmpty() -> LoadingRows()
                error != null && channels.isEmpty() -> ErrorState(error, onRetry)
                filtered.isEmpty() -> ErrorState("No matching channels.") { query = ""; selectedCategory = "All" }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(25.dp), contentPadding = PaddingValues(bottom = 34.dp)) {
                    grouped.forEach { (category, list) ->
                        item(key = "h_$category") {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.width(4.dp).height(25.dp).clip(RoundedCornerShape(3.dp)).background(Color.White))
                                Spacer(Modifier.width(10.dp)); Text(category, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(9.dp)); Text("${list.size}", color = Color(0xFF778092), fontSize = 13.sp)
                            }
                        }
                        item(key = "r_$category") {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp), contentPadding = PaddingValues(horizontal = 5.dp, vertical = 8.dp)) {
                                items(list, key = { it.id }) { ChannelCard(it, onChannel) }
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
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "cardScale")
    val borderColor by animateColorAsState(if (focused) Color.White else Color(0xFF252D3A), tween(120), label = "border")
    Box(Modifier.width(208.dp).height(136.dp).scale(scale).clip(RoundedCornerShape(17.dp)).background(Color(0xFF121823)).border(if (focused) 2.5.dp else 1.dp, borderColor, RoundedCornerShape(17.dp)).onFocusChanged { focused = it.isFocused }.focusable().onPreviewKeyEvent {
        if (it.type == KeyEventType.KeyUp && (it.key == Key.Enter || it.key == Key.DirectionCenter)) { onClick(channel); true } else false
    }.padding(11.dp)) {
        if (channel.logo.isNotBlank()) AsyncImage(channel.logo, channel.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize(0.60f).align(Alignment.Center))
        else Icon(Icons.Default.Tv, null, modifier = Modifier.size(50.dp).align(Alignment.Center), tint = Color(0xFF7D8798))
        Box(Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(6.dp)).background(Color(0xFFE3293D)).padding(horizontal = 6.dp, vertical = 3.dp)) { Text("LIVE", fontSize = 9.sp, fontWeight = FontWeight.Bold) }
        if (channel.streams.size > 1) Text("${channel.streams.size} streams", color = Color(0xFFB8C0CE), fontSize = 9.sp, modifier = Modifier.align(Alignment.TopEnd))
        Text(channel.name, fontSize = 12.sp, maxLines = 1, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun PlayerScreen(channel: Channel, channels: List<Channel>, onBack: () -> Unit, onChannel: (Channel) -> Unit) {
    val context = LocalContext.current
    var streamIndex by remember(channel.id) { mutableIntStateOf(0) }
    val streamUrl = channel.streams.getOrElse(streamIndex) { channel.url }
    val player = remember(channel.id, streamUrl) {
        ExoPlayer.Builder(context).build().apply {
            val builder = MediaItem.Builder().setUri(streamUrl)
            if (streamUrl.lowercase(Locale.US).contains(".m3u8")) builder.setMimeType(MimeTypes.APPLICATION_M3U8)
            setMediaItem(builder.build()); prepare(); playWhenReady = true
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    fun move(delta: Int) { val i = channels.indexOfFirst { it.id == channel.id }; if (i >= 0) onChannel(channels[(i + delta + channels.size) % channels.size]) }

    Box(Modifier.fillMaxSize().background(Color.Black).onPreviewKeyEvent { e ->
        if (e.type != KeyEventType.KeyDown) false else when (e.key) {
            Key.DirectionLeft -> { move(-1); true }; Key.DirectionRight -> { move(1); true }
            Key.DirectionUp, Key.ChannelUp -> { move(1); true }; Key.DirectionDown, Key.ChannelDown -> { move(-1); true }
            Key.Escape, Key.Back -> { onBack(); true }; Key.MediaPlayPause -> { if (player.isPlaying) player.pause() else player.play(); true }
            else -> false
        }
    }.focusable()) {
        AndroidView(factory = { ctx -> PlayerView(ctx).apply { useController = true; controllerAutoShow = true; setPlayer(player); requestFocus() } }, modifier = Modifier.fillMaxSize())
        Row(Modifier.align(Alignment.TopStart).padding(24.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xCC070A10)).padding(horizontal = 15.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("●", color = Color(0xFFFF3346)); Spacer(Modifier.width(7.dp)); Text(channel.name, fontWeight = FontWeight.Bold)
        }
        if (channel.streams.size > 1) {
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 25.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xDD0A0E15)).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                channel.streams.forEachIndexed { i, _ ->
                    FilterChip(selected = i == streamIndex, onClick = { streamIndex = i }, label = { Text("Stream ${i + 1}") })
                }
            }
        }
    }
}

@Composable private fun FocusIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    IconButton(onClick = onClick, modifier = Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(if (focused) Color.White else Color(0xFF171D28)).onFocusChanged { focused = it.isFocused }.focusable()) { Icon(icon, desc, tint = if (focused) Color.Black else Color.White) }
}

@Composable private fun SettingsDialog(loading: Boolean, onRefresh: () -> Unit, onClose: () -> Unit) {
    AlertDialog(onDismissRequest = onClose, title = { Text("Live TV Settings") }, text = { Column(verticalArrangement = Arrangement.spacedBy(14.dp)) { Text("Playlist: GitHub Raw M3U"); Text("Auto refresh: ${BrandConfig.REFRESH_INTERVAL_MS / 60000} minutes", color = Color(0xFF9BA5B5)); Button(onClick = onRefresh, enabled = !loading, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text(if (loading) "Refreshing…" else "Refresh channels") } } }, confirmButton = { TextButton(onClick = onClose) { Text("Close") } })
}

@Composable private fun Logo() {
    if (BrandConfig.REMOTE_LOGO_URL.isNotBlank()) AsyncImage(BrandConfig.REMOTE_LOGO_URL, "Logo", contentScale = ContentScale.Fit, modifier = Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)))
    else Box(Modifier.size(58.dp).clip(RoundedCornerShape(14.dp)).background(Color.White), contentAlignment = Alignment.Center) { Icon(Icons.Default.Tv, null, tint = Color.Black, modifier = Modifier.size(34.dp)) }
}

@Composable private fun LoadingRows() { Column(verticalArrangement = Arrangement.spacedBy(22.dp)) { repeat(3) { Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { repeat(5) { Box(Modifier.width(208.dp).height(136.dp).clip(RoundedCornerShape(17.dp)).background(Color(0xFF121722))) } } } } }

@Composable private fun ErrorState(message: String, retry: () -> Unit) { Column(Modifier.fillMaxWidth().padding(top = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) { Icon(Icons.Default.CloudOff, null, modifier = Modifier.size(50.dp), tint = Color(0xFF8E98A9)); Spacer(Modifier.height(13.dp)); Text(message, color = Color(0xFFB8C0CD), fontSize = 17.sp); Spacer(Modifier.height(15.dp)); Button(onClick = retry) { Text("Retry") } } }
