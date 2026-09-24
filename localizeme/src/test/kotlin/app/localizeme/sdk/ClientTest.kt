package app.localizeme.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** A fake LocalizeMe API: one manifest version at a time, with real hashes. */
private class FakeServer : Transport {
    var version = 1L
    var strings = mutableMapOf(
        "en" to mapOf("home_title" to "Home"),
        "de" to mapOf("home_title" to "Startseite"),
    )
    /** Extra codes a language answers to; the language's own code is always first. */
    var codes = mutableMapOf<String, List<String>>()
    val reports = ArrayList<JSONObject>()
    var unauthorized = false
    var corruptBundles = false
    var offline = false
    var manifestRequests = 0
    var manifestStatuses = ArrayList<Int>()
    var bundleRequests = 0
    val requests = ArrayList<Pair<String, Map<String, String>>>()

    val etag get() = "\"p1-android-r$version\""

    fun bundleBytes(lang: String): ByteArray {
        val json = JSONObject()
        json.put("v", 1); json.put("project", 1); json.put("platform", "android"); json.put("lang", lang)
        json.put("strings", JSONObject(strings[lang] ?: emptyMap<String, String>()))
        return json.toString().toByteArray()
    }

    override fun exchange(method: String, url: String, headers: Map<String, String>, body: ByteArray?): Transport.Response {
        requests += url to headers
        if (offline) throw java.net.ConnectException("offline")
        if (unauthorized) return Transport.Response(401, emptyMap(), """{"success":false,"message":"Invalid SDK key"}""".toByteArray())
        return when {
            url.contains("/ota/v1/manifest") -> {
                manifestRequests++
                if (headers["If-None-Match"] == etag) {
                    manifestStatuses += 304
                    return Transport.Response(304, mapOf("etag" to etag), ByteArray(0))
                }
                manifestStatuses += 200
                val languages = JSONObject()
                for (lang in strings.keys) {
                    val bytes = bundleBytes(lang)
                    val sha = Sha256.hex(bytes)
                    languages.put(lang, JSONObject(mapOf(
                        "url" to "https://api.test/ota/v1/bundles/1/android/$lang/$sha.json",
                        "sha256" to sha, "size" to bytes.size, "strings" to strings[lang]!!.size,
                        "codes" to JSONArray(listOf(lang) + (codes[lang] ?: emptyList())),
                    )))
                }
                val data = JSONObject(mapOf("version" to version, "platform" to "android", "source_language" to "en"))
                data.put("languages", languages)
                val envelope = JSONObject(mapOf("success" to true, "message" to "ok"))
                envelope.put("data", data)
                Transport.Response(200, mapOf("etag" to etag), envelope.toString().toByteArray())
            }
            url.contains("/ota/v1/bundles/") -> {
                bundleRequests++
                val lang = url.split("/")[8]
                var bytes = bundleBytes(lang)
                if (corruptBundles) bytes += ' '.code.toByte()
                Transport.Response(200, emptyMap(), bytes)
            }
            url.contains("/ota/v1/report") -> {
                reports += JSONObject(String(body!!))
                Transport.Response(200, emptyMap(), """{"success":true,"data":{}}""".toByteArray())
            }
            else -> Transport.Response(404, emptyMap(), ByteArray(0))
        }
    }
}

class ClientTest {
    private val server = FakeServer()
    private val root: File = Files.createTempDirectory("lz").toFile()

    private fun makeClient(
        applyImmediately: Boolean = false,
        languages: List<String> = listOf("de-DE"),
        scope: LanguageScope = LanguageScope.DEVICE,
        store: Store = Store(root),
    ): Client {
        val config = LocalizeMeConfig(
            sdkKey = "lzs_test_key_1234", baseUrl = "https://api.test",
            applyImmediately = applyImmediately, languageScope = scope,
        )
        val api = Api(config, server, installId = { "install-1" }, appVersion = { "1.0 (1)" }, osVersion = "Android 15")
        return Client(config, store, api, { languages })
    }

    private fun start(client: Client): Result<CheckOutcome> {
        client.start()
        return client.runCheck(force = true)
    }

