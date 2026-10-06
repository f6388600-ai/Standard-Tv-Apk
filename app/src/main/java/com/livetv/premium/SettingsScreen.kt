package com.livetv.premium

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.DataSaverOn
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

@Composable
internal fun SettingsScreen(
    onBack: () -> Unit,
    sleepMinutes: Int,
    onSleep: (Int) -> Unit,
    onClearRecent: () -> Unit,
    onClearFavorites: () -> Unit
) {
    val context = LocalContext.current
    var autoResume by remember { mutableStateOf(SettingsStore.autoResume(context)) }
    var lowData by remember { mutableStateOf(SettingsStore.lowData(context)) }
    var buffer by remember { mutableStateOf(SettingsStore.bufferMinutes(context)) }
    val firstFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        delay(120)
        try {
            firstFocus.requestFocus()
        } catch (_: Throwable) {
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(Color(0xFF160B2B), Color(0xFF07090E))))
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 44.dp, vertical = 26.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                HeaderButton(Icons.Default.ArrowBack, "Back", onBack)
                Spacer(Modifier.width(20.dp))
                Text("Settings", color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Column(
                    Modifier
                        .widthIn(max = 780.dp)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 18.dp, bottom = 28.dp),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
                ) {
                    SettingRow(
                        Icons.Default.PlayCircle,
                        "Resume last channel",
                        "Open the channel you were watching when the app starts",
                        if (autoResume) "On" else "Off",
                        Modifier.focusRequester(firstFocus)
                    ) {
                        autoResume = !autoResume
                        SettingsStore.setAutoResume(context, autoResume)
                    }
                    SettingRow(
                        Icons.Default.DataSaverOn,
                        "Data saver",
                        "Limit video to SD quality (applies to the next channel you open)",
                        if (lowData) "On" else "Off"
                    ) {
                        lowData = !lowData
                        SettingsStore.setLowData(context, lowData)
                    }
                    SettingRow(
                        Icons.Default.Timer,
                        "Buffer time",
                        "How much live video is kept ready for pause & continue",
                        if (buffer == 0) "Auto" else "$buffer min"
                    ) {
                        buffer = when (buffer) {
                            0 -> 2
                            2 -> 5
                            else -> 0
                        }
                        SettingsStore.setBufferMinutes(context, buffer)
                    }
                    SettingRow(
                        Icons.Default.Bedtime,
                        "Sleep timer",
                        "Close the app automatically after this time",
                        if (sleepMinutes == 0) "Off" else "$sleepMinutes min"
                    ) {
                        val next = when (sleepMinutes) {
                            0 -> 30
                            30 -> 60
                            60 -> 90
                            else -> 0
                        }
                        onSleep(next)
                    }
                    SettingRow(
                        Icons.Default.History,
                        "Clear recently watched",
                        "Remove the Recently Watched row from Home",
                        "Clear"
                    ) {
                        onClearRecent()
                        Toast.makeText(context, "Recently watched cleared", Toast.LENGTH_SHORT).show()
                    }
                    SettingRow(
                        Icons.Default.Favorite,
                        "Clear favorites",
                        "Remove all channels from My Favorites",
                        "Clear"
                    ) {
                        onClearFavorites()
                        Toast.makeText(context, "Favorites cleared", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    value: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.hasFocus },
        shape = RoundedCornerShape(18.dp),
        color = if (focused) Color(0xFF241447) else Color(0xFF121622),
        border = BorderStroke(if (focused) 2.dp else 1.dp, if (focused) Color(0xFFB36BFF) else Color(0xFF343B4E))
    ) {
        Row(Modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Color(0xFFB982FF), modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, color = Color(0xFF9FA7B8), fontSize = 13.sp)
            }
            Spacer(Modifier.width(16.dp))
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (focused) Color(0xFFA56BFF) else Color(0xFF232A3E))
                    .padding(horizontal = 16.dp, vertical = 7.dp)
            ) {
                Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
