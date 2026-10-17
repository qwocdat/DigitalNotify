package com.digitalsmod.digitalnotify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.os.Handler
import android.os.Looper
import android.text.TextUtils

class NotificationAccessService : NotificationListenerService() {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var overlayController: OverlayController? = null

    override fun onCreate() {
        super.onCreate()
        overlayController = OverlayController(this)
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        // Android binds and reconnects this service when notification access is active.
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        if (sbn.packageName == packageName) return
        if ((sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE)
        val selected = prefs.getStringSet(MainActivity.KEY_SELECTED_PACKAGES, emptySet()) ?: emptySet()
        if (sbn.packageName !in selected) return
        if (!android.provider.Settings.canDrawOverlays(this)) return

        val extras = sbn.notification.extras
        val title = (extras.getCharSequence(Notification.EXTRA_TITLE)
            ?: extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))?.toString()?.trim().orEmpty()
        val body = (
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_TEXT)
                ?: extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)
            )?.toString()?.trim().orEmpty()

        val appLabel = try {
            val info = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(info).toString()
        } catch (_: Exception) {
            sbn.packageName.substringAfterLast('.')
        }

        val safeTitle = title.ifBlank { appLabel }
        val safeBody = body.ifBlank {
            if (title.isBlank()) "Có thông báo mới" else "Thông báo mới"
        }
        val data = BannerData(
            packageName = sbn.packageName,
            appName = appLabel,
            title = safeTitle,
            body = safeBody,
            postTime = sbn.postTime
        )
        mainHandler.post { overlayController?.show(data) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        // Removing a source notification does not automatically dismiss its banner.
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        overlayController?.dispose()
        overlayController = null
        super.onDestroy()
    }
}
