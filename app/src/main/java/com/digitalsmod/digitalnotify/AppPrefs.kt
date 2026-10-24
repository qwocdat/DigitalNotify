package com.digitalsmod.digitalnotify

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

internal object AppPrefs {
    const val PREFS = "digitalnotify_preferences"
    const val KEY_SELECTED_PACKAGES = "selected_notification_packages"
    const val KEY_SELECTED_PACKAGES_CSV = "selected_notification_packages_csv"
    const val KEY_MASTER_ENABLED = "master_enabled"
    const val KEY_TRACK_ALL_APPS = "track_all_apps_v4"
    const val KEY_IGNORE_ONGOING = "ignore_ongoing"
    const val KEY_VIBRATE_ENABLED = "vibrate_enabled"
    const val KEY_THEME_INDEX = "theme_index"
    const val KEY_BANNER_DURATION_MS = "banner_duration_ms"
    const val KEY_MUTED_KEYWORDS = "muted_keywords"

    @Volatile
    private var initialized = false
    private val memorySelected = CopyOnWriteArraySet<String>()

    private fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val csv = prefs.getString(KEY_SELECTED_PACKAGES_CSV, null)
            if (!csv.isNullOrBlank()) {
                csv.split('|').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    memorySelected.add(it)
                }
            }
            val legacySet = prefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet())
            if (!legacySet.isNullOrEmpty()) {
                memorySelected.addAll(legacySet)
            }
            initialized = true
        }
    }

    fun getSelectedPackages(context: Context): Set<String> {
        ensureLoaded(context)
        return memorySelected.toSet()
    }

    fun setPackageSelected(context: Context, packageName: String, selected: Boolean) {
        ensureLoaded(context)
        if (selected) memorySelected.add(packageName) else memorySelected.remove(packageName)
        persistSelected(context)
    }

    fun setAllPackagesSelected(context: Context, packages: Collection<String>) {
        ensureLoaded(context)
        memorySelected.clear()
        memorySelected.addAll(packages)
        persistSelected(context)
    }

    private fun persistSelected(context: Context) {
        val snapshot = memorySelected.toSet()
        val csv = snapshot.joinToString("|")
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .putString(KEY_SELECTED_PACKAGES_CSV, csv)
            .putStringSet(KEY_SELECTED_PACKAGES, HashSet(snapshot))
            .commit()
    }

    fun isMasterEnabled(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MASTER_ENABLED, true)
    }

    fun isTrackAllApps(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_TRACK_ALL_APPS, true)
    }

    fun isPackageAllowed(context: Context, packageName: String): Boolean {
        ensureLoaded(context)
        // Always allow if Track All Apps is enabled, OR if the user explicitly checked this app,
        // OR if no apps have been checked yet!
        if (memorySelected.contains(packageName)) return true
        if (isTrackAllApps(context)) return true
        if (memorySelected.isEmpty()) return true
        return false
    }
}
