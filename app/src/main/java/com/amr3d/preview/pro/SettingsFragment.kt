package com.amr3d.preview.pro

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.*
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment

class SettingsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        val view  = inflater.inflate(R.layout.fragment_settings, container, false)
        AppTheme.applyThemeRecursively(view, requireContext())
        val prefs = requireContext().getSharedPreferences("amr3d_prefs", Context.MODE_PRIVATE)
        val ctx   = requireContext()

        // ══ اللغة ══
        val languageGroup = view.findViewById<RadioGroup>(R.id.languageGroup)
        when (LocaleHelper.getSavedLanguage(ctx)) {
            LocaleHelper.Lang.ARABIC  -> languageGroup.check(R.id.radioLangAr)
            LocaleHelper.Lang.ENGLISH -> languageGroup.check(R.id.radioLangEn)
            LocaleHelper.Lang.FRENCH  -> languageGroup.check(R.id.radioLangFr)
            LocaleHelper.Lang.SPANISH -> languageGroup.check(R.id.radioLangEs)
        }
        languageGroup.setOnCheckedChangeListener { _, id ->
            val newLang = when (id) {
                R.id.radioLangAr -> LocaleHelper.Lang.ARABIC
                R.id.radioLangEn -> LocaleHelper.Lang.ENGLISH
                R.id.radioLangFr -> LocaleHelper.Lang.FRENCH
                R.id.radioLangEs -> LocaleHelper.Lang.SPANISH
                else -> LocaleHelper.Lang.ARABIC
            }
            if (newLang != LocaleHelper.getSavedLanguage(ctx)) {
                LocaleHelper.setLanguage(ctx, newLang)
                // إعادة تشغيل نظيفة بالكامل (مش recreate) — عشان نتجنب تعارض
                // الفراجمنتس المحفوظة تلقائياً مع اللي بيضيفها MainActivity يدوياً،
                // اللي كان بيسبب كراش وإحساس إن التطبيق "بيقفل ويفتح لوحده"
                val restartIntent = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(restartIntent)
                requireActivity().finish()
            }
        }

        // ══ وحدة القياس ══
        val unitGroup = view.findViewById<RadioGroup>(R.id.unitGroup)
        when (prefs.getString("unit", "MM")) {
            "MM"   -> unitGroup.check(R.id.radioMM)
            "CM"   -> unitGroup.check(R.id.radioCM)
            "INCH" -> unitGroup.check(R.id.radioInch)
        }
        unitGroup.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("unit", when (id) {
                R.id.radioMM   -> "MM"
                R.id.radioCM   -> "CM"
                R.id.radioInch -> "INCH"
                else -> "MM"
            }).apply()
        }

        // ══ ألوان التطبيق ══
        setupThemeRow(view)

        // ══ تغيير الاسم ══
        view.findViewById<Button>(R.id.btnChangeName).setOnClickListener {
            val input = EditText(ctx).apply {
                hint = getString(R.string.settings_change_name_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                setTextColor(0xFFF2F3F5.toInt())
                setHintTextColor(0xFF9CA3AF.toInt())
                setPadding(40, 24, 40, 24)
                textSize = 16f
                val saved = MainActivity.getUserName(ctx)
                if (saved.isNotEmpty()) setText(saved)
            }
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.settings_change_name))
                .setView(input)
                .setPositiveButton(getString(R.string.action_save)) { _, _ ->
                    val name = input.text.toString().trim().ifEmpty { getString(R.string.settings_default_name) }
                    MainActivity.saveUserName(ctx, name)
                    Toast.makeText(ctx, getString(R.string.toast_saved_name, name), Toast.LENGTH_SHORT).show()
                    refreshVersionText(view)
                }
                .setNegativeButton(getString(R.string.action_cancel), null).show()
        }

        // ══ جودة العرض ══
        val qualityGroup = view.findViewById<RadioGroup>(R.id.qualityGroup)
        when (prefs.getString("quality", "HIGH")) {
            "HIGH"   -> qualityGroup.check(R.id.radioHigh)
            "MEDIUM" -> qualityGroup.check(R.id.radioMedium)
            "LOW"    -> qualityGroup.check(R.id.radioLow)
        }
        qualityGroup.setOnCheckedChangeListener { _, id ->
            prefs.edit().putString("quality", when (id) {
                R.id.radioHigh   -> "HIGH"
                R.id.radioMedium -> "MEDIUM"
                R.id.radioLow    -> "LOW"
                else -> "HIGH"
            }).apply()
            Toast.makeText(ctx, getString(R.string.toast_applies_next_file), Toast.LENGTH_SHORT).show()
        }

        // ══ الصوت ══
        val soundSwitch = view.findViewById<Switch>(R.id.switchSound)
        soundSwitch.isChecked = prefs.getBoolean("sound_enabled", true)
        soundSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("sound_enabled", checked).apply()
        }

        // ══ الانيميشن ══
        val animSwitch = view.findViewById<Switch>(R.id.switchAnim)
        animSwitch.isChecked = prefs.getBoolean("anim_enabled", true)
        animSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("anim_enabled", checked).apply()
        }

        // ══ المحور الرأسي Z-up (لملفات 3ds Max/CAD) — الافتراضي: مفعّل ══
        val zUpSwitch = view.findViewById<Switch>(R.id.switchZUp)
        zUpSwitch.isChecked = prefs.getBoolean("zup_mode", true)
        zUpSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("zup_mode", checked).apply()
        }

        // ══ إظهار الانعكاس تحت الموديل — الافتراضي: مفعّل ══
        val reflectionSwitch = view.findViewById<Switch>(R.id.switchReflection)
        reflectionSwitch.isChecked = prefs.getBoolean("reflection_enabled", true)
        reflectionSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("reflection_enabled", checked).apply()
        }

        // ══ عداد Faces/Vertices أعلى شاشة العارض — الافتراضي: ظاهر ══
        val faceCountSwitch = view.findViewById<Switch>(R.id.switchFaceCount)
        faceCountSwitch.isChecked = prefs.getBoolean("show_face_count", true)
        faceCountSwitch.setOnCheckedChangeListener { _, checked ->
            prefs.edit().putBoolean("show_face_count", checked).apply()
        }

        // ══ الوضع الفاتح/الغامق للواجهة — الافتراضي: غامق (زي ما كان دايمًا) ══
        val lightModeSwitch = view.findViewById<Switch>(R.id.switchLightMode)
        lightModeSwitch.isChecked = AppDisplayMode.isLight(requireContext())
        lightModeSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked != AppDisplayMode.isLight(requireContext())) {
                AppDisplayMode.setLight(requireContext(), checked)
                // بالظبط نفس منطق تغيير اللغة فوق: إعادة تشغيل نظيفة بالكامل (مش recreate)
                // عشان نتجنب تعارض الفراجمنتس المحفوظة تلقائياً مع اللي بيضيفها MainActivity
                // يدوياً — recreate() (سواء يدوي أو التلقائي اللي بيعمله AppCompatDelegate
                // نفسه) كان بيسيب bottomNav في حالة متضاربة ويوقف التنقل تمامًا.
                val restartIntent = Intent(ctx, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(restartIntent)
                requireActivity().finish()
            }
        }

        // ══ تواصل ══
        view.findViewById<Button>(R.id.btnContactWA).setOnClickListener {
            val phone = "201009172167"
            val msg = Uri.encode("مرحبًا، عندي استفسار بخصوص تطبيق Amr3D Preview")
            try {
                startActivity(Intent(Intent.ACTION_VIEW,
                    Uri.parse("whatsapp://send?phone=$phone&text=$msg"))
                    .apply { setPackage("com.whatsapp") })
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$phone?text=$msg")))
            }
        }

        // ══ فيديو التعريف بالتطبيق — مشاهدة اختيارية في أي وقت، من غير ما تأثر
        // على تسلسل "أول تشغيل" (شوف IntroVideoActivity) ══
        view.findViewById<Button>(R.id.btnAboutVideo).setOnClickListener {
            IntroVideoActivity.startAsReplay(ctx)
        }

        // ══ إذن الوصول لكل الملفات ══
        val btnFileAccess = view.findViewById<Button>(R.id.btnFileAccess)
        fun refreshFileAccessButton() {
            val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                android.os.Environment.isExternalStorageManager()
            btnFileAccess.text = if (granted) getString(R.string.settings_file_access_granted) else getString(R.string.settings_file_access)
        }
        refreshFileAccessButton()
        btnFileAccess.setOnClickListener {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                try {
                    startActivity(Intent(
                        android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:${ctx.packageName}")
                    ))
                } catch (_: Exception) {
                    startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            } else {
                Toast.makeText(ctx, getString(R.string.toast_permission_already_granted), Toast.LENGTH_SHORT).show()
            }
        }

        // ══ مسح التاريخ ══
        view.findViewById<Button>(R.id.btnClearHistorySettings).setOnClickListener {
            AlertDialog.Builder(ctx)
                .setTitle(getString(R.string.settings_clear_history_title))
                .setMessage(getString(R.string.settings_clear_history_msg))
                .setPositiveButton(getString(R.string.action_clear)) { _, _ ->
                    HistoryFragment.clearHistory(ctx)
                    Toast.makeText(ctx, getString(R.string.toast_cleared), Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(getString(R.string.action_cancel), null).show()
        }

        refreshVersionText(view)
        return view
    }

    override fun onResume() {
        super.onResume()
        view?.findViewById<Button>(R.id.btnFileAccess)?.let { btn ->
            val granted = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R ||
                android.os.Environment.isExternalStorageManager()
            btn.text = if (granted) getString(R.string.settings_file_access_granted) else getString(R.string.settings_file_access)
        }
    }

    private fun refreshVersionText(view: View) {
        val name = MainActivity.getUserName(requireContext())
        val greeting = if (name.isNotEmpty()) getString(R.string.greeting_with_name, name) else ""
        view.findViewById<TextView>(R.id.tvVersion).text =
            "$greeting${getString(R.string.app_version_info)}"
    }

    /** يبني صف دوائر ألوان الثيم القابلة للاختيار */
    private fun setupThemeRow(view: View) {
        val ctx = requireContext()
        val row = view.findViewById<LinearLayout>(R.id.themeColorRow)
        row.removeAllViews()

        val current = AppTheme.getCurrent(ctx)
        val density = ctx.resources.displayMetrics.density
        val circleSize = (44 * density).toInt()

        AppTheme.ThemeColor.values().forEach { theme ->
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = android.view.Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f
                ).also { it.setMargins(4, 0, 4, 0) }
            }

            val circle = object : View(ctx) {
                override fun onDraw(c: android.graphics.Canvas) {
                    val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
                    val r = minOf(width, height) / 2f - 3f
                    val cx = width / 2f; val cy = height / 2f

                    p.shader = android.graphics.RadialGradient(
                        cx - r * 0.25f, cy - r * 0.25f, r * 1.1f,
                        intArrayOf(theme.accent, theme.accentDark),
                        floatArrayOf(0f, 1f),
                        android.graphics.Shader.TileMode.CLAMP
                    )
                    c.drawCircle(cx, cy, r, p)
                    p.shader = null

                    // بريق
                    p.shader = android.graphics.RadialGradient(
                        cx - r * 0.2f, cy - r * 0.25f, r * 0.5f,
                        intArrayOf(0x99FFFFFF.toInt(), 0x00FFFFFF),
                        floatArrayOf(0f, 1f), android.graphics.Shader.TileMode.CLAMP
                    )
                    c.drawCircle(cx - r * 0.1f, cy - r * 0.15f, r * 0.45f, p)
                    p.shader = null

                    // حلقة اختيار
                    if (theme == AppTheme.getCurrent(ctx)) {
                        p.style = android.graphics.Paint.Style.STROKE
                        p.strokeWidth = 3f
                        p.color = 0xFFFFFFFF.toInt()
                        c.drawCircle(cx, cy, r + 4f, p)
                    }
                }
            }
            circle.layoutParams = LinearLayout.LayoutParams(circleSize, circleSize)
            circle.setOnClickListener {
                AppTheme.setCurrent(ctx, theme)
                setupThemeRow(view) // إعادة رسم لتحديث الحلقة
                Toast.makeText(ctx, getString(R.string.toast_theme_applied, theme.nameAr), Toast.LENGTH_LONG).show()
            }
            cell.addView(circle)
            row.addView(cell)
        }
    }
}
