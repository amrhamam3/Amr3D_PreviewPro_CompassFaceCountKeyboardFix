package com.amr3d.preview.pro

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * يدير حفظ وتطبيق لغة التطبيق (عربي / إنجليزي / فرنسي / إسباني)
 * بشكل مستقل عن لغة نظام الجهاز.
 *
 * الاستخدام:
 *  1) كل Activity لازم يعمل override لـ attachBaseContext ويستدعي LocaleHelper.wrapContext(newBase)
 *  2) عند تغيير اللغة من الإعدادات: LocaleHelper.setLanguage(ctx, lang) ثم activity.recreate()
 */
object LocaleHelper {

    private const val PREFS = "amr3d_prefs"
    private const val KEY_LANG = "app_language"

    enum class Lang(val code: String, val displayName: String) {
        ARABIC ("ar", "🇪🇬  العربية"),
        ENGLISH("en", "🇬🇧  English"),
        FRENCH ("fr", "🇫🇷  Français"),
        SPANISH("es", "🇪🇸  Español");

        companion object {
            fun fromCode(code: String): Lang = values().find { it.code == code } ?: ARABIC
        }
    }

    /** اللغة المحفوظة، أو العربية كافتراضي عند أول تشغيل */
    fun getSavedLanguage(context: Context): Lang {
        val code = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANG, null) ?: return Lang.ARABIC
        return Lang.fromCode(code)
    }

    fun setLanguage(context: Context, lang: Lang) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_LANG, lang.code).apply()
    }

    /** يُستدعى من attachBaseContext في كل Activity لتطبيق اللغة المحفوظة على موارد النصوص */
    fun wrapContext(context: Context): Context {
        val lang = getSavedLanguage(context)
        return updateResources(context, lang.code)
    }

    private fun updateResources(context: Context, langCode: String): Context {
        val locale = Locale(langCode)
        Locale.setDefault(locale)

        val config = Configuration(context.resources.configuration)
        config.setLocale(locale)
        // يضبط اتجاه الواجهة تلقائياً (عربي = RTL، والباقي = LTR)
        config.setLayoutDirection(locale)

        return context.createConfigurationContext(config)
    }
}
