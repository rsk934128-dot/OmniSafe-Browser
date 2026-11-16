package com.example.browser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.BrowserMode
import com.example.data.OnionShieldDatabase
import com.example.data.PrivacyPreferencesRepository
import com.example.data.SecurityConfig
import com.example.data.TorProxyState
import com.example.data.repository.SessionMonitorFirestoreRepository
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Process-wide live session state shared between [BrowserViewModel] and
 * [LiveMonitorForegroundService] so timer ticks, active site metadata, Tor proxy
 * health, and background service status survive Activity destruction or leaving the app.
 */
object LiveSessionRuntime {
    private val _sessionStartEpochMs = MutableStateFlow(System.currentTimeMillis())
    val sessionStartEpochMs: StateFlow<Long> = _sessionStartEpochMs.asStateFlow()

    private val _elapsedMinutes = MutableStateFlow(0L)
    val elapsedMinutes: StateFlow<Long> = _elapsedMinutes.asStateFlow()

    private val _activeSiteHost = MutableStateFlow("manual")
    val activeSiteHost: StateFlow<String> = _activeSiteHost.asStateFlow()

    private val _isForegroundServiceRunning = MutableStateFlow(false)
    val isForegroundServiceRunning: StateFlow<Boolean> = _isForegroundServiceRunning.asStateFlow()

    private val _foregroundSyncStatus = MutableStateFlow<SessionMonitorSyncStatus>(SessionMonitorSyncStatus.Disabled)
    val foregroundSyncStatus: StateFlow<SessionMonitorSyncStatus> = _foregroundSyncStatus.asStateFlow()

    private val _liveTorProxyState = MutableStateFlow<TorProxyState>(TorProxyState.Inactive)
    val liveTorProxyState: StateFlow<TorProxyState> = _liveTorProxyState.asStateFlow()

    fun refreshElapsed(nowEpochMs: Long = System.currentTimeMillis()): Long {
        val mins = ((nowEpochMs - _sessionStartEpochMs.value) / 60_000L).coerceAtLeast(0L)
        _elapsedMinutes.value = mins
        return mins
    }

    fun resetTimer(nowEpochMs: Long = System.currentTimeMillis()) {
        _sessionStartEpochMs.value = nowEpochMs
        _elapsedMinutes.value = 0L
    }

    fun updateActiveSiteHost(rawUrlOrHost: String) {
        val extracted = if (rawUrlOrHost.contains("://")) {
            TrackerAndUrlEngine.normalizeHost(rawUrlOrHost) ?: "manual"
        } else {
            rawUrlOrHost
        }
        _activeSiteHost.value = SessionMonitorFirestoreRepository.sanitizeSiteHost(extracted)
    }

    internal fun setForegroundRunning(running: Boolean) {
        _isForegroundServiceRunning.value = running
        if (!running) {
            _foregroundSyncStatus.value = SessionMonitorSyncStatus.Disabled
            _liveTorProxyState.value = TorProxyState.Inactive
        }
    }

    internal fun updateForegroundSyncStatus(status: SessionMonitorSyncStatus) {
        _foregroundSyncStatus.value = status
    }

    internal fun updateTorProxyState(state: TorProxyState) {
        _liveTorProxyState.value = state
    }
}

/**
 * Play-policy-compliant `dataSync` Foreground Service that keeps the OnionShield
 * browser session alive, maintains & monitors Tor SOCKS5/HTTP proxy & ControlPort
 * connectivity, updates the Session Guard timer, and streams optional Cloud Monitor sync
 * even after the user navigates away from the app.
 */
class LiveMonitorForegroundService : Service() {

    private var serviceScope: CoroutineScope? = null
    private var monitoringJob: Job? = null
    private var tickerJob: Job? = null
    private var torKeepAliveJob: Job? = null
    private var syncStatusForwardJob: Job? = null
    private var syncService: SessionMonitorSyncService? = null

