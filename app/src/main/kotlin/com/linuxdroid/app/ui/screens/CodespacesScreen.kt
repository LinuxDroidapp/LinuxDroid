package com.linuxdroid.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.CodespacesViewModel
import com.linuxdroid.core.model.CodespaceAuthState
import com.linuxdroid.core.model.CodespaceItem
import com.linuxdroid.core.model.CodespaceState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CodespacesScreen(
    navController: NavController,
    activeEnvironmentId: String? = null,
    viewModel: CodespacesViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val neuColors = NeuTheme.colors

    val authState by viewModel.authState.collectAsState()
    val currentUser by viewModel.currentUser.collectAsState()
    val activeDeviceCode by viewModel.activeDeviceCode.collectAsState()
    val authStatusMessage by viewModel.authStatusMessage.collectAsState()

    val codespaces by viewModel.codespaces.collectAsState()
    val isLoading by viewModel.isLoadingCodespaces.collectAsState()
    val lastError by viewModel.codespacesError.collectAsState()
    val actionFeedback by viewModel.actionFeedback.collectAsState()

    var showTokenDialog by remember { mutableStateOf(false) }
    var tokenInput by remember { mutableStateOf("") }
    var showDeviceCodeDialog by remember { mutableStateOf(false) }
    var customClientIdInput by remember { mutableStateOf("") }

    LaunchedEffect(actionFeedback) {
        actionFeedback?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            viewModel.clearFeedback()
        }
    }

    LaunchedEffect(activeDeviceCode) {
        if (activeDeviceCode != null) {
            showDeviceCodeDialog = true
        }
    }

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "GitHub Codespaces",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                            color = neuColors.textPrimary
                        )
                        if (currentUser != null) {
                            Text(
                                "@${currentUser?.login}",
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = SfMono),
                                color = neuColors.textSecondary
                            )
                        }
                    }
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
                    if (authState == CodespaceAuthState.AUTHENTICATED) {
                        NeuIconButton(
                            onClick = { viewModel.refreshCodespaces() },
                            size = 38.dp,
                            tint = neuColors.textPrimary
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                        NeuIconButton(
                            onClick = { viewModel.signOut() },
                            size = 38.dp,
                            tint = neuColors.error
                        ) {
                            Icon(Icons.Default.Logout, contentDescription = "Sign Out")
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(neuColors.background)
        ) {
            if (authState != CodespaceAuthState.AUTHENTICATED) {
                // Not authenticated view
                CodespacesLoginCard(
                    authState = authState,
                    statusMessage = authStatusMessage,
                    onStartDeviceFlow = { viewModel.startDeviceCodeFlow() },
                    onOpenTokenDialog = { showTokenDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .align(Alignment.Center)
                )
            } else {
                // Codespaces list view
                if (isLoading && codespaces.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = neuColors.primaryAccent)
                    }
                } else if (codespaces.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Icon(Icons.Default.CloudOff, contentDescription = null, tint = neuColors.textMuted, modifier = Modifier.size(48.dp))
                            Text(
                                "No Codespaces Found",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = neuColors.textPrimary
                            )
                            Text(
                                "Create a Codespace on github.com to connect from LinuxDroid.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = neuColors.textSecondary,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                            NeuButton(
                                onClick = { viewModel.refreshCodespaces() },
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("Refresh", color = neuColors.textPrimary)
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(codespaces, key = { it.id }) { item ->
                            CodespaceCardItem(
                                item = item,
                                onConnect = {
                                    val envId = activeEnvironmentId ?: "default"
                                    val cmd = viewModel.getTerminalConnectionCommand(item)
                                    navController.navigate(Screen.Terminal.route(envId, cmd))
                                },
                                onOpenWeb = {
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(item.webUrl)).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    runCatching { context.startActivity(intent) }
                                },
                                onStart = { viewModel.startCodespace(item) },
                                onStop = { viewModel.stopCodespace(item) }
                            )
                        }
                    }
                }
            }

            // PAT Dialog
            if (showTokenDialog) {
                AlertDialog(
                    onDismissRequest = { showTokenDialog = false },
                    title = { Text("Enter Personal Access Token", fontWeight = FontWeight.Bold) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                "Paste a GitHub Personal Access Token (classic or fine-grained) with 'codespace' scope.",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textSecondary
                            )
                            OutlinedTextField(
                                value = tokenInput,
                                onValueChange = { tokenInput = it },
                                label = { Text("GitHub Token (ghp_...)") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                if (tokenInput.isNotBlank()) {
                                    viewModel.authenticateWithToken(tokenInput)
                                    showTokenDialog = false
                                    tokenInput = ""
                                }
                            }
                        ) {
                            Text("Sign In")
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTokenDialog = false }) {
                            Text("Cancel")
                        }
                    }
                )
            }

            // Device Authorization Code Dialog
            if (showDeviceCodeDialog && activeDeviceCode != null) {
                val code = activeDeviceCode!!
                AlertDialog(
                    onDismissRequest = {
                        viewModel.cancelDeviceFlow()
                        showDeviceCodeDialog = false
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(Icons.Default.VpnKey, contentDescription = null, tint = neuColors.primaryAccent)
                            Text("GitHub Device Activation", fontWeight = FontWeight.Bold)
                        }
                    },
                    text = {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "Enter this one-time code on GitHub to authorize LinuxDroid:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = neuColors.textSecondary
                            )

                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = neuColors.surfacePressed,
                                modifier = Modifier.clickable {
                                    val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clip.setPrimaryClip(ClipData.newPlainText("GitHub Code", code.userCode))
                                    Toast.makeText(context, "Code copied: ${code.userCode}", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        code.userCode,
                                        style = MaterialTheme.typography.headlineMedium.copy(
                                            fontFamily = SfMono,
                                            fontWeight = FontWeight.Bold,
                                            letterSpacing = 2.sp
                                        ),
                                        color = neuColors.primaryAccent
                                    )
                                    Icon(Icons.Default.ContentCopy, contentDescription = "Copy", tint = neuColors.textMuted, modifier = Modifier.size(18.dp))
                                }
                            }

                            Text(
                                "Tap 'Open GitHub' to open ${code.verificationUri} in your browser.",
                                style = MaterialTheme.typography.bodySmall,
                                color = neuColors.textMuted
                            )

                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            Text(
                                "Waiting for authorization in browser…",
                                style = MaterialTheme.typography.labelSmall,
                                color = neuColors.textSecondary
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val clip = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clip.setPrimaryClip(ClipData.newPlainText("GitHub Code", code.userCode))
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(code.verificationUri)).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { context.startActivity(intent) }
                            }
                        ) {
                            Text("Open GitHub")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = {
                                viewModel.cancelDeviceFlow()
                                showDeviceCodeDialog = false
                            }
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun CodespacesLoginCard(
    authState: CodespaceAuthState,
    statusMessage: String?,
    onStartDeviceFlow: () -> Unit,
    onOpenTokenDialog: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val neuColors = NeuTheme.colors
    NeuCard(
        modifier = modifier,
        elevation = 6.dp,
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = neuColors.primaryAccent.copy(alpha = 0.15f),
                modifier = Modifier.size(64.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.CloudCircle,
                        contentDescription = null,
                        tint = neuColors.primaryAccent,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }

            Text(
                "Connect to GitHub",
                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                color = neuColors.textPrimary
            )

            Text(
                "Authenticate with GitHub to view your Codespaces, start stopped environments, and connect to remote interactive terminals directly inside LinuxDroid.",
                style = MaterialTheme.typography.bodyMedium,
                color = neuColors.textSecondary,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            if (!statusMessage.isNullOrBlank()) {
                Text(
                    statusMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = neuColors.warning,
                    fontFamily = SfMono,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            NeuButton(
                onClick = onStartDeviceFlow,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(Icons.Default.VpnKey, contentDescription = null, tint = neuColors.primaryAccent)
                    Text("Device Code Sign In", fontWeight = FontWeight.Bold, color = neuColors.textPrimary)
                }
            }

            NeuButton(
                onClick = onOpenTokenDialog,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(vertical = 4.dp)
                ) {
                    Icon(Icons.Default.Password, contentDescription = null, tint = neuColors.textMuted)
                    Text("Personal Access Token", color = neuColors.textSecondary)
                }
            }
        }
    }
}

@Composable
private fun CodespaceCardItem(
    item: CodespaceItem,
    onConnect: () -> Unit,
    onOpenWeb: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val neuColors = NeuTheme.colors
    val isRunning = item.state == CodespaceState.AVAILABLE

    NeuCard(
        modifier = Modifier.fillMaxWidth(),
        elevation = 3.dp,
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
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        item.displayName ?: item.name,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        item.repository,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = SfMono),
                        color = neuColors.primaryAccent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = when (item.state) {
                        CodespaceState.AVAILABLE -> neuColors.success.copy(alpha = 0.15f)
                        CodespaceState.STARTING, CodespaceState.AWAITING, CodespaceState.REBUILDING -> neuColors.warning.copy(alpha = 0.15f)
                        else -> neuColors.surfacePressed
                    },
                    border = androidx.compose.foundation.BorderStroke(
                        0.5.dp,
                        when (item.state) {
                            CodespaceState.AVAILABLE -> neuColors.success.copy(alpha = 0.4f)
                            CodespaceState.STARTING, CodespaceState.AWAITING -> neuColors.warning.copy(alpha = 0.4f)
                            else -> neuColors.borderHighlight.copy(alpha = 0.3f)
                        }
                    )
                ) {
                    Text(
                        item.state.raw,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp),
                        color = when (item.state) {
                            CodespaceState.AVAILABLE -> neuColors.success
                            CodespaceState.STARTING, CodespaceState.AWAITING, CodespaceState.REBUILDING -> neuColors.warning
                            else -> neuColors.textMuted
                        },
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }

            val branch = item.branch
            val machineName = item.machineDisplayName
            if (!branch.isNullOrBlank() || !machineName.isNullOrBlank()) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (!branch.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.AccountTree, contentDescription = null, tint = neuColors.textMuted, modifier = Modifier.size(13.dp))
                            Text(branch, style = MaterialTheme.typography.bodySmall.copy(fontFamily = SfMono, fontSize = 11.sp), color = neuColors.textSecondary)
                        }
                    }
                    if (!machineName.isNullOrBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Memory, contentDescription = null, tint = neuColors.textMuted, modifier = Modifier.size(13.dp))
                            Text(machineName, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = neuColors.textMuted)
                        }
                    }
                }
            }

            Divider(color = neuColors.borderHighlight.copy(alpha = 0.2f), thickness = 0.5.dp)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (isRunning) {
                        NeuButton(
                            onClick = onConnect,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.Terminal, contentDescription = null, tint = neuColors.primaryAccent, modifier = Modifier.size(15.dp))
                                Text("Connect Terminal", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = neuColors.primaryAccent)
                            }
                        }
                        NeuButton(
                            onClick = onStop,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text("Stop", fontSize = 12.sp, color = neuColors.textMuted)
                        }
                    } else if (item.state == CodespaceState.STOPPED) {
                        NeuButton(
                            onClick = onStart,
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, tint = neuColors.success, modifier = Modifier.size(15.dp))
                                Text("Start Codespace", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = neuColors.textPrimary)
                            }
                        }
                    } else {
                        // Starting / Transitioning
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp, color = neuColors.warning)
                            Text("State changing…", fontSize = 12.sp, color = neuColors.textMuted)
                        }
                    }
                }

                NeuButton(
                    onClick = onOpenWeb,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.OpenInBrowser, contentDescription = null, tint = neuColors.textSecondary, modifier = Modifier.size(14.dp))
                        Text("Web IDE", fontSize = 12.sp, color = neuColors.textSecondary)
                    }
                }
            }
        }
    }
}
