package app.localizeme.sdk

import org.json.JSONArray
import org.json.JSONObject

internal data class ManifestLanguage(
    val url: String,
    val sha256: String,
    val size: Int,
    val strings: Int,
    /** Every spelling this language answers to (`no`, `nb`, `nb-NO`), the catalog code first. */
    val codes: List<String> = emptyList(),
)

internal data class Manifest(
    val version: Long,
    val platform: String,
    val sourceLanguage: String?,
    val languages: Map<String, ManifestLanguage>,
) {
    /** language code → the codes it answers to, for [LanguageResolver]. */
    val codes: Map<String, List<String>>
        get() = languages.mapValues { (code, language) -> language.codes.ifEmpty { listOf(code) } }

    /** The `data` object of the server's envelope; [Store] keeps it for the 304 case. */
    fun toJson(): String {
        val json = JSONObject()
        json.put("version", version)
        json.put("platform", platform)
        json.put("source_language", sourceLanguage ?: JSONObject.NULL)
        val entries = JSONObject()
        for ((code, language) in languages) {
            val entry = JSONObject()
            entry.put("url", language.url)
            entry.put("sha256", language.sha256)
            entry.put("size", language.size)
            entry.put("strings", language.strings)
            entry.put("codes", JSONArray(language.codes))
            entries.put(code, entry)
        }
        json.put("languages", entries)
        return json.toString()
    }

    companion object {
        /** The server's response: an envelope with the manifest under `data`. */
        fun parse(body: String): Manifest {
            val envelope = JSONObject(body)
            val data = envelope.optJSONObject("data")
                ?: throw LocalizeMeException(ErrorKind.BAD_RESPONSE, envelope.optString("message", "empty manifest"))
            return parseData(data)
        }

        /** The bare manifest, as [toJson] writes it. */
        fun parseData(data: JSONObject): Manifest {
            val languages = LinkedHashMap<String, ManifestLanguage>()
            val json = data.getJSONObject("languages")
            for (code in json.keys()) {
                val entry = json.getJSONObject(code)
                val codes = ArrayList<String>()
                entry.optJSONArray("codes")?.let { array ->
                    for (i in 0 until array.length()) array.optString(i).takeIf { it.isNotEmpty() }?.let { codes += it }
                }
                languages[code] = ManifestLanguage(
                    url = entry.getString("url"),
                    sha256 = entry.getString("sha256"),
                    size = entry.optInt("size"),
                    strings = entry.optInt("strings"),
                    codes = codes,
                )
            }
            return Manifest(
                version = data.getLong("version"),
                platform = data.optString("platform", "android"),
                sourceLanguage = if (data.isNull("source_language")) null else data.optString("source_language"),
                languages = languages,
            )
        }
    }
}

/** What is on disk and in memory: a version plus one bundle per downloaded language. */
internal data class Snapshot(
    val version: Long,
    val sourceLanguage: String?,
    /** language code → sha256 of the bundle file on disk */
    val bundles: Map<String, String>,
    /** language code → every code it answers to, so resolution works offline */
    val codes: Map<String, List<String>> = emptyMap(),
) {
    fun toJson(): String {
        val json = JSONObject()
        json.put("version", version)
        json.put("source_language", sourceLanguage ?: JSONObject.NULL)
        json.put("bundles", JSONObject(bundles))
        val codesJson = JSONObject()
        for ((code, list) in codes) codesJson.put(code, JSONArray(list))
        json.put("codes", codesJson)
        return json.toString()
    }

    companion object {
        val EMPTY = Snapshot(0, null, emptyMap())

        fun parse(text: String): Snapshot {
            val json = JSONObject(text)
            val bundles = LinkedHashMap<String, String>()
            val map = json.getJSONObject("bundles")
            for (code in map.keys()) bundles[code] = map.getString(code)
            val codes = LinkedHashMap<String, List<String>>()
            json.optJSONObject("codes")?.let { codesJson ->
                for (code in codesJson.keys()) {
                    val array = codesJson.optJSONArray(code) ?: continue
                    codes[code] = (0 until array.length()).mapNotNull { array.optString(it).takeIf { s -> s.isNotEmpty() } }
                }
            }
            return Snapshot(
                version = json.getLong("version"),
                sourceLanguage = if (json.isNull("source_language")) null else json.optString("source_language"),
                bundles = bundles,
                codes = codes,
            )
        }
    }
}

/** What an update check found. */
sealed class CheckOutcome {
    /** The server said nothing changed. */
    object UpToDate : CheckOutcome()
    /** New strings were downloaded and applied. */
    data class Applied(val version: Long) : CheckOutcome()
    /** New strings were downloaded and will apply on the next start or on [LocalizeMe.applyNow]. */
    data class Staged(val version: Long) : CheckOutcome()
    /** The check was skipped because one ran less than the minimum interval ago. */
    object Throttled : CheckOutcome()
}

enum class ErrorKind(val wireName: String) {
    NOT_STARTED("not_started"),
    UNAUTHORIZED("unauthorized"),
    BAD_RESPONSE("bad_response"),
    HASH_MISMATCH("hash_mismatch"),
    NETWORK("network"),
    STORAGE("storage"),
}

class LocalizeMeException(val kind: ErrorKind, message: String) : Exception("${kind.wireName}: $message")
