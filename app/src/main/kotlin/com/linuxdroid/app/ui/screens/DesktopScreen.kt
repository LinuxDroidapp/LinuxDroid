package com.linuxdroid.app.ui.screens

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.linuxdroid.native_bridge.NativeBridge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.components.DistroIcon
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.EnvironmentViewModel
import com.linuxdroid.core.display.GuiSurfaceView
import com.linuxdroid.core.model.Environment
import com.linuxdroid.core.model.EnvironmentState
import com.linuxdroid.core.model.StartMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.text.SimpleDateFormat
import java.util.*

/**
 * Lifecycle phases for the Desktop GUI experience.
 */
enum class DesktopPhase {
    /** Active Wayland / X11 Graphical Desktop workspace */
    DESKTOP,
    /** Graphical session startup failure screen (Section 10) */
    FAILED,
}

/**
 * Desktop GUI Mode Screen:
 * Directly presents GuiSurfaceView so LDDM and LDDE render natively
 * on the embedded libweston Wayland display.
 * If session is already RUNNING, restores existing desktop immediately without restarting.
 */
@Composable
fun DesktopScreen(
    navController: NavController,
    environmentViewModel: EnvironmentViewModel = hiltViewModel(),
) {
    val environments by environmentViewModel.environments.collectAsState()
    val neuColors = NeuTheme.colors

    val targetEnvId = remember {
        navController.currentBackStackEntry?.arguments?.getString("environmentId")
    }

    val environment = environments.firstOrNull { it.id.value == targetEnvId }
        ?: environments.firstOrNull()

    if (environment == null) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(neuColors.background),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "No Linux environment found",
                    style = MaterialTheme.typography.titleMedium,
                    color = neuColors.textPrimary,
                )
                NeuButton(onClick = { navController.popBackStack() }) {
                    Text("Return Home")
                }
            }
        }
        return
    }

    var currentPhase by remember {
        mutableStateOf(
            if (environment.state == EnvironmentState.FAILED) DesktopPhase.FAILED else DesktopPhase.DESKTOP
        )
    }

    // Auto-start environment in GUI mode if stopped
    LaunchedEffect(environment.id.value) {
        if (environment.state != EnvironmentState.RUNNING && environment.state != EnvironmentState.STARTING) {
            environmentViewModel.startEnvironment(environment, StartMode.GUI)
        }
    }

    // React to state transitions
    LaunchedEffect(environment.state) {
        if (environment.state == EnvironmentState.FAILED) {
            currentPhase = DesktopPhase.FAILED
        } else if (environment.state == EnvironmentState.RUNNING || environment.state == EnvironmentState.STARTING) {
            currentPhase = DesktopPhase.DESKTOP
        }
    }

    AnimatedContent(
        targetState = currentPhase,
        label = "DesktopPhaseTransition",
        transitionSpec = {
            fadeIn() togetherWith fadeOut()
        }
    ) { phase ->
        when (phase) {
            DesktopPhase.DESKTOP -> {
                LinuxDesktopWorkspace(
                    environment = environment,
                    navController = navController,
                    environmentViewModel = environmentViewModel,
                    onOpenTerminal = {
                        navController.navigate(Screen.Terminal.route(environment.id.value))
                    },
                    onStopSession = {
                        environmentViewModel.stopEnvironment(environment)
                        navController.popBackStack()
                    },
                    onNavigateHome = {
                        navController.popBackStack()
                    }
                )
            }
            DesktopPhase.FAILED -> {
                LinuxGuiFailureScreen(
                    environment = environment,
                    onRetryGui = {
                        currentPhase = DesktopPhase.DESKTOP
                        environmentViewModel.startEnvironment(environment, StartMode.GUI)
                    },
                    onOpenTerminal = {
                        navController.navigate(Screen.Terminal.route(environment.id.value))
                    },
                    onExit = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}

data class DesktopWindow(
    val id: Long,
    val appId: String,
    val title: String
)

/**
 * 3. Graphical Desktop Workspace.
 *
 * Phase 10: Mobile Desktop UX
 * - Touch / Trackpad mode switching
 * - Immersive fullscreen toggle
 * - Collapsible mobile toolbar with modifier keys (ESC, TAB, Ctrl, Alt, Super, Shift, Arrows)
 * - Clipboard quick-paste
 * - Task / Window switcher dialog
 * - Dynamic display scaling (100%, 200%)
 * - Deterministic 5-priority BackHandler
 */
@Composable
private fun LinuxDesktopWorkspace(
    environment: Environment,
    navController: NavController,
    environmentViewModel: EnvironmentViewModel,
    onOpenTerminal: () -> Unit,
    onStopSession: () -> Unit,
    onNavigateHome: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val context = LocalContext.current
    val activity = context as? Activity
    var surfaceViewRef by remember { mutableStateOf<GuiSurfaceView?>(null) }

    // UX state
    var touchMode by remember { mutableStateOf(GuiSurfaceView.TouchMode.DIRECT) }
    var isFullscreen by remember { mutableStateOf(false) }
    var isToolbarExpanded by remember { mutableStateOf(true) }
    var currentScale by remember { mutableStateOf(1) }

    // Overlays and Dialogs
    var showExitDialog by remember { mutableStateOf(false) }
    var showWindowSwitcher by remember { mutableStateOf(false) }
    var showScaleSelector by remember { mutableStateOf(false) }
    var showSessionMenu by remember { mutableStateOf(false) }

    var activeWindows by remember { mutableStateOf<List<DesktopWindow>>(emptyList()) }
    var lastEscTimestamp by remember { mutableStateOf(0L) }

    // Monitor session actions from in-guest LDDE (e.g. Power Menu -> Android or Shutdown)
    LaunchedEffect(environment.rootfsPath) {
        val actionCandidates = listOf(
            java.io.File(environment.rootfsPath, "tmp/linuxdroid_session_action"),
            java.io.File(environment.rootfsPath, "run/user/1000/linuxdroid_session_action"),
            java.io.File(environment.rootfsPath, "run/user/0/linuxdroid_session_action"),
        )
        while (isActive) {
            for (actionFile in actionCandidates) {
                if (actionFile.exists()) {
                    try {
                        val action = actionFile.readText().trim()
                        actionFile.delete()
                        if (action == "minimize") {
                            navController.popBackStack()
                            break
                        } else if (action == "shutdown") {
                            environmentViewModel.stopEnvironment(environment)
                            navController.popBackStack()
                            break
                        }
                    } catch (_: Exception) {}
                }
            }
            delay(250)
        }
    }

    // Latched modifier button states
    var isCtrlLatched by remember { mutableStateOf(false) }
    var isAltLatched by remember { mutableStateOf(false) }
    var isSuperLatched by remember { mutableStateOf(false) }
    var isShiftLatched by remember { mutableStateOf(false) }
    var isCapsLockLatched by remember { mutableStateOf(false) }
    var showKeyboardToolbar by remember { mutableStateOf(true) }
    var selectedKeyCategory by remember { mutableStateOf(KeyCategory.NAV) }

    fun refreshActiveWindows() {
        val raw = NativeBridge.getActiveWindows()
        activeWindows = raw.mapNotNull { desc ->
            val parts = desc.split(":", limit = 3)
            if (parts.isNotEmpty()) {
                val id = parts[0].toLongOrNull() ?: return@mapNotNull null
                val appId = if (parts.size > 1) parts[1] else "App"
                val title = if (parts.size > 2) parts[2] else appId
                DesktopWindow(id, appId, title)
            } else null
        }
    }

    fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val item = clipboard?.primaryClip?.getItemAt(0)
        val text = item?.text?.toString()
        if (!text.isNullOrEmpty()) {
            surfaceViewRef?.pasteText(text)
            Toast.makeText(context, "Pasted ${text.length} characters", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
        }
    }

    // Fullscreen Insets controller
    val insetsController = remember(activity) {
        activity?.window?.let { win ->
            WindowCompat.getInsetsController(win, win.decorView).apply {
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    LaunchedEffect(isFullscreen) {
        if (isFullscreen) {
            insetsController?.hide(WindowInsetsCompat.Type.systemBars())
        } else {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            insetsController?.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // Deterministic BackHandler: navigates back to Android UI while keeping session RUNNING
    BackHandler {
        when {
            // Dismiss any open mobile dialog / sheet
            showExitDialog -> showExitDialog = false
            showWindowSwitcher -> showWindowSwitcher = false
            showScaleSelector -> showScaleSelector = false
            showSessionMenu -> showSessionMenu = false

            // Return to Android UI without killing PRoot or GUI session
            else -> {
                navController.popBackStack()
            }
        }
    }

    Scaffold(
        containerColor = Color(0xFF1E222B),
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF1E222B))
                .padding(if (isFullscreen) PaddingValues(0.dp) else padding),
        ) {
            // 1. Presentation Surface
            AndroidView(
                factory = { ctx ->
                    GuiSurfaceView(ctx).also {
                        it.touchMode = touchMode
                        surfaceViewRef = it
                    }
                },
                update = { view ->
                    view.touchMode = touchMode
                },
                modifier = Modifier.fillMaxSize()
            )

            // 2. Session Reconnection / Status Banner
            if (environment.state != EnvironmentState.RUNNING) {
                Surface(
                    color = Color(0xCC000000),
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = neuColors.warning,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Session state: ${environment.state}...",
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            color = neuColors.warning
                        )
                    }
                }
            }

            // 3. Mobile Desktop Toolbar Overlay
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
            ) {
                if (!isFullscreen || isToolbarExpanded) {
                    // Top Bar / Control Panel
                    Surface(
                        color = Color(0xE6161920),
                        shadowElevation = 6.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                        ) {
                            // Primary Control Row
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    DistroIcon(distribution = environment.distribution, size = 22.dp)
                                    Text(
                                        text = environment.name,
                                        fontFamily = SfPro,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    // Touch Mode Toggle
                                    Surface(
                                        color = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) neuColors.primaryAccent.copy(alpha = 0.2f) else neuColors.secondaryAccent.copy(alpha = 0.2f),
                                        shape = RoundedCornerShape(12.dp),
                                        modifier = Modifier.clickable {
                                            touchMode = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) {
                                                GuiSurfaceView.TouchMode.TRACKPAD
                                            } else {
                                                GuiSurfaceView.TouchMode.DIRECT
                                            }
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            Icon(
                                                imageVector = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) Icons.Default.TouchApp else Icons.Default.Mouse,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) neuColors.primaryAccent else neuColors.secondaryAccent
                                            )
                                            Text(
                                                text = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) "Direct" else "Trackpad",
                                                fontSize = 10.sp,
                                                fontFamily = SfMono,
                                                fontWeight = FontWeight.Bold,
                                                color = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) neuColors.primaryAccent else neuColors.secondaryAccent
                                            )
                                        }
                                    }
                                }

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    // Linux Virtual Keyboard Toolbar toggle
                                    IconButton(
                                        onClick = { showKeyboardToolbar = !showKeyboardToolbar },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Terminal,
                                            contentDescription = "Toggle Linux Keys",
                                            tint = if (showKeyboardToolbar) neuColors.primaryAccent else neuColors.textSecondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    // Soft Keyboard toggle
                                    IconButton(
                                        onClick = { surfaceViewRef?.toggleSoftKeyboard() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Keyboard, contentDescription = "Soft Keyboard", tint = neuColors.primaryAccent, modifier = Modifier.size(16.dp))
                                    }
                                    // Clipboard Paste
                                    IconButton(
                                        onClick = { pasteFromClipboard() },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = neuColors.secondaryAccent, modifier = Modifier.size(16.dp))
                                    }
                                    // Tasks / Window Switcher
                                    IconButton(
                                        onClick = {
                                            refreshActiveWindows()
                                            showWindowSwitcher = true
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.Layers, contentDescription = "Tasks", tint = neuColors.textPrimary, modifier = Modifier.size(16.dp))
                                    }
                                    // Scale Selector
                                    IconButton(
                                        onClick = { showScaleSelector = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.AspectRatio, contentDescription = "Scale", tint = neuColors.textPrimary, modifier = Modifier.size(16.dp))
                                    }
                                    // Fullscreen toggle
                                    IconButton(
                                        onClick = {
                                            isFullscreen = !isFullscreen
                                            if (isFullscreen) isToolbarExpanded = false
                                        },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            if (isFullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                            contentDescription = "Fullscreen",
                                            tint = neuColors.textPrimary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                    // Session Menu
                                    IconButton(
                                        onClick = { showSessionMenu = true },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Menu", tint = neuColors.textPrimary, modifier = Modifier.size(16.dp))
                                    }
                                    // Collapse button if in fullscreen
                                    if (isFullscreen) {
                                        IconButton(
                                            onClick = { isToolbarExpanded = false },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.KeyboardArrowUp, contentDescription = "Collapse", tint = neuColors.textSecondary, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }

                            if (showKeyboardToolbar) {
                                // Row 1: Modifier & Common Key Bar (Horizontally scrollable)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Latchable Modifiers with visual indicators
                                    ModifierPill(label = "Ctrl", isActive = isCtrlLatched) {
                                        isCtrlLatched = surfaceViewRef?.toggleModifier(KeyEvent.KEYCODE_CTRL_LEFT) ?: !isCtrlLatched
                                    }
                                    ModifierPill(label = "Alt", isActive = isAltLatched) {
                                        isAltLatched = surfaceViewRef?.toggleModifier(KeyEvent.KEYCODE_ALT_LEFT) ?: !isAltLatched
                                    }
                                    ModifierPill(label = "Super", isActive = isSuperLatched) {
                                        isSuperLatched = surfaceViewRef?.toggleModifier(KeyEvent.KEYCODE_META_LEFT) ?: !isSuperLatched
                                    }
                                    ModifierPill(label = "Shift", isActive = isShiftLatched) {
                                        isShiftLatched = surfaceViewRef?.toggleModifier(KeyEvent.KEYCODE_SHIFT_LEFT) ?: !isShiftLatched
                                    }
                                    ModifierPill(label = "Caps", isActive = isCapsLockLatched) {
                                        isCapsLockLatched = surfaceViewRef?.toggleModifier(KeyEvent.KEYCODE_CAPS_LOCK) ?: !isCapsLockLatched
                                    }

                                    // Common Navigation & Editing Keys
                                    ModifierPill(label = "ESC", isActive = false) {
                                        surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_ESCAPE)
                                    }
                                    ModifierPill(label = "TAB", isActive = false) {
                                        surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_TAB)
                                    }
                                    ModifierPill(label = "Enter", isActive = false) {
                                        surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_ENTER)
                                    }
                                    ModifierPill(label = "⌫", isActive = false) {
                                        surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_DEL)
                                    }
                                    ModifierPill(label = "Del", isActive = false) {
                                        surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_FORWARD_DEL)
                                    }

                                    // Category Switchers
                                    KeyCategory.entries.forEach { cat ->
                                        ModifierPill(
                                            label = cat.label,
                                            isActive = selectedKeyCategory == cat,
                                            isCategoryTab = true,
                                        ) {
                                            selectedKeyCategory = cat
                                        }
                                    }
                                }

                                // Row 2: Category Key Bar (Horizontally scrollable)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState())
                                        .padding(horizontal = 8.dp, vertical = 2.dp),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    when (selectedKeyCategory) {
                                        KeyCategory.NAV -> {
                                            ModifierPill(label = "←", isActive = false) {
                                                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_DPAD_LEFT)
                                            }
                                            ModifierPill(label = "↑", isActive = false) {
                                                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_DPAD_UP)
                                            }
                                            ModifierPill(label = "↓", isActive = false) {
                                                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_DPAD_DOWN)
                                            }
                                            ModifierPill(label = "→", isActive = false) {
                                                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_DPAD_RIGHT)
                                            }
                                            ModifierPill(label = "Home", isActive = false) {
                                                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_MOVE_HOME)
                                            }
                                            ModifierPill(label = "End", isActive = false) {
                                                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_MOVE_END)
                                            }
                                            ModifierPill(label = "PgUp", isActive = false) {
                                                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_PAGE_UP)
                                            }
                                            ModifierPill(label = "PgDn", isActive = false) {
                                                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_PAGE_DOWN)
                                            }
                                            ModifierPill(label = "Ins", isActive = false) {
                                                surfaceViewRef?.sendSingleKey(KeyEvent.KEYCODE_INSERT)
                                            }
                                        }
                                        KeyCategory.FN -> {
                                            val fKeys = listOf(
                                                "F1" to KeyEvent.KEYCODE_F1,
                                                "F2" to KeyEvent.KEYCODE_F2,
                                                "F3" to KeyEvent.KEYCODE_F3,
                                                "F4" to KeyEvent.KEYCODE_F4,
                                                "F5" to KeyEvent.KEYCODE_F5,
                                                "F6" to KeyEvent.KEYCODE_F6,
                                                "F7" to KeyEvent.KEYCODE_F7,
                                                "F8" to KeyEvent.KEYCODE_F8,
                                                "F9" to KeyEvent.KEYCODE_F9,
                                                "F10" to KeyEvent.KEYCODE_F10,
                                                "F11" to KeyEvent.KEYCODE_F11,
                                                "F12" to KeyEvent.KEYCODE_F12,
                                            )
                                            fKeys.forEach { (label, code) ->
                                                ModifierPill(label = label, isActive = false) {
                                                    surfaceViewRef?.sendSingleKey(code)
                                                }
                                            }
                                        }
                                        KeyCategory.SHORTCUTS -> {
                                            val shortcuts = listOf(
                                                "Ctrl+C" to KeyEvent.KEYCODE_C,
                                                "Ctrl+D" to KeyEvent.KEYCODE_D,
                                                "Ctrl+Z" to KeyEvent.KEYCODE_Z,
                                                "Ctrl+L" to KeyEvent.KEYCODE_L,
                                                "Ctrl+A" to KeyEvent.KEYCODE_A,
                                                "Ctrl+X" to KeyEvent.KEYCODE_X,
                                                "Ctrl+V" to KeyEvent.KEYCODE_V,
                                            )
                                            shortcuts.forEach { (label, code) ->
                                                ModifierPill(label = label, isActive = false) {
                                                    surfaceViewRef?.sendKeyCombination(KeyEvent.KEYCODE_CTRL_LEFT, code)
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

            // Floating Pill when Fullscreen and Toolbar collapsed
            if (isFullscreen && !isToolbarExpanded) {
                Surface(
                    color = Color(0xCC161920),
                    shape = RoundedCornerShape(16.dp),
                    shadowElevation = 4.dp,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 8.dp)
                        .clickable { isToolbarExpanded = true }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        DistroIcon(distribution = environment.distribution, size = 16.dp)
                        Text(
                            text = if (touchMode == GuiSurfaceView.TouchMode.DIRECT) "Touch" else "Trackpad",
                            fontFamily = SfMono,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = neuColors.primaryAccent
                        )
                        Icon(
                            Icons.Default.KeyboardArrowDown,
                            contentDescription = "Expand Toolbar",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // 4. Modals and Dialogs

            // Task / Window Switcher Dialog
            if (showWindowSwitcher) {
                AlertDialog(
                    onDismissRequest = { showWindowSwitcher = false },
                    title = {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Active Linux Windows", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            IconButton(onClick = { showWindowSwitcher = false }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Close", modifier = Modifier.size(16.dp))
                            }
                        }
                    },
                    text = {
                        if (activeWindows.isEmpty()) {
                            Text("No graphical windows currently open.", color = neuColors.textSecondary, fontSize = 13.sp)
                        } else {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(activeWindows) { win ->
                                    Surface(
                                        color = neuColors.surfacePressed,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text(
                                                text = win.title.ifEmpty { win.appId },
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 13.sp,
                                                color = neuColors.textPrimary
                                            )
                                            Text(
                                                text = "App: ${win.appId} • ID: ${win.id}",
                                                fontSize = 11.sp,
                                                fontFamily = SfMono,
                                                color = neuColors.textSecondary
                                            )
                                            Spacer(Modifier.height(6.dp))
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Button(
                                                    onClick = {
                                                        NativeBridge.performWindowAction(win.id, "activate")
                                                        showWindowSwitcher = false
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Focus", fontSize = 11.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        NativeBridge.performWindowAction(win.id, "maximize")
                                                        showWindowSwitcher = false
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Maximize", fontSize = 11.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        NativeBridge.performWindowAction(win.id, "close")
                                                        refreshActiveWindows()
                                                    },
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = neuColors.error),
                                                    modifier = Modifier.height(28.dp)
                                                ) {
                                                    Text("Close", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showWindowSwitcher = false }) {
                            Text("Done")
                        }
                    }
                )
            }

            // Display Scale Dialog
            if (showScaleSelector) {
                AlertDialog(
                    onDismissRequest = { showScaleSelector = false },
                    title = { Text("Display Scaling", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Scale factor for Weston and LDDE desktop:", fontSize = 13.sp, color = neuColors.textSecondary)
                            listOf(
                                1 to "100% (Native / Sharp)",
                                2 to "200% (HiDPI / Touch Friendly)"
                            ).forEach { (scale, label) ->
                                Surface(
                                    color = if (currentScale == scale) neuColors.primaryAccent.copy(alpha = 0.15f) else neuColors.surfacePressed,
                                    shape = RoundedCornerShape(8.dp),
                                    border = if (currentScale == scale) androidx.compose.foundation.BorderStroke(1.dp, neuColors.primaryAccent) else null,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            currentScale = scale
                                            NativeBridge.setOutputScale(scale)
                                            showScaleSelector = false
                                            Toast.makeText(context, "Scale set to $label", Toast.LENGTH_SHORT).show()
                                        }
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 13.sp,
                                        fontWeight = if (currentScale == scale) FontWeight.Bold else FontWeight.Normal,
                                        color = if (currentScale == scale) neuColors.primaryAccent else neuColors.textPrimary,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = { showScaleSelector = false }) {
                            Text("Close")
                        }
                    }
                )
            }

            // Session Menu Dialog
            if (showSessionMenu) {
                AlertDialog(
                    onDismissRequest = { showSessionMenu = false },
                    title = { Text("Desktop Session Menu", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(
                                color = neuColors.surfacePressed,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text("Distribution: ${environment.distribution.displayName} (${environment.architecture.abiName})", fontSize = 12.sp, fontFamily = SfMono)
                                    Text("State: ${environment.state}", fontSize = 12.sp, fontFamily = SfMono)
                                    Text("Scaling: ${currentScale * 100}%", fontSize = 12.sp, fontFamily = SfMono)
                                    Text("Input Mode: ${touchMode.name}", fontSize = 12.sp, fontFamily = SfMono)
                                }
                            }
                            Button(
                                onClick = {
                                    showSessionMenu = false
                                    onOpenTerminal()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Open Terminal CLI")
                            }
                            OutlinedButton(
                                onClick = {
                                    showSessionMenu = false
                                    navController.popBackStack()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Minimize to Android")
                            }
                            OutlinedButton(
                                onClick = {
                                    showSessionMenu = false
                                    NativeBridge.guiStop()
                                    NativeBridge.guiStart()
                                    Toast.makeText(context, "GUI Compositor Restarted", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Restart Wayland Compositor")
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showSessionMenu = false
                                showExitDialog = true
                            },
                            colors = ButtonDefaults.textButtonColors(contentColor = neuColors.error)
                        ) {
                            Text("Exit / Stop")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showSessionMenu = false }) {
                            Text("Dismiss")
                        }
                    }
                )
            }

            // Exit Confirmation Dialog (Back Policy Priority 4 / Session Stop)
            if (showExitDialog) {
                AlertDialog(
                    onDismissRequest = { showExitDialog = false },
                    title = { Text("Exit Desktop Session", fontWeight = FontWeight.Bold) },
                    text = {
                        Text(
                            "Choose whether to leave the Linux environment running in the background or stop it completely.",
                            fontSize = 13.sp,
                            color = neuColors.textSecondary
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                showExitDialog = false
                                onNavigateHome()
                            }
                        ) {
                            Text("Run in Background")
                        }
                    },
                    dismissButton = {
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            TextButton(onClick = { showExitDialog = false }) {
                                Text("Cancel")
                            }
                            TextButton(
                                onClick = {
                                    showExitDialog = false
                                    onStopSession()
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = neuColors.error)
                            ) {
                                Text("Stop Session")
                            }
                        }
                    }
                )
            }
        }
    }
}

private enum class KeyCategory(val label: String) {
    NAV("Nav / Edit"),
    FN("F1-F12"),
    SHORTCUTS("Shortcuts"),
}

@Composable
private fun ModifierPill(
    label: String,
    isActive: Boolean,
    isCategoryTab: Boolean = false,
    onClick: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val bgColor = when {
        isActive -> neuColors.primaryAccent
        isCategoryTab -> neuColors.surfaceHighlight
        else -> Color(0xFF2C3240)
    }
    val textColor = when {
        isActive -> Color.White
        isCategoryTab -> neuColors.primaryAccent
        else -> neuColors.textSecondary
    }
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp),
        border = if (isActive) androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)) else null,
        modifier = Modifier.clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isActive) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                )
            }
            Text(
                text = label,
                fontFamily = SfMono,
                fontSize = 11.sp,
                fontWeight = if (isActive || isCategoryTab) FontWeight.Bold else FontWeight.Normal,
                color = textColor,
            )
        }
    }
}