    private fun errorTypes(): List<String?> = server.reports.flatMap { report ->
        val errors = report.getJSONArray("errors")
        (0 until errors.length()).map { errors.getJSONObject(it).optString("type") }
    }

    private fun missingKeys(): List<String?> = server.reports.flatMap { report ->
        val keys: JSONArray = report.getJSONArray("missing_keys")
        (0 until keys.length()).map { keys.getJSONObject(it).optString("key") }
    }

    @Test fun firstRunDownloadsTheDeviceLanguageAndAppliesIt() {
        val client = makeClient()
        assertEquals(CheckOutcome.Applied(1), start(client).getOrThrow())
        assertEquals("Startseite", client.string("home_title"))
        assertEquals("de", client.language)
        assertEquals(1L, client.version)
        // Only the device language: the shipped resources are the fallback.
        assertEquals(1, server.bundleRequests)

        val (url, headers) = server.requests.first { it.first.contains("/manifest") }
        assertTrue(url.endsWith("?platform=android"))
        assertEquals("Bearer lzs_test_key_1234", headers["Authorization"])
        assertEquals("install-1", headers["X-LocalizeMe-Install"])
        assertEquals(Api.SDK_VERSION, headers["X-LocalizeMe-SDK"])
        assertEquals("1.0 (1)", headers["X-LocalizeMe-App"])
    }

    @Test fun allScopeDownloadsEveryLanguage() {
        val client = makeClient(scope = LanguageScope.ALL)
        assertEquals(CheckOutcome.Applied(1), start(client).getOrThrow())
        assertEquals(2, server.bundleRequests)
        assertEquals(setOf("en", "de"), client.current.bundles.keys)
    }

    @Test fun secondCheckIsConditionalAndUpToDate() {
        val client = makeClient()
        start(client)
        assertEquals(CheckOutcome.UpToDate, client.runCheck(force = true).getOrThrow())
        val (_, headers) = server.requests.last { it.first.contains("/manifest") }
        assertEquals(server.etag, headers["If-None-Match"])
        assertEquals(1, server.bundleRequests)
    }

    @Test fun aNewVersionIsStagedUntilAppliedOrNextStart() {
        val client = makeClient()
        start(client)
        var updated: Long? = null
        client.onUpdate = { updated = it }

        server.version = 2
        server.strings["de"] = mapOf("home_title" to "Start")
        assertEquals(CheckOutcome.Staged(2), client.runCheck(force = true).getOrThrow())
        assertEquals(2L, updated)
        // Still showing the old strings.
        assertEquals("Startseite", client.string("home_title"))
        assertTrue(client.hasStagedUpdate)

        assertTrue(client.applyStaged())
        assertEquals("Start", client.string("home_title"))
        assertEquals(2L, client.version)
        assertFalse(client.hasStagedUpdate)
    }

    @Test fun applyStagedIsSynchronousAndDoesNotFireOnUpdate() {
        val client = makeClient()
        start(client)
        server.version = 2
        server.strings["de"] = mapOf("home_title" to "Start")
        client.runCheck(force = true)
        val updates = ArrayList<Long>()
        client.onUpdate = { updates += it }

        // On the calling thread: the new strings are readable the moment it returns.
        assertTrue(client.applyStaged())
        assertEquals("Start", client.string("home_title"))
        assertEquals(2L, client.version)
        assertTrue("the caller asked for it; no onUpdate", updates.isEmpty())
        assertNull(client.staged)
        assertFalse(File(root, "staged.json").exists())
        // Nothing left to apply.
        assertFalse(client.applyStaged())
    }

    @Test fun aStagedVersionIsPromotedOnTheNextStart() {
        val first = makeClient()
        start(first)
        server.version = 2
        server.strings["de"] = mapOf("home_title" to "Start")
        first.runCheck(force = true)
        assertEquals("Startseite", first.string("home_title"))

        // "Next start": a fresh client over the same cache directory.
        val second = makeClient()
        assertEquals(2L, second.staged?.version)
        second.start()
        assertEquals("Start", second.string("home_title"))
        assertEquals(2L, second.version)
    }

