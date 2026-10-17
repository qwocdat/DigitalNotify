package com.digitalsmod.digitalnotify

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
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
    private lateinit var listenerStatus: TextView
    private lateinit var overlayStatus: TextView
    private lateinit var appList: LinearLayout
    private lateinit var searchInput: EditText
    private var allApps: List<ApplicationInfo> = emptyList()
    private var filterText: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.statusBarColor = Color.rgb(8, 10, 15)
        window.navigationBarColor = Color.rgb(8, 10, 15)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        buildScreen()
        loadApps()
        renderAppList()
    }

    override fun onResume() {
        super.onResume()
        if (::listenerStatus.isInitialized) refreshPermissionStatus()
    }

    private fun buildScreen() {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(8, 10, 15))
        }
        val scroll = ScrollView(this).apply { clipToPadding = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(22), dp(20), dp(30))
        }
        scroll.addView(content)
        page.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        content.addView(TextView(this).apply {
            text = "DIGITALNOTIFY"
            textSize = 12f
            letterSpacing = 0.22f
            setTextColor(Color.rgb(157, 190, 255))
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
            text = "Theo dõi thông báo của ứng dụng bạn chọn và hiển thị lại bằng banner nổi phong cách Liquid Glass."
            textSize = 14f
            setTextColor(Color.rgb(167, 174, 188))
            setLineSpacing(dp(4).toFloat(), 1f)
        })

        val permissionCard = card()
        permissionCard.addView(sectionHeading("QUYỀN TRUY CẬP"))
        listenerStatus = statusLine("Notification Access")
        overlayStatus = statusLine("Hiển thị nổi trên ứng dụng khác")
        permissionCard.addView(listenerStatus)
        permissionCard.addView(actionButton("Cấp quyền đọc thông báo") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        permissionCard.addView(overlayStatus)
        permissionCard.addView(actionButton("Cho phép hiển thị nổi") {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            startActivity(intent)
        })
        content.addView(permissionCard, marginTop(22))

        val appsCard = card()
        appsCard.addView(sectionHeading("ỨNG DỤNG ĐƯỢC THEO DÕI"))
        appsCard.addView(TextView(this).apply {
            text = "Chỉ thông báo từ các ứng dụng được chọn mới tạo banner. Nội dung được xử lý trên thiết bị."
            textSize = 13f
            setTextColor(Color.rgb(157, 165, 180))
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, 0, 0, dp(12))
        })
        searchInput = EditText(this).apply {
            hint = "Tìm ứng dụng..."
            textSize = 14f
            singleLine = true
            setTextColor(Color.WHITE)
            setHintTextColor(Color.rgb(126, 134, 150))
            setPadding(dp(14), 0, dp(14), 0)
            background = rounded(Color.rgb(24, 28, 38), dp(15))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    filterText = s?.toString().orEmpty()
                    renderAppList()
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
        appsCard.addView(searchInput, LinearLayout.LayoutParams(-1, dp(48)).apply { bottomMargin = dp(10) })
        appList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        appsCard.addView(appList)
        content.addView(appsCard, marginTop(14))

        content.addView(TextView(this).apply {
            text = "Bản thử nghiệm 0.1 · Android 8 trở lên\nKhông đọc cơ sở dữ liệu riêng của ứng dụng nguồn. Chỉ xử lý dữ liệu Android cung cấp trong thông báo."
            textSize = 11f
            setTextColor(Color.rgb(108, 117, 133))
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(dp(2), dp(18), dp(2), 0)
        })
        setContentView(page)
        refreshPermissionStatus()
    }

    private fun loadApps() {
        val pm = packageManager
        allApps = try {
            @Suppress("DEPRECATION")
            pm.getInstalledApplications(0)
                .filter { it.packageName != packageName }
                .filter { info ->
                    val isUserApp = (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    val hasLauncher = pm.getLaunchIntentForPackage(info.packageName) != null
                    isUserApp || hasLauncher
                }
                .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun renderAppList() {
        if (!::appList.isInitialized) return
        appList.removeAllViews()
        val pm = packageManager
        val filtered = allApps.filter {
            val label = pm.getApplicationLabel(it).toString()
            label.contains(filterText, ignoreCase = true) || it.packageName.contains(filterText, ignoreCase = true)
        }
        if (filtered.isEmpty()) {
            appList.addView(TextView(this).apply {
                text = "Không tìm thấy ứng dụng."
                textSize = 13f
                setTextColor(Color.LTGRAY)
                setPadding(0, dp(12), 0, dp(12))
            })
            return
        }
        val selected = selectedPackages().toMutableSet()
        filtered.forEachIndexed { index, info ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(2), dp(8), 0, dp(8))
            }
            val icon = ImageView(this).apply {
                try { setImageDrawable(pm.getApplicationIcon(info)) } catch (_: Exception) { }
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            row.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            val names = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(11), 0, dp(4), 0)
            }
            names.addView(TextView(this).apply {
                text = pm.getApplicationLabel(info).toString()
                textSize = 14f
                setTextColor(Color.rgb(245, 247, 252))
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            names.addView(TextView(this).apply {
                text = info.packageName
                textSize = 10f
                setTextColor(Color.rgb(122, 132, 150))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            row.addView(names, LinearLayout.LayoutParams(0, -2, 1f))
            val checkbox = CheckBox(this).apply {
                buttonTintList = android.content.res.ColorStateList.valueOf(Color.rgb(157, 190, 255))
                isChecked = info.packageName in selected
                contentDescription = "Theo dõi ${pm.getApplicationLabel(info)}"
                setOnCheckedChangeListener { _, checked ->
                    val set = selectedPackages().toMutableSet()
                    if (checked) set.add(info.packageName) else set.remove(info.packageName)
                    prefs.edit().putStringSet(KEY_SELECTED_PACKAGES, set).apply()
                }
            }
            row.addView(checkbox)
            row.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
            appList.addView(row, LinearLayout.LayoutParams(-1, -2))
            if (index < filtered.lastIndex) {
                val line = View(this).apply { setBackgroundColor(Color.rgb(31, 36, 47)) }
                appList.addView(line, LinearLayout.LayoutParams(-1, dp(1)))
            }
        }
    }

    private fun selectedPackages(): Set<String> =
        prefs.getStringSet(KEY_SELECTED_PACKAGES, emptySet())?.toSet() ?: emptySet()

    private fun refreshPermissionStatus() {
        if (!::listenerStatus.isInitialized) return
        val component = ComponentName(this, NotificationAccessService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?.split(':')?.any { it.equals(component, ignoreCase = true) } == true
        listenerStatus.text = if (enabled) "● Đã cấp quyền Notification Access" else "○ Chưa cấp quyền Notification Access"
        listenerStatus.setTextColor(if (enabled) Color.rgb(115, 225, 174) else Color.rgb(240, 190, 112))
        overlayStatus.text = if (Settings.canDrawOverlays(this)) "● Đã cho phép hiển thị nổi" else "○ Chưa cho phép hiển thị nổi"
        overlayStatus.setTextColor(if (Settings.canDrawOverlays(this)) Color.rgb(115, 225, 174) else Color.rgb(240, 190, 112))
    }

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        background = rounded(Color.rgb(17, 20, 29), dp(24), Color.rgb(35, 42, 56))
    }

    private fun sectionHeading(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 11f
        letterSpacing = 0.12f
        setTextColor(Color.rgb(155, 185, 245))
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, 0, 0, dp(12))
    }

    private fun statusLine(textValue: String) = TextView(this).apply {
        text = textValue
        textSize = 12f
        setPadding(0, dp(6), 0, dp(5))
    }

    private fun actionButton(textValue: String, action: () -> Unit) = Button(this).apply {
        text = textValue
        textSize = 13f
        isAllCaps = false
        setTextColor(Color.rgb(236, 242, 255))
        background = rounded(Color.rgb(34, 43, 61), dp(14), Color.rgb(57, 72, 97))
        setOnClickListener { action() }
        layoutParams = LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(7); bottomMargin = dp(7) }
    }

    private fun marginTop(top: Int) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) }

    private fun rounded(color: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS = "digitalnotify_preferences"
        const val KEY_SELECTED_PACKAGES = "selected_notification_packages"
    }
}
