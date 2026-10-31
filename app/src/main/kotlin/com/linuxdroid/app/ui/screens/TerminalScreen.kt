package com.linuxdroid.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.service.FloatingTerminalService
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.TerminalViewModel
import com.linuxdroid.core.model.AntigravityAuthState
import com.linuxdroid.core.model.LogExportType
import com.linuxdroid.core.runtime.terminal.TerminalPosition
import com.linuxdroid.core.runtime.terminal.TerminalSelection
import com.linuxdroid.core.runtime.terminal.TerminalSelectionExtractor
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Terminal presentation mode: standard full-screen or movable in-app floating window.
 */
enum class TerminalPresentationMode {
    FULLSCREEN,
    IN_APP_FLOATING,
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun TerminalScreen(
    navController: NavController,
    viewModel: TerminalViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val coroutineScope = rememberCoroutineScope()
    val neuColors = NeuTheme.colors

    val environment by viewModel.environment.collectAsState()
    val lines by viewModel.lines.collectAsState()
    val cursorRow by viewModel.cursorRow.collectAsState()
    val cursorCol by viewModel.cursorCol.collectAsState()
    val terminalTitle by viewModel.terminalTitle.collectAsState()
    val currentTheme by viewModel.currentTheme.collectAsState()
    val isShellActive by viewModel.isShellActive.collectAsState()
    val isStarting by viewModel.isStarting.collectAsState()
    val shellExitCode by viewModel.shellExitCode.collectAsState()
    val remoteControlUrl by viewModel.remoteControlUrl.collectAsState()
    val isRemoteControlReady by viewModel.isRemoteControlReady.collectAsState()
    val detectedUrls by viewModel.detectedUrls.collectAsState()
    val authUrl by viewModel.authUrl.collectAsState()
    val antigravityState by viewModel.antigravityState.collectAsState()

    var showAuthCodeDialog by remember { mutableStateOf(false) }
    var authCodeInput by remember { mutableStateOf("") }

    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }

    // Sentinel " " ensures IME Backspace is reliably detected across all virtual keyboards
    var textInput by remember { mutableStateOf(TextFieldValue(" ", selection = TextRange(1))) }

    // Modifier state (sticky latching: toggled on, applied on next key, then auto-reset)
    var isCtrlActive by remember { mutableStateOf(false) }
    var isAltActive by remember { mutableStateOf(false) }
    var isShiftActive by remember { mutableStateOf(false) }
    var isSymbolsRowOpen by remember { mutableStateOf(false) }

    // Direct touch selection state (on rendered terminal canvas)
    var activeSelection by remember { mutableStateOf<TerminalSelection?>(null) }

    // Presentation Mode & Floating Window State
    var presentationMode by remember { mutableStateOf(TerminalPresentationMode.FULLSCREEN) }
    var isFloatingMinimized by remember { mutableStateOf(false) }
    var floatingOffset by remember { mutableStateOf(Offset(24f, 70f)) }
    var floatingSize by remember { mutableStateOf(DpSize(360.dp, 440.dp)) }
    var showOverlayPermissionDialog by remember { mutableStateOf(false) }

    // In-terminal search state
    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var currentMatchIndex by remember { mutableIntStateOf(0) }

    // Export dialog
    var showExportOptions by remember { mutableStateOf(false) }