    @Test fun aPromotionThatCannotBeSavedKeepsTheStagedVersion() {
        start(makeClient())
        server.version = 2
        server.strings["de"] = mapOf("home_title" to "Start")
        makeClient().runCheck(force = true)

        val readOnly = object : Store(root) {
            override fun saveSnapshot(snapshot: Snapshot, name: String) {
                if (name == "current") throw IOException("read-only file system")
                super.saveSnapshot(snapshot, name)
            }
        }
        val second = makeClient(store = readOnly)
        second.start()
        assertEquals("Startseite", second.string("home_title"))
        assertEquals(1L, second.version)
        assertEquals(2L, second.staged?.version)
        assertTrue(File(root, "staged.json").exists())
    }

    @Test fun applyImmediatelySwapsInPlace() {
        val client = makeClient(applyImmediately = true)
        start(client)
        server.version = 2
        server.strings["de"] = mapOf("home_title" to "Sofort")
        assertEquals(CheckOutcome.Applied(2), client.runCheck(force = true).getOrThrow())
        assertEquals("Sofort", client.string("home_title"))
    }

    @Test fun cachedStringsSurviveAnOfflineStart() {
        start(makeClient())
        server.offline = true
        val offline = makeClient()
        val outcome = start(offline)
        assertEquals("Startseite", offline.string("home_title"))
        assertEquals(ErrorKind.NETWORK, (outcome.exceptionOrNull() as LocalizeMeException).kind)
    }

    @Test fun aHashMismatchIsRejectedAndReported() {
        server.corruptBundles = true
        val client = makeClient()
        val outcome = start(client)
        assertEquals(ErrorKind.HASH_MISMATCH, (outcome.exceptionOrNull() as LocalizeMeException).kind)
        assertNull(client.string("home_title"))
        assertEquals(listOf("hash_mismatch"), errorTypes())
    }

    @Test fun aRevokedKeyKeepsTheCacheAndStopsChecking() {
        val client = makeClient()
        start(client)
        server.unauthorized = true
        val outcome = client.runCheck(force = true)
        assertEquals(ErrorKind.UNAUTHORIZED, (outcome.exceptionOrNull() as LocalizeMeException).kind)
        assertEquals("Startseite", client.string("home_title"))
        val before = server.manifestRequests
        assertEquals(CheckOutcome.Throttled, client.runCheck(force = false).getOrThrow())
        assertEquals(before, server.manifestRequests)
    }

    @Test fun changingLanguageDownloadsTheNewOne() {
        val client = makeClient(languages = listOf("en"))
        start(client)
        assertEquals("Home", client.string("home_title"))
        assertEquals(1, server.bundleRequests)

        client.setLanguage("de")
        client.drainQueue()
        assertEquals(2, server.bundleRequests)
        assertEquals("Startseite", client.string("home_title"))
        assertEquals("de", client.language)
    }

    @Test fun theManifestIsKeptForA304() {
        start(makeClient(languages = listOf("de")))
        assertTrue(File(root, "manifest.json").exists())

        // Same cache, the device now prefers English: the server says 304, the
        // cached manifest still tells us where the English bundle is.
        val second = makeClient(languages = listOf("en"))
        assertEquals(CheckOutcome.Applied(1), start(second).getOrThrow())
        assertEquals(listOf(200, 304), server.manifestStatuses)
        assertEquals(2, server.bundleRequests)
        assertEquals("Home", second.string("home_title"))
        assertEquals("en", second.language)
        assertEquals(setOf("de", "en"), second.current.bundles.keys)
    }

    @Test fun anETagWithoutItsManifestIsFetchedAgain() {
        start(makeClient(languages = listOf("de")))
        assertTrue(File(root, "manifest.json").delete())

        val second = makeClient(languages = listOf("en"))
        assertEquals(CheckOutcome.Applied(1), start(second).getOrThrow())
        // 304 for the stale ETag, then an unconditional request.
        assertEquals(listOf(200, 304, 200), server.manifestStatuses)
        assertNull(server.requests.last { it.first.contains("/manifest") }.second["If-None-Match"])
        assertEquals("Home", second.string("home_title"))
    }

