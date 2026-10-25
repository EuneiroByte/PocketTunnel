package com.kotunnel.client

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kotunnel.client.data.ConfigStore
import com.kotunnel.client.service.TunnelService
import com.kotunnel.client.tunnel.LogLine
import com.kotunnel.client.tunnel.TunnelEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

private val BrandPrimary = Color(0xFF4A6FA5)
private val BrandPrimaryDark = Color(0xFF9FB6DC)
private val FeishuBlue = Color(0xFF3370FF)
private val FeishuBlueDark = Color(0xFF8FB0FF)
private val BrandSurface = Color(0xFFF4F6FB)
private val BrandSurfaceDark = Color(0xFF121212)

private val LightColors = lightColorScheme(
    primary = BrandPrimary, onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE5F3), onPrimaryContainer = Color(0xFF1A2A47),
    secondary = Color(0xFF6B89B8),
    background = BrandSurface, surface = Color.White, surfaceVariant = Color(0xFFE8ECF4),
)
private val DarkColors = darkColorScheme(
    primary = BrandPrimaryDark, onPrimary = Color(0xFF1A2A47),
    primaryContainer = Color(0xFF2A3F63), onPrimaryContainer = Color(0xFFDDE5F3),
    secondary = Color(0xFF9FB6DC),
    background = BrandSurfaceDark, surface = Color(0xFF1E1E1E), surfaceVariant = Color(0xFF2A2A2A),
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject lateinit var config: ConfigStore
    @Inject lateinit var httpMcp: com.kotunnel.client.mcp.HttpMcpClient
    @Inject lateinit var feishuApi: com.kotunnel.client.mcp.FeishuApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppRoot(config, httpMcp, feishuApi) }
    }
}

@Composable
private fun AppRoot(config: ConfigStore,
                   httpMcp: com.kotunnel.client.mcp.HttpMcpClient,
                   feishuApi: com.kotunnel.client.mcp.FeishuApiClient) {
    var themeMode by remember { mutableIntStateOf(config.themeMode) }
    var mcpMode by remember { mutableStateOf(config.mcpMode) }
    val systemDark = isSystemInDarkTheme()
    val darkTheme = when (themeMode) { 1 -> false; 2 -> true; else -> systemDark }
    val context = LocalContext.current
    val baseColors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val feishuMode = mcpMode == "feishu"
    val primary = when {
        feishuMode && darkTheme -> FeishuBlueDark
        feishuMode -> FeishuBlue
        darkTheme -> BrandPrimaryDark
        else -> BrandPrimary
    }
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) baseColors.copy(primary = primary)
    else if (feishuMode) (if (darkTheme) DarkColors else LightColors).copy(primary = primary)
    else baseColors

    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            TunnelScaffold(config, themeMode, mcpMode, httpMcp, feishuApi,
                onThemeChange = { themeMode = it; config.themeMode = it },
                onModeChange = { mcpMode = it })
        }
    }
}

private enum class Tab(val label: String) { OVERVIEW("Overview"), LOGS("Logs"), SETTINGS("Settings") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TunnelScaffold(
    config: ConfigStore,
    themeMode: Int,
    mcpMode: String,
    httpMcp: com.kotunnel.client.mcp.HttpMcpClient,
    feishuApi: com.kotunnel.client.mcp.FeishuApiClient,
    onThemeChange: (Int) -> Unit,
    onModeChange: (String) -> Unit
) {
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }

