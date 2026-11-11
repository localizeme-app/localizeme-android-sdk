package app.localizeme.sdk

import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Typeface
import android.icu.text.PluralRules
import android.text.Spanned
import android.text.style.StyleSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * `<plurals>` over the air: [OtaResources] picks the CLDR category for the
 * quantity, reads `<name>_<category>` from the bundle and checks it against
 * the shipped item for the same quantity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OtaPluralsTest {

    /**
     * An app's shipped `<plurals>`, one id per name, in [locale]. Items are
     * picked by the CLDR rules with the framework's fallback to `other`.
     */
    @Suppress("DEPRECATION")
    private class Shipped(
        app: Resources,
        private val locale: Locale,
        private val plurals: Map<String, Map<String, String>>,
    ) : Resources(app.assets, app.displayMetrics, Configuration(app.configuration).apply { setLocale(locale) }) {
        private val names = plurals.keys.toList()

        fun id(name: String): Int = FIRST_ID + names.indexOf(name).also { require(it >= 0) }

        private fun name(id: Int): String = names.getOrNull(id - FIRST_ID) ?: throw NotFoundException("#$id")

        override fun getResourcePackageName(id: Int): String = name(id).let { PACKAGE }
        override fun getResourceEntryName(id: Int): String = name(id)

        override fun getQuantityText(id: Int, quantity: Int): CharSequence {
            val items = plurals.getValue(name(id))
            val category = PluralRules.forLocale(locale).select(quantity.toDouble())
            return items[category] ?: items["other"] ?: throw NotFoundException("$category")
        }

        override fun getQuantityString(id: Int, quantity: Int): String = getQuantityText(id, quantity).toString()

        override fun getQuantityString(id: Int, quantity: Int, vararg formatArgs: Any?): String =
            String.format(locale, getQuantityString(id, quantity), *formatArgs)
    }

    private fun resources(
        locale: Locale,
        shipped: Map<String, Map<String, String>>,
        bundle: Map<String, String>,
    ): Pair<Shipped, OtaResources> {
        val base = Shipped(RuntimeEnvironment.getApplication().resources, locale, shipped)
        return base to OtaResources(base, PACKAGE) { bundle[it] }
    }

    /** Each quantity's category, read back from an OTA bundle whose forms are named after theirs. */
    private fun categories(locale: Locale, vararg quantities: Int): List<String> {
        val shipped = mapOf("files" to Formats.PLURAL_FORMS.associateWith { "shipped" })
        val (base, ota) = resources(locale, shipped, Formats.PLURAL_FORMS.associate { "files_$it" to it })
        val id = base.id("files")
        return quantities.map { ota.getQuantityString(id, it) }
    }

    @Test
    fun categoryFollowsTheLocalesRules() {
        assertEquals(listOf("other", "one", "other", "other"), categories(Locale.ENGLISH, 0, 1, 2, 21))
        assertEquals(listOf("other", "one", "other"), categories(Locale.GERMAN, 0, 1, 5))
        assertEquals(
            listOf("other", "one", "few", "few", "other", "other", "one", "few"),
            categories(Locale("lt"), 0, 1, 2, 9, 10, 11, 21, 22),
        )
        assertEquals(
            listOf("many", "one", "few", "few", "many", "many", "few", "many"),
            categories(Locale("pl"), 0, 1, 2, 4, 5, 12, 22, 25),
        )
        assertEquals(
            listOf("many", "one", "few", "many", "many", "one", "few"),
            categories(Locale("ru"), 0, 1, 2, 5, 11, 21, 22),
        )
        assertEquals(
            listOf("zero", "one", "two", "few", "few", "many", "many", "other", "other"),
            categories(Locale("ar"), 0, 1, 2, 3, 10, 11, 99, 100, 102),
        )
    }

    @Test
    fun categoryListMatchesTheServers() {
        // ANDROID_PLURAL_FORMS in api/translations/utils.py, in the same order.
        assertEquals(listOf("zero", "one", "two", "few", "many", "other"), Formats.PLURAL_FORMS)
    }

    @Test
    fun eachFormIsCheckedAgainstTheShippedItemForTheSameQuantity() {
        val shipped = mapOf("results" to mapOf("one" to "one Result", "other" to "%,d Results"))
        val (base, ota) = resources(
            Locale.GERMAN,
            shipped,
            mapOf("results_one" to "ein Ergebnis", "results_other" to "%,d Ergebnisse"),
        )
        val id = base.id("results")
        // "ein Ergebnis" has no placeholder, like the shipped "one" item: fine,
        // though the "other" form has one.
        assertEquals("ein Ergebnis", ota.getQuantityString(id, 1, 1))
        assertEquals("1.000 Ergebnisse", ota.getQuantityString(id, 1000, 1000))
        assertEquals("ein Ergebnis", ota.getQuantityText(id, 1).toString())
    }

    @Test
    fun aFormWhosePlaceholdersDifferKeepsTheShippedItem() {
        val shipped = mapOf("results" to mapOf("one" to "one Result", "other" to "%,d Results"))
        val (base, ota) = resources(
            Locale.GERMAN,
            shipped,
            // A placeholder the shipped "one" lacks, and %s for the shipped %,d.
            mapOf("results_one" to "%d Ergebnis", "results_other" to "%s Ergebnisse"),
        )
        val id = base.id("results")
        assertEquals("one Result", ota.getQuantityString(id, 1, 1))
        assertEquals("one Result", ota.getQuantityString(id, 1))
        assertEquals("1.000 Results", ota.getQuantityString(id, 1000, 1000))
        assertEquals("%,d Results", ota.getQuantityString(id, 1000))
        assertEquals("%,d Results", ota.getQuantityText(id, 1000).toString())
    }

    @Test
    fun aMissingFormKeepsTheShippedItemForThatQuantity() {
        val shipped = mapOf(
            "files" to mapOf("one" to "%d файл", "few" to "%d файла", "many" to "%d файлов", "other" to "%d файла"),
        )
        // No "few" in the bundle: never the OTA "other" or "many" in its place.
        val (base, ota) = resources(
            Locale("ru"),
            shipped,
            mapOf("files_one" to "%d файл (OTA)", "files_many" to "%d файлов (OTA)", "files_other" to "%d (OTA)"),
        )
        val id = base.id("files")
        assertEquals("1 файл (OTA)", ota.getQuantityString(id, 1, 1))
        assertEquals("2 файла", ota.getQuantityString(id, 2, 2))
        assertEquals("5 файлов (OTA)", ota.getQuantityString(id, 5, 5))
        assertNull(ota.otaQuantityText(id, 3))
    }

    @Test
    fun styledItemsComeBackAsSpans() {
        val shipped = mapOf("files" to mapOf("one" to "%d file", "other" to "%d files"))
        val (base, ota) = resources(
            Locale.ENGLISH,
            shipped,
            mapOf(
                "files_one" to "<![CDATA[<b>%d</b> file]]>",
                "files_other" to "<b>%d</b> files & <em>more</em>",
            ),
        )
        val id = base.id("files")

        val text = ota.getQuantityText(id, 2)
        assertEquals("%d files & <em>more</em>", text.toString())
        assertTrue(text is Spanned)
        val spans = (text as Spanned).getSpans(0, text.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Typeface.BOLD, spans[0].style)
        assertEquals(0, text.getSpanStart(spans[0]))
        assertEquals(2, text.getSpanEnd(spans[0]))
        // Formatting drops the spans, as the framework's getQuantityString does.
        assertEquals("3 files & <em>more</em>", ota.getQuantityString(id, 3, 3))

        // A CDATA item is the text inside it, tags and all, unstyled.
        val cdata = ota.getQuantityText(id, 1)
        assertFalse(cdata is Spanned)
        assertEquals("<b>%d</b> file", cdata.toString())
        assertEquals("<b>1</b> file", ota.getQuantityString(id, 1, 1))
    }

    @Test
    fun aPlainKeyNamedLikeTheBaseIsNotUsed() {
        // Project 166 keeps "results" as a plain string for its iOS app next to
        // the forms. The plural reads the forms only.
        val shipped = mapOf("results" to mapOf("one" to "%d result", "other" to "%d results"))
        val (base, ota) = resources(
            Locale.ENGLISH,
            shipped,
            mapOf("results" to "Results (iOS)", "results_one" to "%d hit", "results_other" to "%d hits"),
        )
        val id = base.id("results")
        assertEquals("1 hit", ota.getQuantityString(id, 1, 1))
        assertEquals("4 hits", ota.getQuantityString(id, 4, 4))

        // Without forms, the plain key is not a stand-in for them.
        val (base2, ota2) = resources(Locale.ENGLISH, shipped, mapOf("results" to "Results (iOS)"))
        assertEquals("4 results", ota2.getQuantityString(base2.id("results"), 4, 4))
    }

    @Test
    fun aNewBundleValueReplacesTheCachedOne() {
        val shipped = mapOf("files" to mapOf("one" to "%d file", "other" to "%d files"))
        val bundle = mutableMapOf("files_other" to "%d Dateien")
        val base = Shipped(RuntimeEnvironment.getApplication().resources, Locale.ENGLISH, shipped)
        val ota = OtaResources(base, PACKAGE) { bundle[it] }
        val id = base.id("files")
        assertEquals("2 Dateien", ota.getQuantityString(id, 2, 2))
        bundle["files_other"] = "%d Akten"
        assertEquals("2 Akten", ota.getQuantityString(id, 2, 2))
        bundle["files_other"] = "%s Akten"
        assertEquals("2 files", ota.getQuantityString(id, 2, 2))
        bundle.remove("files_other")
        assertEquals("2 files", ota.getQuantityString(id, 2, 2))
    }

    @Test
    fun anotherPackagesPluralsAreLeftAlone() {
        val app = RuntimeEnvironment.getApplication()
        val ota = OtaResources(app.resources, "some.other.app") { "OTA %d" }
        // An android.* resource that is not a plural: still the framework's answer.
        assertNull(ota.otaQuantityText(android.R.string.ok, 1))
    }

    private companion object {
        const val PACKAGE = "app.localizeme.sdk.test"
        const val FIRST_ID = 0x7f990000
    }
}
