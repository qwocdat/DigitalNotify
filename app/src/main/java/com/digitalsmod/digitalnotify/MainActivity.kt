package com.digitalsmod.digitalnotify

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
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

    private lateinit var rootFrame: FrameLayout
    private var appListContainer: LinearLayout? = null
    private var selectedCountBadge: TextView? = null
    private var notifStatusDot: TextView? = null
    private var overlayStatusDot: TextView? = null

    private var cachedApps: List<AppEntry> = emptyList()
    private var isLoadingApps: Boolean = true
    private var searchQuery: String = ""
    private var onboardingStep: Int = 0
    private var waitingForTikTokReturn: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        OverlayManager.get(this)
        NotificationAccessService.requestSafeRebind(this)

        rootFrame = FrameLayout(this).apply {
            setBackgroundColor(IOS_BG)
        }
        setContentView(rootFrame)

        loadInstalledAppsAsync()

        if (AppPrefs.isOnboardingDone(this)) {
            renderMainScreen()
        } else {
            onboardingStep = 0
            renderOnboardingScreen()
        }
    }

    override fun onResume() {
        super.onResume()
        NotificationAccessService.requestSafeRebind(this)

        // If user clicked the TikTok follow/like button in Onboarding and just returned to the app,
        // automatically complete onboarding and enter the main iOS 27 screen!
        if (waitingForTikTokReturn) {
            waitingForTikTokReturn = false
            prefs.edit().putBoolean(AppPrefs.KEY_TIKTOK_VISITED, true).commit()
            AppPrefs.setOnboardingDone(this, true)
            Toast.makeText(
                this,
                tr("Cảm ơn bạn đã ủng hộ Admin! Chào mừng vào ứng dụng.", "Thank you for supporting Admin! Welcome."),
                Toast.LENGTH_SHORT
            ).show()
            renderMainScreen()
            return
        }

        if (AppPrefs.isOnboardingDone(this)) {
            refreshStatusPills()
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        bgExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun isVi(): Boolean = AppPrefs.getLang(this) == "vi"

    private fun tr(vi: String, en: String): String = if (isVi()) vi else en

    // =========================================================================
    // ONBOARDING FLOW (iOS 27 Style: Language -> Guide & Condition -> TikTok Unlock)
    // =========================================================================
    private fun renderOnboardingScreen() {
        rootFrame.removeAllViews()

        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
        }
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(54), dp(24), dp(36))
        }
        rootFrame.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val top = insets.systemWindowInsetTop.coerceAtLeast(dp(24))
            @Suppress("DEPRECATION")
            val bot = insets.systemWindowInsetBottom.coerceAtLeast(dp(24))
            container.setPadding(dp(24), top + dp(24), dp(24), bot + dp(24))
            insets
        }

        // Top iOS 27 Liquid Glass Hero Orb
        val orb = TextView(this).apply {
            text = "✦"
            textSize = 34f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.argb(210, 88, 166, 255), Color.argb(140, 140, 92, 255))
            ).apply {
                cornerRadius = dp(38).toFloat()
                setStroke(dp(1), Color.argb(180, 255, 255, 255))
            }
        }
        container.addView(orb, LinearLayout.LayoutParams(dp(76), dp(76)).apply {
            bottomMargin = dp(18)
        })

        when (onboardingStep) {
            0 -> buildOnboardingStepLanguage(container)
            1 -> buildOnboardingStepCondition(container)
            else -> buildOnboardingStepTikTok(container)
        }

        scroll.addView(container, FrameLayout.LayoutParams(-1, -2))
        rootFrame.addView(scroll, FrameLayout.LayoutParams(-1, -1))
    }

    private fun buildOnboardingStepLanguage(container: LinearLayout) {
        container.addView(TextView(this).apply {
            text = "DigitalNotify · iOS 27"
            textSize = 13f
            letterSpacing = 0.14f
            setTextColor(IOS_ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })

        container.addView(TextView(this).apply {
            text = tr("Chọn Ngôn Ngữ\nChoose Language", "Choose Language\nChọn Ngôn Ngữ")
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(10), 0, dp(10))
        })

        container.addView(TextView(this).apply {
            text = tr(
                "Trải nghiệm thông báo kính lỏng iOS 27 mượt mà trên điện thoại của bạn.",
                "Experience smooth iOS 27 Liquid Glass notifications on your phone."
            )
            textSize = 14.5f
            gravity = Gravity.CENTER
            setTextColor(IOS_SUBTEXT)
            setPadding(dp(8), 0, dp(8), dp(24))
        })

        val langCard = iosGlassCard()
        langCard.addView(
            LiquidGlassButton(this, "🇻🇳  Tiếng Việt", isPrimary = isVi()) {
                AppPrefs.setLang(this, "vi")
                onboardingStep = 1
                renderOnboardingScreen()
            },
            LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(12) }
        )
        langCard.addView(
            LiquidGlassButton(this, "🇺🇸  English", isPrimary = !isVi()) {
                AppPrefs.setLang(this, "en")
                onboardingStep = 1
                renderOnboardingScreen()
            },
            LinearLayout.LayoutParams(-1, dp(52))
        )
        container.addView(langCard, LinearLayout.LayoutParams(-1, -2))
    }

    private fun buildOnboardingStepCondition(container: LinearLayout) {
        container.addView(TextView(this).apply {
            text = tr("Hướng dẫn tối ưu", "Optimization Guide")
            textSize = 27f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(8))
        })

        container.addView(TextView(this).apply {
            text = tr(
                "Thiết lập nhanh trong 3 bước để thông báo kính lỏng hoạt động hoàn hảo.",
                "Quick 3-step setup so Liquid Glass notifications work seamlessly."
            )
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(IOS_SUBTEXT)
            setPadding(0, 0, 0, dp(18))
        })

        val guideCard = iosGlassCard()

        guideCard.addView(guideItem(
            "1",
            tr("Cấp quyền Thông báo & Hiển thị nổi", "Grant Notification & Overlay Access"),
            tr("Cho phép ứng dụng nhận tin nhắn mới và hiển thị thanh kính nổi trên màn hình.", "Allow the app to receive new messages and draw the floating glass banner.")
        ))

        guideCard.addView(
            LiquidGlassButton(this, tr("Bật quyền đọc thông báo", "Enable Notification Access")) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6); bottomMargin = dp(8) }
        )

        guideCard.addView(
            LiquidGlassButton(this, tr("Bật quyền hiển thị nổi", "Enable Floating Overlay")) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            },
            LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(14) }
        )

        guideCard.addView(guideItem(
            "2",
            tr("Lưu ý quan trọng để tối ưu", "Important Optimization Note"),
            tr(
                "Để không bị hiện trùng 2 thông báo cùng lúc, ứng dụng sẽ tự động ẩn thông báo mặc định và bạn nên tắt thông báo nổi (Pop-up) của các ứng dụng đã chọn.",
                "To avoid duplicate pop-ups, the app automatically hides default alerts and you should disable default pop-up banners for selected apps."
            )
        ))

        var accepted = prefs.getBoolean(AppPrefs.KEY_CONDITION_ACCEPTED, false)
        val confirmRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(4), dp(12), dp(4), dp(6))
        }
        val confirmLabel = TextView(this).apply {
            text = tr(
                "Tôi xác nhận nên tắt thông báo nổi mặc định của các app đã chọn để tối ưu sử dụng",
                "I confirm disabling default pop-up alerts for selected apps for optimal use"
            )
            textSize = 13.5f
            setTextColor(Color.WHITE)
            setPadding(0, 0, dp(10), 0)
        }
        val confirmSwitch = LiquidGlassSwitch(this, accepted) { isChecked ->
            accepted = isChecked
            prefs.edit().putBoolean(AppPrefs.KEY_CONDITION_ACCEPTED, isChecked).commit()
        }
        confirmRow.addView(confirmLabel, LinearLayout.LayoutParams(0, -2, 1f))
        confirmRow.addView(confirmSwitch)
        guideCard.addView(confirmRow)

        container.addView(guideCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(16) })

        container.addView(
            LiquidGlassButton(
                this,
                tr("Xác nhận & Tiếp tục ➔", "Confirm & Continue ➔"),
                isPrimary = true
            ) {
                if (!accepted) {
                    Toast.makeText(
                        this,
                        tr("Vui lòng bật nút xác nhận điều kiện tối ưu ở trên để tiếp tục!", "Please toggle the confirmation switch above to continue!"),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@LiquidGlassButton
                }
                onboardingStep = 2
                renderOnboardingScreen()
            },
            LinearLayout.LayoutParams(-1, dp(52))
        )
    }

    private fun buildOnboardingStepTikTok(container: LinearLayout) {
        container.addView(TextView(this).apply {
            text = tr("Ủng hộ Admin ❤️", "Support Admin ❤️")
            textSize = 28f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(6), 0, dp(8))
        })

        container.addView(TextView(this).apply {
            text = tr(
                "Ứng dụng được chia sẻ hoàn toàn miễn phí. Hãy nhấn nút bên dưới để Follow và thả Tim video ủng hộ Admin @digitalxiaoiu5 trên TikTok.\n\nSau khi chuyển qua TikTok rồi quay lại đây, ứng dụng sẽ tự động mở khoá!",
                "This app is completely free. Tap the button below to Follow and Like Admin @digitalxiaoiu5 on TikTok.\n\nWhen you return to this app, it will unlock automatically!"
            )
            textSize = 14.5f
            gravity = Gravity.CENTER
            setLineSpacing(dp(3).toFloat(), 1f)
            setTextColor(IOS_SUBTEXT)
            setPadding(dp(4), 0, dp(4), dp(22))
        })

        val card = iosGlassCard()
        card.addView(TextView(this).apply {
            text = "TikTok · @digitalxiaoiu5"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(4), 0, dp(14))
        })

        card.addView(
            LiquidGlassButton(
                this,
                tr("🎵 Mở TikTok Follow & Tim ủng hộ Admin", "🎵 Open TikTok to Follow & Like Admin"),
                isPrimary = true
            ) {
                waitingForTikTokReturn = true
                openAdminTikTok()
            },
            LinearLayout.LayoutParams(-1, dp(54))
        )

        container.addView(card, LinearLayout.LayoutParams(-1, -2))
    }

    private fun openAdminTikTok() {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(AppPrefs.ADMIN_TIKTOK_URL)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (_: Exception) {
            waitingForTikTokReturn = false
            AppPrefs.setOnboardingDone(this, true)
            renderMainScreen()
        }
    }

    // =========================================================================
    // MAIN SCREEN (iOS 27 Consumer Design — Clean, No Dev Jargon, Liquid Buttons)
    // =========================================================================
    private fun renderMainScreen() {
        rootFrame.removeAllViews()

        val scrollView = ScrollView(this).apply {
            clipToPadding = false
            isFillViewport = true
        }

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(28), dp(18), dp(36))
        }

        rootFrame.setOnApplyWindowInsetsListener { _, insets ->
            @Suppress("DEPRECATION")
            val topInset = insets.systemWindowInsetTop.coerceAtLeast(dp(16))
            @Suppress("DEPRECATION")
            val bottomInset = insets.systemWindowInsetBottom.coerceAtLeast(dp(16))
            content.setPadding(dp(18), topInset + dp(14), dp(18), bottomInset + dp(24))
            insets
        }

        scrollView.addView(
            content,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        )
        rootFrame.addView(scrollView, FrameLayout.LayoutParams(-1, -1))

        // Top Bar: Title + Language Pill
        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        titleCol.addView(TextView(this).apply {
            text = "iOS 27 LIQUID GLASS"
            textSize = 11f
            letterSpacing = 0.16f
            setTextColor(IOS_ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        titleCol.addView(TextView(this).apply {
            text = "DigitalNotify"
            textSize = 30f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        topBar.addView(titleCol, LinearLayout.LayoutParams(0, -2, 1f))

        val langBtn = LiquidGlassButton(this, if (isVi()) "🇻🇳 VI" else "🇺🇸 EN") {
            AppPrefs.setLang(this, if (isVi()) "en" else "vi")
            renderMainScreen()
        }
        topBar.addView(langBtn, LinearLayout.LayoutParams(dp(86), dp(42)))
        content.addView(topBar, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // Preview Banner Button (Liquid Glass Button)
        content.addView(
            LiquidGlassButton(
                this,
                tr("✨ Xem thử hiệu ứng thông báo", "✨ Preview Notification Effect"),
                isPrimary = true
            ) {
                playExactVideoDemoSequence()
            },
            LinearLayout.LayoutParams(-1, dp(52)).apply { bottomMargin = dp(14) }
        )

        // 1. Permission Status Card (Simple user-friendly pills)
        val statusCard = iosGlassCard()
        statusCard.addView(sectionTitle(tr("TRẠNG THÁI HOẠT ĐỘNG", "STATUS")))

        notifStatusDot = TextView(this).apply {
            textSize = 13.5f
            setPadding(0, dp(4), 0, dp(4))
        }
        overlayStatusDot = TextView(this).apply {
            textSize = 13.5f
            setPadding(0, dp(4), 0, dp(10))
        }
        statusCard.addView(notifStatusDot)
        statusCard.addView(overlayStatusDot)

        val permBtnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        permBtnRow.addView(
            LiquidGlassButton(this, tr("Quyền Thông báo", "Notifications")) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) }
        )
        permBtnRow.addView(
            LiquidGlassButton(this, tr("Quyền Hiển thị nổi", "Overlay")) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            },
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )
        statusCard.addView(permBtnRow)
        content.addView(statusCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // 2. Display & Smart Replacement Settings Card
        val settingsCard = iosGlassCard()
        settingsCard.addView(sectionTitle(tr("TUỲ CHỈNH HIỂN THỊ", "DISPLAY SETTINGS")))

        settingsCard.addView(
            buildLiquidSwitchRow(
                title = tr("Bật thông báo Liquid Glass", "Enable Liquid Glass Alerts"),
                subtitle = tr("Hiển thị thông báo nổi phong cách iOS 27", "Show floating iOS 27 glass notifications"),
                prefKey = AppPrefs.KEY_MASTER_ENABLED,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        settingsCard.addView(
            buildLiquidSwitchRow(
                title = tr("Thay thế thông báo mặc định của máy", "Replace Default System Pop-ups"),
                subtitle = tr(
                    "Tự động ẩn thông báo gốc để chỉ hiện duy nhất thanh kính lỏng của DigitalNotify",
                    "Automatically hide original alerts so only DigitalNotify's glass banner appears"
                ),
                prefKey = AppPrefs.KEY_SUPPRESS_SYSTEM_NOTIF,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        settingsCard.addView(
            buildLiquidSwitchRow(
                title = tr("Rung phản hồi nhẹ", "Haptic Feedback"),
                subtitle = tr("Rung nhẹ khi có tin nhắn mới đến", "Subtle vibration when a new message arrives"),
                prefKey = AppPrefs.KEY_VIBRATE_ENABLED,
                defaultValue = true
            )
        )
        settingsCard.addView(dividerView())

        // Glass Style Selector
        settingsCard.addView(TextView(this).apply {
            text = tr("Màu mặt kính", "Glass Appearance")
            textSize = 14f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, dp(12), 0, dp(8))
        })

        val themeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val currentTheme = prefs.getInt(AppPrefs.KEY_THEME_INDEX, 0)
        themeRow.addView(
            LiquidGlassButton(
                this,
                tr("Kính Trắng Sương", "Light Frost"),
                isPrimary = currentTheme == 0
            ) {
                prefs.edit().putInt(AppPrefs.KEY_THEME_INDEX, 0).commit()
                renderMainScreen()
                playSingleTechVerseBanner("Hi")
            },
            LinearLayout.LayoutParams(0, dp(46), 1f).apply { rightMargin = dp(8) }
        )
        themeRow.addView(
            LiquidGlassButton(
                this,
                tr("Kính Đen Khói", "Dark Smoke"),
                isPrimary = currentTheme == 1
            ) {
                prefs.edit().putInt(AppPrefs.KEY_THEME_INDEX, 1).commit()
                renderMainScreen()
                playSingleTechVerseBanner("Hi")
            },
            LinearLayout.LayoutParams(0, dp(46), 1f)
        )
        settingsCard.addView(themeRow)

        content.addView(settingsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // 3. Selected Apps Card
        val appsCard = iosGlassCard()
        val appsHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(6))
        }
        appsHeader.addView(
            sectionTitle(tr("ỨNG DỤNG NHẬN THÔNG BÁO", "SELECTED APPS")).apply { setPadding(0, 0, 0, 0) },
            LinearLayout.LayoutParams(0, -2, 1f)
        )
        selectedCountBadge = TextView(this).apply {
            textSize = 12f
            setTextColor(IOS_ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }
        appsHeader.addView(selectedCountBadge)
        appsCard.addView(appsHeader)

        appsCard.addView(TextView(this).apply {
            text = tr(
                "Bật các ứng dụng bạn muốn nhận thông báo kính lỏng. Nhấn vào biểu tượng ⚙️ cạnh ứng dụng nếu muốn tắt chuông/thông báo nổi mặc định của ứng dụng đó.",
                "Toggle the apps you want Liquid Glass alerts for. Tap ⚙️ next to an app to silence its default system pop-up."
            )
            textSize = 13f
            setTextColor(IOS_SUBTEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })

        // Search input
        val searchBox = EditText(this).apply {
            hint = tr("Tìm kiếm ứng dụng...", "Search apps...")
            setText(searchQuery)
            textSize = 14f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.argb(120, 255, 255, 255))
            setPadding(dp(16), 0, dp(16), 0)
            background = GradientDrawable().apply {
                setColor(Color.argb(45, 255, 255, 255))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.argb(60, 255, 255, 255))
            }
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
        appsCard.addView(searchBox, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(10) })

        // Quick Select All / Clear row
        val bulkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        bulkRow.addView(
            LiquidGlassButton(this, tr("Bật tất cả", "Select All")) {
                val allUserPkgs = cachedApps.filter { it.isUserApp }.map { it.packageName }
                AppPrefs.setAllPackagesSelected(this, allUserPkgs)
                renderAppList()
            },
            LinearLayout.LayoutParams(0, dp(42), 1f).apply { rightMargin = dp(8) }
        )
        bulkRow.addView(
            LiquidGlassButton(this, tr("Tắt tất cả", "Clear All")) {
                AppPrefs.setAllPackagesSelected(this, emptyList())
                renderAppList()
            },
            LinearLayout.LayoutParams(0, dp(42), 1f)
        )
        appsCard.addView(bulkRow, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })

        appListContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        appsCard.addView(appListContainer)
        content.addView(appsCard, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(14) })

        // 4. Support Admin Card at bottom
        val supportCard = iosGlassCard()
        supportCard.addView(
            LiquidGlassButton(
                this,
                tr("❤️ Ủng hộ Admin trên TikTok (@digitalxiaoiu5)", "❤️ Support Admin on TikTok (@digitalxiaoiu5)")
            ) {
                openAdminTikTok()
            },
            LinearLayout.LayoutParams(-1, dp(48))
        )
        content.addView(supportCard, LinearLayout.LayoutParams(-1, -2))

        refreshStatusPills()
        renderAppList()
    }

    private fun refreshStatusPills() {
        val listenerComponent = ComponentName(this, NotificationAccessService::class.java).flattenToString()
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val hasNotif = enabledListeners?.split(':')?.any { it.equals(listenerComponent, ignoreCase = true) } == true
        val hasOverlay = Settings.canDrawOverlays(this)

        notifStatusDot?.apply {
            text = if (hasNotif) {
                tr("● Quyền đọc thông báo: Đã bật", "● Notification Access: Active")
            } else {
                tr("○ Quyền đọc thông báo: Chưa bật", "○ Notification Access: Off")
            }
            setTextColor(if (hasNotif) IOS_GREEN else IOS_ORANGE)
        }

        overlayStatusDot?.apply {
            text = if (hasOverlay) {
                tr("● Quyền hiển thị nổi: Đã bật", "● Floating Overlay: Active")
            } else {
                tr("○ Quyền hiển thị nổi: Chưa bật", "○ Floating Overlay: Off")
            }
            setTextColor(if (hasOverlay) IOS_GREEN else IOS_ORANGE)
        }
    }

    private fun loadInstalledAppsAsync() {
        isLoadingApps = true
        bgExecutor.execute {
            val loaded = try {
                @Suppress("DEPRECATION")
                packageManager.getInstalledApplications(0)
                    .asSequence()
                    .filter { it.packageName != packageName && it.packageName != "android" && it.packageName != "com.android.systemui" }
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
                // Auto-select installed user apps on first install so notifications work immediately!
                if (AppPrefs.getSelectedPackages(this).isEmpty() && loaded.isNotEmpty()) {
                    val defaults = loaded.filter { it.isUserApp }.map { it.packageName }
                    AppPrefs.setAllPackagesSelected(this, defaults)
                }
                renderAppList()
            }
        }
    }

    private fun renderAppList() {
        val container = appListContainer ?: return
        container.removeAllViews()

        val selected = AppPrefs.getSelectedPackages(this)
        selectedCountBadge?.text = tr("Đã bật: ${selected.size}", "Active: ${selected.size}")

        if (isLoadingApps) {
            container.addView(TextView(this).apply {
                text = tr("Đang tải danh sách ứng dụng...", "Loading apps...")
                textSize = 13.5f
                setTextColor(IOS_SUBTEXT)
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }

        val filtered = cachedApps.filter { app ->
            searchQuery.isBlank() || app.label.contains(searchQuery, ignoreCase = true)
        }.sortedWith(
            compareByDescending<AppEntry> { it.packageName in selected }
                .thenByDescending { it.isUserApp }
                .thenBy { it.label.lowercase() }
        )

        if (filtered.isEmpty()) {
            container.addView(TextView(this).apply {
                text = tr("Không tìm thấy ứng dụng phù hợp.", "No matching apps found.")
                textSize = 13.5f
                setTextColor(IOS_SUBTEXT)
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }

        filtered.forEachIndexed { index, app ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(9), dp(2), dp(9))
            }

            val iconView = ImageView(this).apply {
                setImageDrawable(app.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(iconView, LinearLayout.LayoutParams(dp(40), dp(40)))

            val titleTv = TextView(this).apply {
                text = app.label
                textSize = 15f
                setTextColor(Color.WHITE)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(12), 0, dp(8), 0)
            }
            row.addView(titleTv, LinearLayout.LayoutParams(0, -2, 1f))

            // Quick gear shortcut to open system Notification settings for this specific app
            val gearBtn = TextView(this).apply {
                text = "⚙️"
                textSize = 15f
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(10), dp(6))
                setOnClickListener {
                    openAppNotificationSettings(app.packageName)
                }
            }
            row.addView(gearBtn)

            val sw = LiquidGlassSwitch(this, app.packageName in selected) { checked ->
                AppPrefs.setPackageSelected(this, app.packageName, checked)
                val count = AppPrefs.getSelectedPackages(this).size
                selectedCountBadge?.text = tr("Đã bật: $count", "Active: $count")
            }
            row.addView(sw)

            row.setOnClickListener {
                sw.setCheckedProgrammatically(!sw.isChecked, notify = true)
            }

            container.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < filtered.lastIndex) {
                container.addView(dividerView())
            }
        }
    }

    private fun openAppNotificationSettings(pkg: String) {
        try {
            val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                putExtra(Settings.EXTRA_APP_PACKAGE, pkg)
            }
            startActivity(intent)
        } catch (_: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:$pkg")
                    )
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun playExactVideoDemoSequence() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(
                this,
                tr("Vui lòng bật 'Quyền Hiển thị nổi' trước!", "Please enable 'Floating Overlay' permission first!"),
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
        OverlayManager.get(this).show(sample)
    }

    private fun buildLiquidSwitchRow(
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
            setPadding(0, 0, dp(12), 0)
        }
        textCol.addView(TextView(this).apply {
            text = title
            textSize = 14.5f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        textCol.addView(TextView(this).apply {
            text = subtitle
            textSize = 12f
            setTextColor(IOS_SUBTEXT)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(textCol, LinearLayout.LayoutParams(0, -2, 1f))

        val sw = LiquidGlassSwitch(this, prefs.getBoolean(prefKey, defaultValue)) { checked ->
            prefs.edit().putBoolean(prefKey, checked).commit()
        }
        row.addView(sw)
        row.setOnClickListener {
            sw.setCheckedProgrammatically(!sw.isChecked, notify = true)
        }
        return row
    }

    private fun guideItem(stepNum: String, title: String, desc: String): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, dp(6))
        }
        val badge = TextView(this).apply {
            text = stepNum
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.argb(160, 64, 140, 255))
            }
        }
        row.addView(badge, LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(2) })

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, 0, 0)
        }
        col.addView(TextView(this).apply {
            text = title
            textSize = 15f
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        })
        col.addView(TextView(this).apply {
            text = desc
            textSize = 13f
            setTextColor(IOS_SUBTEXT)
            setLineSpacing(dp(2).toFloat(), 1f)
            setPadding(0, dp(2), 0, 0)
        })
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun iosGlassCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    Color.argb(58, 255, 255, 255),
                    Color.argb(28, 180, 210, 255)
                )
            ).apply {
                cornerRadius = dp(26).toFloat()
                setStroke(dp(1), Color.argb(85, 255, 255, 255))
            }
        }
    }

    private fun sectionTitle(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 11.5f
            letterSpacing = 0.12f
            setTextColor(IOS_ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            setPadding(0, 0, 0, dp(8))
        }
    }

    private fun dividerView(): View {
        return View(this).apply {
            setBackgroundColor(Color.argb(36, 255, 255, 255))
            layoutParams = LinearLayout.LayoutParams(-1, dp(1))
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    companion object {
        private val IOS_BG = Color.rgb(7, 11, 20)
        private val IOS_ACCENT = Color.rgb(112, 184, 255)
        private val IOS_SUBTEXT = Color.rgb(182, 194, 216)
        private val IOS_GREEN = Color.rgb(88, 232, 156)
        private val IOS_ORANGE = Color.rgb(255, 190, 92)
    }
}
