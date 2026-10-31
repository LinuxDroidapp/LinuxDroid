package com.linuxdroid.app.ui.screens

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.view.ViewGroup
import android.webkit.*
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavController
import com.linuxdroid.app.ui.theme.*
import com.linuxdroid.app.ui.viewmodel.AntigravityViewModel
import kotlin.math.roundToInt

/**
 * Built-in Antigravity Remote Control WebView Screen with macOS-inspired window controls:
 * - Red dot (Close): Confirmation dialog, terminates background CLI process, closes session, releases resources.
 * - Yellow dot (Minimize): Minimizes WebView into in-app floating mini-window (movable via titlebar drag, resizable via corner grip, preserves CLI & WebView state, restore action).
 * - Green dot (Full screen): Expands WebView to 100% available area, hides surrounding UI, provides floating restore action.
 */
@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun AntigravityWebViewScreen(
    navController: NavController,
    environmentId: String,
    initialUrl: String,
    viewModel: AntigravityViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val neuColors = NeuTheme.colors
    val lifecycleOwner = LocalLifecycleOwner.current
    val density = LocalDensity.current.density

    var webViewInstance by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var pageTitle by remember { mutableStateOf("Antigravity") }
    var pageProgress by remember { mutableFloatStateOf(0f) }
    var isLoading by remember { mutableStateOf(true) }
    var pageError by remember { mutableStateOf<String?>(null) }

    // Window controls states
    var showCloseConfirmDialog by remember { mutableStateOf(false) }
    var isMinimized by remember { mutableStateOf(false) }
    var isFullscreen by remember { mutableStateOf(false) }

    // Floating mini-window coordinates & dimensions
    var windowOffsetX by remember { mutableFloatStateOf(30f) }
    var windowOffsetY by remember { mutableFloatStateOf(80f) }
    var windowWidthDp by remember { mutableFloatStateOf(320f) }
    var windowHeightDp by remember { mutableFloatStateOf(460f) }

    // Intercept hardware/gesture Back
    BackHandler {
        when {
            isFullscreen -> isFullscreen = false
            isMinimized -> {
                isMinimized = false
                viewModel.setMinimized(false)
            }
            webViewInstance?.canGoBack() == true -> webViewInstance?.goBack()
            else -> navController.popBackStack()
        }
    }

    // Lifecycle observer to pause/resume WebView rendering
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> webViewInstance?.onPause()
                Lifecycle.Event.ON_RESUME -> webViewInstance?.onResume()
                Lifecycle.Event.ON_DESTROY -> {
                    webViewInstance?.destroy()
                    webViewInstance = null
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webViewInstance?.destroy()
            webViewInstance = null
        }
    }

    // Confirmation dialog for Red dot (Close)
    if (showCloseConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCloseConfirmDialog = false },
            title = {
                Text(
                    "Terminate Antigravity Session?",
                    fontWeight = FontWeight.Bold,
                    color = neuColors.textPrimary
                )
            },
            text = {
                Text(
                    "This will terminate the background Antigravity CLI process, close the Remote Control tunnel, and release session resources. Any unsaved edits will be lost.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = neuColors.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCloseConfirmDialog = false
                        viewModel.stopAntigravity(environmentId)
                        navController.popBackStack()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = neuColors.error)
                ) {
                    Text("Terminate & Close", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showCloseConfirmDialog = false }) {
                    Text("Cancel", color = neuColors.textSecondary)
                }
            },
            containerColor = neuColors.surface,
            shape = RoundedCornerShape(16.dp)
        )
    }

    Scaffold(
        containerColor = neuColors.background,
        topBar = {
            if (!isFullscreen) {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = pageTitle.ifBlank { "Antigravity Remote Control" },
                                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                color = neuColors.textPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Surface(
                                color = neuColors.success.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp),
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, neuColors.success.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "REMOTE CONTROL",
                                    color = neuColors.success,
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    maxLines = 1,
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        Row(
                            modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // macOS Red Dot (Close)
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .background(Color(0xFFFF5F56), CircleShape)
                                    .border(0.5.dp, Color(0xFFE0443E), CircleShape)
                                    .clickable { showCloseConfirmDialog = true }
                            )
                            // macOS Yellow Dot (Minimize)
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .background(Color(0xFFFFBD2E), CircleShape)
                                    .border(0.5.dp, Color(0xFFDEA123), CircleShape)
                                    .clickable {
                                        isMinimized = !isMinimized
                                        viewModel.setMinimized(isMinimized)
                                    }
                            )
                            // macOS Green Dot (Fullscreen)
                            Box(
                                modifier = Modifier
                                    .size(13.dp)
                                    .background(Color(0xFF27C93F), CircleShape)
                                    .border(0.5.dp, Color(0xFF1AAB29), CircleShape)
                                    .clickable { isFullscreen = true }
                            )
                        }
                    },
                    actions = {
                        // Refresh action
                        NeuIconButton(
                            onClick = {
                                pageError = null
                                webViewInstance?.reload()
                            },
                            size = 36.dp,
                            tint = neuColors.textPrimary
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = "Reload", modifier = Modifier.size(17.dp))
                        }
                        Spacer(Modifier.width(4.dp))
                        // Open in Chrome / External Browser
                        NeuIconButton(
                            onClick = {
                                val uri = Uri.parse(currentUrl)
                                val intent = Intent(Intent.ACTION_VIEW, uri).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                runCatching { context.startActivity(intent) }
                            },
                            size = 36.dp,
                            tint = neuColors.primaryAccent
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = "Open in Browser", modifier = Modifier.size(17.dp))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = neuColors.background,
                        titleContentColor = neuColors.textPrimary,
                    )
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isFullscreen) PaddingValues(0.dp) else padding)
                .background(neuColors.background)
        ) {
            // Background presentation view when minimized into floating mini-window
            if (isMinimized) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Surface(
                        shape = CircleShape,
                        color = neuColors.primaryAccent.copy(alpha = 0.15f),
                        modifier = Modifier.size(64.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.CloudQueue,
                                contentDescription = null,
                                tint = neuColors.primaryAccent,
                                modifier = Modifier.size(36.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Google Antigravity Mini-Window",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = neuColors.textPrimary
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Remote Control session active in floating window. Drag by titlebar or resize with bottom-right corner grip.",
                        style = MaterialTheme.typography.bodySmall,
                        color = neuColors.textSecondary,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))
                    NeuButton(
                        onClick = {
                            isMinimized = false
                            viewModel.setMinimized(false)
                        },
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.OpenInFull, contentDescription = null, modifier = Modifier.size(16.dp), tint = neuColors.primaryAccent)
                        Spacer(Modifier.width(6.dp))
                        Text("Restore Full Window", fontWeight = FontWeight.Bold, color = neuColors.textPrimary)
                    }
                }
            }

            // Single WebView Container Box with dynamic modifier to preserve WebView state
            Box(
                modifier = if (isMinimized) {
                    Modifier
                        .offset { IntOffset(windowOffsetX.roundToInt(), windowOffsetY.roundToInt()) }
                        .size(windowWidthDp.dp, windowHeightDp.dp)
                        .shadow(16.dp, RoundedCornerShape(14.dp))
                        .background(neuColors.surface, RoundedCornerShape(14.dp))
                        .border(1.dp, neuColors.borderHighlight, RoundedCornerShape(14.dp))
                        .clip(RoundedCornerShape(14.dp))
                } else {
                    Modifier.fillMaxSize()
                }
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    // Minimized Floating Window Header Bar
                    if (isMinimized) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp)
                                .background(neuColors.surfacePressed)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        windowOffsetX += dragAmount.x
                                        windowOffsetY += dragAmount.y
                                    }
                                }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .background(Color(0xFFFF5F56), CircleShape)
                                        .border(0.5.dp, Color(0xFFE0443E), CircleShape)
                                        .clickable { showCloseConfirmDialog = true }
                                )
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .background(Color(0xFFFFBD2E), CircleShape)
                                        .border(0.5.dp, Color(0xFFDEA123), CircleShape)
                                        .clickable {
                                            isMinimized = false
                                            viewModel.setMinimized(false)
                                        }
                                )
                                Box(
                                    modifier = Modifier
                                        .size(11.dp)
                                        .background(Color(0xFF27C93F), CircleShape)
                                        .border(0.5.dp, Color(0xFF1AAB29), CircleShape)
                                        .clickable {
                                            isMinimized = false
                                            isFullscreen = true
                                            viewModel.setMinimized(false)
                                        }
                                )
                            }

                            Text(
                                text = "Antigravity Remote",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = neuColors.textSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )

                            IconButton(
                                onClick = {
                                    isMinimized = false
                                    viewModel.setMinimized(false)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    Icons.Default.OpenInFull,
                                    contentDescription = "Restore",
                                    tint = neuColors.primaryAccent,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }

                    // WebView and loading progress
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                WebView(ctx).apply {
                                    layoutParams = ViewGroup.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.MATCH_PARENT
                                    )

                                    // Strict Secure WebView Configuration
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        databaseEnabled = true
                                        allowFileAccess = false
                                        allowContentAccess = false
                                        allowFileAccessFromFileURLs = false
                                        allowUniversalAccessFromFileURLs = false
                                        mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                        cacheMode = WebSettings.LOAD_DEFAULT
                                        useWideViewPort = true
                                        loadWithOverviewMode = true
                                        setSupportZoom(true)
                                        builtInZoomControls = true
                                        displayZoomControls = false
                                    }

                                    webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                            val targetUri = request?.url ?: return false
                                            val host = targetUri.host?.lowercase() ?: ""
                                            val scheme = targetUri.scheme?.lowercase() ?: ""

                                            // Allow navigation to official Antigravity and Google Auth endpoints in this WebView
                                            if (scheme == "https" && (
                                                        host == "antigravity.google" ||
                                                                host.endsWith(".antigravity.google") ||
                                                                host == "antigravity.google.com" ||
                                                                host.endsWith(".antigravity.google.com") ||
                                                                host == "accounts.google.com"
                                                        )) {
                                                return false
                                            }

                                            // External or unverified URL: delegate to external browser
                                            try {
                                                val intent = Intent(Intent.ACTION_VIEW, targetUri).apply {
                                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                }
                                                ctx.startActivity(intent)
                                            } catch (_: Exception) {}
                                            return true
                                        }

                                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                            super.onPageStarted(view, url, favicon)
                                            isLoading = true
                                            pageError = null
                                            url?.let { currentUrl = it }
                                        }

                                        override fun onPageFinished(view: WebView?, url: String?) {
                                            super.onPageFinished(view, url)
                                            isLoading = false
                                            url?.let { currentUrl = it }
                                            view?.title?.let { if (it.isNotBlank()) pageTitle = it }
                                        }

                                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                            super.onReceivedError(view, request, error)
                                            if (request?.isForMainFrame == true) {
                                                isLoading = false
                                                val description = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                                    error?.description?.toString() ?: "Connection error"
                                                } else {
                                                    "Connection error"
                                                }
                                                pageError = description
                                            }
                                        }

                                        override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
                                            super.onReceivedHttpError(view, request, errorResponse)
                                            if (request?.isForMainFrame == true) {
                                                val code = errorResponse?.statusCode ?: 0
                                                if (code >= 400) {
                                                    pageError = "HTTP error $code: ${errorResponse?.reasonPhrase ?: ""}"
                                                }
                                            }
                                        }
                                    }

                                    webChromeClient = object : WebChromeClient() {
                                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                            super.onProgressChanged(view, newProgress)
                                            pageProgress = newProgress / 100f
                                            if (newProgress == 100) {
                                                isLoading = false
                                            }
                                        }

                                        override fun onReceivedTitle(view: WebView?, title: String?) {
                                            super.onReceivedTitle(view, title)
                                            if (!title.isNullOrBlank()) {
                                                pageTitle = title
                                            }
                                        }
                                    }

                                    loadUrl(initialUrl)
                                    webViewInstance = this
                                }
                            },
                            update = { view ->
                                if (view.url != initialUrl && currentUrl == initialUrl) {
                                    view.loadUrl(initialUrl)
                                }
                            }
                        )

                        // Progress bar
                        androidx.compose.animation.AnimatedVisibility(
                            visible = isLoading,
                            enter = fadeIn(),
                            exit = fadeOut(),
                            modifier = Modifier.align(Alignment.TopCenter)
                        ) {
                            LinearProgressIndicator(
                                progress = { pageProgress },
                                modifier = Modifier.fillMaxWidth(),
                                color = neuColors.primaryAccent,
                                trackColor = neuColors.surfacePressed,
                            )
                        }

                        // Error display card with Retry
                        if (pageError != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(neuColors.background.copy(alpha = 0.95f))
                                    .padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                NeuCard(
                                    modifier = Modifier.fillMaxWidth(),
                                    elevation = 6.dp,
                                    shape = RoundedCornerShape(16.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(20.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(14.dp)
                                    ) {
                                        Icon(
                                            Icons.Default.CloudOff,
                                            contentDescription = null,
                                            tint = neuColors.error,
                                            modifier = Modifier.size(48.dp)
                                        )
                                        Text(
                                            "Failed to Load Remote Control",
                                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                                            color = neuColors.textPrimary
                                        )
                                        Text(
                                            pageError ?: "Unable to connect to the Antigravity session. Ensure the CLI is still running in the background.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = neuColors.textSecondary
                                        )

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                                        ) {
                                            NeuButton(
                                                onClick = {
                                                    pageError = null
                                                    webViewInstance?.reload()
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("Retry", fontWeight = FontWeight.Bold, color = neuColors.textPrimary)
                                            }

                                            NeuButton(
                                                onClick = {
                                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl)).apply {
                                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                    }
                                                    runCatching { context.startActivity(intent) }
                                                },
                                                modifier = Modifier.weight(1f),
                                                shape = RoundedCornerShape(10.dp)
                                            ) {
                                                Text("Open in Chrome", fontWeight = FontWeight.Bold, color = neuColors.primaryAccent)
                                            }
                                        }

                                        TextButton(onClick = { navController.popBackStack() }) {
                                            Text("Return to Launcher", color = neuColors.textSecondary, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bottom-right corner resize grip when minimized
                if (isMinimized) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .size(24.dp)
                            .pointerInput(density) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    val deltaWDp = dragAmount.x / density
                                    val deltaHDp = dragAmount.y / density
                                    windowWidthDp = (windowWidthDp + deltaWDp).coerceIn(240f, 600f)
                                    windowHeightDp = (windowHeightDp + deltaHDp).coerceIn(300f, 850f)
                                }
                            },
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Icon(
                            Icons.Default.AspectRatio,
                            contentDescription = "Resize Grip",
                            tint = neuColors.textMuted,
                            modifier = Modifier
                                .size(14.dp)
                                .padding(2.dp)
                        )
                    }
                }
            }

            // Floating restore button when in fullscreen mode
            if (isFullscreen) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(16.dp)
                        .clickable { isFullscreen = false },
                    shape = CircleShape,
                    color = Color.Black.copy(alpha = 0.65f),
                    shadowElevation = 6.dp
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            Icons.Default.FullscreenExit,
                            contentDescription = "Exit Fullscreen",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                        Text("Exit Full Screen", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}
