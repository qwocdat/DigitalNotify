package com.digitalsmod.digitalnotify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentHashMap

class NotificationAccessService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingBursts = ConcurrentHashMap<String, Runnable>()

    override fun onCreate() {
        super.onCreate()
        OverlayManager.get(this)
        startSilentKeepAliveForeground()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
        startSilentKeepAliveForeground()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        isConnected = false
        try {
            NotificationListenerService.requestRebind(
                ComponentName(this, NotificationAccessService::class.java)
            )
        } catch (_: Exception) {
        }
    }

    private fun startSilentKeepAliveForeground() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val channelId = KEEP_ALIVE_CHANNEL_ID
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    channelId,
                    "DigitalNotify Background Service",
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                    enableLights(false)
                    enableVibration(false)
                    setSound(null, null)
                }
                nm.createNotificationChannel(channel)
            }
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
            } else {
                @Suppress("DEPRECATION")
                Notification.Builder(this)
            }
            val notif = builder
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("DigitalNotify đang lắng nghe thông báo")
                .setContentText("Giữ kết nối ổn định cho hiệu ứng Liquid Glass")
                .setOngoing(true)
                .build()

            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    KEEP_ALIVE_NOTIFICATION_ID,
                    notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(KEEP_ALIVE_NOTIFICATION_ID, notif)
            }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        isConnected = true

        val notification = sbn.notification ?: return
        if (!AppPrefs.isMasterEnabled(this)) return
        if (!Settings.canDrawOverlays(this)) return

        // Skip our own silent keep-alive notification, but allow test notifications
        if (sbn.id == KEEP_ALIVE_NOTIFICATION_ID && sbn.packageName == packageName) return
        val isSelfTest = sbn.packageName == packageName &&
            notification.extras?.getBoolean(EXTRA_IS_TEST_NOTIFICATION, false) == true
        if (sbn.packageName == packageName && !isSelfTest) return

        // Check if this package is allowed (Selected apps, or Track All Apps)
        if (!isSelfTest && !AppPrefs.isPackageAllowed(this, sbn.packageName)) {
            return
        }

        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        val ignoreOngoing = prefs.getBoolean(AppPrefs.KEY_IGNORE_ONGOING, true)
        if (ignoreOngoing && !isSelfTest) {
            val isOngoingFlag = sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0
            val isServiceOrMedia = notification.category == Notification.CATEGORY_SERVICE ||
                notification.category == Notification.CATEGORY_TRANSPORT ||
                notification.category == Notification.CATEGORY_PROGRESS
            if (isOngoingFlag && isServiceOrMedia) {
                return
            }
        }

        val extras = notification.extras ?: Bundle.EMPTY
        val (rawTitle, rawBody) = extractTitleAndBody(notification, extras)

        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary && rawTitle.isBlank() && rawBody.isBlank()) {
            return
        }

        // Check muted keywords
        val mutedRaw = prefs.getString(AppPrefs.KEY_MUTED_KEYWORDS, "").orEmpty()
        if (mutedRaw.isNotBlank() && !isSelfTest) {
            val keywords = mutedRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val combined = "$rawTitle $rawBody"
            if (keywords.any { kw -> combined.contains(kw, ignoreCase = true) }) {
                return
            }
        }

        val appLabel = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            sbn.packageName.substringAfterLast('.')
        }

        val largeIconDrawable = try {
            notification.getLargeIcon()?.loadDrawable(this)
        } catch (_: Exception) {
            null
        }

        val appBadgeDrawable = try {
            packageManager.getApplicationIcon(sbn.packageName)
        } catch (_: Exception) {
            null
        }

        val safeTitle = rawTitle.ifBlank { appLabel }
        val safeBody = rawBody.ifBlank {
            notification.tickerText?.toString()?.trim().orEmpty().ifBlank { "Thông báo mới" }
        }

        val data = BannerData(
            packageName = sbn.packageName,
            appName = appLabel,
            title = safeTitle,
            body = safeBody,
            postTime = System.currentTimeMillis(),
            contentIntent = notification.contentIntent,
            largeIcon = largeIconDrawable,
            appBadgeIcon = appBadgeDrawable,
            timeLabel = "now"
        )

        // Coalesce burst notifications: Android apps often post a Group Summary + Child notification
        // 10-50ms apart. If a child notification (!isGroupSummary) arrives, dispatch immediately and
        // cancel any pending summary; if a summary arrives first, wait 75ms in case the child follows.
        val pkg = sbn.packageName
        if (!isGroupSummary) {
            pendingBursts.remove(pkg)?.let { mainHandler.removeCallbacks(it) }
            dispatchBanner(data)
        } else {
            if (!pendingBursts.containsKey(pkg)) {
                val task = Runnable {
                    pendingBursts.remove(pkg)
                    dispatchBanner(data)
                }
                pendingBursts[pkg] = task
                mainHandler.postDelayed(task, 75L)
            }
        }
    }

    private fun dispatchBanner(data: BannerData) {
        NotificationHistoryStore.record(data)
        mainHandler.post {
            OverlayManager.get(this).show(data)
        }
    }

    private fun extractTitleAndBody(notification: Notification, extras: Bundle): Pair<String, String> {
        var title = (
            extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE)
                ?: extras.getCharSequence(Notification.EXTRA_TITLE_BIG)
            )?.toString()?.trim().orEmpty()

        var body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
            )?.toString()?.trim().orEmpty()

        // Parse MessagingStyle (Messenger, Zalo, Telegram, WhatsApp, TikTok, SMS)
        try {
            @Suppress("DEPRECATION")
            val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            if (!messages.isNullOrEmpty()) {
                val lastMsg = messages.lastOrNull() as? Bundle
                if (lastMsg != null) {
                    val msgText = lastMsg.getCharSequence("text")?.toString()?.trim().orEmpty()
                    val sender = lastMsg.getCharSequence("sender")?.toString()?.trim().orEmpty()
                    if (msgText.isNotEmpty()) body = msgText
                    if (sender.isNotEmpty() && (title.isBlank() || !title.contains(sender))) {
                        title = sender
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (body.isBlank()) {
            val lines: Array<CharSequence>? = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            if (!lines.isNullOrEmpty()) {
                body = lines.lastOrNull()?.toString()?.trim().orEmpty()
            }
        }

        if (body.isBlank()) {
            body = (
                extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
                    ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)
                    ?: notification.tickerText
                )?.toString()?.trim().orEmpty()
        }

        return title to body
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    companion object {
        const val EXTRA_IS_TEST_NOTIFICATION = "digitalnotify_is_test"
        private const val KEEP_ALIVE_CHANNEL_ID = "digitalnotify_keepalive"
        private const val KEEP_ALIVE_NOTIFICATION_ID = 99047

        @Volatile
        var isConnected: Boolean = false

        /**
         * Safely requests rebind WITHOUT synchronously disabling the component.
         */
        fun requestSafeRebind(context: Context) {
            try {
                val cn = ComponentName(context, NotificationAccessService::class.java)
                context.packageManager.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
                NotificationListenerService.requestRebind(cn)
            } catch (_: Exception) {
            }
        }

        /**
         * Deep reset with a 650ms async delay between DISABLED and ENABLED, used ONLY when
         * the user manually taps the "Làm mới kết nối" button.
         */
        fun forceDeepRebind(context: Context, onComplete: () -> Unit) {
            val appCtx = context.applicationContext
            val cn = ComponentName(appCtx, NotificationAccessService::class.java)
            val pm = appCtx.packageManager
            try {
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) {
            }
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    pm.setComponentEnabledSetting(
                        cn,
                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                        PackageManager.DONT_KILL_APP
                    )
                    NotificationListenerService.requestRebind(cn)
                } catch (_: Exception) {
                }
                onComplete()
            }, 650L)
        }
    }
}