    // Blinking terminal cursor animation
    val infiniteTransition = rememberInfiniteTransition(label = "terminalCursor")
    val cursorAlpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = keyframes {
                durationMillis = 1000
                1f at 0
                1f at 500
                0f at 501
                0f at 1000
            },
            repeatMode = RepeatMode.Restart
        ),
        label = "cursorAlpha"
    )

    val density = LocalDensity.current

    // Viewport stability: Track if user is at the bottom (following live output) or reading history
    val isFollowingTail = remember {
        derivedStateOf {
            val visible = listState.layoutInfo.visibleItemsInfo
            if (visible.isEmpty() || lines.isEmpty()) true
            else visible.last().index >= lines.size - 2
        }
    }

    var unreadOutputLines by remember { mutableIntStateOf(0) }
    var previousLinesSize by remember { mutableIntStateOf(0) }

    // Stable scrollback: only auto-scroll if user is following live output
    LaunchedEffect(lines.size) {
        if (previousLinesSize > 0 && lines.size > previousLinesSize) {
            val diff = lines.size - previousLinesSize
            if (isFollowingTail.value) {
                listState.scrollToItem(lines.size - 1)
                unreadOutputLines = 0
            } else {
                unreadOutputLines += diff
            }
        } else if (isFollowingTail.value && lines.isNotEmpty()) {
            listState.scrollToItem(lines.size - 1)
        }
        previousLinesSize = lines.size
    }

    LaunchedEffect(isFollowingTail.value) {
        if (isFollowingTail.value) {
            unreadOutputLines = 0
        }
    }

    // Auto-focus keyboard on screen load
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    // Auto-close installation terminal and return home when in-guest GUI install succeeds
    LaunchedEffect(Unit) {
        viewModel.guiInstallCompleted.collect { success ->
            if (success) {
                Toast.makeText(context, "GUI installation complete! Desktop is ready.", Toast.LENGTH_LONG).show()
                kotlinx.coroutines.delay(1200)
                navController.popBackStack()
            } else {
                Toast.makeText(context, "GUI installation failed. Review terminal log for details.", Toast.LENGTH_LONG).show()
            }
        }
    }

    // Auto-navigate to built-in Antigravity WebView when Remote Control is ready
    LaunchedEffect(Unit) {
        viewModel.navigateToWebView.collect { url ->
            val envId = environment?.id?.value ?: viewModel.environmentId
            navController.navigate(Screen.AntigravityWebView.route(envId, url))
        }
    }

    // Search matches calculation
    val searchMatchLines = remember(lines, searchQuery) {
        if (searchQuery.isNotBlank()) {
            lines.mapIndexedNotNull { index, lineData ->
                if (lineData.rawText.contains(searchQuery, ignoreCase = true)) index else null
            }
        } else {
            emptyList()
        }
    }

    val quickCommands = listOf(
        "ls -la",
        "pwd",
        "uname -a",
        "whoami",
        "cat /etc/os-release",
        "df -h",
        "free -m",
        "ps aux",
        "clear",
    )

    val resetStickyModifiers = {
        isShiftActive = false
        isCtrlActive = false
        isAltActive = false
    }

    Scaffold(
        contentWindowInsets = WindowInsets.statusBars,
        containerColor = neuColors.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = environment?.name ?: "Terminal",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = neuColors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Surface(
                            color = if (isShellActive) neuColors.success.copy(alpha = 0.15f) else neuColors.surfacePressed,
                            shape = RoundedCornerShape(6.dp),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, if (isShellActive) neuColors.success.copy(alpha = 0.4f) else neuColors.borderHighlight.copy(alpha = 0.3f))
                        ) {
                            Text(
                                text = if (isShellActive) "ONLINE" else if (isStarting) "STARTING" else "OFFLINE",
                                color = if (isShellActive) neuColors.success else if (isStarting) neuColors.warning else neuColors.textMuted,
                                style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                maxLines = 1,
                            )
                        }
                    }
                },
                navigationIcon = {
                    NeuIconButton(
                        onClick = { navController.popBackStack() },
                        size = 38.dp,
                        tint = neuColors.textPrimary,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", modifier = Modifier.size(18.dp))
                    }
                },
                actions = {
                    // In-terminal search toggle
                    NeuIconButton(
                        onClick = { isSearchActive = !isSearchActive },
                        size = 38.dp,
                        tint = if (isSearchActive) neuColors.primaryAccent else neuColors.textSecondary,
                    ) {
                        Icon(Icons.Default.Search, contentDescription = "Find in Terminal", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))

                    // Floating Mode Toggle (Fullscreen <-> In-App Floating)
                    NeuIconButton(
                        onClick = {
                            presentationMode = if (presentationMode == TerminalPresentationMode.FULLSCREEN) {
                                isFloatingMinimized = false
                                TerminalPresentationMode.IN_APP_FLOATING
                            } else {
                                TerminalPresentationMode.FULLSCREEN
                            }
                        },
                        size = 38.dp,
                        tint = if (presentationMode == TerminalPresentationMode.IN_APP_FLOATING) neuColors.primaryAccent else neuColors.textSecondary,
                    ) {
                        Icon(
                            if (presentationMode == TerminalPresentationMode.IN_APP_FLOATING) Icons.Default.Fullscreen else Icons.Default.PictureInPictureAlt,
                            contentDescription = "Toggle Floating Mode",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(4.dp))

                    // System-wide Overlay Option
                    NeuIconButton(
                        onClick = {
                            if (FloatingTerminalService.canDrawOverlay(context)) {
                                FloatingTerminalService.start(context, viewModel.environmentId)
                                Toast.makeText(context, "System floating terminal overlay active", Toast.LENGTH_SHORT).show()
                            } else {
                                showOverlayPermissionDialog = true
                            }
                        },
                        size = 38.dp,
                        tint = neuColors.textSecondary,
                    ) {
                        Icon(Icons.Default.Layers, contentDescription = "Float Over Other Apps", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))

                    // Export logs dialog
                    NeuIconButton(
                        onClick = { showExportOptions = true },
                        size = 38.dp,
                        tint = neuColors.primaryAccent,
                    ) {
                        Icon(Icons.Default.Share, contentDescription = "Export Logs", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))

                    // Virtual keyboard toggle
                    NeuIconButton(
                        onClick = {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        },
                        size = 38.dp,
                        tint = neuColors.primaryAccent,
                    ) {
                        Icon(Icons.Default.Keyboard, contentDescription = "Show Keyboard", modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(8.dp))
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = neuColors.background,
                    titleContentColor = neuColors.textPrimary,
                ),
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .navigationBarsPadding()
                .background(neuColors.background)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        if (activeSelection != null) {
                            activeSelection = null
                        } else {
                            focusRequester.requestFocus()
                            keyboardController?.show()
                        }
                    }
            ) {
                // Antigravity Remote Control / Web Action Banner
                AnimatedVisibility(
                    visible = remoteControlUrl != null,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    NeuCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        elevation = 4.dp,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Default.CloudQueue,
                                    contentDescription = null,
                                    tint = neuColors.primaryAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "Antigravity Remote Control Ready",
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                        color = neuColors.textPrimary
                                    )
                                    Text(
                                        remoteControlUrl ?: "",
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = SfMono),
                                        color = neuColors.textSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NeuButton(
                                    onClick = {
                                        remoteControlUrl?.let { url ->
                                            val envId = environment?.id?.value ?: viewModel.environmentId
                                            navController.navigate(Screen.AntigravityWebView.route(envId, url))
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Web View", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = neuColors.primaryAccent)
                                }
                                NeuButton(
                                    onClick = {
                                        remoteControlUrl?.let { url ->
                                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                            clipboard.setPrimaryClip(ClipData.newPlainText("Remote Control URL", url))
                                            Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Copy", fontSize = 11.sp, color = neuColors.textPrimary)
                                }
                                NeuButton(
                                    onClick = {
                                        remoteControlUrl?.let { url ->
                                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                            }
                                            runCatching { context.startActivity(intent) }
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                ) {
                                    Text("Chrome", fontSize = 11.sp, color = neuColors.textSecondary)
                                }
                            }
                        }
                    }
                }

                // Antigravity CLI In-Flight Authentication Banner
                if (viewModel.isAntigravitySession && remoteControlUrl == null && antigravityState.authState.isInAuthFlow) {
                    NeuCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        elevation = 4.dp,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = neuColors.primaryAccent
                                )
                                Text(
                                    text = when (antigravityState.authState) {
                                        AntigravityAuthState.STARTING -> "Starting Antigravity CLI…"
                                        AntigravityAuthState.DETECTING_LOGIN -> "Checking login status…"
                                        AntigravityAuthState.SELECTING_GOOGLE_OAUTH -> "Selecting Google OAuth automatically…"
                                        AntigravityAuthState.WAITING_FOR_BROWSER_AUTH -> "Google Sign-in opened in Chrome"
                                        AntigravityAuthState.WAITING_FOR_AUTH_CODE -> "Google Sign-in: Authorization Code Required"
                                        AntigravityAuthState.SUBMITTING_AUTH_CODE -> "Submitting authorization code to CLI…"
                                        AntigravityAuthState.AUTHENTICATING -> "Authenticating session with Google…"
                                        AntigravityAuthState.WAITING_FOR_REMOTE_URL -> "Waiting for Remote Control URL…"
                                        AntigravityAuthState.CONNECTING_WEBVIEW -> "Connecting to WebView…"
                                        AntigravityAuthState.CONNECTED -> "Remote Control Connected"
                                        AntigravityAuthState.MINIMIZED -> "Antigravity Minimized"
                                        AntigravityAuthState.ERROR -> "Antigravity Error"
                                        AntigravityAuthState.CANCELLED -> "Antigravity Cancelled"
                                        AntigravityAuthState.STOPPING -> "Stopping Antigravity…"
                                        AntigravityAuthState.IDLE -> "Antigravity CLI setup in progress…"
                                    },
                                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                                    color = neuColors.textPrimary
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                NeuButton(
                                    onClick = { showAuthCodeDialog = true },
                                    isAccent = true,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.VpnKey, contentDescription = null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Enter Auth Code", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }

                                if (authUrl != null) {
                                    NeuButton(
                                        onClick = { viewModel.openGoogleAuthBrowser() },
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("Re-open Browser", fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }
                }

                // Detected terminal URLs action strip
                if (remoteControlUrl == null && detectedUrls.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 2.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Detected Link:", fontSize = 11.sp, color = neuColors.textMuted, fontFamily = SfMono)
                        detectedUrls.take(3).forEach { url ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = neuColors.surface,
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.borderHighlight.copy(alpha = 0.5f)),
                                modifier = Modifier.clickable {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    runCatching { context.startActivity(intent) }
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(12.dp), tint = neuColors.primaryAccent)
                                    Text(
                                        url.removePrefix("https://").removePrefix("http://").take(30) + if (url.length > 30) "…" else "",
                                        fontSize = 11.sp,
                                        color = neuColors.primaryAccent,
                                        fontFamily = SfMono
                                    )
                                }
                            }
                        }
                    }
                }

                // macOS Spotlight-Style In-Terminal Search Bar
                AnimatedVisibility(
                    visible = isSearchActive,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    NeuCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        elevation = 4.dp,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Search, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(18.dp))
                            BasicTextField(
                                value = searchQuery,
                                onValueChange = {
                                    searchQuery = it
                                    currentMatchIndex = 0
                                    if (searchMatchLines.isNotEmpty()) {
                                        coroutineScope.launch {
                                            listState.scrollToItem(searchMatchLines[0])
                                        }
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(color = neuColors.textPrimary, fontFamily = SfMono),
                                cursorBrush = SolidColor(neuColors.primaryAccent),
                                singleLine = true,
                                decorationBox = { innerTextField ->
                                    if (searchQuery.isEmpty()) {
                                        Text("Find text in terminal...", style = MaterialTheme.typography.bodyMedium, color = neuColors.textMuted)
                                    }
                                    innerTextField()
                                }
                            )

                            if (searchMatchLines.isNotEmpty()) {
                                Text(
                                    text = "${currentMatchIndex + 1}/${searchMatchLines.size}",
                                    fontSize = 11.sp,
                                    color = neuColors.textSecondary,
                                    fontFamily = SfMono
                                )

                                NeuIconButton(
                                    onClick = {
                                        if (searchMatchLines.isNotEmpty()) {
                                            currentMatchIndex = (currentMatchIndex - 1 + searchMatchLines.size) % searchMatchLines.size
                                            coroutineScope.launch { listState.scrollToItem(searchMatchLines[currentMatchIndex]) }
                                        }
                                    },
                                    size = 28.dp,
                                    tint = neuColors.textPrimary
                                ) {
                                    Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Previous Match", modifier = Modifier.size(16.dp))
                                }

                                NeuIconButton(
                                    onClick = {
                                        if (searchMatchLines.isNotEmpty()) {
                                            currentMatchIndex = (currentMatchIndex + 1) % searchMatchLines.size
                                            coroutineScope.launch { listState.scrollToItem(searchMatchLines[currentMatchIndex]) }
                                        }
                                    },
                                    size = 28.dp,
                                    tint = neuColors.textPrimary
                                ) {
                                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Next Match", modifier = Modifier.size(16.dp))
                                }
                            }

                            NeuIconButton(
                                onClick = {
                                    isSearchActive = false
                                    searchQuery = ""
                                },
                                size = 28.dp,
                                tint = neuColors.textMuted
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "Close Search", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Disconnected / Exit status and quick export banner
                if (!isShellActive && !isStarting) {
                    NeuCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = if (shellExitCode != null && shellExitCode != 0) "Session exited (code $shellExitCode)" else "Session inactive",
                                color = neuColors.error,
                                fontSize = 12.sp,
                                fontFamily = SfMono,
                                fontWeight = FontWeight.Bold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                NeuButton(
                                    onClick = { showExportOptions = true },
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = "Export Logs", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Export", fontSize = 11.sp)
                                }
                                NeuButton(
                                    onClick = { viewModel.restartShell() },
                                    isAccent = true,
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Refresh, contentDescription = "Restart", modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Restart", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // Quick Command Chips (Spotlight Style)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 10.dp, vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    quickCommands.forEach { cmd ->
                        NeuButton(
                            onClick = {
                                viewModel.runCommand(cmd)
                                focusRequester.requestFocus()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            },
                            shape = RoundedCornerShape(8.dp),
                            elevation = 2.dp,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(cmd, fontSize = 11.sp, fontFamily = SfMono, color = neuColors.primaryAccent)
                        }
                    }
                }

                // Main Terminal Window (Rendered Fullscreen or In-App Floating)
                if (presentationMode == TerminalPresentationMode.FULLSCREEN) {
                    TerminalWindowCard(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        viewModel = viewModel,
                        navController = navController,
                        lines = lines,
                        cursorRow = cursorRow,
                        cursorCol = cursorCol,
                        terminalTitle = terminalTitle,
                        currentTheme = currentTheme,
                        cursorAlpha = cursorAlpha,
                        isShellActive = isShellActive,
                        isStarting = isStarting,
                        searchQuery = searchQuery,
                        activeSelection = activeSelection,
                        onSelectionChanged = { activeSelection = it },
                        isFollowingTail = isFollowingTail.value,
                        unreadOutputLines = unreadOutputLines,
                        onScrollToBottom = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(maxOf(0, lines.size - 1))
                            }
                        },
                        listState = listState,
                        focusRequester = focusRequester,
                        keyboardController = keyboardController,
                        onToggleFloating = {
                            presentationMode = TerminalPresentationMode.IN_APP_FLOATING
                            isFloatingMinimized = false
                        },
                        onRequestOverlay = {
                            if (FloatingTerminalService.canDrawOverlay(context)) {
                                FloatingTerminalService.start(context, viewModel.environmentId)
                                Toast.makeText(context, "System floating terminal overlay active", Toast.LENGTH_SHORT).show()
                            } else {
                                showOverlayPermissionDialog = true
                            }
                        }
                    )
                } else {
                    // In-App Floating Mode placeholder space
                    Spacer(modifier = Modifier.weight(1f))
                }

                // Extra Keyboard Toolbar with Coding Symbols Row & Inverted-T Cluster
                NeuCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp),
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Left Scrollable 2-Row Function & Symbol Keys
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .horizontalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // Row 1: ESC, Modifier Controls, Paste & Terminal Navigation
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TerminalKeyButton("ESC", onClick = { viewModel.sendEscape(); focusRequester.requestFocus() })
                                    TerminalKeyButton("CTRL", isActive = isCtrlActive, showLed = true, onClick = { isCtrlActive = !isCtrlActive })
                                    TerminalKeyButton("ALT", isActive = isAltActive, showLed = true, onClick = { isAltActive = !isAltActive })
                                    TerminalKeyButton("PASTE", highlight = true, onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clipText.isNullOrEmpty()) {
                                            viewModel.pasteText(clipText)
                                            Toast.makeText(context, "Pasted from clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                        focusRequester.requestFocus()
                                    })
                                    TerminalKeyButton("HOME", onClick = {
                                        viewModel.sendHome(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                        resetStickyModifiers()
                                        focusRequester.requestFocus()
                                    })
                                    TerminalKeyButton("END", onClick = {
                                        viewModel.sendEnd(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                        resetStickyModifiers()
                                        focusRequester.requestFocus()
                                    })
                                    TerminalKeyButton("PGUP", onClick = {
                                        viewModel.sendPageUp(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                        resetStickyModifiers()
                                        focusRequester.requestFocus()
                                    })
                                    TerminalKeyButton("PGDN", onClick = {
                                        viewModel.sendPageDown(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                        resetStickyModifiers()
                                        focusRequester.requestFocus()
                                    })
                                }

                                // Row 2: Downside ESC -> Tab (symbol only ⇥), Shift (symbol only ⇧), Insert, Quick Symbols & Backspace
                                Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                                    TerminalKeyButton("⇥", onClick = {
                                        viewModel.sendTab(shift = isShiftActive)
                                        resetStickyModifiers()
                                        focusRequester.requestFocus()
                                    })
                                    TerminalKeyButton("⇧", isActive = isShiftActive, showLed = true, onClick = { isShiftActive = !isShiftActive })
                                    TerminalKeyButton("INS", onClick = {
                                        viewModel.sendInsert(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                        resetStickyModifiers()
                                        focusRequester.requestFocus()
                                    })
                                    TerminalKeyButton("|", onClick = { viewModel.sendInput("|"); focusRequester.requestFocus() })
                                    TerminalKeyButton("/", onClick = { viewModel.sendInput("/"); focusRequester.requestFocus() })
                                    TerminalKeyButton("-", onClick = { viewModel.sendInput("-"); focusRequester.requestFocus() })
                                    TerminalKeyButton("~", onClick = { viewModel.sendInput("~"); focusRequester.requestFocus() })
                                    TerminalKeyButton("$", onClick = { viewModel.sendInput("$"); focusRequester.requestFocus() })
                                    TerminalKeyButton("\"", onClick = { viewModel.sendInput("\""); focusRequester.requestFocus() })
                                    TerminalKeyButton("'", onClick = { viewModel.sendInput("'"); focusRequester.requestFocus() })
                                    TerminalKeyButton("⌫", highlight = true, onClick = { viewModel.sendBackspace(); focusRequester.requestFocus() })
                                }
                            }

                            // Vertical Separator
                            Box(
                                modifier = Modifier
                                    .width(1.dp)
                                    .height(72.dp)
                                    .background(neuColors.borderHighlight.copy(alpha = 0.3f))
                            )

                            // Right Side: Inverted-T Cluster with Modifier Awareness (Supports Shift+Down, Ctrl+Down, etc.)
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                // Top Row: [ DEL ] [ ↑ ] [ Toggle Menu Button ]
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TerminalNavButton(
                                        text = "DEL",
                                        fontSize = 11.sp,
                                        onClick = {
                                            viewModel.sendDelete(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                            resetStickyModifiers()
                                            focusRequester.requestFocus()
                                        }
                                    )
                                    TerminalNavButton(
                                        text = "↑",
                                        onClick = {
                                            viewModel.sendArrowUp(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                            resetStickyModifiers()
                                            focusRequester.requestFocus()
                                        }
                                    )
                                    TerminalNavButton(
                                        text = if (isSymbolsRowOpen) "▲" else "SYM",
                                        fontSize = if (isSymbolsRowOpen) 14.sp else 11.sp,
                                        highlight = isSymbolsRowOpen,
                                        onClick = {
                                            isSymbolsRowOpen = !isSymbolsRowOpen
                                            focusRequester.requestFocus()
                                        }
                                    )
                                }

                                // Bottom Row of Inverted-T: [ ← ] [ ↓ ] [ → ]
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    TerminalNavButton(
                                        text = "←",
                                        onClick = {
                                            viewModel.sendArrowLeft(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                            resetStickyModifiers()
                                            focusRequester.requestFocus()
                                        }
                                    )
                                    TerminalNavButton(
                                        text = "↓",
                                        onClick = {
                                            // Exact Antigravity shortcut Shift + Arrow Down or regular arrow
                                            viewModel.sendArrowDown(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                            resetStickyModifiers()
                                            focusRequester.requestFocus()
                                        }
                                    )
                                    TerminalNavButton(
                                        text = "→",
                                        onClick = {
                                            viewModel.sendArrowRight(shift = isShiftActive, alt = isAltActive, ctrl = isCtrlActive)
                                            resetStickyModifiers()
                                            focusRequester.requestFocus()
                                        }
                                    )
                                }
                            }
                        }

                        // Expandable 3rd Row: Coding symbols
                        AnimatedVisibility(
                            visible = isSymbolsRowOpen,
                            enter = expandVertically() + fadeIn(),
                            exit = shrinkVertically() + fadeOut()
                        ) {
                            Column {
                                HorizontalDivider(
                                    color = neuColors.borderHighlight.copy(alpha = 0.25f),
                                    thickness = 0.5.dp,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val codingSymbols = listOf(
                                        "\\", "|", "-", "~", "'", "\"", "$", "#",
                                        "=", "_", ":", ";", "<", ">", "{", "}",
                                        "[", "]", "(", ")", "/", "&", "!", "*",
                                        "+", "%", "`", "^", "@", "?"
                                    )
                                    codingSymbols.forEach { sym ->
                                        TerminalKeyButton(
                                            text = sym,
                                            onClick = {
                                                viewModel.sendInput(sym)
                                                focusRequester.requestFocus()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Hidden Transparent BasicTextField Capturing Virtual / Hardware Keyboard Input with Modifier Combination Handling
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                ) {
                    BasicTextField(
                        value = textInput,
                        onValueChange = { newVal ->
                            val newText = newVal.text
                            if (newText.isEmpty()) {
                                viewModel.sendBackspace()
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            } else if (newText.length > 1) {
                                val typed = if (newText.startsWith(" ")) {
                                    newText.substring(1)
                                } else if (newText.endsWith(" ")) {
                                    newText.substring(0, newText.length - 1)
                                } else {
                                    newText
                                }

                                if (typed.isNotEmpty()) {
                                    if (isCtrlActive && typed.length == 1) {
                                        viewModel.inputController.sendCtrlChar(typed[0], alt = isAltActive)
                                        resetStickyModifiers()
                                    } else if (isAltActive && typed.length == 1) {
                                        viewModel.sendKey(typed[0], shift = isShiftActive, alt = true, ctrl = false)
                                        resetStickyModifiers()
                                    } else if (isShiftActive && typed.length == 1) {
                                        viewModel.sendKey(typed[0], shift = true, alt = false, ctrl = false)
                                        resetStickyModifiers()
                                    } else {
                                        viewModel.pasteText(typed)
                                    }
                                }
                            }
                            textInput = TextFieldValue(" ", selection = TextRange(1))
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .focusRequester(focusRequester)
                            .onKeyEvent { keyEvent ->
                                if (keyEvent.type == KeyEventType.KeyDown) {
                                    val shift = keyEvent.isShiftPressed || isShiftActive
                                    val ctrl = keyEvent.isCtrlPressed || isCtrlActive
                                    val alt = keyEvent.isAltPressed || isAltActive

                                    when (keyEvent.key) {
                                        Key.DirectionDown -> {
                                            viewModel.sendArrowDown(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.DirectionUp -> {
                                            viewModel.sendArrowUp(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.DirectionLeft -> {
                                            viewModel.sendArrowLeft(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.DirectionRight -> {
                                            viewModel.sendArrowRight(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.Tab -> {
                                            viewModel.sendTab(shift = shift)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.Enter -> {
                                            viewModel.sendEnter()
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.Backspace -> {
                                            viewModel.sendBackspace()
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.Escape -> {
                                            viewModel.sendEscape()
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.Delete -> {
                                            viewModel.sendDelete(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.Insert -> {
                                            viewModel.sendInsert(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.MoveHome -> {
                                            viewModel.sendHome(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.MoveEnd -> {
                                            viewModel.sendEnd(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.PageUp -> {
                                            viewModel.sendPageUp(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.PageDown -> {
                                            viewModel.sendPageDown(shift = shift, alt = alt, ctrl = ctrl)
                                            resetStickyModifiers()
                                            true
                                        }
                                        Key.F1 -> { viewModel.sendFunctionKey(1, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F2 -> { viewModel.sendFunctionKey(2, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F3 -> { viewModel.sendFunctionKey(3, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F4 -> { viewModel.sendFunctionKey(4, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F5 -> { viewModel.sendFunctionKey(5, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F6 -> { viewModel.sendFunctionKey(6, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F7 -> { viewModel.sendFunctionKey(7, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F8 -> { viewModel.sendFunctionKey(8, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F9 -> { viewModel.sendFunctionKey(9, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F10 -> { viewModel.sendFunctionKey(10, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F11 -> { viewModel.sendFunctionKey(11, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.F12 -> { viewModel.sendFunctionKey(12, shift, alt, ctrl); resetStickyModifiers(); true }
                                        Key.C if ctrl -> { viewModel.sendCtrlC(); resetStickyModifiers(); true }
                                        Key.D if ctrl -> { viewModel.sendCtrlD(); resetStickyModifiers(); true }
                                        Key.Z if ctrl -> { viewModel.sendCtrlZ(); resetStickyModifiers(); true }
                                        Key.L if ctrl -> { viewModel.sendCtrlL(); resetStickyModifiers(); true }
                                        else -> {
                                            val nativeKey = keyEvent.nativeKeyEvent
                                            val unicode = nativeKey.unicodeChar
                                            if (unicode > 0 && unicode != 0x0A && unicode != 0x0D) {
                                                val ch = unicode.toChar()
                                                viewModel.sendKey(ch, shift = shift, alt = alt, ctrl = ctrl)
                                                resetStickyModifiers()
                                                true
                                            } else {
                                                false
                                            }
                                        }
                                    }
                                } else {
                                    false
                                }
                            },
                        cursorBrush = SolidColor(Color.Transparent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.None),
                        keyboardActions = KeyboardActions(onAny = { viewModel.sendEnter() })
                    )
                }
            }

            // In-App Floating Terminal Mode (Draggable & Resizable Floating macOS Window)
            if (presentationMode == TerminalPresentationMode.IN_APP_FLOATING) {
                if (isFloatingMinimized) {
                    // Floating Minimized Status Pill
                    Surface(
                        modifier = Modifier
                            .offset { IntOffset(floatingOffset.x.roundToInt(), floatingOffset.y.roundToInt()) }
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    floatingOffset += dragAmount
                                }
                            }
                            .clickable { isFloatingMinimized = false },
                        shape = RoundedCornerShape(20.dp),
                        color = Color(currentTheme.background),
                        border = BorderStroke(1.dp, neuColors.primaryAccent),
                        shadowElevation = 8.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Box(modifier = Modifier.size(8.dp).background(Color(0xFF27C93F), CircleShape))
                            Text(
                                text = terminalTitle.ifBlank { "bash (floating)" },
                                fontFamily = SfMono,
                                fontSize = 12.sp,
                                color = Color(currentTheme.foreground)
                            )
                            Icon(Icons.Default.OpenInFull, contentDescription = "Restore Window", modifier = Modifier.size(14.dp), tint = neuColors.primaryAccent)
                        }
                    }
                } else {
                    // Movable & Resizable Floating Terminal Card
                    TerminalWindowCard(
                        modifier = Modifier
                            .offset { IntOffset(floatingOffset.x.roundToInt(), floatingOffset.y.roundToInt()) }
                            .size(floatingSize)
                            .shadow(16.dp, RoundedCornerShape(14.dp)),
                        viewModel = viewModel,
                        navController = navController,
                        lines = lines,
                        cursorRow = cursorRow,
                        cursorCol = cursorCol,
                        terminalTitle = terminalTitle,
                        currentTheme = currentTheme,
                        cursorAlpha = cursorAlpha,
                        isShellActive = isShellActive,
                        isStarting = isStarting,
                        searchQuery = searchQuery,
                        activeSelection = activeSelection,
                        onSelectionChanged = { activeSelection = it },
                        isFollowingTail = isFollowingTail.value,
                        unreadOutputLines = unreadOutputLines,
                        onScrollToBottom = {
                            coroutineScope.launch {
                                listState.animateScrollToItem(maxOf(0, lines.size - 1))
                            }
                        },
                        listState = listState,
                        focusRequester = focusRequester,
                        keyboardController = keyboardController,
                        isFloatingMode = true,
                        onTitlebarDrag = { dragAmount -> floatingOffset += dragAmount },
                        onResizeDrag = { dragAmount ->
                            val newWidth = (floatingSize.width + with(density) { dragAmount.x.toDp() }).coerceIn(240.dp, 800.dp)
                            val newHeight = (floatingSize.height + with(density) { dragAmount.y.toDp() }).coerceIn(200.dp, 800.dp)
                            floatingSize = DpSize(newWidth, newHeight)
                        },
                        onMinimizeFloating = { isFloatingMinimized = true },
                        onCloseFloating = { presentationMode = TerminalPresentationMode.FULLSCREEN },
                        onToggleFloating = { presentationMode = TerminalPresentationMode.FULLSCREEN },
                        onRequestOverlay = {
                            if (FloatingTerminalService.canDrawOverlay(context)) {
                                FloatingTerminalService.start(context, viewModel.environmentId)
                                Toast.makeText(context, "System floating terminal overlay active", Toast.LENGTH_SHORT).show()
                            } else {
                                showOverlayPermissionDialog = true
                            }
                        }
                    )
                }
            }

            // System Overlay Permission Dialog
            if (showOverlayPermissionDialog) {
                AlertDialog(
                    onDismissRequest = { showOverlayPermissionDialog = false },
                    containerColor = neuColors.background,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Layers, contentDescription = null, tint = neuColors.primaryAccent)
                            Text("Floating Terminal Overlay", color = neuColors.textPrimary, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Text(
                            "Display LinuxDroid's terminal as a movable floating window above other Android apps.\n\n" +
                            "This requires the 'Display over other apps' permission in Android Settings.\n" +
                            "You can dismiss the floating window anytime via its close button or notification.",
                            style = MaterialTheme.typography.bodySmall,
                            color = neuColors.textSecondary
                        )
                    },
                    confirmButton = {
                        NeuButton(
                            onClick = {
                                showOverlayPermissionDialog = false
                                try {
                                    val intent = Intent(
                                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Could not open overlay settings", Toast.LENGTH_SHORT).show()
                                }
                            },
                            isAccent = true,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Open Settings", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                    },
                    dismissButton = {
                        NeuButton(
                            onClick = {
                                showOverlayPermissionDialog = false
                                presentationMode = TerminalPresentationMode.IN_APP_FLOATING
                                Toast.makeText(context, "Using in-app floating mode", Toast.LENGTH_SHORT).show()
                            },
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Text("Use In-App Float", fontSize = 12.sp)
                        }
                    }
                )
            }

            // Google OAuth Authorization Code Entry Dialog
            if (showAuthCodeDialog) {
                AlertDialog(
                    onDismissRequest = { showAuthCodeDialog = false },
                    containerColor = neuColors.background,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.VpnKey, contentDescription = null, tint = neuColors.primaryAccent)
                            Text("Submit Authorization Code", color = neuColors.textPrimary, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "Paste or enter the authorization code displayed on Google's completion page:",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary
                            )

                            OutlinedTextField(
                                value = authCodeInput,
                                onValueChange = { authCodeInput = it },
                                placeholder = { Text("4/0A...", color = neuColors.textMuted) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = TextStyle(fontFamily = SfMono, fontSize = 13.sp, color = neuColors.textPrimary),
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = neuColors.primaryAccent,
                                    unfocusedBorderColor = neuColors.borderHighlight,
                                    focusedTextColor = neuColors.textPrimary,
                                    unfocusedTextColor = neuColors.textPrimary,
                                )
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clipText.isNullOrBlank()) {
                                            authCodeInput = clipText.trim()
                                        }
                                    }
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(14.dp), tint = neuColors.primaryAccent)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Paste from Clipboard", fontSize = 12.sp, color = neuColors.primaryAccent)
                                }
                            }
                        }
                    },
                    confirmButton = {
                        NeuButton(
                            onClick = {
                                if (authCodeInput.isNotBlank()) {
                                    viewModel.submitAntigravityAuthCode(authCodeInput.trim())
                                    showAuthCodeDialog = false
                                    authCodeInput = ""
                                }
                            },
                            isAccent = true,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Submit to CLI", fontWeight = FontWeight.Bold)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showAuthCodeDialog = false }) {
                            Text("Cancel", color = neuColors.textSecondary)
                        }
                    }
                )
            }

            // Export Options Modal Dialog
            if (showExportOptions) {
                AlertDialog(
                    onDismissRequest = { showExportOptions = false },
                    containerColor = neuColors.background,
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = neuColors.primaryAccent)
                            Text("Export Terminal & Logs", color = neuColors.textPrimary, fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                "Choose the export type for the current terminal session and runtime environment:",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary
                            )

                            Spacer(Modifier.height(4.dp))

                            NeuButton(
                                onClick = {
                                    showExportOptions = false
                                    viewModel.exportLogs(context, LogExportType.TERMINAL_FAILURE_LOG, asJson = false)
                                },
                                isAccent = true,
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Terminal Session & Failure Log", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            NeuButton(
                                onClick = {
                                    showExportOptions = false
                                    viewModel.exportLogs(context, LogExportType.FAILURE_REPORT_COMPACT, asJson = false)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Failure Report (Compact)", fontSize = 13.sp)
                            }

                            NeuButton(
                                onClick = {
                                    showExportOptions = false
                                    viewModel.exportLogs(context, LogExportType.FULL_LOGS, asJson = false)
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.FolderZip, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Full Raw Logs Archive (.zip)", fontSize = 13.sp)
                            }

                            NeuButton(
                                onClick = {
                                    showExportOptions = false
                                    val text = viewModel.getTerminalPlainText()
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Scrollback", text))
                                    Toast.makeText(context, "Terminal scrollback copied to clipboard", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Copy Terminal Scrollback", fontSize = 13.sp)
                            }
                        }
                    },
                    confirmButton = {
                        NeuButton(
                            onClick = { showExportOptions = false },
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

/**
 * Reusable macOS Terminal Window Card Component supporting fullscreen and in-app floating mode.
 */
@Composable
private fun TerminalWindowCard(
    modifier: Modifier = Modifier,
    viewModel: TerminalViewModel,
    navController: NavController,
    lines: List<com.linuxdroid.core.runtime.TerminalLineData>,
    cursorRow: Int,
    cursorCol: Int,
    terminalTitle: String,
    currentTheme: com.linuxdroid.core.runtime.terminal.TerminalTheme,
    cursorAlpha: Float,
    isShellActive: Boolean,
    isStarting: Boolean,
    searchQuery: String,
    activeSelection: TerminalSelection?,
    onSelectionChanged: (TerminalSelection?) -> Unit,
    isFollowingTail: Boolean,
    unreadOutputLines: Int,
    onScrollToBottom: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    focusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    isFloatingMode: Boolean = false,
    onTitlebarDrag: (Offset) -> Unit = {},
    onResizeDrag: (Offset) -> Unit = {},
    onMinimizeFloating: () -> Unit = {},
    onCloseFloating: () -> Unit = {},
    onToggleFloating: () -> Unit = {},
    onRequestOverlay: () -> Unit = {},
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val neuColors = NeuTheme.colors
    val coroutineScope = rememberCoroutineScope()

    NeuCard(
        modifier = modifier,
        isInset = true,
        shape = RoundedCornerShape(14.dp),
        elevation = if (isFloatingMode) 6.dp else 3.dp
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val fontSizeSp = 12.sp
            val lineHeightSp = 16.sp
            val charWidthDp = with(density) { (fontSizeSp * 0.6f).toDp() }
            val lineHeightDp = with(density) { lineHeightSp.toDp() }

            val calculatedCols = (maxWidth / charWidthDp).toInt().coerceIn(20, 240)
            val calculatedRows = (maxHeight / lineHeightDp).toInt().coerceIn(5, 120)

            LaunchedEffect(calculatedRows, calculatedCols) {
                viewModel.resize(calculatedRows, calculatedCols)
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // macOS Terminal Titlebar (Draggable when in floating mode)
                val titlebarModifier = if (isFloatingMode) {
                    Modifier.pointerInput(Unit) {
                        detectDragGestures { change, dragAmount ->
                            change.consume()
                            onTitlebarDrag(dragAmount)
                        }
                    }
                } else {
                    Modifier
                }

                Box(modifier = titlebarModifier) {
                    MacosWindowHeader(
                        title = terminalTitle.ifBlank { viewModel.environment.value?.name ?: "bash" },
                        badgeText = "${calculatedCols}x${calculatedRows}",
                        subtitle = if (isFloatingMode) "floating" else "pts/0",
                        onClose = {
                            if (isFloatingMode) onCloseFloating() else navController.popBackStack()
                        },
                        onMinimize = {
                            if (isFloatingMode) onMinimizeFloating() else viewModel.clear()
                        },
                        onMaximize = {
                            if (isFloatingMode) {
                                onToggleFloating()
                            } else {
                                focusRequester.requestFocus()
                                keyboardController?.show()
                            }
                        },
                        actions = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                // Theme cycling button
                                NeuIconButton(
                                    onClick = {
                                        viewModel.cycleTheme()
                                        val themeName = viewModel.currentTheme.value.name
                                        Toast.makeText(context, "Theme: $themeName", Toast.LENGTH_SHORT).show()
                                    },
                                    size = 24.dp,
                                    tint = neuColors.textMuted
                                ) {
                                    Icon(Icons.Default.Palette, contentDescription = "Theme", modifier = Modifier.size(12.dp))
                                }

                                // Float / Fullscreen toggle button
                                NeuIconButton(
                                    onClick = onToggleFloating,
                                    size = 24.dp,
                                    tint = if (isFloatingMode) neuColors.primaryAccent else neuColors.textMuted
                                ) {
                                    Icon(
                                        if (isFloatingMode) Icons.Default.Fullscreen else Icons.Default.PictureInPictureAlt,
                                        contentDescription = "Float Window",
                                        modifier = Modifier.size(12.dp)
                                    )
                                }

                                // Clear buffer button
                                NeuIconButton(
                                    onClick = { viewModel.clear() },
                                    size = 24.dp,
                                    tint = neuColors.textMuted
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear", modifier = Modifier.size(12.dp))
                                }
                            }
                        }
                    )
                }

                HorizontalDivider(
                    color = neuColors.borderHighlight.copy(alpha = 0.25f),
                    thickness = 0.5.dp
                )

                // Terminal Display Area with Direct Touch Selection
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(currentTheme.background))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        itemsIndexed(lines) { index, lineData ->
                            val isLastLine = index == lines.size - 1
                            val isCursorLine = if (lines.size <= 30) (index == cursorRow) else isLastLine
                            val hasSearchMatch = searchQuery.isNotBlank() && lineData.rawText.contains(searchQuery, ignoreCase = true)
                            val charWidthPx = with(density) { (12.sp * 0.6f).toPx() }
                            val lineHeightPx = with(density) { 16.sp.toPx() }

                            val annotatedString = buildAnnotatedString {
                                var charIdx = 0
                                for (span in lineData.spans) {
                                    val spanColor = getAdaptiveTerminalColor(
                                        rawColor = span.color,
                                        isDarkTheme = currentTheme.isDark,
                                        textPrimary = Color(if (currentTheme.isDark) 0xFFF1F5F9L else 0xFF0F172AL)
                                    )
                                    for (ch in span.text) {
                                        val isAtCursor = isCursorLine && isShellActive && charIdx == cursorCol
                                        val isSelected = activeSelection?.contains(index, charIdx) == true

                                        val defaultBg = if (isSelected) {
                                            Color(currentTheme.selectionBackground)
                                        } else if (isAtCursor) {
                                            Color(currentTheme.cursorColor).copy(alpha = cursorAlpha)
                                        } else if (hasSearchMatch) {
                                            neuColors.warning.copy(alpha = 0.25f)
                                        } else if (span.backgroundColor != 0L) {
                                            Color(span.backgroundColor)
                                        } else {
                                            Color.Transparent
                                        }

                                        val style = SpanStyle(
                                            color = if (isAtCursor && !isSelected) {
                                                if (currentTheme.isDark) Color.Black else Color.White
                                            } else {
                                                spanColor
                                            },
                                            fontFamily = SfMono,
                                            fontSize = 12.sp,
                                            fontWeight = if (span.isBold || isAtCursor) FontWeight.Bold else FontWeight.Normal,
                                            textDecoration = if (span.isUnderline) TextDecoration.Underline else TextDecoration.None,
                                            background = defaultBg
                                        )
                                        withStyle(style) {
                                            append(ch)
                                        }
                                        charIdx++
                                    }
                                }
                            }

                            Box(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .pointerInput(lineData.rawText) {
                                            detectTapGestures(
                                                onTap = {
                                                    if (activeSelection != null) {
                                                        onSelectionChanged(null)
                                                    } else {
                                                        focusRequester.requestFocus()
                                                        keyboardController?.show()
                                                    }
                                                },
                                                onLongPress = { offset ->
                                                    val col = (offset.x / charWidthPx).toInt().coerceIn(0, maxOf(0, lineData.rawText.length - 1))
                                                    val (startCol, endCol) = TerminalSelectionExtractor.findWordBoundary(lineData.rawText, col)
                                                    onSelectionChanged(
                                                        TerminalSelection(
                                                            start = TerminalPosition(index, startCol),
                                                            end = TerminalPosition(index, endCol)
                                                        )
                                                    )
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                }
                                            )
                                        },
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = annotatedString,
                                        fontFamily = SfMono,
                                        fontSize = 12.sp,
                                        lineHeight = 16.sp,
                                    )
                                    if (isLastLine && isShellActive && cursorCol >= lineData.rawText.length) {
                                        Spacer(Modifier.width(1.dp))
                                        Box(
                                            modifier = Modifier
                                                .width(7.dp)
                                                .height(14.dp)
                                                .background(
                                                    color = Color(currentTheme.cursorColor).copy(alpha = cursorAlpha),
                                                    shape = RoundedCornerShape(1.dp)
                                                )
                                        )
                                    }
                                }

                                // Interactive Draggable Selection Handles directly on the grid
                                if (activeSelection != null && !activeSelection.isEmpty) {
                                    val normStart = activeSelection.normalizedStart
                                    val normEnd = activeSelection.normalizedEnd

                                    // Start Handle (Droplet Pin at start position)
                                    if (normStart.row == index) {
                                        val handleX = (normStart.col * charWidthPx).coerceAtLeast(0f)
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .offset {
                                                    IntOffset(
                                                        x = (handleX - with(density) { 14.dp.toPx() }).roundToInt(),
                                                        y = -with(density) { 6.dp.toPx() }.roundToInt()
                                                    )
                                                }
                                                .size(width = 28.dp, height = 30.dp)
                                                .pointerInput(activeSelection) {
                                                    var accumDx = 0f
                                                    var accumDy = 0f
                                                    detectDragGestures(
                                                        onDragEnd = { accumDx = 0f; accumDy = 0f },
                                                        onDragCancel = { accumDx = 0f; accumDy = 0f }
                                                    ) { change, dragAmount ->
                                                        change.consume()
                                                        accumDx += dragAmount.x
                                                        accumDy += dragAmount.y
                                                        val colDelta = (accumDx / charWidthPx).toInt()
                                                        val rowDelta = (accumDy / lineHeightPx).toInt()
                                                        if (colDelta != 0 || rowDelta != 0) {
                                                            val newRow = (normStart.row + rowDelta).coerceIn(0, lines.size - 1)
                                                            val maxCol = lines[newRow].rawText.length
                                                            val newCol = (normStart.col + colDelta).coerceIn(0, maxCol)
                                                            val newPos = TerminalPosition(newRow, newCol)
                                                            if (newPos != normStart) {
                                                                onSelectionChanged(activeSelection.copy(start = newPos))
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                if (colDelta != 0) accumDx -= colDelta * charWidthPx
                                                                if (rowDelta != 0) accumDy -= rowDelta * lineHeightPx
                                                            }
                                                        }
                                                        if (dragAmount.y < 0 && index <= listState.firstVisibleItemIndex + 1) {
                                                            listState.dispatchRawDelta(dragAmount.y * 1.5f)
                                                        } else if (dragAmount.y > 0 && index >= listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size - 2) {
                                                            listState.dispatchRawDelta(dragAmount.y * 1.5f)
                                                        }
                                                    }
                                                },
                                            contentAlignment = Alignment.TopCenter
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(10.dp)
                                                        .clip(CircleShape)
                                                        .background(neuColors.primaryAccent)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .width(2.5.dp)
                                                        .height(14.dp)
                                                        .background(neuColors.primaryAccent)
                                                )
                                            }
                                        }
                                    }

                                    // End Handle (Droplet Pin at end position)
                                    if (normEnd.row == index) {
                                        val handleX = (normEnd.col * charWidthPx).coerceAtLeast(0f)
                                        Box(
                                            modifier = Modifier
                                                .align(Alignment.TopStart)
                                                .offset {
                                                    IntOffset(
                                                        x = (handleX - with(density) { 14.dp.toPx() }).roundToInt(),
                                                        y = with(density) { 2.dp.toPx() }.roundToInt()
                                                    )
                                                }
                                                .size(width = 28.dp, height = 30.dp)
                                                .pointerInput(activeSelection) {
                                                    var accumDx = 0f
                                                    var accumDy = 0f
                                                    detectDragGestures(
                                                        onDragEnd = { accumDx = 0f; accumDy = 0f },
                                                        onDragCancel = { accumDx = 0f; accumDy = 0f }
                                                    ) { change, dragAmount ->
                                                        change.consume()
                                                        accumDx += dragAmount.x
                                                        accumDy += dragAmount.y
                                                        val colDelta = (accumDx / charWidthPx).toInt()
                                                        val rowDelta = (accumDy / lineHeightPx).toInt()
                                                        if (colDelta != 0 || rowDelta != 0) {
                                                            val newRow = (normEnd.row + rowDelta).coerceIn(0, lines.size - 1)
                                                            val maxCol = lines[newRow].rawText.length
                                                            val newCol = (normEnd.col + colDelta).coerceIn(0, maxCol)
                                                            val newPos = TerminalPosition(newRow, newCol)
                                                            if (newPos != normEnd) {
                                                                onSelectionChanged(activeSelection.copy(end = newPos))
                                                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                                                if (colDelta != 0) accumDx -= colDelta * charWidthPx
                                                                if (rowDelta != 0) accumDy -= rowDelta * lineHeightPx
                                                            }
                                                        }
                                                        if (dragAmount.y < 0 && index <= listState.firstVisibleItemIndex + 1) {
                                                            listState.dispatchRawDelta(dragAmount.y * 1.5f)
                                                        } else if (dragAmount.y > 0 && index >= listState.firstVisibleItemIndex + listState.layoutInfo.visibleItemsInfo.size - 2) {
                                                            listState.dispatchRawDelta(dragAmount.y * 1.5f)
                                                        }
                                                    }
                                                },
                                            contentAlignment = Alignment.BottomCenter
                                        ) {
                                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                Box(
                                                    modifier = Modifier
                                                        .width(2.5.dp)
                                                        .height(14.dp)
                                                        .background(neuColors.primaryAccent)
                                                )
                                                Box(
                                                    modifier = Modifier
                                                        .size(10.dp)
                                                        .clip(CircleShape)
                                                        .background(neuColors.primaryAccent)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Direct Touch Selection Action Toolbar (Replaces system text-selection dialog)
                    if (activeSelection != null && !activeSelection.isEmpty) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = neuColors.surface,
                            border = BorderStroke(1.dp, neuColors.primaryAccent.copy(alpha = 0.6f)),
                            shadowElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                NeuButton(
                                    onClick = {
                                        val text = viewModel.extractSelection(activeSelection)
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Selection", text))
                                        Toast.makeText(context, "Selection copied", Toast.LENGTH_SHORT).show()
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                        onSelectionChanged(null)
                                    },
                                    isAccent = true,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Copy", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                                NeuButton(
                                    onClick = {
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clipText = clipboard.primaryClip?.getItemAt(0)?.text?.toString()
                                        if (!clipText.isNullOrEmpty()) {
                                            viewModel.pasteText(clipText)
                                        }
                                        onSelectionChanged(null)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.ContentPaste, contentDescription = "Paste", modifier = Modifier.size(13.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Paste", fontSize = 11.sp)
                                }
                                NeuButton(
                                    onClick = {
                                        val lineIdx = activeSelection.normalizedStart.row.coerceIn(0, lines.size - 1)
                                        val lineLen = lines.getOrNull(lineIdx)?.rawText?.length ?: 0
                                        onSelectionChanged(
                                            TerminalSelection(
                                                start = TerminalPosition(lineIdx, 0),
                                                end = TerminalPosition(lineIdx, lineLen)
                                            )
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Line", fontSize = 11.sp)
                                }
                                NeuButton(
                                    onClick = {
                                        val lastIdx = maxOf(0, lines.size - 1)
                                        val lastCol = lines.lastOrNull()?.rawText?.length ?: 0
                                        onSelectionChanged(
                                            TerminalSelection(
                                                start = TerminalPosition(0, 0),
                                                end = TerminalPosition(lastIdx, lastCol)
                                            )
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("Select All", fontSize = 11.sp)
                                }
                                NeuIconButton(
                                    onClick = { onSelectionChanged(null) },
                                    size = 28.dp,
                                    tint = neuColors.textSecondary
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(14.dp))
                                }
                            }
                        }
                    }

                    // Discreet "Jump to Bottom" badge when user is reading history
                    if (!isFollowingTail) {
                        Surface(
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = 14.dp, bottom = 12.dp)
                                .clickable { onScrollToBottom() },
                            shape = RoundedCornerShape(20.dp),
                            color = neuColors.surface,
                            border = BorderStroke(1.dp, neuColors.primaryAccent.copy(alpha = 0.5f)),
                            shadowElevation = 6.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.ArrowDownward, contentDescription = "Jump to Bottom", tint = neuColors.primaryAccent, modifier = Modifier.size(14.dp))
                                Text(
                                    text = if (unreadOutputLines > 0) "Bottom (+$unreadOutputLines)" else "Bottom",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = neuColors.primaryAccent,
                                    fontFamily = SfMono
                                )
                            }
                        }
                    }
                }
            }

            // Bottom-Right Corner Resize Handle in In-App Floating Mode
            if (isFloatingMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .pointerInput(density) {
                            detectDragGestures { change, dragAmount ->
                                change.consume()
                                onResizeDrag(dragAmount)
                            }
                        }
                        .padding(4.dp),
                    contentAlignment = Alignment.BottomEnd
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .border(1.5.dp, neuColors.primaryAccent, RoundedCornerShape(2.dp))
                    )
                }
            }

            if (isStarting) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                    color = neuColors.primaryAccent,
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

@Composable
private fun TerminalNavButton(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    highlight: Boolean = false,
    onClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    NeuButton(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        elevation = 2.dp,
        isAccent = highlight,
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = text,
            fontSize = fontSize,
            fontFamily = SfMono,
            fontWeight = FontWeight.Bold,
            color = if (highlight) neuColors.primaryAccent else neuColors.textPrimary
        )
    }
}

@Composable
private fun TerminalKeyButton(
    text: String,
    isActive: Boolean = false,
    highlight: Boolean = false,
    showLed: Boolean = false,
    onClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    NeuButton(
        onClick = onClick,
        isAccent = isActive || highlight,
        elevation = if (isActive) 1.dp else 3.dp,
        shape = RoundedCornerShape(8.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (showLed) {
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(if (isActive) Color(0xFF22C55E) else neuColors.textMuted.copy(alpha = 0.4f))
                )
            }
            Text(
                text = text,
                fontSize = 12.sp,
                fontFamily = SfMono,
                fontWeight = if (isActive || highlight) FontWeight.Bold else FontWeight.Medium,
                color = if (isActive) Color.White else if (highlight) neuColors.primaryAccent else neuColors.textPrimary
            )
        }
    }
}

/**
 * Maps raw ANSI terminal colors to high-contrast legible palette based on Light/Dark theme.
 */
private fun getAdaptiveTerminalColor(rawColor: Long, isDarkTheme: Boolean, textPrimary: Color): Color {
    return if (isDarkTheme) {
        when (rawColor) {
            0xFF1E1E1E, 0xFF000000 -> Color(0xFF94A3B8)
            0xFFE0E0E0 -> Color(0xFFF1F5F9)
            else -> Color(rawColor)
        }
    } else {
        when (rawColor) {
            0xFFE0E0E0, 0xFFFFFFFF, 0xFFF1F5F9 -> textPrimary
            0xFF757575, 0xFF1E1E1E, 0xFF000000 -> Color(0xFF0F172A)
            0xFFE57373, 0xFFFF8A80 -> Color(0xFFDC2626) // Crisp Red
            0xFF81C784, 0xFFA5D6A7 -> Color(0xFF15803D) // Crisp Green
            0xFFFFD54F, 0xFFFFE082 -> Color(0xFFB45309) // Crisp Amber/Yellow
            0xFF64B5F6, 0xFF90CAF9 -> Color(0xFF1D4ED8) // Crisp Blue
            0xFFBA68C8, 0xFFCE93D8 -> Color(0xFF7E22CE) // Crisp Magenta/Purple
            0xFF4DD0E1, 0xFF80DEEA -> Color(0xFF0E7490) // Crisp Cyan/Teal
            else -> {
                val c = Color(rawColor)
                val luminance = 0.299f * c.red + 0.587f * c.green + 0.114f * c.blue
                if (luminance > 0.65f) textPrimary else c
            }
        }
    }
}

