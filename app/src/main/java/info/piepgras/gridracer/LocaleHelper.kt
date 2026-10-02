package info.piepgras.gridracer

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Helper for per-app language switching, matching the pattern in Work Time Tracker and
 * Everyday Hero. The language choice is stored in SharedPreferences and applied via
 * [attachBaseContext] in [MainActivity].
 *
 * To add a new supported language later, simply:
 * 1. Add a new [Language] entry to [SUPPORTED_LANGUAGES]
 * 2. Create `res/values-<code>/strings.xml` with the translated strings
 */
object LocaleHelper {

    private const val PREFS_NAME = "grid_racer_locale_prefs"
    private const val KEY_LANGUAGE = "selected_language"

    /**
     * A language the app ships translations for.
     *
     * @property code The language code, as used in `res/values-<code>` and the stored preference.
     * @property nativeName The language's own name for itself, as the language picker shows it.
     */
    data class Language(
        val code: String,
        val nativeName: String,
    )

    /** Every language the app is translated into, in the order the picker lists them. */
    val SUPPORTED_LANGUAGES = listOf(
        Language("en", "English"),
        Language("de", "Deutsch"),
        Language("ru", "Русский"),
    )

    /**
     * Stores [languageCode] as the user's choice and applies it to [context].
     *
     * @return A context configured for the language; the activity has to be recreated to use it.
     */
    fun setLocale(context: Context, languageCode: String): Context {
        persistLanguage(context, languageCode)
        return applyLocale(context, languageCode)
    }

    /**
     * Should be called from [android.app.Activity.attachBaseContext].
     */
    fun onAttach(context: Context): Context {
        val languageCode = getPersistedLanguage(context)
        return applyLocale(context, languageCode)
    }

    /** The language the user chose, or [getDefaultLanguageCode] if they never chose one. */
    fun getPersistedLanguage(context: Context): String {
        return getPreferences(context)
            .getString(KEY_LANGUAGE, getDefaultLanguageCode())
            ?: getDefaultLanguageCode()
    }

    private fun persistLanguage(context: Context, languageCode: String) {
        getPreferences(context).edit()
            .putString(KEY_LANGUAGE, languageCode)
            .apply()
    }

    /** The device's language if the app is translated into it, otherwise English. */
    fun getDefaultLanguageCode(): String {
        return getSystemLanguage().takeIf { code ->
            SUPPORTED_LANGUAGES.any { it.code == code }
        } ?: "en"
    }

    // minSdk is 26, so the pre-API-24 branches this used to carry (and the deprecation
    // suppressions that hid them) were dead code.
    private fun getSystemLanguage(): String = LocaleList.getDefault().get(0)?.language ?: "en"

    private fun applyLocale(context: Context, languageCode: String): Context {
        val locale = Locale.forLanguageTag(languageCode)
        Locale.setDefault(locale)
        val config = Configuration(context.resources.configuration)
        config.setLocales(LocaleList(locale))
        return context.createConfigurationContext(config)
    }

    private fun getPreferences(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
}
