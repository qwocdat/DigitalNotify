package com.digitalsmod.digitalnotify

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat

class MainActivity : Activity() {

    private val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    private lateinit var notificationStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var appList: LinearLayout
    private lateinit var searchInput: EditText

    private var installedApps: List<ApplicationInfo> = emptyList()
    private var searchQuery: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.rgb(8, 10, 15)
        window.navigationBarColor = Color.rgb(8, 10, 15)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        buildScreen()
        loadInstalledApps()
        renderAppList()
        refreshPermissionStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::notificationStatus.isInitialized) refreshPermissionStatus()
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
            setPadding(dp(20), dp(22), dp(20), dp(30))
        }

        scrollView.addView(
            content,
            ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT
            )
        )
        root.addView(scrollView, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "DIGITALNOTIFY"
            textSize = 12f
            letterSpacing = 0.22f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        })

        content.addView(TextView(this).apply {
            text = "Notifications,\nreimagined."
            textSize = 33f
            setLineSpacing(dp(1).toFloat(), 0.96f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            setPadding(0, dp(9), 0, dp(8))
        })

        content.addView(TextView(this).apply {
            text = "Theo dõi thông báo từ ứng dụng bạn chọn và hiển thị chúng bằng banner nổi phong cách Liquid Glass."
            textSize = 14f
            setTextColor(SUBTEXT)
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        val permissionsCard = createCard()
        permissionsCard.addView(sectionHeading("QUYỀN TRUY CẬP"))

        notificationStatus = statusLine()
        overlayStatus = statusLine()

        permissionsCard.addView(notificationStatus)
        permissionsCard.addView(actionButton("Cấp quyền đọc thông báo") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        permissionsCard.addView(overlayStatus)
        permissionsCard.addView(actionButton("Cho phép hiển thị nổi") {
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
        })

        content.addView(permissionsCard, topMargin(22))

        val appsCard = createCard()
        appsCard.addView(sectionHeading("ỨNG DỤNG ĐƯỢC THEO DÕI"))

        appsCard.addView(TextView(this).apply {
            text = "Chọn ứng dụng muốn hiển thị banner. Chỉ các ứng dụng được chọn mới được xử lý."
            textSize = 13f
            setTextColor(SUBTEXT)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })

        searchInput = EditText(this).apply {
            hint = "Tìm ứng dụng..."
            textSize = 14f
            setSingleLine(true)
            setTextColor(Color.WHITE)
            setHintTextColor(MUTED)
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(24, 28, 38), 15)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence?, start: Int, count: Int, after: Int
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence?, start: Int, before: Int, count: Int
                ) {
                    searchQuery = s?.toString().orEmpty()
                    renderAppList()
                }

                override fun afterTextChanged(s: Editable?) = Unit
            })
        }

        appsCard.addView(
            searchInput,
            LinearLayout.LayoutParams(-1, dp(48)).apply {
                bottomMargin = dp(10)
            }
        )

        appList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        appsCard.addView(appList)
        content.addView(appsCard, topMargin(14))

        content.addView(TextView(this).apply {
            text = "DIGITALNOTIFY · 0.1.0\nAndroid 8 trở lên. Ứng dụng chỉ nhận nội dung Android cung cấp trong thông báo, không đọc cơ sở dữ liệu riêng của ứng dụng khác."
            textSize = 11f
            setTextColor(MUTED)
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(2), dp(18), dp(2), 0)
        })

        setContentView(root)
    }

    private fun loadInstalledApps() {
        installedApps = try {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
                .asSequence()
                .filter { it.packageName != packageName }
                .filter { info ->
                    val isUserApp =
                        (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    val hasLauncher =
                        packageManager.getLaunchIntentForPackage(info.packageName) != null
                    isUserApp || hasLauncher
                }
                .sortedBy {
                    packageManager.getApplicationLabel(it).toString().lowercase()
                }
                .toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun renderAppList() {
        if (!::appList.isInitialized) return

        appList.removeAllViews()
        val filteredApps = installedApps.filter { info ->
            val label = packageManager.getApplicationLabel(info).toString()
            label.contains(searchQuery, ignoreCase = true) ||
                info.packageName.contains(searchQuery, ignoreCase = true)
        }

        if (filteredApps.isEmpty()) {
            appList.addView(TextView(this).apply {
                text = if (installedApps.isEmpty()) {
                    "Không thể tải danh sách ứng dụng."
                } else {
                    "Không tìm thấy ứng dụng."
                }
                textSize = 13f
                setTextColor(SUBTEXT)
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }

        filteredApps.forEachIndexed { index, info ->
            val appRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(8), 0, dp(8))
                isClickable = true
                isFocusable = true
            }

            val appIcon = ImageView(this).apply {
                try {
                    setImageDrawable(packageManager.getApplicationIcon(info))
                } catch (_: Exception) {
                    setImageDrawable(null)
                }
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = packageManager.getApplicationLabel(info).toString()
            }
            appRow.addView(appIcon, LinearLayout.LayoutParams(dp(38), dp(38)))

            val nameColumn = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, dp(4), 0)
            }

            nameColumn.addView(TextView(this).apply {
                text = packageManager.getApplicationLabel(info).toString()
                textSize = 14f
                setTextColor(Color.rgb(245, 247, 252))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })

            nameColumn.addView(TextView(this).apply {
                text = info.packageName
                textSize = 10f
                setTextColor(MUTED)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })

            appRow.addView(nameColumn, LinearLayout.LayoutParams(0, -2, 1f))

            val checkBox = CheckBox(this).apply {
                buttonTintList = ColorStateList.valueOf(ACCENT)
                isChecked = info.packageName in selectedPackages()
                contentDescription =
                    "Theo dõi ${packageManager.getApplicationLabel(info)}"
                setOnCheckedChangeListener { _, checked ->
                    val selected = selectedPackages().toMutableSet()
                    if (checked) selected.add(info.packageName)
                    else selected.remove(info.packageName)

                    prefs.edit()
                        .putStringSet(KEY_SELECTED_PACKAGES, selected)
                        .apply()
                }
            }

            appRow.addView(checkBox)
            appRow.setOnClickListener {
                checkBox.isChecked = !checkBox.isChecked
            }

            appList.addView(appRow, LinearLayout.LayoutParams(-1, -2))

            if (index < filteredApps.lastIndex) {
                appList.addView(View(this).apply {
                    setBackgroundColor(DIVIDER)
                }, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
    }

    private fun selectedPackages(): Set<String> {
        return prefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet())?.toSet()
            ?: emptySet()
    }

    private fun refreshPermissionStatus() {
        if (!::notificationStatus.isInitialized || !::overlayStatus.isInitialized) return

        val listenerComponent =
            ComponentName(this, NotificationAccessService::class.java).flattenToString()

        val enabledListeners = Settings.Secure.getString(
            contentResolver,
            "enabled_notification_listeners"
        )

        val hasNotificationAccess = enabledListeners
            ?.split(':')
            ?.any { it.equals(listenerComponent, ignoreCase = true) } == true

        notificationStatus.text = if (hasNotificationAccess) {
            "● Đã cấp quyền Notification Access"
        } else {
            "○ Chưa cấp quyền Notification Access"
        }
        notificationStatus.setTextColor(
            if (hasNotificationAccess) SUCCESS else WARNING
        )

        val hasOverlayAccess = Settings.canDrawOverlays(this)
        overlayStatus.text = if (hasOverlayAccess) {
            "● Đã cho phép hiển thị nổi"
        } else {
            "○ Chưa cho phép hiển thị nổi"
        }
        overlayStatus.setTextColor(if (hasOverlayAccess) SUCCESS else WARNING)
    }

    private fun createCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(CARD, 24, CARD_STROKE)
        }
    }

    private fun sectionHeading(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 11f
            letterSpacing = 0.12f
            setTextColor(ACCENT)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setPadding(0, 0, 0, dp(12))
        }
    }

    private fun statusLine(): TextView {
        return TextView(this).apply {
            textSize = 12f
            setPadding(0, dp(6), 0, dp(5))
        }
    }

    private fun actionButton(value: String, action: () -> Unit): Button {
        return Button(this).apply {
            text = value
            textSize = 13f
            isAllCaps = false
            setTextColor(Color.rgb(236, 242, 255))
            background = rounded(BUTTON, 14, BUTTON_STROKE)
            minHeight = dp(48)
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply {
                topMargin = dp(7)
                bottomMargin = dp(7)
            }
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

        private val BACKGROUND = Color.rgb(8, 10, 15)
        private val CARD = Color.rgb(17, 20, 29)
        private val CARD_STROKE = Color.rgb(35, 42, 56)
        private val BUTTON = Color.rgb(34, 43, 61)
        private val BUTTON_STROKE = Color.rgb(57, 72, 97)
        private val ACCENT = Color.rgb(157, 190, 255)
        private val SUBTEXT = Color.rgb(167, 174, 188)
        private val MUTED = Color.rgb(122, 132, 150)
        private val DIVIDER = Color.rgb(31, 36, 47)
        private val SUCCESS = Color.rgb(115, 225, 174)
        private val WARNING = Color.rgb(240, 190, 112)
    }
}
