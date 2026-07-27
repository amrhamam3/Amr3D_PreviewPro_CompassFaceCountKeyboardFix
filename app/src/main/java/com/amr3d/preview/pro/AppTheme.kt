package com.amr3d.preview.pro

import android.content.Context
import android.graphics.Color

/**
 * نظام الثيمات — يحفظ ويطبّق لون التطبيق المختار بشكل ديناميكي.
 * كل ثيم له لون أساسي (accent) ولون خلفية (يبقى داكن دائماً للحفاظ على القراءة).
 */
object AppTheme {

    enum class ThemeColor(val id: String, val nameAr: String, val accent: Int, val accentDark: Int) {
        ORANGE ("orange", "🟠  برتقالي (افتراضي)", Color.parseColor("#FF8A1E"), Color.parseColor("#C46800")),
        BLUE   ("blue",   "🔵  أزرق",              Color.parseColor("#3D8BFF"), Color.parseColor("#1A5FCC")),
        GREEN  ("green",  "🟢  أخضر",              Color.parseColor("#3DDC84"), Color.parseColor("#1FA85C")),
        PURPLE ("purple", "🟣  بنفسجي",            Color.parseColor("#A855F7"), Color.parseColor("#7C2FD6")),
        RED    ("red",    "🔴  أحمر",              Color.parseColor("#FF4757"), Color.parseColor("#CC1F2E")),
        GOLD   ("gold",   "🟡  ذهبي",              Color.parseColor("#FFD23F"), Color.parseColor("#D4A018"));

        companion object {
            fun fromId(id: String): ThemeColor = values().find { it.id == id } ?: ORANGE
        }
    }

    private const val PREFS = "amr3d_prefs"
    private const val KEY_THEME = "app_theme_color"

    fun getCurrent(context: Context): ThemeColor {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_THEME, "orange") ?: "orange"
        return ThemeColor.fromId(id)
    }

    fun setCurrent(context: Context, theme: ThemeColor) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME, theme.id).apply()
    }

    /** يطبّق لون الثيم على عناصر واجهة شائعة (نصوص، أزرار) برمجياً */
    fun applyToTextView(tv: android.widget.TextView, context: Context) {
        tv.setTextColor(getCurrent(context).accent)
    }

    /** لون البرتقالي الافتراضي (@color/accent_orange) اللي متبني عليه كل الـ XML layouts حاليًا.
     * بنستخدمه كمرجع: أي view لونه الحالي بيساوي ده، معناه إنه لسه على اللون الافتراضي
     * ولسه محتاج يتلوّن بلون الثيم المختار. */
    private val DEFAULT_ACCENT = Color.parseColor("#FF8A1E")

    /**
     * بتمشي جوه شجرة الـ Views كلها (recursively) وبتلوّن أي عنصر لونه لسه الافتراضي
     * (نص، زرار، أيقونة، progress/seek bar، خلفية) بلون الثيم الحالي — بدل ما يقتصر
     * التلوين على الشريط السفلي بس زي ما كان قبل كده.
     */
    fun applyThemeRecursively(root: android.view.View, context: Context) {
        val theme = getCurrent(context)
        applyToSingleView(root, theme)
        if (root is android.view.ViewGroup) {
            for (i in 0 until root.childCount) {
                applyThemeRecursively(root.getChildAt(i), context)
            }
        }
    }

    private fun applyToSingleView(view: android.view.View, theme: ThemeColor) {
        // نص عادي (عناوين الأقسام في الإعدادات، تسميات، إلخ)
        if (view is android.widget.TextView && view.currentTextColor == DEFAULT_ACCENT) {
            view.setTextColor(theme.accent)
        }
        // ToggleButton / RadioButton / CheckBox (buttonTint)
        if (view is android.widget.CompoundButton) {
            val tint = androidx.core.widget.CompoundButtonCompat.getButtonTintList(view)
            if (tint != null && tint.defaultColor == DEFAULT_ACCENT) {
                androidx.core.widget.CompoundButtonCompat.setButtonTintList(
                    view, android.content.res.ColorStateList.valueOf(theme.accent))
            }
        }
        // أيقونات (ImageView tint)
        if (view is android.widget.ImageView) {
            val tint = androidx.core.widget.ImageViewCompat.getImageTintList(view)
            if (tint != null && tint.defaultColor == DEFAULT_ACCENT) {
                androidx.core.widget.ImageViewCompat.setImageTintList(
                    view, android.content.res.ColorStateList.valueOf(theme.accent))
            }
        }
        // ProgressBar (progressTint)
        if (view is android.widget.ProgressBar) {
            val pTint = view.progressTintList
            if (pTint != null && pTint.defaultColor == DEFAULT_ACCENT) {
                view.progressTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            }
        }
        // SeekBar (thumbTint)
        if (view is android.widget.SeekBar) {
            val thumbTint = view.thumbTintList
            if (thumbTint != null && thumbTint.defaultColor == DEFAULT_ACCENT) {
                view.thumbTintList = android.content.res.ColorStateList.valueOf(theme.accent)
            }
        }
        // أيقونات جوه زرار نصي (drawableStart/Top...) — زي أيقونات شريط أدوات العارض
        // الجديدة (إضاءة/خامة/قياس...) اللي بقت Vector بدل إيموجي عشان الثيم يتطبق عليها
        if (view is android.widget.TextView) {
            val dTint = androidx.core.widget.TextViewCompat.getCompoundDrawableTintList(view)
            if (dTint != null && dTint.defaultColor == DEFAULT_ACCENT) {
                androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(
                    view, android.content.res.ColorStateList.valueOf(theme.accent))
            }
        }
        // خلفية أي زرار (backgroundTint)
        val bgTint = androidx.core.view.ViewCompat.getBackgroundTintList(view)
        if (bgTint != null && bgTint.defaultColor == DEFAULT_ACCENT) {
            androidx.core.view.ViewCompat.setBackgroundTintList(
                view, android.content.res.ColorStateList.valueOf(theme.accent))
        }
        // خلفية سادة (زي الشريط في fragment_slicer اللي بيستخدم background مباشرة)
        val bg = view.background
        if (bg is android.graphics.drawable.ColorDrawable && bg.color == DEFAULT_ACCENT) {
            view.setBackgroundColor(theme.accent)
        }
    }
}
