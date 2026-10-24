package com.digitalsmod.digitalnotify

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
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

    private val prefs by lazy { getSharedPreferences(AppPrefs.PREFS, MODE_PRIVATE) }
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private lateinit var notificationStatus: TextView
    private lateinit var serviceBindStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var batteryStatus: TextView
    private lateinit var selectedCountBadge: TextView
    private lateinit var appListContainer: LinearLayout
    private lateinit var historyContainer: LinearLayout
    private lateinit var searchInput: EditText

    private var cachedApps: List<AppEntry> = emptyList()
    private var isLoadingApps: Boolean = true
    private var searchQuery: String = ""
    private var filterMode: Int = FILTER_USER

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        OverlayManager.get(this)
        NotificationAccessService.requestSafeRebind(this)

        buildScreen()
        loadInstalledAppsAsync()
        refreshPermissionStatus()
        refreshHistoryList()
    }

    override fun onResume() {
        super.onResume()
        NotificationAccessService.requestSafeRebind(this)
        if (::notificationStatus.isInitialized) {
            refreshPermissionStatus()
            refreshHistoryList()
            mainHandler.postDelayed({ refreshPermissionStatus() }, 700L)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
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
            setPadding(dp(18), dp(22), dp(18), dp(36))
        }

        root.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val topInset = insets.systemWindowInsetTop.coerceAtLeast(dp(16))
            @Suppress("DEPRECATION")
            val bottomInset = insets.systemWindowInsetBottom.coerceAtLeast(dp(16))
            content.setPadding(dp(18), topInset + dp(10), dp(18), bottomInset + dp(24))
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

        // Top Live Backdrop Stage (Weather "Mandalay 28°" + Calendar "OCTOBER" from 90047.mp4)
        // Placed right at the top of the screen so when the banner expands at the top, you see
        // the real optical glass blur over the dark-blue Weather widget and white Calendar widget!
        content.addView(buildVideoBackdropWidgets())

        // Demo & Test Action Buttons
        content.addView(
            actionButton("▶ Chạy thử hiệu ứng Video tại đây (TechVerse: Hi ➔ Hello)", primary = true) {
                playExactVideoDemoSequence()
            },
            topMargin(14)
        )

        content.addView(
            actionButton("🏠 Chạy thử ngoài Màn hình chính (Tự ẩn App & hiện sau 2 giây)") {
                playHomeScreenDemoSequence()
            },
            topMargin(4)
        )

        content.addView(
            actionButton("🔔 Gửi thông báo hệ thống thật (Kiểm tra dịch vụ ngầm)") {
                sendRealSystemTestNotification()
            },
            topMargin(4)
        )

        // 1. Permissions & Live Service Connection Card
        val permissionsCard = createCard()
        permissionsCard.addView(sectionHeading("TRẠNG THÁI KẾT NỐI & QUYỀN HỆ THỐNG"))

        notificationStatus = statusLine()
        serviceBindStatus = statusLine()
        overlayStatus = statusLine()
        batteryStatus = statusLine()

        permissionsCard.addView(notificationStatus)
        permissionsCard.addView(serviceBindStatus)
        permissionsCard.addView(actionButton("1. Mở cài đặt Quyền đọc thông báo (Notification Access)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        permissionsCard.addView(actionButton("🔄 Làm mới sâu kết nối dịch vụ (Nhấn nếu vừa cài đè APK)") {
            Toast.makeText(this, "Đang khởi động lại kết nối NotificationListenerService...", Toast.LENGTH_SHORT).show()
            NotificationAccessService.forceDeepRebind(this) {
                refreshPermissionStatus()
                Toast.makeText(this, "Đã kết nối lại dịch vụ lắng nghe!", Toast.LENGTH_SHORT).show()
            }
        })

        permissionsCard.addView(overlayStatus)
        permissionsCard.addView(actionButton("2. Cho phép hiển thị nổi trên ứng dụng khác (Overlay)") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        permissionsCard.addView(batteryStatus)
        permissionsCard.addView(actionButton("3. Tắt giới hạn pin chạy nền (Tránh bị hệ thống đóng ngầm)") {
            requestIgnoreBatteryOptimizations()
        })

        content.addView(permissionsCard, topMargin(14))

        // 2. Liquid Glass Settings Card
        val settingsCard = createCard()
        settingsCard.addView(sectionHeading("TUỲ CHỈNH GIAO DIỆN & BỘ LỌC"))

        settingsCard.addView(
            buildSwitchRow(
                title = "Bật hiển thị Liquid Glass Banner",
                subtitle = "Công tắc tổng bật/tắt thông báo nổi",
                prefKey = AppPrefs.KEY_MASTER_ENABLED,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        settingsCard.addView(
            buildSwitchRow(
                title = "Nhận thông báo từ tất cả ứng dụng",
                subtitle = "Tắt công tắc này nếu bạn chỉ muốn hiện thông báo cho các ứng dụng đã tích chọn ở dưới",
                prefKey = AppPrefs.KEY_TRACK_ALL_APPS,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        settingsCard.addView(
            buildSwitchRow(
                title = "Bỏ qua thanh phát nhạc & tiến trình hệ thống",
                subtitle = "Chỉ hiện thông báo tin nhắn và cảnh báo thực sự",
                prefKey = AppPrefs.KEY_IGNORE_ONGOING,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        settingsCard.addView(
            buildSwitchRow(
                title = "Rung phản hồi nhẹ (Haptic)",
                subtitle = "Rung nhẹ 15ms khi thông báo bung ra",
                prefKey = AppPrefs.KEY_VIBRATE_ENABLED,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        // Theme selector
        settingsCard.addView(TextView(this).apply {
            text = "Chất liệu mặt kính (Optical Glass Shader)"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(10), 0, dp(8))
        })

        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val themes = listOf("Kính Trong Suốt (Giống Video 100%)" to 0, "Kính Tối Frost" to 1)
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
                    prefs.edit().putInt(AppPrefs.KEY_THEME_INDEX, idx).apply()
                    updateThemeSelection(idx)
                    playSingleTechVerseBanner("Hi")
                }
            }
            themeButtons.add(chip)
            themeRow.addView(
                chip,
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    if (idx == 0) rightMargin = dp(6)
                }
            )
        }
        updateThemeSelection(prefs.getInt(AppPrefs.KEY_THEME_INDEX, 0))
        settingsCard.addView(themeRow)

        // Duration selector
        settingsCard.addView(TextView(this).apply {
            text = "Thời gian hiển thị Banner"
            textSize = 13f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, dp(14), 0, dp(8))
        })

        val durationRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val durations = listOf("3.5 giây" to 3500L, "5.5 giây" to 5500L, "8.0 giây" to 8000L)
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
                    prefs.edit().putLong(AppPrefs.KEY_BANNER_DURATION_MS, ms).apply()
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
        updateDurationSelection(prefs.getLong(AppPrefs.KEY_BANNER_DURATION_MS, 5500L))
        settingsCard.addView(durationRow)

        content.addView(settingsCard, topMargin(14))

        // 3. Recent Notification History Card
        val historyCard = createCard()
        val historyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        historyHeader.addView(
            sectionHeading("NHẬT KÝ THÔNG BÁO ĐÃ BẮT ĐƯỢC").apply { setPadding(0, 0, 0, 0) },
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
            sectionHeading("DANH SÁCH ỨNG DỤNG ĐÃ CHỌN").apply { setPadding(0, 0, 0, 0) },
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
            text = "Mọi ứng dụng được tích chọn bên dưới đều được lưu tức thì vào bộ nhớ dịch vụ."
            textSize = 12.5f
            setTextColor(SUBTEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(10))
        })

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
                    val current = AppPrefs.getSelectedPackages(this@MainActivity).toMutableSet()
                    currentFilteredApps().forEach { current.add(it.packageName) }
                    AppPrefs.setAllPackagesSelected(this@MainActivity, current)
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
                    AppPrefs.setAllPackagesSelected(this@MainActivity, emptyList())
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

        setContentView(root)
        updateSelectedBadge()
    }

    /**
     * Reconstructs the exact Weather ("Mandalay 28°") and Calendar ("OCTOBER") widget stage
     * from 90047.mp4 at the top of the screen so when the notification banner expands over it,
     * the real glass blur (`FLAG_BLUR_BEHIND`) and translucency can be seen over dark & white cards!
     */
    private fun buildVideoBackdropWidgets(): LinearLayout {
        val stage = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(4))
        }

        // Left Widget: Weather ("Mandalay 28° Cloudy H:32° L:26°")
        val weatherCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(48, 53, 63), 22, Color.rgb(66, 72, 85))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        weatherCard.addView(TextView(this).apply {
            text = "Mandalay ↗"
            textSize = 13.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        weatherCard.addView(TextView(this).apply {
            text = "28°"
            textSize = 34f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(6))
        })
        weatherCard.addView(TextView(this).apply {
            text = "☁ Cloudy\nH:32° L:26°"
            textSize = 12f
            setTextColor(Color.rgb(225, 230, 240))
        })

        // Right Widget: Calendar ("OCTOBER")
        val calendarCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(Color.rgb(246, 244, 245), 22)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        calendarCard.addView(TextView(this).apply {
            text = "OCTOBER"
            textSize = 11.5f
            letterSpacing = 0.08f
            setTextColor(Color.rgb(212, 38, 46))
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(4))
        })
        calendarCard.addView(TextView(this).apply {
            text = "S   M   T   W   T   F   S\n              1   2   3\n4   5   6   7   8   9  10\n11  12  13  14  15  16  17\n18  19  20  21  22  23  24"
            textSize = 10.5f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
            setTextColor(Color.rgb(32, 32, 36))
        })

        stage.addView(
            weatherCard,
            LinearLayout.LayoutParams(0, dp(142), 1f).apply { rightMargin = dp(10) }
        )
        stage.addView(
            calendarCard,
            LinearLayout.LayoutParams(0, dp(142), 1f)
        )
        return stage
    }

    private fun playExactVideoDemoSequence() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                "Vui lòng cấp quyền 'Hiển thị nổi (Overlay)' ở mục số 2 trước!",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        mainHandler.removeCallbacksAndMessages(null)
        playSingleTechVerseBanner("Hi")
        mainHandler.postDelayed({
            playSingleTechVerseBanner("Hello")
        }, 2800L)
    }

    private fun playHomeScreenDemoSequence() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Vui lòng cấp quyền Overlay trước!", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(this, "Đang ra Màn hình chính... Banner sẽ xuất hiện sau 2 giây!", Toast.LENGTH_SHORT).show()
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(homeIntent)
        mainHandler.postDelayed({
            playSingleTechVerseBanner("Hi")
        }, 1800L)
        mainHandler.postDelayed({
            playSingleTechVerseBanner("Hello")
        }, 4600L)
    }

    private fun playSingleTechVerseBanner(messageText: String) {
        if (!Settings.canDrawOverlays(this)) return
        val avatarPx = dp(46)
        val badgePx = dp(20)
        val sample = BannerData(
            packageName = packageName,
            appName = "TikTok",
            title = "TechVerse",
            body = messageText,
            postTime = System.currentTimeMillis(),
            largeIcon = DemoIcons.createTechVerseAvatar(resources, avatarPx),
            appBadgeIcon = DemoIcons.createTikTokBadge(resources, badgePx),
            timeLabel = "now"
        )
        NotificationHistoryStore.record(sample)
        refreshHistoryList()
        OverlayManager.get(this).show(sample)
    }

    private fun sendRealSystemTestNotification() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Hãy cấp quyền Overlay trước!", Toast.LENGTH_SHORT).show()
            return
        }
        NotificationAccessService.requestSafeRebind(this)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "digitalnotify_test_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "DigitalNotify Test",
                NotificationManager.IMPORTANCE_HIGH
            )
            nm.createNotificationChannel(channel)
        }

        val extras = Bundle().apply {
            putBoolean(NotificationAccessService.EXTRA_IS_TEST_NOTIFICATION, true)
        }

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }

        val notif = builder
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("TechVerse")
            .setContentText("Hello — Thông báo hệ thống thật đã hoạt động!")
            .setExtras(extras)
            .setAutoCancel(true)
            .build()

        try {
            nm.notify((System.currentTimeMillis() % 10000).toInt() + 100, notif)
        } catch (_: Exception) {
        }
        mainHandler.postDelayed({
            if (NotificationHistoryStore.list().none { it.body.contains("hệ thống thật") }) {
                playSingleTechVerseBanner("Hello")
            } else {
                refreshHistoryList()
            }
        }, 500L)
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
        val selected = AppPrefs.getSelectedPackages(this)
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

        val selected = AppPrefs.getSelectedPackages(this)
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
                    AppPrefs.setPackageSelected(this@MainActivity, app.packageName, checked)
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
                text = "Chưa có thông báo nào trong phiên này."
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
                    OverlayManager.get(this@MainActivity).show(
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
        val trackAll = AppPrefs.isTrackAllApps(this)
        val count = AppPrefs.getSelectedPackages(this).size
        selectedCountBadge.text = if (trackAll) "Tất cả (+$count đã ghim)" else "Đã chọn: $count"
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

        notificationStatus.text = if (hasNotificationAccess) {
            "● Đã bật quyền đọc thông báo (Notification Access)"
        } else {
            "○ Chưa bật quyền đọc thông báo"
        }
        notificationStatus.setTextColor(if (hasNotificationAccess) SUCCESS else WARNING)

        serviceBindStatus.text = when {
            !hasNotificationAccess -> "○ Dịch vụ ngầm: Đang chờ cấp quyền Notification Access"
            NotificationAccessService.isConnected -> "● Dịch vụ ngầm: ĐANG KẾT NỐI & LẮNG NGHE TỨC THÌ"
            else -> "⚠️ Quyền đã bật nhưng hệ thống chưa kết nối (Hãy nhấn 'Làm mới sâu kết nối' hoặc Tắt/Bật lại quyền)"
        }
        serviceBindStatus.setTextColor(
            if (hasNotificationAccess && NotificationAccessService.isConnected) SUCCESS else WARNING
        )

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
            "○ Đang bật tiết kiệm pin (Nên tắt để không bị đóng ngầm)"
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
                prefs.edit().putBoolean(prefKey, isChecked).commit()
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
            setPadding(0, dp(5), 0, dp(4))
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
                topMargin = dp(5)
                bottomMargin = dp(5)
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
