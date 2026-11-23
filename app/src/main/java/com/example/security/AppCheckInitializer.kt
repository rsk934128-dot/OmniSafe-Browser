package com.example.security

import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.BuildConfig
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.FirebaseAppCheck
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory
import com.google.firebase.appcheck.recaptcha.RecaptchaAppCheckProviderFactory

private const val TAG = "AppCheckInit"

private const val DEBUG_TOKEN_EXTRA = "FIREBASE_APPCHECK_DEBUG_TOKEN"
private const val DEBUG_STORE_PREFIX = "com.google.firebase.appcheck.debug.store."
private const val DEBUG_SECRET_KEY = "com.google.firebase.appcheck.debug.DEBUG_SECRET"
private const val RECAPTCHA_SITE_KEY_RES = "recaptcha_site_key"
private const val OVERRIDE_PREFS = "onionshield_appcheck_override"
private const val KEY_CUSTOM_DEBUG_TOKEN = "custom_debug_token"

data class AppCheckDiagnostics(
    val isDebugBuild: Boolean,
    val hasIntentDebugToken: Boolean,
    val hasRecaptchaSiteKey: Boolean,
    val activeDebugSecretUuid: String?,
    val providerInstalled: Boolean,
)

@Volatile
private var lastIntentHadDebugToken: Boolean = false
@Volatile
private var lastProviderInstalled: Boolean = false

fun configureAppCheck(context: Context, intent: Intent?) {
    try {
        if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
            FirebaseApp.initializeApp(context)
        }

        if (BuildConfig.DEBUG) {
            val intentToken = intent?.getStringExtra(DEBUG_TOKEN_EXTRA)?.trim()
            val savedOverrideToken = context.getSharedPreferences(OVERRIDE_PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CUSTOM_DEBUG_TOKEN, null)
                ?.trim()
            val effectiveToken = intentToken?.takeIf { it.isNotEmpty() }
                ?: savedOverrideToken?.takeIf { it.isNotEmpty() }

            if (!intentToken.isNullOrEmpty()) {
                lastIntentHadDebugToken = true
            }

            if (!effectiveToken.isNullOrEmpty()) {
                val persistenceKey = FirebaseApp.getInstance().persistenceKey
                val prefs = context.getSharedPreferences(
                    "$DEBUG_STORE_PREFIX$persistenceKey",
                    Context.MODE_PRIVATE,
                )
                // Must use commit() synchronously so DebugAppCheckProviderFactory reads the secret immediately.
                prefs.edit()
                    .putString(DEBUG_SECRET_KEY, effectiveToken)
                    .commit()
                Log.d(TAG, "App Check debug secret configured successfully.")
            } else {
                Log.w(TAG, "No debug token found in Intent extra.")
            }

            FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                DebugAppCheckProviderFactory.getInstance(),
            )
            lastProviderInstalled = true
            Log.d(TAG, "DebugAppCheckProviderFactory installed.")
        } else {
            val resId = context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
            if (resId != 0) {
                val siteKey = context.getString(resId)
                if (siteKey.isNotBlank()) {
                    FirebaseAppCheck.getInstance().installAppCheckProviderFactory(
                        RecaptchaAppCheckProviderFactory.getInstance(siteKey),
                    )
                    lastProviderInstalled = true
                    Log.d(TAG, "RecaptchaAppCheckProviderFactory installed.")
                } else {
                    Log.e(TAG, "recaptcha_site_key resource string is blank.")
                }
            } else {
                Log.e(TAG, "recaptcha_site_key string resource not found in package.")
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Failed to initialize Firebase App Check", e)
    }
}

fun readActiveDebugSecretUuid(context: Context): String? = runCatching {
    if (FirebaseApp.getApps(context).none { it.name == FirebaseApp.DEFAULT_APP_NAME }) {
        FirebaseApp.initializeApp(context)
    }
    val persistenceKey = FirebaseApp.getInstance().persistenceKey
    val prefs = context.getSharedPreferences(
        "$DEBUG_STORE_PREFIX$persistenceKey",
        Context.MODE_PRIVATE,
    )
    prefs.getString(DEBUG_SECRET_KEY, null)?.takeIf { it.isNotBlank() }
}.getOrNull()

fun saveCustomAppCheckDebugToken(context: Context, token: String) {
    val clean = token.trim()
    val overridePrefs = context.getSharedPreferences(OVERRIDE_PREFS, Context.MODE_PRIVATE)
    if (clean.isEmpty()) {
        overridePrefs.edit().remove(KEY_CUSTOM_DEBUG_TOKEN).commit()
    } else {
        overridePrefs.edit().putString(KEY_CUSTOM_DEBUG_TOKEN, clean).commit()
    }
    configureAppCheck(context, null)
}

fun getAppCheckDiagnostics(context: Context): AppCheckDiagnostics {
    val resId = context.resources.getIdentifier(RECAPTCHA_SITE_KEY_RES, "string", context.packageName)
    val hasRecaptcha = resId != 0 && runCatching { context.getString(resId).isNotBlank() }.getOrDefault(false)
    val debugUuid = if (BuildConfig.DEBUG) readActiveDebugSecretUuid(context) else null
    return AppCheckDiagnostics(
        isDebugBuild = BuildConfig.DEBUG,
        hasIntentDebugToken = lastIntentHadDebugToken,
        hasRecaptchaSiteKey = hasRecaptcha,
        activeDebugSecretUuid = debugUuid,
        providerInstalled = lastProviderInstalled,
    )
}
