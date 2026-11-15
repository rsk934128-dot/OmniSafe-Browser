package com.example.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CodeOff
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Https
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Router
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.browser.TorProxyController
import com.example.data.ProxyProtocol
import com.example.data.SearchEngine
import com.example.data.TorProxyState
import com.example.ui.BrowserUiState
import com.example.ui.BrowserViewModel
import com.example.ui.theme.LocalShieldColors

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SecuritySettingsScreen(
    uiState: BrowserUiState,
    viewModel: BrowserViewModel,
    onNavigateBack: () -> Unit,
) {
    val context = LocalContext.current
    val shieldColors = LocalShieldColors.current
    val config = uiState.securityConfig

    var hostInput by remember(config.torProxyHost) { mutableStateOf(config.torProxyHost) }
    var portInput by remember(config.torProxyPort) { mutableStateOf(config.torProxyPort.toString()) }
    var selectedProtocol by remember(config.torProxyProtocol) { mutableStateOf(config.torProxyProtocol) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("Security & Tor Proxy") },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.testTag("settings_back_button"),
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Tor Proxy Network Layer Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, shieldColors.torAccent.copy(alpha = 0.45f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Router, contentDescription = null, tint = shieldColors.torAccent)
                            Column {
                                Text("Tor / Onion Proxy Routing", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    text = "Routes WebView traffic via ProxyController when Tor mode is active",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProxyProtocol.entries.forEach { protocol ->
                                FilterChip(
                                    selected = selectedProtocol == protocol,
                                    onClick = {
                                        selectedProtocol = protocol
                                        portInput = protocol.defaultPort.toString()
                                    },
                                    label = { Text(protocol.label) },
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = hostInput,
                                onValueChange = { hostInput = it },
                                label = { Text("Proxy Host") },
                                singleLine = true,
                                modifier = Modifier.weight(2f),
                            )
                            OutlinedTextField(
                                value = portInput,
                                onValueChange = { portInput = it.filter(Char::isDigit) },
                                label = { Text("Port") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f),
                            )
                        }

                        val statusText = when (val st = uiState.torProxyState) {
                            is TorProxyState.Connected -> "Connected to ${st.endpoint} (${st.latencyMs} ms)"
                            is TorProxyState.Checking -> "Checking socket reachability…"
                            is TorProxyState.Unreachable -> "Unreachable at ${st.endpoint}: ${st.reason}"
                            is TorProxyState.UnsupportedApi -> st.reason
                            TorProxyState.Inactive -> "Standby (Surface Web Mode active)"
                        }
                        Text(
                            text = statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = when (uiState.torProxyState) {
                                is TorProxyState.Connected -> shieldColors.secureGreen
                                is TorProxyState.Unreachable -> shieldColors.warningAmber
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    val port = portInput.toIntOrNull() ?: selectedProtocol.defaultPort
                                    viewModel.updateTorProxyConfig(hostInput, port, selectedProtocol)
                                    viewModel.testTorProxyConnection()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("save_test_proxy_button"),
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Save & Test")
                            }
                            OutlinedButton(
                                onClick = { TorProxyController.launchOrbot(context) },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("launch_orbot_button"),
                            ) {
                                Icon(Icons.Rounded.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(if (TorProxyController.isOrbotInstalled(context)) "Open Orbot" else "Get Orbot")
                            }
                        }
                    }
                }
            }

            // Hardening Layers Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("Security & Anti-Tracking Layers", style = MaterialTheme.typography.titleMedium)

                        SettingsSwitchItem(
                            title = "No-Script Mode",
                            subtitle = "Block all JavaScript execution and external .js resources",
                            icon = Icons.Rounded.CodeOff,
                            checked = config.noScriptEnabled,
                            onCheckedChange = viewModel::setNoScript,
                        )
                        SettingsSwitchItem(
                            title = "WebRTC Leak Protection",
                            subtitle = "Disable RTCPeerConnection and media device enumeration",
                            icon = Icons.Rounded.VisibilityOff,
                            checked = config.webRtcLeakProtectionEnabled,
                            onCheckedChange = viewModel::setWebRtcLeakProtection,
                        )
                        SettingsSwitchItem(
                            title = "HTTPS Everywhere",
                            subtitle = "Upgrade clearnet HTTP to HTTPS and block mixed content",
                            icon = Icons.Rounded.Https,
                            checked = config.httpsEverywhereEnabled,
                            onCheckedChange = viewModel::setHttpsEverywhere,
                        )
                        SettingsSwitchItem(
                            title = "Fingerprint Randomization",
                            subtitle = "Inject canvas/WebGL noise and rotate User-Agent (Seed #${uiState.activeTab.fingerprintSeed})",
                            icon = Icons.Rounded.Fingerprint,
                            checked = config.fingerprintRandomizationEnabled,
                            onCheckedChange = viewModel::setFingerprintRandomization,
                        )
                        SettingsSwitchItem(
                            title = "Tracker & Ad Blocker",
                            subtitle = "Block known telemetry and analytics hosts before network dispatch",
                            icon = Icons.Rounded.Block,
                            checked = config.trackerBlockerEnabled,
                            onCheckedChange = viewModel::setTrackerBlocker,
                        )
                        SettingsSwitchItem(
                            title = "Block Third-Party Cookies",
                            subtitle = "Prevent cross-site cookie tracking",
                            icon = Icons.Rounded.Lock,
                            checked = config.blockThirdPartyCookies,
                            onCheckedChange = viewModel::setBlockThirdPartyCookies,
                        )
                        SettingsSwitchItem(
                            title = "Dark WebView Content",
                            subtitle = "Apply algorithmic darkening to rendered web pages",
                            icon = Icons.Rounded.DarkMode,
                            checked = config.forceDarkWebTheme,
                            onCheckedChange = viewModel::setForceDarkTheme,
                        )
                        SettingsSwitchItem(
                            title = "Save Surface Web History",
                            subtitle = "Off by default. .onion visits are never stored on disk.",
                            icon = Icons.Rounded.History,
                            checked = config.saveSurfaceHistory,
                            onCheckedChange = viewModel::setSaveSurfaceHistory,
                        )
                    }
                }
            }

            // Search Engine Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp),
                    shape = MaterialTheme.shapes.large,
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(Icons.Rounded.Search, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Text("Default Search Engine", style = MaterialTheme.typography.titleMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SearchEngine.entries.forEach { engine ->
                                FilterChip(
                                    selected = config.searchEngine == engine,
                                    onClick = { viewModel.setSearchEngine(engine) },
                                    label = { Text(engine.displayName) },
                                )
                            }
                        }
                    }
                }
            }

            // Legal Notice Card
            item {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 640.dp)
                        .clickable { viewModel.openDisclaimer() },
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(Icons.Rounded.Gavel, contentDescription = null, tint = shieldColors.warningAmber)
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Legal & Security Disclaimer", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = "Review operational security rules and Tor usage guidelines",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsSwitchItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            tint = if (checked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
