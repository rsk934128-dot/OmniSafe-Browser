package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.data.BrowserMode
import com.example.ui.BrowserViewModel
import com.example.ui.Routes
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.LibraryScreen
import com.example.ui.screens.SecuritySettingsScreen
import com.example.ui.theme.OnionShieldTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val browserViewModel: BrowserViewModel = viewModel()
            val uiState by browserViewModel.uiState.collectAsStateWithLifecycle()
            val isBookmarked by browserViewModel.isCurrentPageBookmarked.collectAsStateWithLifecycle()
            val bookmarks by browserViewModel.bookmarks.collectAsStateWithLifecycle()
            val history by browserViewModel.history.collectAsStateWithLifecycle()
            val downloads by browserViewModel.downloads.collectAsStateWithLifecycle()

            val navController = rememberNavController()
            val isTorMode = uiState.securityConfig.browserMode == BrowserMode.TOR_ONION

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
                            onDeleteBookmark = browserViewModel::removeBookmark,
                            onDeleteHistory = browserViewModel::deleteHistoryEntry,
                            onClearAllHistory = browserViewModel::clearAllHistory,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable(Routes.ROUTE_DOWNLOADS) {
                        DownloadsScreen(
                            downloads = downloads,
                            onDeleteDownload = browserViewModel::deleteDownloadRecord,
                            onClearDownloads = browserViewModel::clearDownloads,
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