    // Request POST_NOTIFICATIONS on Android 13+
    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }
    val ctx = LocalContext.current
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
            if (!granted) notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        bottomBar = {
            NavigationBar(Modifier.imePadding()) {
                NavigationBarItem(
                    selected = tab == Tab.OVERVIEW,
                    onClick = { tab = Tab.OVERVIEW },
                    icon = { Icon(Icons.Default.Home, null) },
                    label = { Text("Overview") }
                )
                NavigationBarItem(
                    selected = tab == Tab.LOGS,
                    onClick = { tab = Tab.LOGS },
                    icon = { Icon(Icons.Default.List, null) },
                    label = { Text("Logs") }
                )
                NavigationBarItem(
                    selected = tab == Tab.SETTINGS,
                    onClick = { tab = Tab.SETTINGS },
                    icon = { Icon(Icons.Default.Settings, null) },
                    label = { Text("Settings") }
                )
            }
        }
    ) { padding ->
        Crossfade(targetState = tab, label = "tab") { t ->
            when (t) {
                Tab.OVERVIEW -> OverviewTab(config, padding, onGoSettings = { tab = Tab.SETTINGS }, onGoLogs = { tab = Tab.LOGS })
                Tab.LOGS -> LogsTab(padding)
                Tab.SETTINGS -> SettingsTab(config, themeMode, mcpMode, httpMcp, feishuApi, onThemeChange, onModeChange, padding)
            }
        }
    }
}

@Composable
private fun OverviewTab(
    config: ConfigStore,
    padding: androidx.compose.foundation.layout.PaddingValues,
    onGoSettings: () -> Unit,
    onGoLogs: () -> Unit
) {
    val running by TunnelService.running.collectAsState()
    val status: TunnelEngine.Status by TunnelService.status.collectAsState()
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // Keep a rolling log buffer in memory for overview + diagnostics.
    var recentLogs by remember { mutableStateOf(listOf<LogLine>()) }
    LaunchedEffect(Unit) {
        TunnelService.logs.collect { line ->
            // State writes must happen on the main thread; SharedFlow emits on the
            // emitter's (IO) thread by default, so hop to Main.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                recentLogs = (recentLogs + line).takeLast(200)
            }
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackHost) }) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(inner)
                .padding(20.dp)
                .verticalScroll(rememberScrollState())
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                "KoTunnel",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "OpenAI MCP Tunnel",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
            )

            // Big status card
            ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusHero(status, running)
                    Row {
                        OutlinedButton(onClick = onGoSettings, shape = RoundedCornerShape(16.dp)) { Text("Settings") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = onGoLogs, shape = RoundedCornerShape(16.dp)) { Text("Logs") }
                    }
                }
            }

            // Start/Stop
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(
                    onClick = {
                        if (config.tunnelId.isBlank() || config.apiKey.isBlank()) {
                            scope.launch { snackHost.showSnackbar("Please configure Tunnel ID + API Key in Settings") }
                            return@FilledTonalButton
                        }
                        val intent = Intent(MyApp.app, TunnelService::class.java).apply { action = TunnelService.ACTION_START }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) MyApp.app.startForegroundService(intent)
                        else MyApp.app.startService(intent)
                    },
                    enabled = !running, shape = RoundedCornerShape(16.dp)
                ) { Text("Start") }
                OutlinedButton(
                    onClick = {
                        val intent = Intent(MyApp.app, TunnelService::class.java).apply { action = TunnelService.ACTION_STOP }
                        MyApp.app.startService(intent)
                    },
                    enabled = running, shape = RoundedCornerShape(16.dp)
                ) { Text("Stop") }
            }

            // Diagnostics copy
            OutlinedButton(
                onClick = {
                    val diag = buildDiagnostics(config, running, status, recentLogs)
                    val cm = MyApp.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("kotunnel-diagnostics", diag))
                    scope.launch { snackHost.showSnackbar("Diagnostics copied to clipboard") }
                },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)
            ) { Text("Copy diagnostics") }

            // Recent log preview
            if (recentLogs.isNotEmpty()) {
                Text("Recent", style = MaterialTheme.typography.titleMedium)
                ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        recentLogs.takeLast(5).reversed().forEach { LogRow(it) }
                    }
                }
            }
        }
    }
}

