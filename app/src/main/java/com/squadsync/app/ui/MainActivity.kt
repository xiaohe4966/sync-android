package com.squadsync.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.squadsync.app.model.Peer
import com.squadsync.app.model.RemoteApp
import com.squadsync.app.net.SlaveService

class MainActivity : ComponentActivity() {

    private val vm: SquadViewModel by viewModels()

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* ignore; we keep going */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureNotificationPermission()
        // Bring up both halves of the app: foreground WS server + master
        // discovery. The user never has to press "被控" or "主控" again —
        // every phone in the room is automatically a peer of every other.
        AutoStartService.bringUp(this, vm)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SquadScreen(vm, this)
                }
            }
        }
    }

    override fun onDestroy() {
        // Keep the service running when the activity is just rotated / paused,
        // but on full process death we tear it down so NSD doesn't hold a
        // dangling port. (START_STICKY in SlaveService will recreate us
        // automatically if the OS kills the process while the user expects it
        // to be running.)
        super.onDestroy()
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SquadScreen(vm: SquadViewModel, activity: ComponentActivity) {
    val peers by vm.peers.collectAsState()
    var roomCode by remember { mutableStateOf(vm.roomCode) }
    var deviceName by remember { mutableStateOf(vm.deviceName) }
    var relayUrl by remember { mutableStateOf(com.squadsync.app.model.AppPrefs.relayUrl) }

    // Master-side slider position (0..100 percent). Initialised from the
    // device's current media volume so it never starts at "0%" on a quiet phone.
    var globalPercent by remember { mutableStateOf(vm.localVolumePercent().toFloat()) }

    // Ticking clock so "信号弱 → 离线" transitions render every second.
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            nowMs = System.currentTimeMillis()
        }
    }

    // Brightness slider position (0..100 percent). Initialised from the
    // device's current screen brightness.
    var brightnessPercent by remember { mutableStateOf(vm.localBrightnessPercent().toFloat()) }

    // Which peer's "open apps" dialog is open, if any.
    var openAppsFor by remember { mutableStateOf<Peer?>(null) }
    var openPkgFor by remember { mutableStateOf<Peer?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("SquadSync · 房间 $roomCode") }) }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Config card collapsed by default — frees the screen for the slider and
