package com.livetv.premium

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Update
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

internal enum class RefreshStatus { IDLE, UPDATING, DONE, FAILED }

private fun agoText(ms: Long): String {
    if (ms <= 0L) return "Never"
    val minutes = (System.currentTimeMillis() - ms) / 60_000L
    return when {
        minutes < 1L -> "Just now"
        minutes < 60L -> "$minutes min ago"
        minutes < 24L * 60L -> "${minutes / 60L} hr ago"
        else -> "${minutes / (24L * 60L)} d ago"
    }
}

private fun nextText(lastSync: Long): String {
    if (lastSync <= 0L) return "soon"
    val left = lastSync + BrandConfig.REFRESH_INTERVAL_MS - System.currentTimeMillis()
    if (left <= 0L) return "any moment"
    return "in ${left / 60_000L + 1L} min"
}

private fun openLink(context: Context, uri: String, action: String = Intent.ACTION_VIEW) {
    try {
        context.startActivity(Intent(action, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Throwable) {
        Toast.makeText(context, "No app found to open this", Toast.LENGTH_SHORT).show()
    }
}

@Composable
internal fun AboutScreen(
    onBack: () -> Unit,
    channelCount: Int,
    categoryCount: Int,
    lastSync: Long,
    refreshStatus: RefreshStatus,
    refreshMessage: String,
    onRefresh: () -> Unit
) {
    val context = LocalContext.current
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val backFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        try {
            backFocus.requestFocus()
        } catch (_: Throwable) {
        }
    }
    val syncExact = if (lastSync > 0L) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(lastSync))
    } else "Not updated yet"

    val glow = rememberInfiniteTransition(label = "glow")
    val glowScale by glow.animateFloat(
        initialValue = 0.88f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(tween(2200), RepeatMode.Reverse),
        label = "glowScale"
    )

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF160B2B), Color(0xFF07090E))))
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (event.key) {
                    // Up / Down walk through the buttons and sections; at the ends the page just scrolls.
                    Key.DirectionDown -> {
                        if (!focusManager.moveFocus(FocusDirection.Down)) {
                            scope.launch { scroll.animateScrollBy(320f) }
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        if (!focusManager.moveFocus(FocusDirection.Up)) {
                            scope.launch { scroll.animateScrollBy(-320f) }
                        }
                        true
                    }
                    else -> false
                }
            }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 26.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(Icons.Default.ArrowBack, "Back", onBack, backFocus)
                Spacer(Modifier.width(20.dp))
                Text("About", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }

            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(top = 14.dp, bottom = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // ---- Hero
                Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.fillMaxSize()) {
                        drawCircle(
                            brush = Brush.radialGradient(listOf(Color(0x88A56BFF), Color.Transparent)),
                            radius = size.minDimension / 2f * glowScale
                        )
                    }
                    Logo(Modifier.size(112.dp))
                }
                Text(BrandConfig.APP_NAME, color = Color.White, fontSize = 32.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(2.dp))
                Text("Premium Live TV", color = Color(0xFFB982FF), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Color(0x33A56BFF))
                        .padding(horizontal = 14.dp, vertical = 5.dp)
                ) {
                    Text("Version ${BuildConfig.VERSION_NAME}", color = Color(0xFFD9B8FF), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(26.dp))

                // ---- Stats
                Row(Modifier.fillMaxWidth(0.84f), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    StatTile(Icons.Default.LiveTv, channelCount.toString(), "Total Live Channels", null, Modifier.weight(1f))
                    StatTile(Icons.Default.Category, categoryCount.toString(), "Categories", null, Modifier.weight(1f))
                    StatTile(Icons.Default.Update, agoText(lastSync), "Last Update", syncExact, Modifier.weight(1f))
                }
                Spacer(Modifier.height(16.dp))

                // ---- Status + manual refresh
                Surface(
                    modifier = Modifier.fillMaxWidth(0.84f),
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF121622),
                    border = BorderStroke(1.dp, Color(0xFF343B4E))
                ) {
                    Column(Modifier.padding(22.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(Modifier.size(9.dp).clip(CircleShape).background(Color(0xFF3DDC84)))
                                    Spacer(Modifier.width(8.dp))
                                    Text("Connected", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "Channels update automatically every hour - next ${nextText(lastSync)}",
                                    color = Color(0xFF9FA7B8),
                                    fontSize = 13.sp
                                )
                            }
                            Button(
                                onClick = onRefresh,
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFA56BFF), contentColor = Color.White)
                            ) {
                                if (refreshStatus == RefreshStatus.UPDATING) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                                } else {
                                    Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    if (refreshStatus == RefreshStatus.UPDATING) "Updating…" else "Update Now",
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (refreshStatus != RefreshStatus.IDLE && refreshMessage.isNotBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(
                                refreshMessage,
                                color = when (refreshStatus) {
                                    RefreshStatus.DONE -> Color(0xFF3DDC84)
                                    RefreshStatus.FAILED -> Color(0xFFFF8A80)
                                    else -> Color(0xFFB982FF)
                                },
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---- Features
                FocusCard(Modifier.fillMaxWidth(0.84f)) {
                    Text("Features", color = Color(0xFFC58CFF), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(12.dp))
                    val features = listOf(
                        Icons.Default.FlashOn to "Fast start - channels open instantly",
                        Icons.Default.PauseCircle to "Pause & continue, or jump back to live",
                        Icons.Default.FastForward to "Time bar, double-tap to seek",
                        Icons.Default.History to "Recently watched",
                        Icons.Default.Favorite to "Favorites - hold OK on a channel",
                        Icons.Default.Refresh to "Auto retry when a stream drops",
                        Icons.Default.Dialpad to "Type a channel number to jump",
                        Icons.Default.SettingsRemote to "Full remote (D-pad) control"
                    )
                    features.chunked(2).forEach { pair ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
                            pair.forEach { (icon, text) ->
                                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(icon, null, tint = Color(0xFFB66CFF), modifier = Modifier.size(20.dp))
                                    Spacer(Modifier.width(10.dp))
                                    Text(text, color = Color(0xFFE6E8EE), fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))

                // ---- Developer
                Surface(
                    modifier = Modifier.fillMaxWidth(0.84f),
                    shape = RoundedCornerShape(22.dp),
                    color = Color(0xFF121622),
                    border = BorderStroke(1.dp, Color(0xFF343B4E))
                ) {
                    Column(Modifier.padding(24.dp)) {
                        Text("Developer", color = Color(0xFFC58CFF), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                        Spacer(Modifier.height(14.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(64.dp)
                                    .clip(CircleShape)
                                    .background(Brush.linearGradient(listOf(Color(0xFFB66CFF), Color(0xFF5B4BFF)))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    BrandConfig.DEV_NAME.take(1).uppercase(),
                                    color = Color.White,
                                    fontSize = 28.sp,
                                    fontWeight = FontWeight.ExtraBold
                                )
                            }
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(BrandConfig.DEV_NAME, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                                Text("Developer of ${BrandConfig.APP_NAME}", color = Color(0xFF9FA7B8), fontSize = 13.sp)
                            }
                        }
                        Spacer(Modifier.height(18.dp))
                        val hasContact = BrandConfig.DEV_TELEGRAM_URL.isNotBlank() ||
                            BrandConfig.DEV_PHONE.isNotBlank() || BrandConfig.DEV_WEBSITE_URL.isNotBlank()
                        if (hasContact) {
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (BrandConfig.DEV_TELEGRAM_URL.isNotBlank()) {
                                    ContactButton(Icons.Default.Send, "Telegram") {
                                        openLink(context, BrandConfig.DEV_TELEGRAM_URL)
                                    }
                                }
                                if (BrandConfig.DEV_PHONE.isNotBlank()) {
                                    ContactButton(Icons.Default.Call, "Call") {
                                        openLink(context, "tel:${BrandConfig.DEV_PHONE}", Intent.ACTION_DIAL)
                                    }
                                }
                                if (BrandConfig.DEV_WEBSITE_URL.isNotBlank()) {
                                    ContactButton(Icons.Default.Language, "Website") {
                                        openLink(context, BrandConfig.DEV_WEBSITE_URL)
                                    }
                                }
                            }
                        } else {
                            Text("Contact details coming soon.", color = Color(0xFF9FA7B8), fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                        Text("Made with ♥ in Bangladesh", color = Color(0xFFB982FF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(18.dp))
                Text(
                    "© ${BrandConfig.APP_NAME} - all rights reserved",
                    color = Color(0xFF6C7488),
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun StatTile(icon: ImageVector, value: String, label: String, caption: String?, modifier: Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Color(0xFF121622),
        border = BorderStroke(1.dp, Color(0xFF343B4E))
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, tint = Color(0xFFB66CFF), modifier = Modifier.size(26.dp))
            Spacer(Modifier.height(8.dp))
            Text(value, color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            Spacer(Modifier.height(2.dp))
            Text(label, color = Color(0xFF9FA7B8), fontSize = 12.sp, textAlign = TextAlign.Center)
            if (caption != null) {
                Spacer(Modifier.height(3.dp))
                Text(caption, color = Color(0xFF6C7488), fontSize = 10.sp, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A card the remote can stop on, so the page scrolls section by section. */
@Composable
private fun FocusCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        modifier = modifier.onFocusChanged { focused = it.isFocused }.focusable(),
        shape = RoundedCornerShape(22.dp),
        color = Color(0xFF121622),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color(0xFFB36BFF) else Color(0xFF343B4E))
    ) {
        Column(Modifier.padding(24.dp)) { content() }
    }
}

@Composable
private fun ContactButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = Modifier.onFocusChanged { focused = it.hasFocus },
        shape = RoundedCornerShape(16.dp),
        color = if (focused) Color(0xFFA56BFF) else Color(0xFF1B2133),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color.White else Color(0xFF343B4E))
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        }
    }
}
