package com.digitalsmod.digitalnotify

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
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
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isConnected = true
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

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        isConnected = true

        val pkg = sbn.packageName ?: return
        // Strictly block our own package and system overlay notifications to prevent infinite loops!
        if (pkg == packageName || pkg == "android" || pkg == "com.android.systemui") return

        val notification = sbn.notification ?: return
        if (!AppPrefs.isMasterEnabled(this)) return
        if (!Settings.canDrawOverlays(this)) return

        // Only show notifications for apps selected by the user
        if (!AppPrefs.isPackageAllowed(this, pkg)) return

        val prefs = getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE)
        val ignoreOngoing = prefs.getBoolean(AppPrefs.KEY_IGNORE_ONGOING, true)
        if (ignoreOngoing) {
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

        // Block any system warning mentioning DigitalNotify or overlay display
        val combinedText = "$rawTitle $rawBody"
        if (combinedText.contains("DigitalNotify", ignoreCase = true) ||
            combinedText.contains("đang hiện trên các ứng dụng", ignoreCase = true) ||
            combinedText.contains("displaying over other apps", ignoreCase = true)
        ) {
            return
        }

        val isGroupSummary = (notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0
        if (isGroupSummary && rawTitle.isBlank() && rawBody.isBlank()) {
            return
        }

        val appLabel = try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            pkg.substringAfterLast('.')
        }

        val largeIconDrawable = try {
            notification.getLargeIcon()?.loadDrawable(this)
        } catch (_: Exception) {
            null
        }

        val appBadgeDrawable = try {
            packageManager.getApplicationIcon(pkg)
        } catch (_: Exception) {
            null
        }

        val safeTitle = rawTitle.ifBlank { appLabel }
        val safeBody = rawBody.ifBlank {
            notification.tickerText?.toString()?.trim().orEmpty().ifBlank { "Tin nhắn mới" }
        }

        // Auto-suppress original system notification so ONLY DigitalNotify's Liquid Glass banner appears!
        if (AppPrefs.isSuppressSystemNotif(this) && !sbn.isOngoing) {
            try {
                cancelNotification(sbn.key)
            } catch (_: Exception) {
            }
        }

        val data = BannerData(
            packageName = pkg,
            appName = appLabel,
            title = safeTitle,
            body = safeBody,
            postTime = System.currentTimeMillis(),
            contentIntent = notification.contentIntent,
            largeIcon = largeIconDrawable,
            appBadgeIcon = appBadgeDrawable,
            timeLabel = "now"
        )

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
        @Volatile
        var isConnected: Boolean = false

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
    }
}