// device list. Tap the row to expand the room/device fields and action buttons.
            var configExpanded by remember { mutableStateOf(false) }
            Card(elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { configExpanded = !configExpanded }
                    ) {
                        Text(
                            "配置",
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "房间 $roomCode · $deviceName",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            modifier = Modifier.weight(2f)
                        )
                        Icon(
                            if (configExpanded) Icons.Default.KeyboardArrowUp
                            else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (configExpanded) "收起" else "展开"
                        )
                    }
                    // Hoist relayConnected outside of the if/else so the
                    // "current route" hint below the URL field can read it.
                    val relayOn by vm.relayConnected.collectAsState()
                    if (configExpanded) {
                        OutlinedTextField(
                            value = roomCode, onValueChange = { roomCode = it },
                            label = { Text("房间码（必须与其他手机一致）") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = deviceName, onValueChange = { deviceName = it },
                            label = { Text("本机昵称") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        // Optional: relay server URL. Empty = LAN only.
                        OutlinedTextField(
                            value = relayUrl, onValueChange = { relayUrl = it },
                            label = { Text("转发服务器 URL（可选，跨网用）") },
                            placeholder = { Text("ws://1.2.3.4:7879 或 wss://relay.example.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .background(
                                        when {
                                            relayUrl.isBlank() -> Color(0xFF9E9E9E)
                                            relayOn -> Color(0xFF2E7D32)
                                            else -> Color(0xFFEF6C00)
                                        }
                                    )
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    relayUrl.isBlank() -> "未配置（仅局域网）"
                                    relayOn -> "已连 $relayUrl"
                                    else -> "未连接 $relayUrl"
                                },
                                fontSize = 12.sp,
                                color = Color.Gray,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedButton(
                                onClick = {
                                    android.util.Log.i("SquadScreen", "试连 relay=$relayUrl")
                                    vm.setRelayUrl(relayUrl)
                                },
                                enabled = true,
                                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                            ) { Text("试连", fontSize = 12.sp) }
                        }
                        // Tell the user which transport their next command
                        // will take. This is the most-asked question while
                        // testing ("did my command reach the relay or only
                        // mDNS?"). Updated whenever either connection state
                        // changes.
                        Text(
                            when {
                                relayUrl.isBlank() ->
                                    "📡 命令通过：本地 mDNS 局域网"
                                relayOn ->
                                    "📡 命令通过：转发服务器 ($relayUrl)"
                                else ->
                                    "📡 命令通过：本地 mDNS 局域网（转发服务器未连接）"
                            },
                            fontSize = 11.sp,
                            color = Color(0xFF1565C0),
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                        // Single "退出 SquadSync" button. The service is now
                        // started automatically on launch and there is no
                        // separate "主控" / "被控" choice — every phone plays
                        // both roles.
                        OutlinedButton(
                            onClick = {
                                AutoStartService.tearDown(activity, vm)
                                activity.finish()
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("退出 SquadSync") }
                    }
                }
            }

            Card(elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("音量（滑动即时同步给所有设备）", fontWeight = FontWeight.Bold)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.VolumeUp, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = globalPercent,
                            onValueChange = {
                                // Throttle: only push when the integer percent steps
                                // (avoid a flood of frames between 33 and 34).
                                val newPct = it.toInt().coerceIn(0, 100)
                                if (newPct != globalPercent.toInt()) {
                                    globalPercent = newPct.toFloat()
                                    vm.broadcastVolumePercent(newPct)
                                }
                            },
                            valueRange = 0f..100f,
                            steps = 99, // 1% granularity
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${globalPercent.toInt()}%", fontSize = 14.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = {
                            globalPercent = 0f
                            vm.broadcastVolumePercent(0)
                        }) {
                            Icon(Icons.Default.VolumeOff, contentDescription = null)
                            Spacer(Modifier.width(4.dp)); Text("静音全部")
                        }
                        OutlinedButton(onClick = {
                            globalPercent = 100f
                            vm.broadcastVolumePercent(100)
                        }) {
                            Icon(Icons.Default.VolumeUp, contentDescription = null)
                            Spacer(Modifier.width(4.dp)); Text("最大")
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        IconButton(onClick = { vm.broadcastPrev() }) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "上一首",
                                modifier = Modifier.size(40.dp))
                        }
                        IconButton(onClick = { vm.broadcastToggle() }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "播放/暂停",
                                modifier = Modifier.size(48.dp))
                        }
                        IconButton(onClick = { vm.broadcastNext() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "下一首",
                                modifier = Modifier.size(40.dp))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        OutlinedButton(onClick = { vm.broadcastPlay() }) {
                            Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text("播放")
                        }
                        OutlinedButton(onClick = { vm.broadcastPause() }) {
                            Icon(Icons.Default.Pause, null); Spacer(Modifier.width(4.dp)); Text("暂停")
                        }
                    }
                }
            }

            Card(elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("屏幕亮度（滑动即时同步给所有设备）", fontWeight = FontWeight.Bold)

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.WbSunny, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Slider(
                            value = brightnessPercent,
                            onValueChange = {
                                val newPct = it.toInt().coerceIn(0, 100)
                                if (newPct != brightnessPercent.toInt()) {
                                    brightnessPercent = newPct.toFloat()
                                    vm.broadcastBrightnessPercent(newPct)
                                }
                            },
                            valueRange = 0f..100f,
                            steps = 99,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("${brightnessPercent.toInt()}%", fontSize = 14.sp)
                    }
                    if (!vm.hasBrightnessPermission()) {
                        Text(
                            "提示：本机的亮度控制需要「修改系统设置」权限。被控端请在被控手机上手动开启（设置 → 应用 → SquadSync → 修改系统设置）。",
                            fontSize = 11.sp,
                            color = Color(0xFFEF6C00)
                        )
                    }
                }
            }

            // ---- Activity log ----
            Card(elevation = CardDefaults.cardElevation(1.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("最近活动", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        TextButton(onClick = { EventLog.info("(已清空)") }) {
                            Text("清空", fontSize = 12.sp)
                        }
                    }
                    val entries by EventLog.entries.collectAsState()
                    if (entries.isEmpty()) {
                        Text("（暂无活动）", fontSize = 12.sp, color = Color.Gray)
                    } else {
                        // Show newest first; cap to 8 lines so the screen doesn't
                        // get overwhelmed.
                        entries.reversed().take(8).forEach { e ->
                            Text(
                                EventLog.format(e),
                                fontSize = 11.sp,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                color = when (e.level) {
                                    EventLog.Level.Send -> Color(0xFF1565C0)
                                    EventLog.Level.Ack -> Color(0xFF2E7D32)
                                    EventLog.Level.Info -> Color.DarkGray
                                    EventLog.Level.Error -> Color(0xFFC62828)
                                }
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "已发现 ${peers.size} 台设备（${peers.values.count { it.selected }} 已选）",
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                if (peers.isNotEmpty()) {
                    TextButton(onClick = {
                        val allSelected = peers.values.all { it.selected }
                        vm.selectAll(!allSelected)
                    }) {
                        Text(
                            if (peers.values.all { it.selected }) "全不选" else "全选",
                            fontSize = 12.sp
                        )
                    }
                }
            }
            if (peers.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text("暂无设备。请确保所有手机在同一 WiFi，并设置相同房间码。")
                }
            } else {
                // Plain Column (already in a verticalScroll) — LazyColumn would
                // crash with "infinity max height" because it would also try to
                // be scrollable inside an already-scrollable parent.
                peers.values.toList().forEach { peer ->
                    PeerRow(peer, nowMs, vm) { action ->
                        when (action) {
                            "tog" -> vm.sendTo(peer.id, "toggle")
                            "mute" -> vm.sendTo(peer.id, "mute")
                            "unmute" -> vm.sendTo(peer.id, "unmute")
                            "apps" -> {
                                android.util.Log.i("SquadScreen", "open apps for ${peer.id}")
                                vm.requestAppsFor(peer.id)
                                openAppsFor = peer
                            }
                            "pkg" -> {
                                openPkgFor = peer
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- Apps picker dialog ----
    if (openAppsFor != null) {
        AppPickerDialog(
            peer = openAppsFor!!,
            onDismiss = { openAppsFor = null },
            onPick = { pkg ->
                vm.launchAppOn(openAppsFor!!.id, pkg)
                openAppsFor = null
            }
        )
    }

    // ---- Direct-package-name dialog ----
    if (openPkgFor != null) {
        PackageDialog(
            peer = openPkgFor!!,
            onDismiss = { openPkgFor = null },
            onLaunch = { pkg ->
                vm.launchAppOn(openPkgFor!!.id, pkg)
                openPkgFor = null
            }
        )
    }
}

/** Modal dialog listing the apps the selected slave reported via LIST_APPS. */
@Composable
private fun AppPickerDialog(
    peer: Peer,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在 ${peer.name} 上打开应用") },
        text = {
            if (peer.apps.isEmpty()) {
                Text("正在从该设备获取应用列表…\n请稍候再点一次「打开应用」。")
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 480.dp)) {
                    items(peer.apps, key = { it.packageName }) { app ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    android.util.Log.i("AppPicker", "click ${app.packageName}")
                                    onPick(app.packageName)
                                }
                                .padding(vertical = 10.dp, horizontal = 4.dp)
                        ) {
                            Text(
                                app.label,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            if (app.isSystem) {
                                Text("系统", fontSize = 10.sp, color = Color.Gray)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("关闭") }
        }
    )
}

/**
 * Lightweight dialog: type an Android package name and we send a
 * `launch_app` with that target. Useful when the slave's app list
 * hasn't loaded yet or the app you want isn't in it.
 */
@Composable
private fun PackageDialog(
    peer: Peer,
    onDismiss: () -> Unit,
    onLaunch: (String) -> Unit
) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("在 ${peer.name} 上打开包") },
        text = {
            Column {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it.trim() },
                    label = { Text("包名") },
                    placeholder = { Text("例如 com.luna.music") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "提示：常见音乐 / 视频 / 工具包名",
                    fontSize = 11.sp,
                    color = Color.Gray
                )
                // Quick-pick chips for the apps the user opens most.
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "com.luna.music" to "汽水",
                        "com.tencent.qqmusic" to "QQ音乐",
                        "com.netease.cloudmusic" to "网易云",
                        "com.android.settings" to "设置"
                    ).forEach { (pkg, label) ->
                        androidx.compose.material3.AssistChip(
                            onClick = { input = pkg },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { if (input.isNotEmpty()) onLaunch(input) },
                enabled = input.isNotEmpty()
            ) { Text("打开") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

/**
 * Visual state of a peer card.
 *   - [Online]    : WebSocket is up + state frame received in last 6 s
 *   - [Connecting]: Discovered, WS not yet open
 *   - [Stale]     : Was online but no state for > 6 s (intermittent)
 *   - [Offline]   : Never seen live OR was removed from the network
 */
private enum class PeerStatus(val label: String) {
    Online("已连接"),
    Connecting("连接中"),
    Stale("信号弱"),
    Offline("离线")
}

private fun peerStatusOf(peer: Peer, now: Long): PeerStatus {
    if (!peer.online) {
        // WS not open. Distinguish "never connected" from "lost long ago" so the
        // user can tell whether to wait or investigate.
        val age = now - peer.lastSeenMs
        return if (age < 30_000) PeerStatus.Connecting else PeerStatus.Offline
    }
    val age = now - peer.lastSeenMs
    return when {
        age < 6_000 -> PeerStatus.Online
        age < 20_000 -> PeerStatus.Stale
        else -> PeerStatus.Stale   // very stale but WS still claims online — keep
                                    // the dot blue until next heartbeat
    }
}

@Composable
private fun PeerRow(
    peer: Peer,
    now: Long,
    vm: com.squadsync.app.ui.SquadViewModel,
    onAction: (String) -> Unit
) {
    val status = peerStatusOf(peer, now)
    val (statusColor, dotColor) = when (status) {
        PeerStatus.Online -> Color(0xFF2E7D32) to Color(0xFF2E7D32)
        PeerStatus.Connecting -> Color(0xFF1565C0) to Color(0xFF64B5F6)
        PeerStatus.Stale -> Color(0xFFEF6C00) to Color(0xFFFFB74D)
        PeerStatus.Offline -> Color(0xFF616161) to Color(0xFFBDBDBD)
    }

    Card(elevation = CardDefaults.cardElevation(1.dp)) {
        Column(Modifier.padding(12.dp)) {
            // Row 1: status dot + name + badge
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .background(dotColor)
                )
                Spacer(Modifier.width(8.dp))
                // Multi-select checkbox — tapping the name also toggles it.
                Checkbox(
                    checked = peer.selected,
                    onCheckedChange = { vm.setPeerSelected(peer.id, it) }
                )
                Text(
                    peer.name,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .weight(1f)
                        .clickable { vm.setPeerSelected(peer.id, !peer.selected) }
                )
                // Connection badge
                Box(
                    Modifier
                        .background(statusColor.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        status.label,
                        fontSize = 11.sp,
                        color = statusColor,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "音量 ${peer.volume}/${peer.maxVolume}% · 亮度 ${peer.brightness}%",
                    fontSize = 14.sp,
                    color = if (status == PeerStatus.Online) Color.Unspecified else Color.Gray,
                    modifier = Modifier.weight(1f)
                )
                Text(peer.id, fontSize = 11.sp, color = Color.Gray)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = { onAction("tog") },
                    enabled = peer.online
                ) { Text("播放/暂停") }
                OutlinedButton(
                    onClick = { onAction("mute") },
                    enabled = peer.online
                ) { Text("静音") }
                OutlinedButton(
                    onClick = { onAction("unmute") },
                    enabled = peer.online
                ) { Text("取消静音") }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { onAction("apps") },
                enabled = peer.online,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Apps, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (peer.apps.isEmpty()) "打开应用（先获取列表）"
                    else "打开应用（${peer.apps.size} 个）"
                )
            }
            Spacer(Modifier.height(4.dp))
            // "Type package name" — fast path for known apps. Avoids the
            // round-trip to list every installed package on the slave.
            OutlinedButton(
                onClick = { onAction("pkg") },
                enabled = peer.online,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("输入包名直接打开…", fontSize = 13.sp)
            }
        }
    }
}