private fun buildDiagnostics(
    config: ConfigStore,
    running: Boolean,
    status: TunnelEngine.Status,
    logs: List<LogLine>
): String {
    val tid = config.tunnelId
    val tidMasked = if (tid.length > 8) tid.take(4) + "…" + tid.takeLast(4) else "(unset)"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    return buildString {
        appendLine("KoTunnel v${com.kotunnel.client.BuildConfig.VERSION_NAME} diagnostics")
        appendLine("time: ${fmt.format(Date())}")
        appendLine("tunnel_id: $tidMasked")
        appendLine("api_key: ${if (config.apiKey.isNotBlank()) "set (${config.apiKey.length} chars)" else "MISSING"}")
        appendLine("base_url: ${config.baseUrl}")
        appendLine("mcp_mode: ${config.mcpMode}")
        appendLine("feishu_app_id: ${if (config.feishuAppId.isNotBlank()) "set" else "MISSING"}")
        appendLine("service_running: $running")
        appendLine("status: ${status::class.simpleName}")
        if (status is TunnelEngine.Status.Error) appendLine("error: ${status.message}")
        if (status is TunnelEngine.Status.AuthError) appendLine("auth_error: ${status.message}")
        appendLine("--- last 20 logs ---")
        logs.takeLast(20).forEach {
            appendLine("${fmt.format(Date(it.ts))} [${it.level}] ${it.msg}")
        }
    }
}

