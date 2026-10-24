package com.digitalsmod.digitalnotify

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.WindowCompat
import java.util.concurrent.Executors

internal data class AppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable?,
    val isUserApp: Boolean
)

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }
    private val bgExecutor = Executors.newSingleThreadExecutor()
    private var previewOverlayController: OverlayController? = null

    private lateinit var notificationStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var selectedCountBadge: TextView
    private lateinit var appListContainer: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var searchInput: EditText

    private var cachedApps: List<AppEntry> = emptyList()
    private var isLoadingApps: Boolean = true
    private var searchQuery: String = ""
    private var filterMode: Int = FILTER_USER // 0 = All, 1 = Selected, 2 = User Apps, 3 = System

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        previewOverlayController = OverlayController(this)

        buildScreen()
        loadInstalledAppsAsync()
        refreshPermissionStatus()
        refreshHistoryList()
    }

    override fun onResume() {
        super.onResume()
        if (::notificationStatus.isInitialized) {
            refreshPermissionStatus()
            refreshHistoryList()
        }
    }

    override fun onDestroy() {
        previewOverlayController?.dispose()
        previewOverlayController = null
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun buildScreen() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        val scrollView = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(26), dp(20), dp(36))
        }

        // Apply WindowInsets so Android 15 edge-to-edge never overlaps status or navigation bars
        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val topInset = insets.systemWindowInsetTop.coerceAtLeast(dp(16))
            @Suppress("DEPRECATION")
            val bottomInset = insets.systemWindowInsetBottom.coerceAtLeast(dp(16))
            content.setPadding(dp(20), topInset + dp(14), dp(20), bottomInset + dp(24))
            insets
        }

        scrollView.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        // Header Badge
        content.addView(TextView(this).apply {
            text = "DIGITALNOTIFY · PRO 0.2"
            textSize = 11.5f
            letterSpacing = 0.20f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        content.addView(TextView(this).apply {
            text = "Liquid Glass\nNotifications."
            textSize = 32f
            setLineSpacing(dp(1).toFloat(), 0.96f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(8), 0, dp(8))
        })

        content.addView(TextView(this).apply {
            text = "Hiển thị thông báo nổi phong cách kính mờ AGSL 120Hz với thao tác vuốt thông minh, lọc từ khóa và tuỳ biến giao diện."
            textSize = 13.5f
            setTextColor(SUBTEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
        })

        // Quick Test Banner Button
        val testBannerBtn = actionButton(
            "✨ Xem thử Banner Liquid Glass ngay",
            primary = true
        ) {
            triggerTestBanner()
        }
        content.addView(testBannerBtn, topMargin(16))

        // 1. Permissions & System Status Card
        val permissionsCard = createCard()
        permissionsCard.addView(sectionHeading("TRẠNG THÁI & QUYỀN HỆ THỐNG"))

        notificationStatus = statusLine()
        overlayStatus = statusLine()
        batteryStatus = statusLine()

        permissionsCard.addView(notificationStatus)
        permissionsCard.addView(actionButton("1. Cấp quyền đọc thông báo (Notification Access)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        permissionsCard.addView(overlayStatus)
        permissionsCard.addView(actionButton("2. Cho phép hiển thị trên ứng dụng khác (Overlay)") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        permissionsCard.addView(batteryStatus)
        permissionsCard.addView(actionButton("3. Tắt giới hạn pin chạy nền (Khuyên dùng)") {
            requestIgnoreBatteryOptimizations()
        })

        content.addView(permissionsCard, topMargin(16))

        // 2. Liquid Glass Customization Card
        val settingsCard = createCard()
        settingsCard.addView(sectionHeading("TUỲ CHỈNH LIQUID GLASS & HÀNH VI"))

        settingsCard.addView(
            buildSwitchRow(
                title = "Bật dịch vụ hiển thị Banner",
                subtitle = "Tạm dừng hoặc bật nhanh toàn bộ thông báo nổi",
                prefKey = KEY_MASTER_ENABLED,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        settingsCard.addView(
            buildSwitchRow(
                title = "Theo dõi tất cả ứng dụng",
                subtitle = "Hiển thị thông báo từ mọi ứng dụng mà không cần tích chọn từng app",
                prefKey = KEY_TRACK_ALL_APPS,
                defaultValue = false
            )
        )
        settingsCard.addView(dividerView())

        settingsCard.addView(
            buildSwitchRow(
                title = "Bỏ qua thông báo đang chạy nền (Ongoing)",
                subtitle = "Chặn các thông báo phát nhạc, bộ đếm giờ hoặc thanh tiến trình tải xuống",
                prefKey = KEY_IGNORE_ONGOING,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        settingsCard.addView(
            buildSwitchRow(
                title = "Phản hồi rung nhẹ (Haptic)",
                subtitle = "Rung nhẹ 18ms khi banner xuất hiện",
                prefKey = KEY_VIBRATE_ENABLED,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        settingsCard.addView(
            buildSwitchRow(
                title = "Hiển thị Banner ở cạnh dưới màn hình",
                subtitle = "Dễ chạm bằng một tay trên màn hình lớn",
                prefKey = KEY_POSITION_BOTTOM,
                defaultValue = false
            )
        )
        settingsCard.addView(dividerView())

        // Theme selector
        settingsCard.addView(TextView(this).apply {
            text = "Màu sắc bề mặt kính (Shader Theme)"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(10), 0, dp(8))
        })

        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val themes = listOf("Frost Blue" to 0, "Aurora Violet" to 1, "Obsidian Gold" to 2)
        val themeButtons = mutableListOf<TextView>()

        fun updateThemeSelection(selectedIdx: Int) {
            themeButtons.forEachIndexed { idx, tv ->
                val active = idx == selectedIdx
                tv.background = rounded(
                    if (active) BUTTON_PRIMARY else Color.rgb(24, 29, 40),
                    12,
                    if (active) ACCENT else CARD_STROKE
                )
                tv.setTextColor(if (active) Color.WHITE else SUBTEXT)
            }
        }

        themes.forEach { (label, idx) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener {
                    prefs.edit().putInt(KEY_THEME_INDEX, idx).apply()
                    updateThemeSelection(idx)
                    triggerTestBanner()
                }
            }
            themeButtons.add(chip)
            themeRow.addView(
                chip,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (idx < 2) rightMargin = dp(6)
                }
            )
        }
        updateThemeSelection(prefs.getInt(KEY_THEME_INDEX, 0))
        settingsCard.addView(themeRow)

        // Duration selector
        settingsCard.addView(TextView(this).apply {
            text = "Thời gian tự động đóng Banner"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(14), 0, dp(8))
        })

        val durationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val durations = listOf("Nhanh (3.5s)" to 3500L, "Chuẩn (5.2s)" to 5200L, "Lâu (8.0s)" to 8000L)
        val durationButtons = mutableListOf<TextView>()

        fun updateDurationSelection(currentMs: Long) {
            durationButtons.forEachIndexed { idx, tv ->
                val active = durations[idx].second == currentMs
                tv.background = rounded(
                    if (active) BUTTON_PRIMARY else Color.rgb(24, 29, 40),
                    12,
                    if (active) ACCENT else CARD_STROKE
                )
                tv.setTextColor(if (active) Color.WHITE else SUBTEXT)
            }
        }

        durations.forEachIndexed { i, (label, ms) ->
            val chip = TextView(this).apply {
                text = label
                textSize = 12f
                gravity = Gravity.CENTER
                setPadding(dp(10), dp(9), dp(10), dp(9))
                setOnClickListener {
                    prefs.edit().putLong(KEY_BANNER_DURATION_MS, ms).apply()
                    updateDurationSelection(ms)
                }
            }
            durationButtons.add(chip)
            durationRow.addView(
                chip,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (i < durations.lastIndex) rightMargin = dp(6)
                }
            )
        }
        updateDurationSelection(prefs.getLong(KEY_BANNER_DURATION_MS, 5200L))
        settingsCard.addView(durationRow)

        // Muted keywords input
        settingsCard.addView(TextView(this).apply {
            text = "Từ khóa chặn thông báo (cách nhau bằng dấu phẩy)"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(14), 0, dp(6))
        })

        val mutedInput = EditText(this).apply {
            hint = "Ví dụ: Quảng cáo, khuyến mãi, đang tải..."
            setText(prefs.getString(KEY_MUTED_KEYWORDS, ""))
            textSize = 13.5f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(24, 28, 38), 14, CARD_STROKE)
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    prefs.edit().putString(KEY_MUTED_KEYWORDS, s?.toString().orEmpty()).apply()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        settingsCard.addView(mutedInput, LinearLayout.LayoutParams(-1, dp(46)))

        content.addView(settingsCard, topMargin(14))

        // 3. Recent Notification History Card
        val historyCard = createCard()
        val historyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        historyHeader.addView(
            sectionHeading("NHẬT KÝ BANNER GẦN ĐÂY").apply { setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        historyHeader.addView(TextView(this).apply {
            text = "Xoá lịch sử"
            textSize = 11.5f
            setTextColor(ACCENT)
            setPadding(dp(8), dp(4), dp(4), dp(4))
            setOnClickListener {
                NotificationHistoryStore.clear()
                refreshHistoryList()
            }
        })
        historyCard.addView(historyHeader)

        historyContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }
        historyCard.addView(historyContainer)
        content.addView(historyCard, topMargin(14))

        // 4. App Allowlist Card
        val appsCard = createCard()
        val appsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        appsHeader.addView(
            sectionHeading("DANH SÁCH ỨNG DỤNG THEO DÕI").apply { setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        selectedCountBadge = TextView(this).apply {
            textSize = 11f
            setTextColor(ACCENT)
            background = rounded(Color.rgb(25, 34, 52), 10, BUTTON_STROKE)
            setPadding(dp(9), dp(4), dp(9), dp(4))
        }
        appsHeader.addView(selectedCountBadge)
        appsCard.addView(appsHeader)

        appsCard.addView(TextView(this).apply {
            text = "Chọn các ứng dụng được phép hiển thị Liquid Glass Banner (hoặc bật 'Theo dõi tất cả ứng dụng' ở trên)."
            textSize = 12.5f
            setTextColor(SUBTEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(10))
        })

        // Search box
        searchInput = EditText(this).apply {
            hint = "Tìm theo tên ứng dụng hoặc gói (package)..."
            textSize = 14f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(24, 28, 38), 14, CARD_STROKE)
            inputType = InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    searchQuery = s?.toString().orEmpty()
                    renderAppList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        appsCard.addView(
            searchInput,
            LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) }
        )

        // Filter & bulk action row
        val filterScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val filters = listOf(
            "Ứng dụng cài thêm" to FILTER_USER,
            "Đã chọn" to FILTER_SELECTED,
            "Tất cả" to FILTER_ALL,
            "Hệ thống" to FILTER_SYSTEM
        )
        val filterChips = mutableListOf<TextView>()

        fun updateFilterChips() {
            filterChips.forEachIndexed { idx, chip ->
                val active = filters[idx].second == filterMode
                chip.background = rounded(
                    if (active) BUTTON_PRIMARY else Color.rgb(24, 29, 40),
                    12,
                    if (active) ACCENT else CARD_STROKE
                )
                chip.setTextColor(if (active) Color.WHITE else SUBTEXT)
            }
        }

        filters.forEach { (title, mode) ->
            val chip = TextView(this).apply {
                text = title
                textSize = 12f
                setPadding(dp(12), dp(7), dp(12), dp(7))
                setOnClickListener {
                    filterMode = mode
                    updateFilterChips()
                    renderAppList()
                }
            }
            filterChips.add(chip)
            filterRow.addView(
                chip,
                LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(6) }
            )
        }
        updateFilterChips()
        filterScroll.addView(filterRow)
        appsCard.addView(filterScroll, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        // Select All / Deselect All buttons
        val bulkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bulkRow.addView(
            TextView(this).apply {
                text = "✓ Chọn tất cả danh sách hiện tại"
                textSize = 11.5f
                setTextColor(ACCENT)
                setPadding(dp(4), dp(6), dp(12), dp(6))
                setOnClickListener {
                    val selected = selectedPackages().toMutableSet()
                    currentFilteredApps().forEach { selected.add(it.packageName) }
                    prefs.edit().putStringSet(KEY_SELECTED_PACKAGES, selected).apply()
                    renderAppList()
                }
            }
        )
        bulkRow.addView(
            TextView(this).apply {
                text = "✕ Bỏ chọn tất cả"
                textSize = 11.5f
                setTextColor(SUBTEXT)
                setPadding(dp(12), dp(6), dp(4), dp(6))
                setOnClickListener {
                    prefs.edit().putStringSet(KEY_SELECTED_PACKAGES, emptySet()).apply()
                    renderAppList()
                }
            }
        )
        appsCard.addView(bulkRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(6) })

        appListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        appsCard.addView(appListContainer)
        content.addView(appsCard, topMargin(14))

        content.addView(TextView(this).apply {
            text = "DIGITALNOTIFY · v0.2.0 Pro Liquid Glass\nMẹo: Vuốt banner lên hoặc sang ngang để đóng nhanh, chạm giữ để dừng đếm ngược, hoặc nhấn vào banner để mở trực tiếp cuộc trò chuyện."
            textSize = 11.5f
            setTextColor(MUTED)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(2), dp(18), dp(2), 0)
        })

        setContentView(root)
        updateSelectedBadge()
    }

    private fun triggerTestBanner() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Vui lòng cấp quyền 'Hiển thị trên ứng dụng khác (Overlay)' trước để xem thử Banner!",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        val themeName = when (prefs.getInt(KEY_THEME_INDEX, 0)) {
            1 -> "Aurora Violet"
            2 -> "Obsidian Gold"
            else -> "Frost Blue"
        }
        val sample = BannerData(
            packageName = packageName,
            appName = "DigitalNotify",
            title = "Liquid Glass ($themeName)",
            body = "Vuốt lên/ngang để đóng · Chạm giữ để đọc · Nhấn để mở ứng dụng.",
            postTime = System.currentTimeMillis()
        )
        NotificationHistoryStore.record(sample)
        refreshHistoryList()
        previewOverlayController?.show(sample)
    }

    private fun loadInstalledAppsAsync() {
        isLoadingApps = true
        renderAppList()
        bgExecutor.execute {
            val loaded = try {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != packageName }
                    .mapNotNull { info ->
                        val isUserApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                        val hasLauncher = packageManager.getLaunchIntentForPackage(info.packageName) != null
                        if (!isUserApp && !hasLauncher) return@mapNotNull null

                        val label = try {
                            packageManager.getApplicationLabel(info).toString()
                        } catch (_: Exception) {
                            info.packageName
                        }
                        val icon = try {
                            packageManager.getApplicationIcon(info)
                        } catch (_: Exception) {
                            null
                        }
                        AppEntry(
                            packageName = info.packageName,
                            label = label,
                            icon = icon,
                            isUserApp = isUserApp
                        )
                    }
                    .sortedBy { it.label.lowercase() }
                    .toList()
            } catch (_: Exception) {
                emptyList()
            }

            runOnUiThread {
                cachedApps = loaded
                isLoadingApps = false
                renderAppList()
            }
        }
    }

    private fun currentFilteredApps(): List<AppEntry> {
        val selected = selectedPackages()
        return cachedApps.filter { app ->
            val matchesMode = when (filterMode) {
                FILTER_SELECTED -> app.packageName in selected
                FILTER_USER -> app.isUserApp
                FILTER_SYSTEM -> !app.isUserApp
                else -> true
            }
            val matchesQuery = searchQuery.isBlank() ||
                app.label.contains(searchQuery, ignoreCase = true) ||
                app.packageName.contains(searchQuery, ignoreCase = true)
            matchesMode && matchesQuery
        }.sortedWith(
            compareByDescending<AppEntry> { it.packageName in selected }
                .thenBy { it.label.lowercase() }
        )
    }

    private fun renderAppList() {
        if (!::appListContainer.isInitialized) return
        updateSelectedBadge()
        appListContainer.removeAllViews()

        if (isLoadingApps) {
            appListContainer.addView(TextView(this).apply {
                text = "Đang tải danh sách ứng dụng..."
                textSize = 13f
                setTextColor(SUBTEXT)
                setPadding(0, dp(14), 0, dp(14))
            })
            return
        }

        val filteredApps = currentFilteredApps()
        if (filteredApps.isEmpty()) {
            appListContainer.addView(TextView(this).apply {
                text = if (cachedApps.isEmpty()) {
                    "Không thể tải danh sách ứng dụng."
                } else {
                    "Không có ứng dụng nào khớp bộ lọc hiện tại."
                }
                textSize = 13f
                setTextColor(SUBTEXT)
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }

        val selected = selectedPackages()
        filteredApps.forEachIndexed { index, app ->
            val appRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(8), 0, dp(8))
                isClickable = true
                isFocusable = true
            }

            val appIcon = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = app.label
            }
            appRow.addView(appIcon, LinearLayout.LayoutParams(dp(38), dp(38)))

            val nameColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, dp(4), 0)
            }

            nameColumn.addView(TextView(this).apply {
                text = app.label
                textSize = 14f
                setTextColor(Color.rgb(245, 247, 252))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })

            nameColumn.addView(TextView(this).apply {
                text = app.packageName
                textSize = 10.5f
                setTextColor(MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })

            appRow.addView(nameColumn, LinearLayout.LayoutParams(0, -2, 1f))

            val checkBox = CheckBox(this).apply {
                buttonTintList = ColorStateList.valueOf(ACCENT)
                isChecked = app.packageName in selected
                contentDescription = "Theo dõi ${app.label}"
                setOnCheckedChangeListener { _, checked ->
                    val updated = selectedPackages().toMutableSet()
                    if (checked) updated.add(app.packageName)
                    else updated.remove(app.packageName)

                    prefs.edit()
                        .putStringSet(KEY_SELECTED_PACKAGES, updated)
                        .apply()
                    updateSelectedBadge()
                }
            }

            appRow.addView(checkBox)
            appRow.setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
            }

            appListContainer.addView(appRow, LinearLayout.LayoutParams(-1, -2))

            if (index < filteredApps.lastIndex) {
                appListContainer.addView(dividerView())
            }
        }
    }

    private fun refreshHistoryList() {
        if (!::historyContainer.isInitialized) return
        historyContainer.removeAllViews()
        val items = NotificationHistoryStore.list()
        if (items.isEmpty()) {
            historyContainer.addView(TextView(this).apply {
                text = "Chưa có thông báo nào được ghi nhận trong phiên này. Hãy nhấn nút 'Xem thử Banner' ở trên!"
                textSize = 12.5f
                setTextColor(MUTED)
                setPadding(0, dp(6), 0, dp(4))
            })
            return
        }

        items.take(6).forEachIndexed { idx, item ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(10), dp(8), dp(10), dp(8))
                background = rounded(Color.rgb(22, 26, 36), 12)
                setOnClickListener {
                    previewOverlayController?.show(
                        BannerData(
                            packageName = item.packageName,
                            appName = item.appName,
                            title = item.title,
                            body = item.body,
                            postTime = System.currentTimeMillis()
                        )
                    )
                }
            }
            row.addView(TextView(this).apply {
                text = "${item.appName} · ${item.formattedTime()} (Nhấn để phát lại)"
                textSize = 10.5f
                setTextColor(ACCENT)
            })
            row.addView(TextView(this).apply {
                text = "${item.title}: ${item.body}"
                textSize = 12.5f
                setTextColor(Color.WHITE)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setPadding(0, dp(2), 0, 0)
            })
            historyContainer.addView(
                row,
                LinearLayout.LayoutParams(-1, -2).apply {
                    if (idx < items.size - 1 && idx < 5) bottomMargin = dp(6)
                }
            )
        }
    }

    private fun updateSelectedBadge() {
        if (!::selectedCountBadge.isInitialized) return
        val trackAll = prefs.getBoolean(KEY_TRACK_ALL_APPS, false)
        val count = selectedPackages().size
        selectedCountBadge.text = if (trackAll) "Tất cả ứng dụng" else "Đã chọn: $count"
    }

    private fun selectedPackages(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet())?.toSet()
            ?: emptySet()
    }

    private fun refreshPermissionStatus() {
        if (!::notificationStatus.isInitialized || !::overlayStatus.isInitialized) return

        val listenerComponent = ComponentName(this, NotificationAccessService::class.java)
        val flatComponent = listenerComponent.flattenToString()

        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )

        val hasNotificationAccess = enabledListeners
            ?.split(':')
            ?.any { it.equals(flatComponent, ignoreCase = true) } == true

        if (hasNotificationAccess) {
            try {
                NotificationListenerService.requestRebind(listenerComponent)
            } catch (_: Exception) {
            }
        }

        notificationStatus.text = if (hasNotificationAccess) {
            "● Đã cấp quyền đọc thông báo (Notification Access)"
        } else {
            "○ Chưa cấp quyền đọc thông báo"
        }
        notificationStatus.setTextColor(if (hasNotificationAccess) SUCCESS else WARNING)

        val hasOverlayAccess = Settings.canDrawOverlays(this)
        overlayStatus.text = if (hasOverlayAccess) {
            "● Đã cho phép hiển thị nổi (Overlay)"
        } else {
            "○ Chưa cho phép hiển thị nổi (Overlay)"
        }
        overlayStatus.setTextColor(if (hasOverlayAccess) SUCCESS else WARNING)

        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val ignoringBattery = pm?.isIgnoringBatteryOptimizations(packageName) == true
        batteryStatus.text = if (ignoringBattery) {
            "● Đã tắt giới hạn pin chạy nền"
        } else {
            "○ Đang bật tiết kiệm pin (có thể làm trễ thông báo)"
        }
        batteryStatus.setTextColor(if (ignoringBattery) SUCCESS else SUBTEXT)
    }

    private fun requestIgnoreBatteryOptimizations() {
        try {
            val intent = Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun buildSwitchRow(
        title: String,
        subtitle: String,
        prefKey: String,
        defaultValue: Boolean
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val textCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, dp(10), 0)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 13.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 11.5f
            setTextColor(SUBTEXT)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(prefKey, defaultValue)
            thumbTintList = ColorStateList.valueOf(ACCENT)
            trackTintList = ColorStateList.valueOf(Color.rgb(55, 72, 104))
            setOnCheckedChangeListener { _, isChecked ->
                prefs.edit().putBoolean(prefKey, isChecked).apply()
                updateSelectedBadge()
            }
        }
        row.addView(sw)
        row.setOnClickListener { sw.isChecked = !sw.isChecked }
        return row
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(CARD, 22, CARD_STROKE)
        }
    }

    private fun sectionHeading(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(10))
        }
    }

    private fun statusLine(): TextView {
        return TextView(this).apply {
            textSize = 12.5f
            setPadding(0, dp(6), 0, dp(4))
        }
    }

    private fun actionButton(
        value: String,
        primary: Boolean = false,
        action: () -> Unit
    ): Button {
        return Button(this).apply {
            text = value
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.WHITE)
            background = rounded(
                if (primary) BUTTON_PRIMARY else BUTTON,
                14,
                if (primary) ACCENT else BUTTON_STROKE
            )
            minHeight = dp(46)
            setPadding(dp(14), 0, dp(14), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(46)).apply {
                topMargin = dp(6)
                bottomMargin = dp(6)
            }
        }
    }

    private fun dividerView(): View {
        return View(this).apply {
            setBackgroundColor(DIVIDER)
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        }
    }

    private fun topMargin(value: Int): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(-1, -2).apply {
            topMargin = dp(value)
        }
    }

    private fun rounded(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        const val PREFS = "digitalnotify_preferences"
        const val KEY_SELECTED_PACKAGES = "selected_notification_packages"
        const val KEY_MASTER_ENABLED = "master_enabled"
        const val KEY_TRACK_ALL_APPS = "track_all_apps"
        const val KEY_IGNORE_ONGOING = "ignore_ongoing"
        const val KEY_VIBRATE_ENABLED = "vibrate_enabled"
        const val KEY_POSITION_BOTTOM = "position_bottom"
        const val KEY_THEME_INDEX = "theme_index"
        const val KEY_BANNER_DURATION_MS = "banner_duration_ms"
        const val KEY_MUTED_KEYWORDS = "muted_keywords"

        private const val FILTER_ALL = 0
        private const val FILTER_SELECTED = 1
        private const val FILTER_USER = 2
        private const val FILTER_SYSTEM = 3

        private val BACKGROUND = Color.rgb(8, 10, 15)
        private val CARD = Color.rgb(16, 20, 29)
        private val CARD_STROKE = Color.rgb(35, 43, 58)
        private val BUTTON = Color.rgb(29, 37, 54)
        private val BUTTON_PRIMARY = Color.rgb(42, 68, 118)
        private val BUTTON_STROKE = Color.rgb(58, 74, 102)
        private val ACCENT = Color.rgb(157, 190, 255)
        private val SUBTEXT = Color.rgb(168, 176, 192)
        private val MUTED = Color.rgb(118, 128, 148)
        private val DIVIDER = Color.rgb(30, 36, 48)
        private val SUCCESS = Color.rgb(110, 228, 172)
        private val WARNING = Color.rgb(245, 192, 108)
    }
}
