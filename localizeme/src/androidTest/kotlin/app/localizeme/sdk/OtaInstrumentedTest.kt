package app.localizeme.sdk

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StyleSpan
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.Toolbar
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.localizeme.sdk.test.R
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** A LocalizeMe API held in memory: one version, one language, real hashes. */
private class MemoryServer(strings: Map<String, String>) : Transport {
    private val bundle: ByteArray = JSONObject()
        .put("v", 1).put("project", 1).put("platform", "android").put("lang", "en")
        .put("strings", JSONObject(strings))
        .toString().toByteArray()
    private val sha = Sha256.hex(bundle)

    override fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Transport.Response = when {
        url.contains("/ota/v1/manifest") -> {
            val language = JSONObject()
                .put("url", "https://test/ota/v1/bundles/1/android/en/$sha.json")
                .put("sha256", sha).put("size", bundle.size).put("strings", 1)
                .put("codes", JSONArray(listOf("en")))
            val data = JSONObject()
                .put("version", 1).put("platform", "android").put("source_language", "en")
                .put("languages", JSONObject().put("en", language))
            val envelope = JSONObject().put("success", true).put("message", "ok").put("data", data)
            Transport.Response(200, mapOf("etag" to "\"test-1\""), envelope.toString().toByteArray())
        }
        url.contains("/ota/v1/bundles/") -> Transport.Response(200, emptyMap(), bundle)
        else -> Transport.Response(200, emptyMap(), """{"success":true,"data":{}}""".toByteArray())
    }
}

/**
 * The wrapped Activity end to end: resources, inflation and AppCompat's
 * night-mode context. Needs an emulator: `./gradlew :localizeme:connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class OtaInstrumentedTest {
    private val ota = mapOf(
        "lz_test_title" to "OTA title",
        "lz_test_hint" to "OTA hint",
        "lz_test_toolbar" to "OTA toolbar",
        "lz_test_subtitle" to "OTA subtitle",
        "lz_test_description" to "OTA description",
        "lz_test_plain" to "OTA plain",
        "lz_test_count" to "%1\$s Stück",
        "lz_test_range" to "%2\$d bis %1\$d",
        "lz_test_bold" to "OTA <b>bold</b>",
        "lz_test_mixed" to "OTA <em>as text</em> & <b>bold</b>",
    )
    private lateinit var root: File

    @Before
    fun installFakeClient() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        root = File(app.cacheDir, "localizeme-test-${System.nanoTime()}")
        val config = LocalizeMeConfig(sdkKey = "lzs_instrumented", baseUrl = "https://test", applyImmediately = true, reportMissingKeys = false)
        val api = Api(config, MemoryServer(ota), installId = { "install" }, appVersion = { "1.0 (1)" }, osVersion = "Android test")
        val client = Client(config, Store(root), api, preferredLanguages = { listOf("en") })
        LocalizeMe.install(client)
        client.drainQueue()
        assertEquals("OTA title", client.string("lz_test_title"))
        // Night mode makes AppCompat wrap the base context and go through createConfigurationContext.
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        }
    }

    @After
    fun tearDown() {
        LocalizeMe.reset()
        root.deleteRecursively()
    }

    private fun withActivity(block: (TestActivity) -> Unit) {
        ActivityScenario.launch(TestActivity::class.java).use { scenario ->
            scenario.onActivity(block)
        }
    }

    @Test
    fun getStringAndGetTextReturnTheOtaValue() = withActivity { activity ->
        assertTrue("the activity's resources are wrapped", activity.resources is OtaResources)
        assertEquals("OTA title", activity.getString(R.string.lz_test_title))
        assertEquals("OTA plain", activity.resources.getText(R.string.lz_test_plain).toString())
        assertEquals("Only shipped", activity.getString(R.string.lz_test_untranslated))
    }

    @Test
    fun nightModeConfigurationReachesTheWrappedResources() = withActivity { activity ->
        val resources = activity.resources as OtaResources
        val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        assertEquals(Configuration.UI_MODE_NIGHT_YES, night)
        assertEquals(0, resources.base.configuration.diff(resources.configuration))
        assertEquals(resources.base.displayMetrics, resources.displayMetrics)
    }

    @Test
    fun inflatedViewsShowTheOtaText() = withActivity { activity ->
        val title = activity.findViewById<TextView>(R.id.lz_title)
        assertEquals("OTA title", title.text.toString())
        assertEquals("OTA description", title.contentDescription.toString())
        assertEquals("OTA hint", activity.findViewById<EditText>(R.id.lz_input).hint.toString())
        val toolbar = activity.findViewById<Toolbar>(R.id.lz_toolbar)
        assertEquals("OTA toolbar", toolbar.title.toString())
        assertEquals("OTA subtitle", toolbar.subtitle.toString())
        assertEquals("A literal stays", activity.findViewById<TextView>(R.id.lz_literal).text.toString())
    }

    @Test
    fun formattingFallsBackWhenThePlaceholdersDiffer() = withActivity { activity ->
        // %1$s cannot stand in for the shipped %1$d: the shipped string wins.
        assertEquals("3 items", activity.getString(R.string.lz_test_count, 3))
        // Reordered positional arguments are fine.
        assertEquals("5 bis 1", activity.getString(R.string.lz_test_range, 1, 5))
    }

    @Test
    fun inlineMarkupBecomesSpans() = withActivity { activity ->
        val text = activity.getText(R.string.lz_test_bold)
        assertEquals("OTA bold", text.toString())
        assertTrue(text is Spanned)
        val spans = (text as Spanned).getSpans(0, text.length, StyleSpan::class.java)
        assertEquals(1, spans.size)
        assertEquals(Typeface.BOLD, spans[0].style)
        assertEquals(4, text.getSpanStart(spans[0]))
        assertEquals(8, text.getSpanEnd(spans[0]))

        // Only the eleven styling tags are markup; <em> and & stay as written.
        val mixed = activity.getText(R.string.lz_test_mixed)
        assertEquals("OTA <em>as text</em> & bold", mixed.toString())
        val mixedSpans = (mixed as Spanned).getSpans(0, mixed.length, StyleSpan::class.java)
        assertEquals(1, mixedSpans.size)
        assertEquals(mixed.toString().indexOf("bold"), mixed.getSpanStart(mixedSpans[0]))

        val view = activity.findViewById<TextView>(R.id.lz_bold)
        assertNotNull(view.text)
        val viewSpans = (view.text as Spanned).getSpans(0, view.text.length, StyleSpan::class.java)
        assertFalse(viewSpans.isEmpty())
    }
}