    @Test fun aStagedVersionGetsTheNewLanguageAndIsNotStagedTwice() {
        val client = makeClient(languages = listOf("de"))
        start(client)
        server.version = 2
        server.strings["de"] = mapOf("home_title" to "Start")
        assertEquals(CheckOutcome.Staged(2), client.runCheck(force = true).getOrThrow())
        val updates = ArrayList<Long>()
        client.onUpdate = { updates += it }

        client.setLanguage("en")
        client.drainQueue()
        // de@1, de@2, and now en@2 — into the staged snapshot, which is what shows next.
        assertEquals(3, server.bundleRequests)
        assertEquals(setOf("de", "en"), client.staged?.bundles?.keys)
        assertEquals(setOf("de"), client.current.bundles.keys)
        assertEquals(listOf(2L), updates)

        // Everything wanted is staged already: another check changes nothing.
        assertEquals(CheckOutcome.UpToDate, client.runCheck(force = true).getOrThrow())
        assertEquals(3, server.bundleRequests)
        assertEquals(listOf(2L), updates)

        assertTrue(client.applyStaged())
        assertEquals("Home", client.string("home_title"))
        assertEquals("en", client.language)
        assertEquals(2L, client.version)
    }

    @Test fun deviceTagsResolveThroughTheManifestCodes() {
        server.strings = mutableMapOf("no" to mapOf("home_title" to "Hjem"), "en" to mapOf("home_title" to "Home"))
        server.codes["no"] = listOf("nb", "nn")
        val client = makeClient(languages = listOf("nb-NO", "en"))
        assertEquals(CheckOutcome.Applied(1), start(client).getOrThrow())
        assertEquals("no", client.language)
        assertEquals("Hjem", client.string("home_title"))
        assertEquals(listOf("no", "nb", "nn"), client.current.codes["no"])

        // The codes are persisted, so the same choice is made offline on the next start.
        server.offline = true
        val second = makeClient(languages = listOf("nb-NO", "en"))
        second.start()
        assertEquals("no", second.language)
    }

    @Test fun aCorruptCacheReportsVersionZeroAndRecovers() {
        val first = makeClient()
        start(first)
        val sha = first.current.bundles.getValue("de")
        Store(root).bundleFile(sha).writeText("{not json")

        val client = makeClient()
        client.start()
        assertEquals(0L, client.version)
        assertNull(client.string("home_title"))
        assertNull(client.staged)
        assertFalse(File(root, "current.json").exists())

        // The wipe also dropped the ETag, so the next check starts over.
        assertEquals(CheckOutcome.Applied(1), client.runCheck(force = true).getOrThrow())
        assertEquals("Startseite", client.string("home_title"))
        assertEquals(1L, client.version)
        assertTrue(errorTypes().contains("storage"))
    }

    @Test fun aFailingStoreIsReportedAndTheExecutorSurvives() {
        val fullDisk = object : Store(root) {
            override fun saveBundle(bytes: ByteArray, sha256: String) {
                throw IOException("No space left on device")
            }
        }
        val client = makeClient(store = fullDisk)
        val outcome = start(client)
        val error = outcome.exceptionOrNull() as LocalizeMeException
        assertEquals(ErrorKind.STORAGE, error.kind)
        assertTrue(error.message!!.contains("No space left"))
        assertNull(client.string("home_title"))

        // The same failure through the executor: it is reported, nothing propagates.
        var reported: Result<CheckOutcome>? = null
        client.check(force = true) { reported = it }
        client.drainQueue()
        assertNotNull(reported)
        assertEquals(ErrorKind.STORAGE, (reported!!.exceptionOrNull() as LocalizeMeException).kind)
        assertEquals(listOf("storage"), errorTypes().distinct())
    }

    @Test fun missingKeysAreReportedWithTheNextCheck() {
        val client = makeClient()
        start(client)
        client.noteMissing("settings_title")
        client.runCheck(force = true)
        assertEquals(listOf("settings_title"), missingKeys())
    }

    @Test fun theThrottleSkipsBackToBackChecks() {
        val client = makeClient()
        start(client)
        assertEquals(CheckOutcome.Throttled, client.runCheck(force = false).getOrThrow())
    }
}
