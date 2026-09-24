package app.localizeme.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class LanguageResolverTest {
    /** Languages that answer only to their own code. */
    private fun plain(vararg codes: String): Map<String, List<String>> = codes.associateWith { listOf(it) }

    @Test fun exactMatchWinsOverBase() =
        assertEquals("pt-BR", LanguageResolver.resolve(listOf("pt-BR"), plain("pt", "pt-BR"), null))

    @Test fun fallsBackToBaseLanguage() =
        assertEquals("pt", LanguageResolver.resolve(listOf("pt-BR", "en"), plain("pt", "en"), null))

    @Test fun nextPreferredWhenFirstIsUnpublished() =
        assertEquals("de", LanguageResolver.resolve(listOf("fi", "de-DE"), plain("en", "de"), null))

    @Test fun underscoresAndCaseDoNotMatter() =
        assertEquals("zh-Hant", LanguageResolver.resolve(listOf("ZH_hant"), plain("zh-Hant"), null))

    @Test fun subtagsAreStrippedOneAtATime() {
        assertEquals("zh-Hant", LanguageResolver.resolve(listOf("zh-Hant-TW"), plain("zh", "zh-Hant"), null))
        assertEquals("zh", LanguageResolver.resolve(listOf("zh-Hans-CN"), plain("zh", "zh-Hant"), null))
    }

    @Test fun aliasCodesMapBackToTheCatalogCode() {
        val available = mapOf("no" to listOf("no", "nb", "nn"), "en" to listOf("en"))
        assertEquals("no", LanguageResolver.resolve(listOf("nb-NO", "en"), available, null))
        assertEquals("no", LanguageResolver.resolve(listOf("en"), available, "nb"))
    }

    @Test fun overrideWins() =
        assertEquals("lt", LanguageResolver.resolve(listOf("en"), plain("en", "lt"), "lt"))

    @Test fun anUnknownOverrideFallsBackToThePreferredList() =
        assertEquals("en", LanguageResolver.resolve(listOf("en"), plain("en", "lt"), "fi"))

    @Test fun nothingMatches() {
        assertNull(LanguageResolver.resolve(listOf("fi"), plain("en"), null))
        assertNull(LanguageResolver.resolve(listOf("fi"), emptyMap(), "fi"))
    }

    @Test fun anInjectedMatcherIsHonouredAndMappedBack() {
        val matcher = LanguageResolver.Matcher { preferred, codes ->
            assertEquals(listOf("nb-NO"), preferred)
            assertTrue(codes.containsAll(listOf("no", "nb", "en")))
            "nb"
        }
        assertEquals("no", LanguageResolver.resolve(listOf("nb-NO"), mapOf("no" to listOf("nb"), "en" to listOf("en")), null, matcher))
    }

    @Test fun aFailingMatcherFallsBackToTheStripping() {
        val matcher = LanguageResolver.Matcher { _, _ -> throw IllegalArgumentException("bad range") }
        assertEquals("de", LanguageResolver.resolve(listOf("de-AT"), plain("de", "en"), null, matcher))
    }
}

class FormatsTest {
    @Test fun plainStringsAreCompatible() {
        assertTrue(Formats.compatible("Hello", "Hallo"))
        assertTrue(Formats.compatible("Hello %s", "Hallo %s"))
    }

    @Test fun reorderedPositionalArgumentsAreCompatible() =
        assertTrue(Formats.compatible("%1\$s has %2\$d items", "%2\$d Artikel bei %1\$s"))

    @Test fun percentAndNewlineTakeNoArgument() {
        assertTrue(Formats.compatible("%d%%", "%d %%"))
        assertTrue(Formats.compatible("%s%n", "%s"))
        assertTrue(Formats.compatible("100%%", "100 %%"))
    }

    @Test fun precisionWidthAndFlagsDoNotMatter() {
        assertTrue(Formats.compatible("%.2f", "%,10.1f"))
        assertTrue(Formats.compatible("%05d", "%d"))
        assertTrue(Formats.compatible("%1\$S", "%1\$s"))
    }

    @Test fun aChangedConversionIsIncompatible() =
        assertFalse(Formats.compatible("%1\$d items", "%1\$s Stück"))

    @Test fun aMissingOrExtraPlaceholderIsIncompatible() {
        assertFalse(Formats.compatible("%1\$s and %2\$s", "%1\$s"))
        assertFalse(Formats.compatible("%s", "%s %s"))
        assertFalse(Formats.compatible("Hello", "Hello %s"))
    }

    @Test fun dateConversionsKeepTheirPrefix() {
        assertTrue(Formats.compatible("%1\$tY", "%1\$TY"))
        assertFalse(Formats.compatible("%1\$tY", "%1\$s"))
    }