@Composable
private fun StatusHero(status: TunnelEngine.Status, running: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        when {
            !running -> StatusIcon(Color(0xFF9AA0A6), "Stopped", spinner = false)
            status is TunnelEngine.Status.Polling -> StatusIcon(MaterialTheme.colorScheme.primary, "Connecting…", spinner = true)
            status is TunnelEngine.Status.Connected -> StatusIcon(Color(0xFF34A853), "Connected", spinner = false, check = true)
            status is TunnelEngine.Status.AuthError -> StatusIcon(Color(0xFFD93025), "Auth error", spinner = false, error = true)
            status is TunnelEngine.Status.Error -> StatusIcon(Color(0xFFD93025), "Error", spinner = false, error = true)
            else -> StatusIcon(Color(0xFF9AA0A6), "Idle", spinner = false)
        }
        Column {
            val sub = when (val s = TunnelService.status.value) {
                is TunnelEngine.Status.AuthError -> s.message ?: ""
                is TunnelEngine.Status.Error -> s.message ?: ""
                else -> ""
            }
            if (sub.isNotBlank()) Text(sub, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LogsTab(padding: androidx.compose.foundation.layout.PaddingValues) {
    var logs by remember { mutableStateOf(listOf<LogLine>()) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(Unit) {
        TunnelService.logs.collect { line ->
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                logs = (logs + line).takeLast(500)
                // Auto-scroll to bottom (newest = last). Defer one frame so layout updates first.
                if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
            }
        }
    }
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    Scaffold(snackbarHost = { SnackbarHost(snackHost) }) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(inner)
                .padding(20.dp)
                .imePadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Logs", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { logs = emptyList() }) { Text("Clear") }
                TextButton(onClick = {
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.US)
                    val text = logs.joinToString("\n") { "${fmt.format(Date(it.ts))} [${it.level}] ${it.msg}" }
                    val cm = MyApp.app.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("kotunnel-logs", text))
                    scope.launch { snackHost.showSnackbar("Logs copied") }
                }) { Text("Copy") }
            }
            ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxSize()) {
                if (logs.isEmpty()) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Text("No logs yet", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    LazyColumn(state = listState, modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(logs.reversed()) { LogRow(it) }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsTab(
    config: ConfigStore,
    themeMode: Int,
    mcpMode: String,
    httpMcp: com.kotunnel.client.mcp.HttpMcpClient,
    feishuApi: com.kotunnel.client.mcp.FeishuApiClient,
    onThemeChange: (Int) -> Unit,
    onModeChange: (String) -> Unit,
    padding: androidx.compose.foundation.layout.PaddingValues
) {
    var tunnelId by remember { mutableStateOf(config.tunnelId) }
    var apiKey by remember { mutableStateOf(config.apiKey) }
    var baseUrl by remember { mutableStateOf(config.baseUrl) }
    var showAdvanced by remember { mutableStateOf(false) }
    var mcpUrl by remember { mutableStateOf(config.mcpServerUrl) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var feishuAppId by remember { mutableStateOf(config.feishuAppId) }
    var feishuAppSecret by remember { mutableStateOf(config.feishuAppSecret) }
    var secretVisible by remember { mutableStateOf(false) }
    var feishuTesting by remember { mutableStateOf(false) }
    var feishuResult by remember { mutableStateOf<String?>(null) }
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Column(
        Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            ThemeDropdown(themeMode, onThemeChange)
        }

        // Tunnel credentials
        ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Tunnel", fontWeight = FontWeight.SemiBold)
                OutlinedTextField(
                    value = tunnelId, onValueChange = { tunnelId = it },
                    label = { Text("Tunnel ID") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey, onValueChange = { apiKey = it },
                    label = { Text("Control Plane API Key") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(onClick = { showAdvanced = !showAdvanced }) {
                    Text(if (showAdvanced) "Hide advanced" else "Advanced: Base URL")
                }
                if (showAdvanced) {
                    OutlinedTextField(
                        value = baseUrl, onValueChange = { baseUrl = it },
                        label = { Text("Base URL") }, singleLine = true,
                        shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()
                    )
                }
                TextButton(onClick = {
                    config.tunnelId = tunnelId
                    config.apiKey = apiKey
                    config.baseUrl = baseUrl
                    scope.launch { snackHost.showSnackbar("Tunnel config saved") }
                }) { Text("Save tunnel config") }
            }
        }

        // MCP backend
        Text("MCP backend", style = MaterialTheme.typography.titleMedium)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(selected = mcpMode == "builtin", onClick = { onModeChange("builtin") }, shape = SegmentedButtonDefaults.itemShape(0, 3)) { Text("Demo") }
            SegmentedButton(selected = mcpMode == "http", onClick = { onModeChange("http") }, shape = SegmentedButtonDefaults.itemShape(1, 3)) { Text("Local HTTP") }
            SegmentedButton(selected = mcpMode == "feishu", onClick = { onModeChange("feishu") }, shape = SegmentedButtonDefaults.itemShape(2, 3)) { Text("Feishu") }
        }

        Crossfade(targetState = mcpMode, label = "modeCard") { mode ->
            when (mode) {
                "http" -> HttpModeCard(mcpUrl, { mcpUrl = it; testResult = null }, testing, testResult, onTest = {
                    if (mcpUrl.startsWith("http://") || mcpUrl.startsWith("https://")) {
                        config.mcpServerUrl = mcpUrl
                        testing = true; testResult = null
                        scope.launch {
                            val (ok, msg) = httpMcp.probe(mcpUrl)
                            testResult = (if (ok) "✓ " else "✗ ") + msg
                            testing = false
                        }
                    }
                })
                "feishu" -> FeishuModeCard(feishuAppId, feishuAppSecret, secretVisible,
                    onAppId = { feishuAppId = it; feishuResult = null },
                    onAppSecret = { feishuAppSecret = it; feishuResult = null },
                    onToggleVisibility = { secretVisible = !secretVisible },
                    authorized = config.feishuUserAccessToken.isNotBlank(),
                    expiryMs = config.feishuTokenExpiry,
                    testing = feishuTesting, testResult = feishuResult,
                    onConnect = {
                        config.feishuAppId = feishuAppId
                        config.feishuAppSecret = feishuAppSecret
                        feishuTesting = true; feishuResult = null
                        scope.launch {
                            val r = feishuApi.testConnection()
                            feishuResult = if (r.isSuccess) "✓ " + r.getOrNull() else "✗ " + (r.exceptionOrNull()?.message ?: "error")
                            feishuTesting = false
                        }
                    }
                )
                else -> DemoModeCard()
            }
        }
    }
}

@Composable
private fun DemoModeCard() {
    ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Text("Built-in demo MCP", fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("Tools: server_info, echo, uppercase.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun HttpModeCard(url: String, onUrlChange: (String) -> Unit, testing: Boolean, testResult: String?, onTest: () -> Unit) {
    ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Local HTTP MCP", fontWeight = FontWeight.SemiBold)
            val urlValid = url.startsWith("http://") || url.startsWith("https://")
            OutlinedTextField(value = url, onValueChange = onUrlChange,
                label = { Text("MCP URL (http://127.0.0.1:8765/mcp)") }, singleLine = true,
                isError = url.isNotBlank() && !urlValid, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onTest, enabled = !testing && url.isNotBlank(), shape = RoundedCornerShape(12.dp)) {
                    Text(if (testing) "Testing…" else "Test connection")
                }
                testResult?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(it, fontSize = 12.sp, color = if (it.startsWith("✓")) Color(0xFF34A853) else Color(0xFFD93025))
                }
            }
        }
    }
}

@Composable
private fun FeishuModeCard(
    appId: String, appSecret: String, secretVisible: Boolean,
    onAppId: (String) -> Unit, onAppSecret: (String) -> Unit,
    onToggleVisibility: () -> Unit,
    authorized: Boolean, expiryMs: Long,
    testing: Boolean, testResult: String?, onConnect: () -> Unit
) {
    ElevatedCard(shape = RoundedCornerShape(28.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Feishu / Lark MCP", fontWeight = FontWeight.SemiBold)
            OutlinedTextField(value = appId, onValueChange = onAppId,
                label = { Text("App ID (cli_xxx)") }, singleLine = true,
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = appSecret, onValueChange = onAppSecret,
                label = { Text("App Secret") }, singleLine = true,
                visualTransformation = if (secretVisible) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { IconButton(onClick = onToggleVisibility) { Icon(if (secretVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } },
                shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FilledTonalButton(onClick = onConnect, enabled = !testing && appId.isNotBlank() && appSecret.isNotBlank(), shape = RoundedCornerShape(12.dp)) {
                    Text(if (testing) "Connecting…" else "Connect Feishu")
                }
                OutlinedButton(onClick = onConnect, enabled = !testing && appId.isNotBlank() && appSecret.isNotBlank(), shape = RoundedCornerShape(12.dp)) {
                    Text("Test")
                }
            }
            testResult?.let { Text(it, fontSize = 12.sp, color = if (it.startsWith("✓")) Color(0xFF34A853) else Color(0xFFD93025)) }
            Text(
                if (authorized) "Connected" + if (expiryMs > 0) " (expires ${Date(expiryMs)})" else ""
                else "Not connected",
                style = MaterialTheme.typography.bodySmall,
                color = if (authorized) Color(0xFF34A853) else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ThemeDropdown(mode: Int, onChange: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val label = when (mode) { 1 -> "Light"; 2 -> "Dark"; else -> "Auto" }
    Box {
        OutlinedButton(onClick = { expanded = true }, shape = RoundedCornerShape(16.dp)) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Follow system") }, onClick = { onChange(0); expanded = false })
            DropdownMenuItem(text = { Text("Light") }, onClick = { onChange(1); expanded = false })
            DropdownMenuItem(text = { Text("Dark") }, onClick = { onChange(2); expanded = false })
        }
    }
}

@Composable
private fun StatusIcon(color: Color, label: String, spinner: Boolean, check: Boolean = false, error: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (spinner) {
            CircularProgressIndicator(modifier = Modifier.size(36.dp), color = color, strokeWidth = 3.dp)
        } else if (check) {
            val scale by animateFloatAsState(1f, animationSpec = spring(dampingRatio = 0.4f, stiffness = 300f), label = "pulse")
            Icon(Icons.Default.CheckCircle, null, tint = color, modifier = Modifier.size(36.dp).scale(scale))
        } else if (error) {
            Icon(Icons.Default.Error, null, tint = color, modifier = Modifier.size(36.dp))
        } else {
            Box(Modifier.size(20.dp).clip(CircleShape).background(color))
        }
        Text(label, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun LogRow(line: LogLine) {
    val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.US) }
    val color = when (line.level) {
        "E" -> Color(0xFFD93025)
        "W" -> Color(0xFFE37400)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text("${fmt.format(Date(line.ts))} [${line.level}] ${line.msg}", fontSize = 12.sp, color = color)
}

object MyApp {
    lateinit var app: android.content.Context
}
