package com.example.ui.components

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.VerifiedUser
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.R
import com.example.security.UserGeminiKeyStore
import com.example.security.getAppCheckDiagnostics
import com.example.security.saveCustomAppCheckDebugToken
import com.example.ui.theme.LocalShieldColors

@Composable
fun AiConnectionSetupDialog(
    onDismiss: () -> Unit,
    onSavedOrUpdated: () -> Unit = {},
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val shieldColors = LocalShieldColors.current

    val hasPersonalKey by UserGeminiKeyStore.hasPersonalKeyFlow.collectAsStateWithLifecycle()
    val preferPersonalKey by UserGeminiKeyStore.preferPersonalKeyFlow.collectAsStateWithLifecycle()
    var diagnostics by remember { mutableStateOf(getAppCheckDiagnostics(context)) }
    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var debugTokenInput by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .testTag("ai_connection_setup_dialog"),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, shieldColors.surfaceAccent.copy(alpha = 0.55f)),
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Key,
                            contentDescription = null,
                            tint = shieldColors.surfaceAccent,
                            modifier = Modifier.size(22.dp),
                        )
                        Text(
                            text = stringResource(R.string.ai_setup_title),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(40.dp)
                            .testTag("ai_setup_close_button"),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(R.string.cd_close_ai_setup),
                        )
                    }
                }

                // Active connection status banner
                val activeOk = hasPersonalKey || diagnostics.hasIntentDebugToken || diagnostics.hasRecaptchaSiteKey
                val statusTint = if (activeOk) shieldColors.secureGreen else shieldColors.warningAmber
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = statusTint.copy(alpha = 0.13f),
                    border = BorderStroke(1.dp, statusTint.copy(alpha = 0.45f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(
                            imageVector = if (activeOk) Icons.Rounded.CheckCircle else Icons.Rounded.WarningAmber,
                            contentDescription = null,
                            tint = statusTint,
                            modifier = Modifier.size(20.dp),
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                text = when {
                                    hasPersonalKey -> "Personal Gemini API Key active (AES-256 encrypted)"
                                    diagnostics.hasIntentDebugToken -> "AI Studio App Check debug token active"
                                    diagnostics.hasRecaptchaSiteKey -> "Release App Check (reCAPTCHA) configured"
                                    else -> "Installed APK needs a Personal Gemini Key"
                                },
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = stringResource(R.string.ai_setup_explanation),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Section 1: Personal Gemini API Key
                Text(
                    text = "Personal Gemini API Key (Works on any phone)",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Get a free key at aistudio.google.com/apikey",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")),
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Spacer(Modifier.width(4.dp))
                        Text("Get Key", style = MaterialTheme.typography.labelMedium)
                    }
                }

                OutlinedTextField(
                    value = apiKeyInput,
                    onValueChange = { apiKeyInput = it },
                    label = {
                        Text(
                            if (hasPersonalKey) {
                                "Replace saved Gemini API key (AIza…)"
                            } else {
                                "Paste Gemini API key (AIza…)"
                            },
                        )
                    },
                    singleLine = true,
                    visualTransformation = if (showApiKey) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                imageVector = if (showApiKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (showApiKey) "Hide key" else "Show key",
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("ai_setup_key_input"),
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Button(
                        onClick = {
                            val ok = UserGeminiKeyStore.saveApiKey(context, apiKeyInput, preferPersonal = true)
                            if (ok) {
                                apiKeyInput = ""
                                statusMessage = "Personal Gemini API Key saved securely on device."
                                onSavedOrUpdated()
                            } else {
                                statusMessage = "Enter a valid non-empty Gemini API key."
                            }
                        },
                        enabled = apiKeyInput.isNotBlank(),
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .testTag("ai_setup_save_key_button"),
                    ) {
                        Text("Save & Enable AI", maxLines = 1)
                    }

                    if (hasPersonalKey) {
                        OutlinedButton(
                            onClick = {
                                UserGeminiKeyStore.clearApiKey(context)
                                statusMessage = "Personal API Key removed; reverted to Firebase App Check."
                                onSavedOrUpdated()
                            },
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("ai_setup_clear_key_button"),
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteOutline,
                                contentDescription = "Remove saved API key",
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Clear", maxLines = 1)
                        }
                    }
                }

                if (hasPersonalKey) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Use Personal Key First",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = "Skips Firebase App Check delay on standalone APKs.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = preferPersonalKey,
                            onCheckedChange = {
                                UserGeminiKeyStore.setPersonalKeyPreferred(context, it)
                                onSavedOrUpdated()
                            },
                            modifier = Modifier.testTag("ai_setup_prefer_personal_switch"),
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))

                // Section 2: Firebase App Check Diagnostics
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.VerifiedUser,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = "Firebase App Check Diagnostics",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                }

                Text(
                    text = "Build: ${if (diagnostics.isDebugBuild) "DEBUG" else "RELEASE"} • " +
                        "Provider: ${if (diagnostics.providerInstalled) "Installed" else "Not configured"} • " +
                        "reCAPTCHA key: ${if (diagnostics.hasRecaptchaSiteKey) "Present" else "Absent"}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (diagnostics.isDebugBuild) {
                    val debugUuid = diagnostics.activeDebugSecretUuid
                    if (!debugUuid.isNullOrBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Active App Check Debug Token UUID",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(
                                        text = debugUuid,
                                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                        maxLines = 1,
                                    )
                                }
                                IconButton(
                                    onClick = {
                                        clipboard.setText(AnnotatedString(debugUuid))
                                        statusMessage = "Copied Debug Token UUID to clipboard."
                                    },
                                    modifier = Modifier.size(40.dp),
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.ContentCopy,
                                        contentDescription = "Copy Debug Token UUID",
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = debugTokenInput,
                            onValueChange = { debugTokenInput = it },
                            label = { Text("Custom Firebase Debug Token (optional)") },
                            singleLine = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("ai_setup_debug_token_input"),
                        )
                        OutlinedButton(
                            onClick = {
                                saveCustomAppCheckDebugToken(context, debugTokenInput)
                                diagnostics = getAppCheckDiagnostics(context)
                                statusMessage = if (debugTokenInput.isBlank()) {
                                    "Cleared custom App Check debug token."
                                } else {
                                    "Applied custom App Check debug token."
                                }
                                debugTokenInput = ""
                                onSavedOrUpdated()
                            },
                            modifier = Modifier.height(48.dp),
                        ) {
                            Text("Apply")
                        }
                    }
                }

                if (!statusMessage.isNullOrBlank()) {
                    Text(
                        text = statusMessage!!,
                        style = MaterialTheme.typography.bodySmall,
                        color = shieldColors.secureGreen,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
