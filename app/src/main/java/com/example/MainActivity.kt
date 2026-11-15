package com.example

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.browser.HomeAppShortcutHelper
import com.example.browser.TrackerAndUrlEngine
import com.example.data.BrowserMode
import com.example.ui.BrowserViewModel
import com.example.ui.Routes
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SecuritySettingsScreen
import com.example.ui.screens.VaultScreen
import com.example.ui.theme.OnionShieldTheme
import kotlinx.coroutines.flow.MutableStateFlow

private data class ExternalLinkIntent(
    val url: String,
    val title: String = "",
    val isSharedAction: Boolean = false,
)

class MainActivity : FragmentActivity() {

    private val pendingExternalIntent = MutableStateFlow<ExternalLinkIntent?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingExternalIntent.value = extractExternalIntent(intent)

        setContent {
            val browserViewModel: BrowserViewModel = viewModel()
            val uiState by browserViewModel.uiState.collectAsStateWithLifecycle()
            val isBookmarked by browserViewModel.isCurrentPageBookmarked.collectAsStateWithLifecycle()
            val bookmarks by browserViewModel.bookmarks.collectAsStateWithLifecycle()
            val history by browserViewModel.history.collectAsStateWithLifecycle()
            val downloads by browserViewModel.downloads.collectAsStateWithLifecycle()
            val externalIntent by pendingExternalIntent.collectAsStateWithLifecycle()

            val navController = rememberNavController()
            val isTorMode = uiState.securityConfig.browserMode == BrowserMode.TOR_ONION

            LaunchedEffect(externalIntent) {
                val target = externalIntent
                if (target != null) {
                    if (target.isSharedAction) {
                        browserViewModel.showLinkActionSheet(
                            url = target.url,
                            title = target.title,
                            fromExternalShare = true,
                        )
                    } else {
                        browserViewModel.openExternalLaunchUrl(target.url)
                    }
                    pendingExternalIntent.value = null
                }
            }

            OnionShieldTheme(
                isTorMode = isTorMode,
                darkTheme = true,
            ) {
                NavHost(
                    navController = navController,
                    startDestination = Routes.ROUTE_BROWSER,
                ) {
                    composable(Routes.ROUTE_BROWSER) {
                        BrowserScreen(
                            uiState = uiState,
                            isBookmarked = isBookmarked,
                            viewModel = browserViewModel,
                            onOpenSecuritySettings = {
                                navController.navigate(Routes.ROUTE_SHIELD_SETTINGS)
                            },
                            onOpenLibrary = {
                                navController.navigate(Routes.ROUTE_LIBRARY)
                            },
                            onOpenDownloads = {
                                navController.navigate(Routes.ROUTE_DOWNLOADS)
                            },
                            onOpenVault = {
                                navController.navigate(Routes.ROUTE_VAULT)
                            },
                        )
                    }

                    composable(Routes.ROUTE_SHIELD_SETTINGS) {
                        SecuritySettingsScreen(
                            uiState = uiState,
                            viewModel = browserViewModel,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_LIBRARY) {
                        LibraryScreen(
                            bookmarks = bookmarks,
                            history = history,
                            onOpenUrl = { url -> browserViewModel.openUrlOrSearch(url) },
                            onInstallToHomeScreen = { title, url ->
                                browserViewModel.installLinkToHomeAndMenu(this@MainActivity, title, url)
                            },
                            onDeleteBookmark = browserViewModel::removeBookmark,
                            onDeleteHistory = browserViewModel::deleteHistoryEntry,
                            onClearAllHistory = browserViewModel::clearAllHistory,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_DOWNLOADS) {
                        LaunchedEffect(Unit) {
                            browserViewModel.refreshDownloadStatuses()
                        }
                        DownloadsScreen(
                            downloads = downloads,
                            pendingDownload = uiState.pendingDownload,
                            onConfirmPendingDownload = browserViewModel::confirmPendingDownload,
                            onCancelPendingDownload = browserViewModel::cancelPendingDownload,
                            onRefreshDownloads = browserViewModel::refreshDownloadStatuses,
                            onOpenOrInstallDownload = browserViewModel::openOrInstallDownloadedFile,
                            onOpenSystemDownloads = browserViewModel::openSystemDownloadsFolder,
                            onRequestDirectDownload = browserViewModel::requestDirectDownload,
                            onDeleteDownload = browserViewModel::deleteDownloadRecord,
                            onClearDownloads = browserViewModel::clearDownloads,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_VAULT) {
                        VaultScreen(
                            uiState = uiState,
                            onUnlockWithBiometricKey = browserViewModel::unlockVaultAfterBiometricAuth,
                            onSaveBiometricWrappedKeyAndUnlock = browserViewModel::saveBiometricWrappedKeyAndUnlock,
                            onLockVault = browserViewModel::lockVault,
                            onAddEntry = browserViewModel::addVaultEntry,
                            onDeleteEntry = browserViewModel::deleteVaultEntry,
                            onWipeVault = browserViewModel::wipeEntireVault,
                            onOpenUrl = { url -> browserViewModel.openUrlOrSearch(url) },
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val extracted = extractExternalIntent(intent)
        if (extracted != null) {
            pendingExternalIntent.value = extracted
        }
    }

    private fun extractExternalIntent(intent: Intent?): ExternalLinkIntent? {
        if (intent == null) return null
        if (intent.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            val sharedUrl = TrackerAndUrlEngine.extractFirstHttpUrl(sharedText) ?: return null
            val sharedTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
            return ExternalLinkIntent(
                url = sharedUrl,
                title = sharedTitle,
                isSharedAction = true,
            )
        }
        val candidate = intent.getStringExtra(HomeAppShortcutHelper.EXTRA_PINNED_APP_URL)
            ?: intent.dataString
            ?: return null
        return candidate.takeIf {
            it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true)
        }?.let { ExternalLinkIntent(url = it, isSharedAction = false) }
    }
}
