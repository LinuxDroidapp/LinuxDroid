package com.linuxdroid.app.ui.screens

import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavController
import com.linuxdroid.app.ui.navigation.Screen
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.AntigravityViewModel
import com.linuxdroid.core.model.AntigravityAuthState
import com.linuxdroid.linux.bootstrap.AntigravityManager

/**
 * Dedicated Google OAuth Authorization Code Input Screen for Antigravity CLI.
 *
 * Requirements:
 * - Title: Complete Google Authentication
 * - Explanation: Paste the authorization code returned by Google.
 * - Clearly visible text input field supporting long codes, keyboard input, paste, selection
 * - Paste button using Android clipboard API after explicit user action (never silently inspects clipboard)
 * - Submit Code button (disabled during submission, verifies process is alive and waiting)
 * - Clear button
 * - Cancel Authentication action (cleanly terminates process without orphans)
 * - Re-open browser action
 * - Failure handling (CLI exited with Start Again action, recoverable code error with retry)
 * - Automatic continuation to WebView when Remote Control URL is detected
 * - Strict security: code is never stored persistently, never logged, and cleared after transmission
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AntigravityAuthCodeScreen(
    navController: NavController,
    environmentId: String? = null,
    viewModel: AntigravityViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val neuColors = NeuTheme.colors

    val state by viewModel.state.collectAsState()
    val activeEnv by viewModel.activeEnvironment.collectAsState()
    val targetEnv = activeEnv

    // Transient in-memory code buffer (never logged or persisted)
    var authCodeInput by remember { mutableStateOf("") }
    var localErrorMessage by remember { mutableStateOf<String?>(null) }

    // Intercept Back button: clean cancellation
    BackHandler {
        viewModel.cancelAuthentication(targetEnv?.id?.value ?: environmentId)
        navController.popBackStack()
    }

    // Automatically navigate to WebView when Remote Control URL is detected
    LaunchedEffect(state.authState, state.remoteControlUrl) {
        if (state.authState == AntigravityAuthState.CONNECTED && !state.remoteControlUrl.isNullOrBlank()) {
            val envId = targetEnv?.id?.value ?: environmentId ?: ""
            navController.navigate(Screen.AntigravityWebView.route(envId, state.remoteControlUrl!!)) {
                popUpTo(Screen.Antigravity.route(envId)) { inclusive = false }
            }
        }
    }

    // React to cancellation: return to previous screen
    LaunchedEffect(state.authState) {
        if (state.authState == AntigravityAuthState.CANCELLED) {
            navController.popBackStack()
        }
    }

    val isSubmitting = state.authState == AntigravityAuthState.SUBMITTING_AUTH_CODE ||
            state.authState == AntigravityAuthState.AUTHENTICATING
    val isCliExited = state.authState == AntigravityAuthState.ERROR &&
            (state.errorMessage?.contains("no longer running", ignoreCase = true) == true ||
             state.errorMessage?.contains("exited", ignoreCase = true) == true)

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Complete Google Authentication",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary
                    )
                },
                navigationIcon = {
                    NeuIconButton(
                        onClick = {
                            viewModel.cancelAuthentication(targetEnv?.id?.value ?: environmentId)
                            navController.popBackStack()
                        },
                        size = 38.dp,
                        tint = neuColors.textPrimary
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            // Main Authentication Card
            NeuCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = 6.dp,
                shape = RoundedCornerShape(20.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header with Key/Lock Icon
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = neuColors.primaryAccent.copy(alpha = 0.15f),
                            modifier = Modifier.size(52.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.VpnKey,
                                    contentDescription = null,
                                    tint = neuColors.primaryAccent,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                when (state.authState) {
                                    AntigravityAuthState.WAITING_FOR_AUTH_CODE -> "Waiting for Authorization Code…"
                                    AntigravityAuthState.SUBMITTING_AUTH_CODE,
                                    AntigravityAuthState.AUTHENTICATING -> "Verifying authorization…"
                                    else -> "Waiting for Browser Sign-in…"
                                },
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                ),
                                color = neuColors.textPrimary
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Complete Google sign-in in your browser. If you aren't automatically redirected, paste the authorization code below:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = neuColors.textSecondary
                            )
                        }
                    }

                    HorizontalDivider(color = neuColors.borderHighlight.copy(alpha = 0.3f))

                    // Failure State Banner: CLI Process Exited
                    if (isCliExited) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = neuColors.error.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, neuColors.error.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = neuColors.error)
                                    Text(
                                        "Antigravity session is no longer running",
                                        fontWeight = FontWeight.Bold,
                                        color = neuColors.error
                                    )
                                }
                                Text(
                                    state.errorMessage ?: "The background Antigravity CLI process has terminated.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = neuColors.textSecondary
                                )
                                if (targetEnv != null) {
                                    NeuButton(
                                        onClick = {
                                            localErrorMessage = null
                                            viewModel.startAntigravity(targetEnv)
                                        },
                                        shape = RoundedCornerShape(10.dp),
                                        isAccent = true
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                            Text("Start Again", fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Recoverable Error Banner (e.g. Invalid Code / Submission Error)
                    val activeError = localErrorMessage ?: if (!isCliExited && state.authState == AntigravityAuthState.ERROR) state.errorMessage else null
                    if (!activeError.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = neuColors.error.copy(alpha = 0.12f),
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = neuColors.error, modifier = Modifier.size(18.dp))
                                Text(
                                    activeError,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = neuColors.error
                                )
                            }
                        }
                    }

                    // Progress indicator while submitting / authenticating
                    if (isSubmitting) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
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
                                    state.statusMessage ?: "Authenticating with Google…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = neuColors.textSecondary
                                )
                            }
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                color = neuColors.primaryAccent,
                                trackColor = neuColors.surfacePressed
                            )
                        }
                    }

                    // Clearly visible authorization code text input field
                    OutlinedTextField(
                        value = authCodeInput,
                        onValueChange = {
                            authCodeInput = it
                            localErrorMessage = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = {
                            Text("Paste authorization code here…", color = neuColors.textMuted)
                        },
                        minLines = 3,
                        maxLines = 5,
                        textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = SfMono),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Ascii,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(
                            onDone = { focusManager.clearFocus() }
                        ),
                        shape = RoundedCornerShape(12.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = neuColors.primaryAccent,
                            unfocusedBorderColor = neuColors.borderHighlight,
                            focusedTextColor = neuColors.textPrimary,
                            unfocusedTextColor = neuColors.textPrimary,
                            cursorColor = neuColors.primaryAccent,
                            focusedContainerColor = neuColors.surfacePressed.copy(alpha = 0.3f),
                            unfocusedContainerColor = neuColors.surfacePressed.copy(alpha = 0.2f),
                        ),
                        enabled = !isSubmitting && !isCliExited
                    )

                    // Secondary action buttons: Paste & Clear
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        // Explicit Paste Button using Android Clipboard API
                        NeuButton(
                            onClick = {
                                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                                val clip = clipboardManager?.primaryClip
                                if (clip != null && clip.itemCount > 0) {
                                    val itemText = clip.getItemAt(0).coerceToText(context)?.toString()?.trim()
                                    if (!itemText.isNullOrBlank()) {
                                        authCodeInput = itemText
                                        localErrorMessage = null
                                        Toast.makeText(context, "Code pasted from clipboard", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    Toast.makeText(context, "Clipboard is empty", Toast.LENGTH_SHORT).show()
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            enabled = !isSubmitting && !isCliExited
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.ContentPaste,
                                    contentDescription = "Paste Code",
                                    tint = neuColors.primaryAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("Paste", fontWeight = FontWeight.Bold, color = neuColors.textPrimary)
                            }
                        }

                        // Clear Button
                        NeuButton(
                            onClick = {
                                authCodeInput = ""
                                localErrorMessage = null
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            enabled = authCodeInput.isNotEmpty() && !isSubmitting
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(
                                    Icons.Default.Clear,
                                    contentDescription = "Clear Code",
                                    tint = neuColors.textSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text("Clear", fontWeight = FontWeight.Bold, color = neuColors.textSecondary)
                            }
                        }
                    }

                    // Primary Action: Submit Code Button
                    NeuButton(
                        onClick = {
                            focusManager.clearFocus()
                            val trimmedCode = authCodeInput.trim()
                            if (trimmedCode.isNotBlank()) {
                                val result = viewModel.submitAuthCode(trimmedCode)
                                when (result) {
                                    is AntigravityManager.AuthCodeSubmitResult.Success -> {
                                        // Clear sensitive code immediately from memory and input field
                                        authCodeInput = ""
                                        localErrorMessage = null
                                    }
                                    is AntigravityManager.AuthCodeSubmitResult.IgnoredDuplicate -> {
                                        authCodeInput = ""
                                        localErrorMessage = null
                                        Toast.makeText(context, "Authentication already in progress or completed", Toast.LENGTH_SHORT).show()
                                    }
                                    is AntigravityManager.AuthCodeSubmitResult.Error -> {
                                        localErrorMessage = result.message
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        isAccent = true,
                        shape = RoundedCornerShape(12.dp),
                        enabled = authCodeInput.trim().isNotBlank() && !isSubmitting && !isCliExited
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(vertical = 6.dp)
                        ) {
                            if (isSubmitting) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = neuColors.textPrimary
                                )
                                Text("Verifying authorization…", fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                Text("Submit Authorization Code", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            }
                        }
                    }

                    // Bottom Navigation Actions: Browser & Cancel
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.authUrl != null) {
                            NeuButton(
                                onClick = { viewModel.openGoogleAuthBrowser() },
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(16.dp), tint = neuColors.primaryAccent)
                                    Text("Reopen Browser", fontSize = 12.sp, color = neuColors.primaryAccent)
                                }
                            }
                        } else {
                            Spacer(Modifier.width(1.dp))
                        }

                        // Cancel Authentication Action
                        NeuButton(
                            onClick = {
                                viewModel.cancelAuthentication(targetEnv?.id?.value ?: environmentId)
                                navController.popBackStack()
                            },
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(vertical = 2.dp)
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = neuColors.error)
                                Text("Cancel", fontSize = 12.sp, color = neuColors.error)
                            }
                        }
                    }
                }
            }

            // Instructional Steps Card
            NeuCard(
                modifier = Modifier.fillMaxWidth(),
                elevation = 2.dp,
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "How Google Authentication Works",
                        fontWeight = FontWeight.Bold,
                        color = neuColors.textPrimary,
                        fontSize = 13.sp
                    )
                    Text(
                        "1. Sign in with your Google account in the browser.\n" +
                        "2. Copy the authorization code displayed on the completion page.\n" +
                        "3. Tap Paste above and click Submit Code.\n" +
                        "4. Antigravity will automatically initialize Remote Control.",
                        style = MaterialTheme.typography.bodySmall,
                        color = neuColors.textSecondary,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
