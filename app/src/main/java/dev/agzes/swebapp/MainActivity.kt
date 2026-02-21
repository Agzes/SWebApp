package dev.agzes.swebapp

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.background
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.agzes.swebapp.data.SettingsRepository
import dev.agzes.swebapp.service.ServerService
import dev.agzes.swebapp.server.ServerLogger
import dev.agzes.swebapp.ui.theme.SWebAppTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.URL
import javax.net.ssl.HttpsURLConnection

private fun isNewerVersion(remote: String, current: String): Boolean {
    val r = remote.split(".").mapNotNull { it.toIntOrNull() }
    val c = current.split(".").mapNotNull { it.toIntOrNull() }
    for (i in 0 until maxOf(r.size, c.size)) {
        val rv = r.getOrElse(i) { 0 }
        val cv = c.getOrElse(i) { 0 }
        if (rv > cv) return true
        if (rv < cv) return false
    }
    return false
}

suspend fun checkForUpdates(currentVersion: String): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
    var connection: HttpsURLConnection? = null
    try {
        val url = URL("https://raw.githubusercontent.com/Agzes/SWebApp/refs/heads/main/version")
        connection = url.openConnection() as HttpsURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 5000
        connection.readTimeout = 5000

        if (connection.responseCode == 200) {
            val response = connection.inputStream.bufferedReader()
                .use { it.readText().take(128) }
                .trim()
            val remoteVersion = response.removePrefix("v")

            if (remoteVersion.isNotEmpty() && isNewerVersion(remoteVersion, currentVersion)) {
                return@withContext Pair(true, "https://github.com/Agzes/SWebApp/releases/latest")
            }
        }
    } catch (e: Exception) {
    } finally {
        connection?.disconnect()
    }
    Pair(false, null)
}

suspend fun getLocalIpAddress(): String? = withContext(Dispatchers.IO) {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        while (interfaces.hasMoreElements()) {
            val networkInterface = interfaces.nextElement()
            val addresses = networkInterface.inetAddresses
            while (addresses.hasMoreElements()) {
                val address = addresses.nextElement()
                if (!address.isLoopbackAddress && address is Inet4Address) {
                    return@withContext address.hostAddress
                }
            }
        }
    } catch (ex: Exception) {
    }
    null
}

