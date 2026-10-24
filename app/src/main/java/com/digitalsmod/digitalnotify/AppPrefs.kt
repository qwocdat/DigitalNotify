package com.digitalsmod.digitalnotify

import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

internal object AppPrefs {
    const val PREFS = "digitalnotify_preferences"
    const val KEY_SELECTED_PACKAGES = "selected_notification_packages"
    const val KEY_SELECTED_PACKAGES_CSV = "selected_notification_packages_csv"
    const val KEY_MASTER_ENABLED = "master_enabled"
    const val KEY_SUPPRESS_SYSTEM_NOTIF = "suppress_system_notif"
    const val KEY_IGNORE_ONGOING = "ignore_ongoing"
    const val KEY_VIBRATE_ENABLED = "vibrate_enabled"
    const val KEY_THEME_INDEX = "theme_index"
    const val KEY_BANNER_DURATION_MS = "banner_duration_ms"
    const val KEY_LANGUAGE = "app_language" // "vi" or "en"
    const val KEY_ONBOARDING_DONE = "onboarding_done_v5"
    const val KEY_CONDITION_ACCEPTED = "condition_accepted_v5"
    const val KEY_TIKTOK_VISITED = "tiktok_visited_v5"

    const val ADMIN_TIKTOK_URL = "https://www.tiktok.com/@digitalxiaoiu5"

    // System packages that must NEVER trigger floating banners (prevents the overlay warning loop!)
    private val BLOCKED_SYSTEM_PACKAGES = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.google.android.ext.services",
        "com.miui.securitycenter",
        "com.samsung.android.app.cocktailbarservice"
    )

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

    fun isSuppressSystemNotif(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SUPPRESS_SYSTEM_NOTIF, true)
    }

    fun isPackageAllowed(context: Context, packageName: String): Boolean {
        if (packageName == context.packageName) return false
        if (packageName in BLOCKED_SYSTEM_PACKAGES) return false
        ensureLoaded(context)
        return memorySelected.contains(packageName)
    }

    fun getLang(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getString(KEY_LANGUAGE, "vi") ?: "vi"
    }

    fun setLang(context: Context, lang: String) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_LANGUAGE, lang).commit()
    }

    fun isOnboardingDone(context: Context): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ONBOARDING_DONE, false)
    }

    fun setOnboardingDone(context: Context, done: Boolean) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ONBOARDING_DONE, done).commit()
    }
}
