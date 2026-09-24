package app.localizeme.sdk

import java.util.Locale

/**
 * Picks which published language a device should show, the way the OS picks
 * one: the most specific match for the preferred locale first, then its
 * parents (`zh-Hant-TW` → `zh-Hant` → `zh`), then the next preferred locale.
 *
 * A language answers to every code the manifest lists for it (`no` also to
 * `nb` and `nb-NO`); the result is always the catalog code the bundles are
 * keyed by.
 */
internal object LanguageResolver {
    /**
     * A lookup over BCP 47 tags: the preferred ranges and every code offered,
     * returning the matching code or null. Injected so the pure fallback is
     * what unit tests exercise; [systemMatcher] is used on a device.
     */
    fun interface Matcher {
        fun match(preferred: List<String>, codes: Collection<String>): String?
    }

    /** RFC 4647 lookup through `java.util.Locale`, available from API 26. */
    val systemMatcher: Matcher = Matcher { preferred, codes ->
        if (android.os.Build.VERSION.SDK_INT < 26) return@Matcher null
        try {
            val ranges = Locale.LanguageRange.parse(preferred.joinToString(","))
            Locale.lookupTag(ranges, codes)
        } catch (e: Exception) {
            null
        }
    }

    fun resolve(
        preferred: List<String>,
        available: Map<String, List<String>>,
        override: String?,
        matcher: Matcher? = null,
    ): String? {
        if (available.isEmpty()) return null
        // Every spelling → the catalog code; the catalog code itself always counts.
        val byNormalized = LinkedHashMap<String, String>()
        for ((code, codes) in available) {
            byNormalized.putIfAbsent(normalize(code), code)
            for (alias in codes) byNormalized.putIfAbsent(normalize(alias), code)
        }
        val allCodes = byNormalized.keys.map { it.replace('_', '-') }

        fun find(wanted: List<String>): String? {
            if (wanted.isEmpty()) return null
            val tags = wanted.map { it.replace('_', '-') }.filter { it.isNotEmpty() }
            if (matcher != null) {
                val hit = runCatching { matcher.match(tags, allCodes) }.getOrNull()
                if (hit != null) byNormalized[normalize(hit)]?.let { return it }
            }
            for (tag in tags) {
                var candidate = normalize(tag)
                while (candidate.isNotEmpty()) {
                    byNormalized[candidate]?.let { return it }
                    val cut = candidate.lastIndexOf('-')
                    if (cut < 0) break
                    candidate = candidate.substring(0, cut)
                }
            }
            return null
        }

        if (override != null) find(listOf(override))?.let { return it }
        return find(preferred)
    }

    /** `pt-BR` and `pt_BR` are the same language; case does not matter either. */
    fun normalize(code: String): String = code.replace('_', '-').lowercase()
}
