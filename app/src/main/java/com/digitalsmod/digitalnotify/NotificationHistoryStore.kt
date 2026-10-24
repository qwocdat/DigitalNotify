package com.digitalsmod.digitalnotify

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

internal data class HistoryItem(
    val packageName: String,
    val appName: String,
    val title: String,
    val body: String,
    val timestamp: Long
) {
    fun formattedTime(): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }
}

internal object NotificationHistoryStore {
    private const val MAX_ITEMS = 30
    private val items = CopyOnWriteArrayList<HistoryItem>()

    fun record(data: BannerData) {
        items.add(
            0,
            HistoryItem(
                packageName = data.packageName,
                appName = data.appName,
                title = data.title,
                body = data.body,
                timestamp = data.postTime
            )
        )
        while (items.size > MAX_ITEMS) {
            items.removeAt(items.lastIndex)
        }
    }

    fun list(): List<HistoryItem> = items.toList()

    fun clear() {
        items.clear()
    }
}
