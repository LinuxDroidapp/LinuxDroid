package com.linuxdroid.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.AntigravityViewModel
import com.linuxdroid.core.model.AntigravityAuthState
import com.linuxdroid.core.model.AntigravityStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntigravityScreen(
    navController: NavController,
    environmentId: String? = null,
    viewModel: AntigravityViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val neuColors = NeuTheme.colors

    val state by viewModel.state.collectAsState()
    val activeEnv by viewModel.activeEnvironment.collectAsState()

    val targetEnv = activeEnv

    // Automatically navigate to WebView upon Remote Control URL detection
    LaunchedEffect(Unit) {
        viewModel.navigateToWebView.collect { url ->
            val envId = targetEnv?.id?.value ?: environmentId ?: ""
            navController.navigate(Screen.AntigravityWebView.route(envId, url))
        }
    }

    LaunchedEffect(state.authState, state.remoteControlUrl) {
        if (state.authState == AntigravityAuthState.CONNECTED && !state.remoteControlUrl.isNullOrBlank()) {
            val envId = targetEnv?.id?.value ?: environmentId ?: ""
            navController.navigate(Screen.AntigravityWebView.route(envId, state.remoteControlUrl!!))
        }
    }

    // When browser is launched, waiting for, or submitting authorization code, present dedicated full screen
    if (state.authState == AntigravityAuthState.WAITING_FOR_BROWSER_AUTH ||
        state.authState == AntigravityAuthState.WAITING_FOR_AUTH_CODE ||
        state.authState == AntigravityAuthState.SUBMITTING_AUTH_CODE ||
        state.authState == AntigravityAuthState.AUTHENTICATING
    ) {
        AntigravityAuthCodeScreen(
            navController = navController,
            environmentId = environmentId,
            viewModel = viewModel
        )
        return
    }

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Antigravity CLI",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary
                    )
                },
                navigationIcon = {
                    NeuIconButton(
                        onClick = { navController.popBackStack() },
                        size = 38.dp,
                        tint = neuColors.textPrimary
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (targetEnv != null) {
                        NeuIconButton(
                            onClick = { viewModel.checkForUpdates(targetEnv, autoUpdate = false) },
                            size = 38.dp,
                            tint = neuColors.textPrimary
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Check Updates")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = neuColors.background,
                    titleContentColor = neuColors.textPrimary,
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(neuColors.background)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Main Antigravity Hero Card
            NeuCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = 5.dp,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = neuColors.primaryAccent.copy(alpha = 0.15f),
                                modifier = Modifier.size(48.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        Icons.Default.CloudQueue,
                                        contentDescription = null,
                                        tint = neuColors.primaryAccent,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                            Column {
                                Text(
                                    "Google Antigravity",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                    color = neuColors.textPrimary
                                )
                                Text(
                                    "Agentic AI Coding Assistant",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = neuColors.textSecondary
                                )
                            }
                        }

                        // Status Badge
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = when (state.status) {
                                AntigravityStatus.RUNNING -> neuColors.success.copy(alpha = 0.15f)
                                AntigravityStatus.INSTALLED -> neuColors.primaryAccent.copy(alpha = 0.15f)
                                AntigravityStatus.INSTALLING, AntigravityStatus.UPDATING, AntigravityStatus.CHECKING_FOR_UPDATES -> neuColors.warning.copy(alpha = 0.15f)
                                AntigravityStatus.FAILED -> neuColors.error.copy(alpha = 0.15f)
                                AntigravityStatus.NOT_INSTALLED -> neuColors.surfacePressed
                                else -> neuColors.surfacePressed
                            },
                            border = androidx.compose.foundation.BorderStroke(
                                0.5.dp,
                                when (state.status) {
                                    AntigravityStatus.RUNNING -> neuColors.success.copy(alpha = 0.4f)
                                    AntigravityStatus.INSTALLED -> neuColors.primaryAccent.copy(alpha = 0.4f)
                                    AntigravityStatus.FAILED -> neuColors.error.copy(alpha = 0.4f)
                                    else -> neuColors.borderHighlight.copy(alpha = 0.3f)
                                }
                            )
                        ) {
                            Text(
                                text = when (state.status) {
                                    AntigravityStatus.RUNNING -> "RUNNING"
                                    AntigravityStatus.INSTALLED -> "INSTALLED"
                                    AntigravityStatus.INSTALLING -> "INSTALLING"
                                    AntigravityStatus.UPDATING -> "UPDATING"
                                    AntigravityStatus.CHECKING_FOR_UPDATES -> "CHECKING"
                                    AntigravityStatus.FAILED -> "FAILED"
                                    AntigravityStatus.NOT_INSTALLED -> "NOT INSTALLED"
                                    AntigravityStatus.LAUNCHING -> "LAUNCHING"
                                },
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                                color = when (state.status) {
                                    AntigravityStatus.RUNNING -> neuColors.success
                                    AntigravityStatus.INSTALLED -> neuColors.primaryAccent
                                    AntigravityStatus.INSTALLING, AntigravityStatus.UPDATING, AntigravityStatus.CHECKING_FOR_UPDATES -> neuColors.warning
                                    AntigravityStatus.FAILED -> neuColors.error
                                    else -> neuColors.textMuted
                                },
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (!state.statusMessage.isNullOrBlank()) {
                        Text(
                            state.statusMessage ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = SfMono),
                            color = neuColors.textSecondary
                        )
                    }

                    if (state.status == AntigravityStatus.INSTALLING || state.status == AntigravityStatus.UPDATING) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth(),
                                color = neuColors.primaryAccent,
                                trackColor = neuColors.surfacePressed
                            )
                        }
                    }

                    if (!state.errorMessage.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = neuColors.error.copy(alpha = 0.1f),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = neuColors.error, modifier = Modifier.size(18.dp))
                                Text(
                                    state.errorMessage ?: "",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                                    color = neuColors.error
                                )
                            }
                        }
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        if (state.canInstall && targetEnv != null) {
                            NeuButton(
                                onClick = { viewModel.installAntigravity(targetEnv) },
                                modifier = Modifier.weight(1f),
                                shape = RoundedCornerShape(12.dp),
                                enabled = !state.status.isBusy
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, tint = neuColors.primaryAccent)
                                    Text(if (state.status == AntigravityStatus.FAILED) "Retry Install" else "Install Antigravity", fontWeight = FontWeight.Bold, color = neuColors.textPrimary)
                                }
                            }
                        }

                        if (state.canLaunch && targetEnv != null) {
                            if (!state.isRunning && !state.authState.isConnecting) {
                                NeuButton(
                                    onClick = { viewModel.startAntigravity(targetEnv) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.PlayArrow,
                                            contentDescription = null,
                                            tint = neuColors.success
                                        )
                                        Text(
                                            "Launch Antigravity",
                                            fontWeight = FontWeight.Bold,
                                            color = neuColors.textPrimary
                                        )
                                    }
                                }
                            } else {
                                NeuButton(
                                    onClick = {
                                        if (state.remoteControlUrl != null) {
                                            navController.navigate(Screen.AntigravityWebView.route(targetEnv.id.value, state.remoteControlUrl!!))
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    enabled = state.remoteControlUrl != null
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.OpenInBrowser,
                                            contentDescription = null,
                                            tint = neuColors.primaryAccent
                                        )
                                        Text(
                                            if (state.remoteControlUrl != null) "Open WebView" else "Connecting…",
                                            fontWeight = FontWeight.Bold,
                                            color = neuColors.textPrimary
                                        )
                                    }
                                }

                                NeuButton(
                                    onClick = { viewModel.stopAntigravity(targetEnv.id.value) },
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.padding(vertical = 4.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.Stop,
                                            contentDescription = null,
                                            tint = neuColors.error,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Text("Stop", fontWeight = FontWeight.Bold, color = neuColors.error)
                                    }
                                }
                            }

                            NeuButton(
                                onClick = { viewModel.checkForUpdates(targetEnv, autoUpdate = false) },
                                shape = RoundedCornerShape(12.dp),
                                enabled = !state.status.isBusy
                            ) {
                                Text("Check Updates", fontSize = 12.sp, color = neuColors.textSecondary)
                            }
                        }
                    }

                    if (state.hasSavedSession) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = neuColors.surface,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.primaryAccent.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(Icons.Default.LockOpen, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(14.dp))
                                Text(
                                    "Saved CLI authentication session detected in guest rootfs",
                                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                    color = neuColors.textSecondary
                                )
                            }
                        }
                    }
                }
            }

            // Progression connecting card
            if (state.authState.isConnecting) {
                NeuCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 5.dp,
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    strokeWidth = 2.5.dp,
                                    color = neuColors.primaryAccent
                                )
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        when (state.authState) {
                                            AntigravityAuthState.STARTING -> "Starting Background CLI…"
                                            AntigravityAuthState.DETECTING_LOGIN -> "Detecting Login Status…"
                                            AntigravityAuthState.SELECTING_GOOGLE_OAUTH -> "Selecting Google OAuth…"
                                            AntigravityAuthState.WAITING_FOR_BROWSER_AUTH -> "Waiting for Browser Sign-in…"
                                            AntigravityAuthState.AUTHENTICATING -> "Authenticating with Google…"
                                            AntigravityAuthState.WAITING_FOR_REMOTE_URL -> "Waiting for Remote Control URL…"
                                            AntigravityAuthState.CONNECTING_WEBVIEW -> "Connecting to WebView…"
                                            else -> "Starting Antigravity…"
                                        },
                                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                        color = neuColors.textPrimary
                                    )
                                    Text(
                                        state.statusMessage ?: "Setting up background session…",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = neuColors.textSecondary
                                    )
                                }
                                NeuIconButton(
                                    onClick = { viewModel.stopAntigravity(targetEnv?.id?.value) },
                                    size = 32.dp,
                                    tint = neuColors.error
                                ) {
                                    Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.size(16.dp))
                                }
                            }

                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = neuColors.primaryAccent,
                                trackColor = neuColors.surfacePressed
                            )
                        }
                    }
                }

            // Remote Control Card
            if (state.remoteControlUrl != null && targetEnv != null) {
                NeuCard(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = 4.dp,
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.Lan, contentDescription = null, tint = neuColors.primaryAccent)
                            Text("Remote Control Session", fontWeight = FontWeight.Bold, color = neuColors.textPrimary)
                        }

                        Text(
                            state.remoteControlUrl ?: "",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = SfMono),
                            color = neuColors.textSecondary
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NeuButton(
                                onClick = {
                                    state.remoteControlUrl?.let { url ->
                                        navController.navigate(Screen.AntigravityWebView.route(targetEnv.id.value, url))
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Open Built-in View", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = neuColors.primaryAccent)
                            }
                            NeuButton(
                                onClick = {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clip.setPrimaryClip(ClipData.newPlainText("Remote Control URL", state.remoteControlUrl))
                                    Toast.makeText(context, "URL copied", Toast.LENGTH_SHORT).show()
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Copy Link", fontSize = 12.sp, color = neuColors.textPrimary)
                            }
                            NeuButton(
                                onClick = {
                                    state.remoteControlUrl?.let { url ->
                                        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        }
                                        runCatching { context.startActivity(intent) }
                                    }
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Chrome", fontSize = 12.sp, color = neuColors.textSecondary)
                            }
                        }
                    }
                }
            }

            // Information & Commands Card
            NeuCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Execution Command", fontWeight = FontWeight.Bold, color = neuColors.textPrimary)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = neuColors.surfacePressed,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            viewModel.getLaunchCommand(),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = SfMono),
                            color = neuColors.primaryAccent,
                            modifier = Modifier.padding(10.dp)
                        )
                    }

                    Text(
                        "Installed at /usr/local/bin/agy inside the rootfs. Launching connects to the official remote control bridge allowing paired web or desktop sessions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = neuColors.textSecondary
                    )
                }
            }

            // Opt-in Automation Diagnostics Card
            var showDiagnostics by remember { mutableStateOf(false) }
            val diagnosticsList = viewModel.menuDiagnostics

            NeuCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.BugReport, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(18.dp))
                            Text("Menu Diagnostics (Opt-in)", fontWeight = FontWeight.Bold, color = neuColors.textPrimary)
                        }
                        TextButton(onClick = { showDiagnostics = !showDiagnostics }) {
                            Text(if (showDiagnostics) "Hide" else "Show", fontSize = 12.sp, color = neuColors.primaryAccent)
                        }
                    }

                    if (showDiagnostics) {
                        if (diagnosticsList.isEmpty()) {
                            Text(
                                "No menu interaction events recorded yet.",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textMuted
                            )
                        } else {
                            diagnosticsList.takeLast(10).forEach { diag ->
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = neuColors.surfacePressed,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                "State: ${diag.menuState}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontFamily = SfMono),
                                                color = neuColors.primaryAccent
                                            )
                                            Text(
                                                "Option: ${diag.highlightedOption.name}",
                                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = SfMono),
                                                color = neuColors.textSecondary
                                            )
                                        }
                                        if (diag.keyAction != null || diag.ptyInputBytesHex != null) {
                                            Text(
                                                "Key: ${diag.keyAction ?: "None"} · PTY Bytes: ${diag.ptyInputBytesHex ?: "None"}",
                                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = SfMono, fontSize = 11.sp),
                                                color = neuColors.textPrimary
                                            )
                                        }
                                        Text(
                                            "Transition Confirmed: ${diag.menuTransitionConfirmed} · ${diag.detailMessage}",
                                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                                            color = neuColors.textSecondary
                                        )
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