/**
 * 4. Deterministic GUI Startup Failure Screen (Section 10).
 * Never silently switches GUI -> CLI. Exposes [ Retry GUI ] and [ Open Terminal ].
 */
@Composable
private fun LinuxGuiFailureScreen(
    environment: Environment,
    onRetryGui: () -> Unit,
    onOpenTerminal: () -> Unit,
    onExit: () -> Unit,
) {
    val neuColors = NeuTheme.colors

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(neuColors.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        NeuCard(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            elevation = 6.dp,
            shape = RoundedCornerShape(20.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(neuColors.error.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = neuColors.error,
                        modifier = Modifier.size(32.dp),
                    )
                }

                Text(
                    text = "GUI Startup Failed",
                    style = MaterialTheme.typography.titleLarge.copy(
                        fontWeight = FontWeight.Bold,
                    ),
                    color = neuColors.textPrimary,
                    textAlign = TextAlign.Center,
                )

                Text(
                    text = environment.failureMessage ?: "The Linux graphical desktop session failed to start.",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = SfMono,
                    color = neuColors.textSecondary,
                    textAlign = TextAlign.Center,
                )

                Spacer(modifier = Modifier.height(8.dp))

                NeuButton(
                    onClick = onRetryGui,
                    modifier = Modifier.fillMaxWidth(),
                    isAccent = true,
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Retry GUI", fontWeight = FontWeight.SemiBold)
                }

                NeuButton(
                    onClick = onOpenTerminal,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Default.Terminal, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Open Terminal", fontWeight = FontWeight.SemiBold)
                }

                TextButton(onClick = onExit) {
                    Text("Return Home", color = neuColors.textSecondary)
                }
            }
        }
    }
}


