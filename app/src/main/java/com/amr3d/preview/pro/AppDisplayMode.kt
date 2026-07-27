package com.amr3d.preview.pro

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

/** بيتحكم في اختيار المستخدم اليدوي بين الوضع الغامق (الحالي) والوضع الفاتح للواجهة —
 * منفصل تمامًا عن AppTheme.kt (اللي بيتحكم في لون الـ accent بس). التبديل هنا يدوي 100%
 * (المستخدم بيختار من شاشة الإعدادات)، مش تلقائي حسب إعداد النظام، عشان كده بنستخدم
 * MODE_NIGHT_YES/MODE_NIGHT_NO الصريحين بدل MODE_NIGHT_FOLLOW_SYSTEM.
 *
 * ملحوظة مهمة: منطقة عرض الموديل (GLViewerView) وعارض الـ DXF (DXF2DView) بيرسموا
 * خلفيتهم بالكود مباشرة (مش من أي @color resource)، فمبيتأثروش تلقائيًا بتغيير الوضع
 * هنا — الربط بينهم وبين الوضع الحالي بيحصل صراحةً في ViewerFragment (شوف
 * applyViewerBackgroundForCurrentMode) وقت تحميل كل عارض. */
object AppDisplayMode {

    private const val PREFS = "amr3d_prefs"
    private const val KEY_LIGHT = "display_mode_light"

    /** true لو المستخدم مفعّل الوضع الفاتح — false (الافتراضي) يعني الوضع الغامق الحالي */
    fun isLight(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_LIGHT, false)

    /** بيحفظ اختيار المستخدم ويطبّقه فورًا عن طريق AppCompatDelegate — الـ Activities
     * اللي بترث AppCompatActivity بتعيد بناء نفسها تلقائيًا لتطبيق الألوان الجديدة. */
    fun setLight(context: Context, light: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_LIGHT, light).apply()
        applyNightMode(light)
    }

    /** بيتنادى في أول نقطة ممكنة في كل Activity (قبل super.onCreate) عشان يطبّق آخر
     * اختيار محفوظ للمستخدم قبل ما أي شاشة تترسم. */
    fun applySavedMode(context: Context) {
        applyNightMode(isLight(context))
    }

    private fun applyNightMode(light: Boolean) {
        AppCompatDelegate.setDefaultNightMode(
            if (light) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        )
    }
}
