package io.github.mangi.eta.ui.app

import android.content.Context
import android.content.res.Configuration
import io.github.mangi.eta.R
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * The app ships English only (`localeFilters = listOf("en")`); resource resolution must therefore
 * fall back to the default strings for every locale instead of a locale-specific override.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LocaleResourcesTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun everyLocaleResolvesToTheDefaultEnglishResources() {
        for (languageTag in listOf("en-US", "zh-CN", "zh-TW", "fr-FR")) {
            assertEquals("Settings", localizedString(languageTag, R.string.route_settings))
            assertEquals("Appearance & Theme", localizedString(languageTag, R.string.appearance_title))
            assertEquals("Yesterday", localizedString(languageTag, R.string.time_yesterday))
        }
    }

    @Test
    fun pluralsUseTheEnglishQuantities() {
        for (languageTag in listOf("en-US")) {
            assertEquals("1 model", localizedQuantity(languageTag, R.plurals.provider_models_count, 1))
            assertEquals("2 models", localizedQuantity(languageTag, R.plurals.provider_models_count, 2))
        }
    }

    @Suppress("DEPRECATION")
    private fun localizedString(languageTag: String, resourceId: Int): String {
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return resources.getString(resourceId)
    }

    @Suppress("DEPRECATION")
    private fun localizedQuantity(languageTag: String, resourceId: Int, quantity: Int): String {
        val resources = context.resources
        val configuration = Configuration(resources.configuration).apply {
            setLocale(Locale.forLanguageTag(languageTag))
        }
        resources.updateConfiguration(configuration, resources.displayMetrics)
        return resources.getQuantityString(resourceId, quantity, quantity)
    }
}
