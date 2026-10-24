package com.digitalsmod.digitalnotify

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotificationAccessService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayController: OverlayController? = null

    override fun onCreate() {
        super.onCreate()
        overlayController = OverlayController(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn == null) return
        if (sbn.packageName == packageName) return

        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        if (!prefs.getBoolean(MainActivity.KEY_MASTER_ENABLED, true)) return

        val ignoreOngoing = prefs.getBoolean(MainActivity.KEY_IGNORE_ONGOING, true)
        if (ignoreOngoing && (sbn.isOngoing || (notification.flags and Notification.FLAG_ONGOING_EVENT) != 0)) {
            return
        }

        val trackAllApps = prefs.getBoolean(MainActivity.KEY_TRACK_ALL_APPS, false)
        val selected = prefs.getStringSet(MainActivity.KEY_SELECTED_PACKAGES, emptySet()) ?: emptySet()
        if (!trackAllApps && sbn.packageName !in selected) return
        if (!Settings.canDrawOverlays(this)) return

        val extras = notification.extras
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString()?.trim().orEmpty()
        val body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
            )?.toString()?.trim().orEmpty()

        // Filter out muted keywords if configured
        val mutedRaw = prefs.getString(MainActivity.KEY_MUTED_KEYWORDS, "").orEmpty()
        if (mutedRaw.isNotBlank()) {
            val keywords = mutedRaw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val combined = "$title $body"
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

        val customDrawable = try {
            notification.getLargeIcon()?.loadDrawable(this)
        } catch (_: Exception) {
            null
        }

        val safeTitle = title.ifBlank { appLabel }
        val safeBody = body.ifBlank {
            if (title.isBlank()) "Có thông báo mới" else "Nhấn để mở ứng dụng"
        }

        val data = BannerData(
            packageName = sbn.packageName,
            appName = appLabel,
            title = safeTitle,
            body = safeBody,
            postTime = sbn.postTime,
            contentIntent = notification.contentIntent,
            customIcon = customDrawable
        )
        NotificationHistoryStore.record(data)
        mainHandler.post { overlayController?.show(data) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        overlayController?.dispose()
        overlayController = null
        super.onDestroy()
    }
}
