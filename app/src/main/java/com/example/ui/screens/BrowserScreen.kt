package com.example.ui.screens

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Bookmark
import androidx.compose.material.icons.rounded.BookmarkBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.browser.TorProxyController
import com.example.data.BrowserMode
import com.example.data.BrowserTab
import com.example.data.TorProxyState
import com.example.ui.BrowserUiState
import com.example.ui.BrowserViewModel
import com.example.ui.components.BrowserWebView
import com.example.ui.components.DisclaimerDialog
import com.example.ui.components.DownloadSandboxDialog
import com.example.ui.components.QuickShieldSheet
import com.example.ui.components.ShredConfirmDialog
import com.example.ui.components.StartPageContent
import com.example.ui.components.TabSwitcherSheet
import com.example.ui.theme.LocalShieldColors

@Composable
fun BrowserScreen(
    uiState: BrowserUiState,
    isBookmarked: Boolean,
    viewModel: BrowserViewModel,
    onOpenSecuritySettings: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val shieldColors = LocalShieldColors.current

    var showTabSwitcher by remember { mutableStateOf(false) }
    var showQuickShields by remember { mutableStateOf(false) }
    var showOverflowMenu by remember { mutableStateOf(false) }

    val activeTab = uiState.activeTab
    val isTorMode = uiState.securityConfig.browserMode == BrowserMode.TOR_ONION
    val accentColor by animateColorAsState(
        targetValue = if (isTorMode) shieldColors.torAccent else shieldColors.surfaceAccent,
        label = "browserAccent",
    )

    BackHandler(enabled = !activeTab.isStartPage || activeTab.canGoBack) {
        if (!viewModel.goBack()) {
            (context as? Activity)?.finish()
        }
    }

    LaunchedEffect(uiState.statusBannerMessage) {
        if (uiState.statusBannerMessage != null) {
            kotlinx.coroutines.delay(3200)
            viewModel.dismissStatusBanner()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding(),
            ) {
                // Dual Mode Switcher + Proxy Pill
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    DualModeSwitcher(
                        currentMode = uiState.securityConfig.browserMode,
                        onSelectMode = { viewModel.switchBrowserMode(it) },
                    )

                    ProxyStatusChip(
                        mode = uiState.securityConfig.browserMode,
                        proxyState = uiState.torProxyState,
                        noScriptEnabled = uiState.securityConfig.noScriptEnabled,
                        onClick = { showQuickShields = true },
                    )
                }

                // Omnibox Row
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp)
                        .padding(bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (uiState.isOmniboxFocused) accentColor else MaterialTheme.colorScheme.outline.copy(alpha = 0.6f),
                        ),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(
                                imageVector = when {
                                    activeTab.isOnionAddress || isTorMode -> Icons.Rounded.Security
                                    activeTab.isHttpsSecure -> Icons.Rounded.Lock
                                    else -> Icons.Rounded.WarningAmber
                                },
                                contentDescription = "Connection security",
                                tint = when {
                                    activeTab.isOnionAddress || isTorMode -> shieldColors.torAccent
                                    activeTab.isHttpsSecure -> shieldColors.secureGreen
                                    else -> shieldColors.warningAmber
                                },
                                modifier = Modifier.size(18.dp),
                            )

                            Box(modifier = Modifier.weight(1f)) {
                                if (uiState.omniboxInput.isEmpty()) {
                                    Text(
                                        text = if (isTorMode) "Search or enter .onion / URL…" else "Search or enter URL…",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                BasicTextField(
                                    value = uiState.omniboxInput,
                                    onValueChange = viewModel::onOmniboxChanged,
                                    singleLine = true,
                                    textStyle = MaterialTheme.typography.labelMedium.copy(
                                        color = MaterialTheme.colorScheme.onSurface,
                                    ),
                                    cursorBrush = SolidColor(accentColor),
                                    keyboardOptions = KeyboardOptions(
                                        keyboardType = KeyboardType.Uri,
                                        imeAction = ImeAction.Go,
                                    ),
                                    keyboardActions = KeyboardActions(
                                        onGo = {
                                            focusManager.clearFocus()
                                            viewModel.openUrlOrSearch(uiState.omniboxInput)
                                        },
                                    ),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { focusState ->
                                            viewModel.onOmniboxFocusChanged(focusState.isFocused)
                                        }
                                        .testTag("omnibox_input"),
                                )
                            }

                            if (uiState.omniboxInput.isNotEmpty() && uiState.isOmniboxFocused) {
                                Icon(
                                    imageVector = Icons.Rounded.Close,
                                    contentDescription = "Clear address",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(18.dp)
                                        .clickable { viewModel.onOmniboxChanged("") },
                                )
                            } else if (!activeTab.isStartPage) {
                                Icon(
                                    imageVector = if (isBookmarked) Icons.Rounded.Bookmark else Icons.Rounded.BookmarkBorder,
                                    contentDescription = "Bookmark page",
                                    tint = if (isBookmarked) accentColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clickable { viewModel.toggleBookmarkCurrentPage() }
                                        .testTag("bookmark_toggle_button"),
                                )
                            }
                        }
                    }

                    if (!activeTab.isStartPage) {
                        IconButton(
                            onClick = { viewModel.reloadOrStop() },
                            modifier = Modifier
                                .size(42.dp)
                                .testTag("reload_stop_button"),
                        ) {
                            Icon(
                                imageVector = if (activeTab.isLoading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                                contentDescription = if (activeTab.isLoading) "Stop loading" else "Reload page",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }

                // Loading Progress Bar
                if (activeTab.isLoading) {
                    LinearProgressIndicator(
                        progress = { (activeTab.progress / 100f).coerceIn(0.05f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(2.5.dp),
                        color = accentColor,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                }
            }
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                Column(modifier = Modifier.navigationBarsPadding()) {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { viewModel.goBack() },
                            enabled = !activeTab.isStartPage || activeTab.canGoBack,
                            modifier = Modifier.testTag("nav_back_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                                contentDescription = "Navigate back",
                            )
                        }

                        IconButton(
                            onClick = { viewModel.goForward() },
                            enabled = activeTab.canGoForward,
                            modifier = Modifier.testTag("nav_forward_button"),
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                                contentDescription = "Navigate forward",
                            )
                        }

                        IconButton(
                            onClick = { viewModel.goHome() },
                            modifier = Modifier.testTag("nav_home_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Home,
                                contentDescription = "Start page",
                            )
                        }

                        // Multi-Tab Counter Button
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .clickable { showTabSwitcher = true }
                                .testTag("tabs_switcher_button"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Surface(
                                shape = RoundedCornerShape(7.dp),
                                color = accentColor.copy(alpha = 0.14f),
                                border = BorderStroke(1.5.dp, accentColor),
                                modifier = Modifier.size(24.dp),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = uiState.tabs.size.toString(),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = accentColor,
                                    )
                                }
                            }
                        }

                        // Shred Session Button
                        IconButton(
                            onClick = { viewModel.requestShredSession() },
                            modifier = Modifier.testTag("shred_session_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = "Shred session",
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }

                        // Quick Shield Controls
                        IconButton(
                            onClick = { showQuickShields = true },
                            modifier = Modifier.testTag("quick_shield_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Shield,
                                contentDescription = "Security shield controls",
                                tint = accentColor,
                            )
                        }

                        // Overflow Menu
                        Box {
                            IconButton(
                                onClick = { showOverflowMenu = true },
                                modifier = Modifier.testTag("browser_menu_button"),
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.MoreVert,
                                    contentDescription = "Browser menu",
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflowMenu,
                                onDismissRequest = { showOverflowMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Bookmarks & History") },
                                    leadingIcon = { Icon(Icons.Rounded.Bookmark, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenLibrary()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Downloads Manager") },
                                    leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenDownloads()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Security & Proxy Setup") },
                                    leadingIcon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        onOpenSecuritySettings()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text("Legal & Security Notice") },
                                    leadingIcon = { Icon(Icons.Rounded.Gavel, contentDescription = null) },
                                    onClick = {
                                        showOverflowMenu = false
                                        viewModel.openDisclaimer()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            if (activeTab.isStartPage) {
                StartPageContent(
                    tab = activeTab,
                    config = uiState.securityConfig,
                    torProxyState = uiState.torProxyState,
                    totalTrackersBlocked = uiState.totalTrackersBlocked,
                    totalScriptsBlocked = uiState.totalScriptsBlocked,
                    onNavigateUrl = { url -> viewModel.openUrlOrSearch(url) },
                    onToggleNoScript = viewModel::setNoScript,
                    onToggleHttpsEverywhere = viewModel::setHttpsEverywhere,
                    onToggleWebRtc = viewModel::setWebRtcLeakProtection,
                    onToggleFingerprint = viewModel::setFingerprintRandomization,
                    onTestTorSocket = viewModel::testTorProxyConnection,
                    onOpenSecuritySettings = onOpenSecuritySettings,
                )
            } else {
                val cfg = uiState.securityConfig
                BrowserWebView(
                    url = activeTab.url,
                    tabKey = activeTab.id,
                    javaScriptEnabled = !cfg.noScriptEnabled,
                    enforceHttps = cfg.httpsEverywhereEnabled,
                    useTorSocks5Proxy = cfg.browserMode == BrowserMode.TOR_ONION,
                    torProxyHost = cfg.torProxyHost,
                    torProxyPort = cfg.torProxyPort,
                    torProxyScheme = cfg.torProxyProtocol.scheme,
                    blockThirdPartyCookies = cfg.blockThirdPartyCookies,
                    manageProxyOverride = false,
                    externalWebViewFactory = { _ ->
                        val wv = viewModel.webViewPool.getOrCreateWebView(
                            tabId = activeTab.id,
                            seed = activeTab.fingerprintSeed,
                            config = cfg,
                        )
                        if (wv.url.isNullOrBlank() && !activeTab.isStartPage) {
                            wv.loadUrl(activeTab.url)
                        }
                        wv
                    },
                )

                // Error / Onion Proxy Recovery Overlay
                if (activeTab.lastError != null) {
                    WebErrorOverlay(
                        tab = activeTab,
                        torProxyState = uiState.torProxyState,
                        proxyEndpoint = uiState.securityConfig.torEndpoint,
                        onRetry = { viewModel.reloadOrStop() },
                        onSwitchToSurface = { viewModel.switchBrowserMode(BrowserMode.SURFACE) },
                        onOpenOrbot = { TorProxyController.launchOrbot(context) },
                        onOpenProxySettings = onOpenSecuritySettings,
                    )
                }
            }

            // Status Banner Snackbar
            AnimatedVisibility(
                visible = uiState.statusBannerMessage != null,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(12.dp),
            ) {
                uiState.statusBannerMessage?.let { message ->
                    Snackbar(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        action = {
                            TextButton(onClick = viewModel::dismissStatusBanner) {
                                Text("OK")
                            }
                        },
                    ) {
                        Text(message, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    if (showTabSwitcher) {
        TabSwitcherSheet(
            tabs = uiState.tabs,
            activeTabId = uiState.activeTabId,
            onSelectTab = viewModel::selectTab,
            onCloseTab = viewModel::closeTab,
            onNewSurfaceTab = { viewModel.createNewTab(BrowserMode.SURFACE) },
            onNewTorTab = { viewModel.createNewTab(BrowserMode.TOR_ONION) },
            onDismiss = { showTabSwitcher = false },
        )
    }

    if (showQuickShields) {
        QuickShieldSheet(
            tab = activeTab,
            config = uiState.securityConfig,
            onToggleNoScript = viewModel::setNoScript,
            onToggleFingerprint = viewModel::setFingerprintRandomization,
            onToggleHttpsEverywhere = viewModel::setHttpsEverywhere,
            onToggleWebRtc = viewModel::setWebRtcLeakProtection,
            onToggleTrackerBlocker = viewModel::setTrackerBlocker,
            onToggleThirdPartyCookies = viewModel::setBlockThirdPartyCookies,
            onRotateSeed = viewModel::regenerateFingerprintSeed,
            onOpenFullSettings = onOpenSecuritySettings,
            onDismiss = { showQuickShields = false },
        )
    }

    if (uiState.showDisclaimerDialog) {
        DisclaimerDialog(onAccept = viewModel::acceptDisclaimer)
    }

    uiState.pendingDownload?.let { pending ->
        DownloadSandboxDialog(
            request = pending,
            onConfirm = viewModel::confirmPendingDownload,
            onCancel = viewModel::cancelPendingDownload,
        )
    }

    if (uiState.showShredConfirmDialog) {
        ShredConfirmDialog(
            onConfirm = viewModel::confirmShredSession,
            onDismiss = viewModel::dismissShredConfirm,
        )
    }
}

@Composable
private fun DualModeSwitcher(
    currentMode: BrowserMode,
    onSelectMode: (BrowserMode) -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    Surface(
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
    ) {
        Row(
            modifier = Modifier.padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ModePill(
                label = "Surface",
                icon = Icons.Rounded.Language,
                selected = currentMode == BrowserMode.SURFACE,
                accent = shieldColors.surfaceAccent,
                testTag = "mode_surface_button",
                onClick = { onSelectMode(BrowserMode.SURFACE) },
            )
            ModePill(
                label = "Tor .onion",
                icon = Icons.Rounded.Security,
                selected = currentMode == BrowserMode.TOR_ONION,
                accent = shieldColors.torAccent,
                testTag = "mode_tor_button",
                onClick = { onSelectMode(BrowserMode.TOR_ONION) },
            )
        }
    }
}

@Composable
private fun ModePill(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    testTag: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = CircleShape,
        color = if (selected) accent.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent,
        border = if (selected) BorderStroke(1.dp, accent.copy(alpha = 0.65f)) else null,
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag(testTag),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = if (selected) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ProxyStatusChip(
    mode: BrowserMode,
    proxyState: TorProxyState,
    noScriptEnabled: Boolean,
    onClick: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    val (label, color) = when {
        mode == BrowserMode.TOR_ONION && proxyState is TorProxyState.Connected ->
            "TOR OK" to shieldColors.secureGreen
        mode == BrowserMode.TOR_ONION && proxyState is TorProxyState.Unreachable ->
            "NO TOR" to shieldColors.warningAmber
        mode == BrowserMode.TOR_ONION ->
            "TOR" to shieldColors.torAccent
        noScriptEnabled ->
            "NO-JS" to shieldColors.warningAmber
        else ->
            "SHIELD" to shieldColors.surfaceAccent
    }

    Surface(
        shape = CircleShape,
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
        modifier = Modifier
            .clip(CircleShape)
            .clickable(onClick = onClick)
            .testTag("proxy_status_chip"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun WebErrorOverlay(
    tab: BrowserTab,
    torProxyState: TorProxyState,
    proxyEndpoint: String,
    onRetry: () -> Unit,
    onSwitchToSurface: () -> Unit,
    onOpenOrbot: () -> Unit,
    onOpenProxySettings: () -> Unit,
) {
    val shieldColors = LocalShieldColors.current
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background.copy(alpha = 0.96f))
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 480.dp),
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WarningAmber,
                        contentDescription = "Connection diagnostic",
                        tint = shieldColors.warningAmber,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = if (tab.isOnionAddress || tab.mode == BrowserMode.TOR_ONION) {
                            "Tor / Onion Route Failed"
                        } else {
                            "Page Could Not Load"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }

                Text(
                    text = tab.lastError ?: "Unknown connection error",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (tab.mode == BrowserMode.TOR_ONION && torProxyState is TorProxyState.Unreachable) {
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = shieldColors.warningAmberContainer.copy(alpha = 0.45f),
                    ) {
                        Text(
                            text = "No local Tor daemon detected at $proxyEndpoint. Start Orbot (Tor Proxy) or point OnionShield to a running SOCKS5/HTTP Tor node.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(12.dp),
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Button(
                        onClick = onRetry,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(vertical = 10.dp),
                    ) {
                        Text("Retry")
                    }
                    if (tab.mode == BrowserMode.TOR_ONION) {
                        OutlinedButton(
                            onClick = onOpenOrbot,
                            modifier = Modifier.weight(1f),
                            contentPadding = PaddingValues(vertical = 10.dp),
                        ) {
                            Text("Launch Orbot")
                        }
                    }
                }

                if (tab.mode == BrowserMode.TOR_ONION) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        TextButton(onClick = onOpenProxySettings) {
                            Text("Proxy Config")
                        }
                        if (!tab.isOnionAddress) {
                            TextButton(
                                onClick = onSwitchToSurface,
                                colors = ButtonDefaults.textButtonColors(contentColor = shieldColors.surfaceAccent),
                            ) {
                                Text("Use Surface Mode")
                            }
                        }
                    }
                }
            }
        }
    }
}