    @Test fun relativeIndexRefersToThePreviousArgument() =
        assertTrue(Formats.compatible("%s %<s", "%1\$s %1\$s"))

    @Test fun theElevenStyleTagsMatchTheServer() =
        assertEquals(
            setOf("b", "i", "u", "font", "a", "big", "small", "sup", "sub", "strike", "tt"),
            Formats.STYLE_TAGS,
        )

    @Test fun markupIsRecognised() {
        assertTrue(Formats.hasMarkup("Read the <b>manual</b>"))
        assertTrue(Formats.hasMarkup("<a href=\"https://x\">link</a>"))
        assertTrue(Formats.hasMarkup("<FONT color='red'>x</FONT>"))
        assertTrue(Formats.hasMarkup("E = mc<sup>2</sup>, <tt>code</tt>, <big>big</big>"))
        assertTrue(Formats.hasMarkup("Hello <b>bold <i>both</i></b> end"))
    }

    @Test fun proseIsNotMarkup() {
        assertFalse(Formats.hasMarkup("5 < 6"))
        assertFalse(Formats.hasMarkup("a <b c"))
        assertFalse(Formats.hasMarkup("<div>block</div>"))
        assertFalse(Formats.hasMarkup("<bold>"))
        assertFalse(Formats.hasMarkup("plain"))
    }

    @Test fun tagsOutsideTheElevenAreText() {
        // Html.fromHtml would style these; aapt does not, so neither does the SDK.
        assertFalse(Formats.hasMarkup("line<br/>break"))
        assertFalse(Formats.hasMarkup("<em>x</em> <strong>y</strong> <s>z</s>"))
    }

    @Test fun unpairedTagsAreText() {
        assertFalse(Formats.hasMarkup("<b>unclosed"))
        assertFalse(Formats.hasMarkup("a stray </b> close"))
    }

    @Test fun htmlKeepsTheElevenAndEscapesEverythingElse() {
        assertEquals("Read the <b>terms</b> &amp; more", Formats.toHtml("Read the <b>terms</b> & more"))
        assertEquals(
            "x &lt;em&gt;y&lt;/em&gt; <b>z</b>",
            Formats.toHtml("x <em>y</em> <b>z</b>"),
        )
        assertEquals("5 &lt; 6 <i>x</i><br>next", Formats.toHtml("5 < 6 <i>x</i>\nnext"))
        assertEquals("&lt;b&gt;unclosed <i>x</i>", Formats.toHtml("<b>unclosed <i>x</i>"))
        assertEquals("<B>x</B>", Formats.toHtml("<B>x</B>"))
    }

    @Test fun htmlEscapesTextEntitiesButLeavesAttributesAsWritten() {
        // Text "&amp;" is shown as written, like the exported strings.xml; an
        // attribute keeps its source, which fromHtml decodes as aapt would.
        assertEquals(
            "AT&amp;T &amp;amp; <a href=\"https://e.com/?a=1&amp;b=2\">x</a>",
            Formats.toHtml("AT&T &amp; <a href=\"https://e.com/?a=1&amp;b=2\">x</a>"),
        )
    }
}

class StoreTest {
    private val store = Store(Files.createTempDirectory("lz").toFile())

    @Test fun snapshotRoundTrip() {
        assertNull(store.loadSnapshot("current"))
        store.saveSnapshot(Snapshot(7, "en", mapOf("de" to "abc"), mapOf("de" to listOf("de", "de-DE"))), "current")
        assertEquals(7L, store.loadSnapshot("current")?.version)
        assertEquals(mapOf("de" to "abc"), store.loadSnapshot("current")?.bundles)
        assertEquals(listOf("de", "de-DE"), store.loadSnapshot("current")?.codes?.get("de"))
        store.saveSnapshot(Snapshot(8, null, emptyMap()), "current")
        assertEquals(8L, store.loadSnapshot("current")?.version)
        assertNull(store.loadSnapshot("current")?.sourceLanguage)
        store.removeSnapshot("current")
        assertNull(store.loadSnapshot("current"))
        store.wipe()
    }

    @Test fun manifestRoundTrip() {
        assertNull(store.loadManifest())
        val manifest = Manifest(
            3, "android", "en",
            mapOf("no" to ManifestLanguage("https://x/no.json", "ab", 10, 1, listOf("no", "nb"))),
        )
        store.saveManifest(manifest)
        assertEquals(manifest, store.loadManifest())
        File(store.root, "manifest.json").writeText("{broken")
        assertNull(store.loadManifest())
        store.wipe()
    }