fun formatUriPath(uriString: String?): String {
    if (uriString.isNullOrEmpty()) return "Not selected"
    return try {
        val decoded = Uri.decode(uriString)
        val segment = Uri.parse(decoded).lastPathSegment ?: return "Unknown"
        segment.replaceFirst(Regex("^primary:"), "Internal Storage/")
            .replaceFirst(Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}:"), "SD Card/")
    } catch (e: Exception) {
        "Unknown"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val repository = SettingsRepository(applicationContext)

        setContent {
            val themeMode by repository.themeModeFlow.collectAsState(initial = 0)
            SWebAppTheme(themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(repository)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(repository: SettingsRepository) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val folderUri by repository.selectedFolderUriFlow.collectAsState(initial = "")
    val localhostOnly by repository.localhostOnlyFlow.collectAsState(initial = true)
    val autoStart by repository.autoStartFlow.collectAsState(initial = false)
    val spaMode by repository.spaModeFlow.collectAsState(initial = true)
    val batterySaver by repository.batterySaverFlow.collectAsState(initial = false)
    val hotReload by repository.hotReloadFlow.collectAsState(initial = false)
    val showNotification by repository.showNotificationFlow.collectAsState(initial = true)
    val restrictWebFeatures by repository.restrictWebFeaturesFlow.collectAsState(initial = true)
    val interceptConsole by repository.interceptConsoleFlow.collectAsState(initial = false)
    val themeMode by repository.themeModeFlow.collectAsState(initial = 0)
    val port by repository.portFlow.collectAsState(initial = 8080)

    var isServerRunning by remember { mutableStateOf(context.isServiceRunning()) }
    var localIp by remember { mutableStateOf("Fetching...") }
    val logsList = remember { mutableStateListOf<String>() }
    var updateAvailableUrl by remember { mutableStateOf<String?>(null) }
    var isLogsExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        launch {
            try {
                val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(context.packageName, 0)
                }
                val versionName = packageInfo.versionName ?: "1.0"
                val (hasUpdate, url) = checkForUpdates(versionName)
                if (hasUpdate) {
                    updateAvailableUrl = url
                }
            } catch (e: Exception) {
            }
        }
        launch {
            ServerLogger.logs.collect { newLog ->
                if (logsList.size > 100) logsList.removeAt(0)
                logsList.add(newLog)
            }
        }
        while (isActive) {
            isServerRunning = context.isServiceRunning()
            localIp = getLocalIpAddress() ?: "Unknown WiFi IP"
            delay(1500)
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) {}

    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
            scope.launch { repository.saveSelectedFolderUri(uri.toString()) }
        }
    }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("SWebApp", fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                        Text(
                            "Simple HTTP Server for Android",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    FilledTonalIconButton(
                        onClick = {
                            val browserIntent =
                                Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Agzes/SWebApp"))
                            context.startActivity(browserIntent)
                        },
                        colors = IconButtonDefaults.filledTonalIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    ) {
                        Icon(imageVector = Icons.Default.Info, contentDescription = "Manual/Info")
                    }
                },
                actions = {
                    val appWidgetManager = context.getSystemService(AppWidgetManager::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appWidgetManager?.isRequestPinAppWidgetSupported == true) {
                        FilledTonalIconButton(
                            onClick = {
                                val myProvider =
                                    ComponentName(context, dev.agzes.swebapp.widget.ServerWidgetProvider::class.java)
                                appWidgetManager.requestPinAppWidget(myProvider, null, null)
                            },
                            colors = IconButtonDefaults.filledTonalIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        ) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_widget),
                                contentDescription = "Add Widget"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    val intent = Intent(context, ServerService::class.java).apply {
                        action = if (isServerRunning) ServerService.ACTION_STOP else ServerService.ACTION_START
                    }
                    if (isServerRunning) {
                        context.startService(intent)
                    } else {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && showNotification) {
                            context.startForegroundService(intent)
                        } else {
                            context.startService(intent)
                        }
                    }
                },
                expanded = true,
                icon = {
                    if (isServerRunning) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_stop),
                            contentDescription = "Power"
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.PlayArrow,
                            contentDescription = "Power"
                        )
                    }
                },
                text = { Text(if (isServerRunning) "Stop Server" else "Start Server") },
                containerColor = if (isServerRunning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (isServerRunning) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onPrimaryContainer
            )
        },
        floatingActionButtonPosition = FabPosition.End
    ) { innerPadding ->
        if (updateAvailableUrl != null) {
            AlertDialog(
                onDismissRequest = { updateAvailableUrl = null },
                title = { Text("Update Available") },
                text = { Text("A new version of SWebApp is available. Would you like to download it from GitHub?") },
                confirmButton = {
                    TextButton(onClick = {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(updateAvailableUrl))
                        context.startActivity(browserIntent)
                        updateAvailableUrl = null
                    }) {
                        Text("Update")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateAvailableUrl = null }) {
                        Text("Later")
                    }
                }
            )
        }
        val onlineContainer = MaterialTheme.colorScheme.primaryContainer
        val onlineText = MaterialTheme.colorScheme.onPrimaryContainer
        val animatedContainerColor = animateColorAsState(
            targetValue = if (isServerRunning) onlineContainer else MaterialTheme.colorScheme.surfaceVariant,
            label = "containerColor"
        ).value
        val animatedTextColor = animateColorAsState(
            targetValue = if (isServerRunning) onlineText else MaterialTheme.colorScheme.onSurfaceVariant,
            label = "textColor"
        ).value

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .animateContentSize(),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = animatedContainerColor
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AnimatedContent(
                                targetState = isServerRunning,
                                label = "statusAnimation"
                            ) { running ->
                                Text(
                                    text = if (running) "Status: Online" else "Status: Offline",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = animatedTextColor
                                )
                            }

                            AnimatedVisibility(
                                visible = isServerRunning,
                                enter = fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
                                exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(300))
                            ) {
                                val host = if (localhostOnly) "127.0.0.1" else localIp
                                val url = "http://$host:$port"
                                FilledTonalButton(
                                    onClick = {
                                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                        context.startActivity(browserIntent)
                                    },
                                    colors = ButtonDefaults.filledTonalButtonColors(
                                        containerColor = animatedTextColor.copy(alpha = 0.15f),
                                        contentColor = animatedTextColor
                                    ),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text("Open in Browser", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = isServerRunning,
                            enter = expandVertically(
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioMediumBouncy,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                                )
                            ) + fadeIn(animationSpec = androidx.compose.animation.core.tween(300)),
                            exit = shrinkVertically(
                                animationSpec = androidx.compose.animation.core.spring(
                                    dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                                    stiffness = androidx.compose.animation.core.Spring.StiffnessMedium
                                )
                            ) + fadeOut(animationSpec = androidx.compose.animation.core.tween(200))
                        ) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                val host = if (localhostOnly) "127.0.0.1" else localIp
                                val url = "http://$host:$port"
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = animatedTextColor,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            val clipboard =
                                                context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("URL", url))
                                            Toast.makeText(context, "URL Copied", Toast.LENGTH_SHORT).show()
                                        }
                                        .padding(vertical = 4.dp)
                                )
                                Text(
                                    text = "Tap URL to copy",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = animatedTextColor.copy(alpha = 0.7f)
                                )
                            }
                        }

                        AnimatedVisibility(
                            visible = !isServerRunning,
                            enter = fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(
                                    300,
                                    delayMillis = 150
                                )
                            ),
                            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(150))
                        ) {
                            Text(
                                text = "Server is not running. Press Start to begin serving your web app.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = animatedTextColor
                            )
                        }
                    }
                }
            }

            item {
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { folderPickerLauncher.launch(null) },
                    colors = CardDefaults.outlinedCardColors()
                ) {
                    Row(
                        modifier = Modifier.padding(20.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.List,
                            contentDescription = "Folder",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(32.dp)
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Column {
                            Text(
                                text = "Target Directory",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = formatUriPath(folderUri),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        SettingSwitch(
                            label = "Local Only",
                            description = "Restrict access to this device (127.0.0.1)",
                            checked = localhostOnly,
                            onCheckedChange = { scope.launch { repository.saveLocalhostOnly(it) } }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SettingSwitch(
                            label = "SPA Mode",
                            description = "Fallback to index.html for unknown routes",
                            checked = spaMode,
                            onCheckedChange = { scope.launch { repository.saveSpaMode(it) } }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SettingSwitch(
                            label = "Auto-Start",
                            description = "Start automatically on device boot",
                            checked = autoStart,
                            onCheckedChange = { scope.launch { repository.saveAutoStart(it) } }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SettingSwitch(
                            label = "Battery Saver",
                            description = "Stop server when battery is low (<15%)",
                            checked = batterySaver,
                            onCheckedChange = { scope.launch { repository.saveBatterySaver(it) } }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SettingSwitch(
                            label = "Hot Reload",
                            description = "Auto-refresh clients when files change",
                            checked = hotReload,
                            onCheckedChange = { scope.launch { repository.saveHotReload(it) } }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SettingSwitch(
                            label = "Show Notification",
                            description = "Show a persistent notification when the server is running",
                            checked = showNotification,
                            onCheckedChange = { scope.launch { repository.saveShowNotification(it) } }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SettingSwitch(
                            label = "Restrict Web Features",
                            description = "Block camera, microphone, and geolocation",
                            checked = restrictWebFeatures,
                            onCheckedChange = { scope.launch { repository.saveRestrictWebFeatures(it) } }
                        )
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        SettingSwitch(
                            label = "Log Browser Console",
                            description = "Send browser console logs to the server",
                            checked = interceptConsole,
                            onCheckedChange = { scope.launch { repository.saveInterceptConsole(it) } }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Theme Mode",
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )

                        SegmentedThemeControl(
                            selectedTheme = themeMode,
                            onThemeSelected = { newMode -> scope.launch { repository.saveThemeMode(newMode) } }
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = port.toString(),
                            onValueChange = {
                                it.toIntOrNull()?.let { p ->
                                    if (p in 1024..65535) scope.launch { repository.savePort(p) }
                                }
                            },
                            label = { Text("Port Number (1024-65535)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            shape = RoundedCornerShape(12.dp),
                            isError = port !in 1024..65535
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                    }
                }
            }

            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(if (isLogsExpanded) 400.dp else 56.dp)
                        .animateContentSize(
                            animationSpec = androidx.compose.animation.core.spring(
                                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioLowBouncy,
                                stiffness = androidx.compose.animation.core.Spring.StiffnessLow
                            )
                        ),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    )
                ) {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { isLogsExpanded = !isLogsExpanded }
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Server Logs",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Icon(
                                imageVector = if (isLogsExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                                contentDescription = "Toggle Logs",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        AnimatedVisibility(
                            visible = isLogsExpanded,
                            enter = fadeIn(
                                animationSpec = androidx.compose.animation.core.tween(
                                    300,
                                    delayMillis = 100
                                )
                            ),
                            exit = fadeOut(animationSpec = androidx.compose.animation.core.tween(150))
                        ) {
                            Column(modifier = Modifier.fillMaxSize()) {
                                HorizontalDivider(modifier = Modifier.padding(bottom = 8.dp))
                                LazyColumn(
                                    modifier = Modifier.fillMaxSize(),
                                    contentPadding = PaddingValues(bottom = 8.dp),
                                    state = rememberLazyListState(initialFirstVisibleItemIndex = if (logsList.isEmpty()) 0 else logsList.size - 1)
                                ) {
                                    items(logsList) { log ->
                                        Text(
                                            text = log,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp, bottom = 96.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Text(
                            text = "Made with ❤️ • v1.0",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingSwitch(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

fun Context.isServiceRunning(): Boolean = dev.agzes.swebapp.service.ServerService.isRunning

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SegmentedThemeControl(
    selectedTheme: Int,
    onThemeSelected: (Int) -> Unit
) {
    val options = listOf("Auto", "Light", "Dark")
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(50),
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            options.forEachIndexed { index, text ->
                val selected = selectedTheme == index
                val animColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                val animTextColor by animateColorAsState(if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant)

                Surface(
                    modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp),
                    color = animColor,
                    shape = RoundedCornerShape(50),
                    onClick = { onThemeSelected(index) }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = text,
                            color = animTextColor,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }
    }
}