    private var latestStats: SessionGuardStats = SessionGuardStats()
    private var latestConfig: SecurityConfig = SecurityConfig()
    private var latestSyncStatus: SessionMonitorSyncStatus = SessionMonitorSyncStatus.Disabled
    private var latestTorState: TorProxyState = TorProxyState.Inactive

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAndClearPreference()
                return START_NOT_STICKY
            }
            ACTION_NEW_CIRCUIT -> {
                promoteToForeground(buildNotification(latestStats, latestConfig, latestSyncStatus, latestTorState))
                LiveSessionRuntime.setForegroundRunning(true)
                startMonitoringLoopsIfNeeded()
                triggerNewTorCircuit()
                return START_STICKY
            }
            else -> {
                // Immediately promote to foreground within Android 14+ startup deadline
                promoteToForeground(buildNotification(latestStats, latestConfig, latestSyncStatus, latestTorState))
                LiveSessionRuntime.setForegroundRunning(true)
                startMonitoringLoopsIfNeeded()
                return START_STICKY
            }
        }
    }

    private fun startMonitoringLoopsIfNeeded() {
        val scope = serviceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
            serviceScope = it
        }

        if (tickerJob == null) {
            tickerJob = scope.launch {
                while (isActive) {
                    LiveSessionRuntime.refreshElapsed()
                    delay(15_000L)
                }
            }
        }

        if (torKeepAliveJob == null) {
            torKeepAliveJob = scope.launch {
                val prefsRepo = PrivacyPreferencesRepository(applicationContext)
                prefsRepo.securityConfig.collectLatest { cfg ->
                    latestConfig = cfg
                    if (cfg.browserMode == BrowserMode.TOR_ONION) {
                        while (isActive) {
                            val nextTorState = checkTorConnectivityOnce(cfg)
                            if (nextTorState != latestTorState) {
                                latestTorState = nextTorState
                                LiveSessionRuntime.updateTorProxyState(nextTorState)
                            } else {
                                LiveSessionRuntime.updateTorProxyState(nextTorState)
                            }
                            updateNotification(
                                buildNotification(latestStats, cfg, latestSyncStatus, latestTorState),
                            )
                            delay(cfg.circuitPrivacyLevel.livePollIntervalMs)
                        }
                    } else {
                        latestTorState = TorProxyState.Inactive
                        LiveSessionRuntime.updateTorProxyState(TorProxyState.Inactive)
                        updateNotification(
                            buildNotification(latestStats, cfg, latestSyncStatus, latestTorState),
                        )
                    }
                }
            }
        }

        if (monitoringJob != null) return

        val prefsRepo = PrivacyPreferencesRepository(applicationContext)
        val db = OnionShieldDatabase.getInstance(applicationContext)
        val roundDao = db.sessionRoundLogDao()

        val activeSyncService = runCatching {
            SessionMonitorSyncService(
                repository = SessionMonitorFirestoreRepository(applicationContext),
                auth = Firebase.auth,
                scope = scope,
            )
        }.getOrNull().also { syncService = it }

        val statsFlow = combine(
            roundDao.observeAll(),
            prefsRepo.securityConfig,
            LiveSessionRuntime.elapsedMinutes,
        ) { rounds, cfg, elapsed ->
            cfg to SessionGuardEngine.computeSessionStats(
                rounds = rounds,
                guardEnabled = cfg.sessionGuardEnabled,
                elapsedMinutes = elapsed,
                timeLimitMinutes = cfg.sessionTimeLimitMinutes,
                budgetLimit = cfg.sessionBudgetLimit,
            )
        }

        activeSyncService?.startObserving(
            siteHostFlow = LiveSessionRuntime.activeSiteHost,
            configFlow = prefsRepo.securityConfig,
            statsFlow = combine(
                roundDao.observeAll(),
                prefsRepo.securityConfig,
                LiveSessionRuntime.elapsedMinutes,
            ) { rounds, cfg, elapsed ->
                SessionGuardEngine.computeSessionStats(
                    rounds = rounds,
                    guardEnabled = cfg.sessionGuardEnabled,
                    elapsedMinutes = elapsed,
                    timeLimitMinutes = cfg.sessionTimeLimitMinutes,
                    budgetLimit = cfg.sessionBudgetLimit,
                )
            },
            roundsFlow = roundDao.observeAll(),
        )

        if (activeSyncService != null && syncStatusForwardJob == null) {
            syncStatusForwardJob = scope.launch {
                activeSyncService.syncStatus.collectLatest { status ->
                    latestSyncStatus = status
                    LiveSessionRuntime.updateForegroundSyncStatus(status)
                    updateNotification(buildNotification(latestStats, latestConfig, status, latestTorState))
                }
            }
        }

        monitoringJob = scope.launch {
            statsFlow.collectLatest { (cfg, stats) ->
                latestConfig = cfg
                latestStats = stats
                updateNotification(buildNotification(stats, cfg, latestSyncStatus, latestTorState))
            }
        }
    }

    private suspend fun checkTorConnectivityOnce(cfg: SecurityConfig): TorProxyState {
        val (handshakeOk, latencyMs) = TorCircuitService.probeProxyHandshake(
            host = cfg.torProxyHost,
            port = cfg.torProxyPort,
            protocol = cfg.torProxyProtocol,
        )
        return if (handshakeOk && latencyMs != null) {
            TorProxyState.Connected(endpoint = cfg.torEndpoint, latencyMs = latencyMs)
        } else {
            TorProxyController.checkProxyReachability(
                host = cfg.torProxyHost,
                port = cfg.torProxyPort,
                protocol = cfg.torProxyProtocol,
            )
        }
    }

    private fun triggerNewTorCircuit() {
        val scope = serviceScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO).also {
            serviceScope = it
        }
        scope.launch {
            val cfg = latestConfig
            TorCircuitService.requestNewCircuit(
                host = cfg.torProxyHost,
                controlPort = cfg.torControlPort,
                password = cfg.torControlPassword,
            )
            val refreshed = checkTorConnectivityOnce(cfg)
            latestTorState = refreshed
            LiveSessionRuntime.updateTorProxyState(refreshed)
            updateNotification(buildNotification(latestStats, cfg, latestSyncStatus, refreshed))
        }
    }

    private fun stopAndClearPreference() {
        LiveSessionRuntime.setForegroundRunning(false)
        tickerJob?.cancel()
        tickerJob = null
        torKeepAliveJob?.cancel()
        torKeepAliveJob = null
        monitoringJob?.cancel()
        monitoringJob = null
        syncStatusForwardJob?.cancel()
        syncStatusForwardJob = null
        syncService?.stop()
        syncService = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)

        val appContext = applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                PrivacyPreferencesRepository(appContext).setBackgroundLiveService(false)
            }
        }
        stopSelf()
    }

    override fun onDestroy() {
        LiveSessionRuntime.setForegroundRunning(false)
        tickerJob?.cancel()
        torKeepAliveJob?.cancel()
        monitoringJob?.cancel()
        syncStatusForwardJob?.cancel()
        syncService?.stop()
        serviceScope?.cancel()
        serviceScope = null
        super.onDestroy()
    }

    private fun promoteToForeground(notification: Notification) {
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceType)
    }

    private fun updateNotification(notification: Notification) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.live_monitor_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = getString(R.string.live_monitor_channel_desc)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    internal fun buildNotification(
        stats: SessionGuardStats,
        config: SecurityConfig,
        syncStatus: SessionMonitorSyncStatus,
        torState: TorProxyState = TorProxyState.Inactive,
    ): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPendingIntent = PendingIntent.getActivity(
            this,
            REQUEST_CODE_OPEN,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val stopIntent = Intent(this, LiveMonitorForegroundService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            REQUEST_CODE_STOP,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val isTorMode = config.browserMode == BrowserMode.TOR_ONION
        val torSummary = if (isTorMode) {
            when (torState) {
                is TorProxyState.Connected -> "Tor Connected (${torState.latencyMs}ms)"
                is TorProxyState.Unreachable -> "Tor Unreachable"
                is TorProxyState.Checking -> "Tor Checking..."
                is TorProxyState.UnsupportedApi -> "Proxy Unsupported"
                TorProxyState.Inactive -> "Tor Standby"
            }
        } else {
            "Surface Shield Active"
        }

        val cloudLabel = when {
            !config.liveCloudMonitorSyncEnabled -> "Local"
            syncStatus is SessionMonitorSyncStatus.SyncingLive -> "Cloud Live"
            syncStatus is SessionMonitorSyncStatus.SignInRequired -> "Sign-In"
            syncStatus is SessionMonitorSyncStatus.Error -> "Cached"
            else -> "Sync Ready"
        }

        val summaryLine = buildString {
            append(torSummary)
            append(" • ${stats.elapsedMinutes}m active")
            if (stats.totalRounds > 0) {
                append(" • ${stats.totalRounds} rnds")
            }
            append(" • $cloudLabel")
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_shield_notify)
            .setContentTitle(
                getString(
                    if (isTorMode) R.string.live_monitor_notif_tor_title else R.string.live_monitor_notif_title,
                ),
            )
            .setContentText(summaryLine)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryLine))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openPendingIntent)
            .addAction(0, getString(R.string.live_monitor_stop_action), stopPendingIntent)

        if (isTorMode) {
            val newCircuitIntent = Intent(this, LiveMonitorForegroundService::class.java)
                .setAction(ACTION_NEW_CIRCUIT)
            val newCircuitPendingIntent = PendingIntent.getService(
                this,
                REQUEST_CODE_NEW_CIRCUIT,
                newCircuitIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(0, getString(R.string.live_monitor_new_circuit_action), newCircuitPendingIntent)
        }

        return builder.build()
    }

    companion object {
        const val CHANNEL_ID = "onion_shield_live_monitor"
        const val NOTIFICATION_ID = 4109
        const val ACTION_START = "com.example.action.START_LIVE_MONITOR"
        const val ACTION_STOP = "com.example.action.STOP_LIVE_MONITOR"
        const val ACTION_NEW_CIRCUIT = "com.example.action.NEW_TOR_CIRCUIT"
        private const val REQUEST_CODE_OPEN = 100
        private const val REQUEST_CODE_STOP = 101
        private const val REQUEST_CODE_NEW_CIRCUIT = 102

        fun start(context: Context) {
            val intent = Intent(context, LiveMonitorForegroundService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveMonitorForegroundService::class.java))
        }
    }
}