    @Test fun bundlesArePrunedByHash() {
        val body = """{"v":1,"project":1,"platform":"android","lang":"de","strings":{"a":"b"}}""".toByteArray()
        store.saveBundle(body, "keep")
        store.saveBundle(body, "drop")
        assertEquals(mapOf("a" to "b"), store.loadBundle("keep"))
        store.pruneBundles(setOf("keep"))
        assertTrue(store.hasBundle("keep"))
        assertFalse(store.hasBundle("drop"))
        store.wipe()
    }

    @Test fun etagRoundTrip() {
        assertNull(store.manifestETag)
        store.manifestETag = "\"abc\""
        assertEquals("\"abc\"", store.manifestETag)
        store.manifestETag = null
        assertNull(store.manifestETag)
        store.wipe()
    }

    @Test fun aCorruptBundleReadsAsMissing() {
        File(store.root, "bundles").mkdirs()
        store.bundleFile("bad").writeText("{not json")
        assertNull(store.loadBundle("bad"))
        store.wipe()
    }

    @Test fun aFailedWriteLeavesNoTemporaryFile() {
        // A non-empty directory where the file should go: the rename cannot succeed.
        File(store.root, "current.json/blocker").apply { parentFile!!.mkdirs(); writeText("x") }
        try {
            store.saveSnapshot(Snapshot.EMPTY, "current")
            throw AssertionError("expected the write to fail")
        } catch (e: LocalizeMeException) {
            assertEquals(ErrorKind.STORAGE, e.kind)
        }
        assertTrue(store.root.listFiles()!!.none { it.name.endsWith(".tmp") })
        store.wipe()
    }
}

class ReporterTest {
    @Test fun errorsAreDeduplicatedByKindPerStart() {
        val reporter = Reporter()
        reporter.recordError(LocalizeMeException(ErrorKind.NETWORK, "one"))
        reporter.recordError(LocalizeMeException(ErrorKind.NETWORK, "two"))
        reporter.recordError(LocalizeMeException(ErrorKind.STORAGE, "three"))
        val (errors, _) = reporter.drain()
        assertEquals(listOf("network", "storage"), errors.map { it["type"] })
        assertTrue(reporter.isEmpty)
    }

    @Test fun missingKeysAreDeduplicatedAndCapped() {
        val reporter = Reporter()
        reporter.recordMissingKey("a", "de")
        reporter.recordMissingKey("a", "de")
        reporter.recordMissingKey("a", "en")
        repeat(500) { reporter.recordMissingKey("k$it", null) }
        val (_, missing) = reporter.drain()
        assertEquals(Reporter.MAX_MISSING_KEYS, missing.size)
        assertEquals(2, missing.count { it["key"] == "a" })
    }

    @Test fun restorePutsFailedReportsBack() {
        val reporter = Reporter()
        reporter.recordError(LocalizeMeException(ErrorKind.NETWORK, "x"))
        val (errors, missing) = reporter.drain()
        assertTrue(reporter.isEmpty)
        reporter.restore(errors, missing)
        assertFalse(reporter.isEmpty)
    }
}

class Sha256Test {
    @Test fun knownDigest() =
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Sha256.hex("abc".toByteArray()))
}

class ManifestTest {
    @Test fun parsesTheEnvelope() {
        val manifest = Manifest.parse(
            """{"success":true,"data":{"version":3,"platform":"android","source_language":"en",
               "languages":{"de":{"url":"https://x/de.json","sha256":"ab","size":10,"strings":1},
                            "no":{"url":"https://x/no.json","sha256":"cd","size":10,"strings":1,"codes":["no","nb"]}}}}""",
        )
        assertEquals(3L, manifest.version)
        assertEquals("en", manifest.sourceLanguage)
        assertEquals("ab", manifest.languages["de"]?.sha256)
        // Without codes a language answers to its own code only.
        assertEquals(mapOf("de" to listOf("de"), "no" to listOf("no", "nb")), manifest.codes)
    }

    @Test fun serialisesAndParsesItsOwnData() {
        val manifest = Manifest.parse(
            """{"success":true,"data":{"version":3,"platform":"android","source_language":null,
               "languages":{"no":{"url":"https://x/no.json","sha256":"cd","size":10,"strings":1,"codes":["no","nb"]}}}}""",
        )
        assertEquals(manifest, Manifest.parseData(org.json.JSONObject(manifest.toJson())))
        assertNull(manifest.sourceLanguage)
    }

    @Test fun aMissingBodyIsABadResponse() {
        try {
            Manifest.parse("""{"success":false,"message":"nope"}""")
            throw AssertionError("expected an exception")
        } catch (e: LocalizeMeException) {
            assertEquals(ErrorKind.BAD_RESPONSE, e.kind)
        }
    }
}
