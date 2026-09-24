package app.localizeme.sdk

/**
 * Buffers what the SDK wants to tell the dashboard and sends it with the next
 * check, so reporting never costs a request of its own on start.
 */
internal class Reporter {
    private val errors = ArrayList<Map<String, String>>()
    private val seenErrorKinds = HashSet<String>()
    private val missing = LinkedHashMap<String, Map<String, String>>()

    @Synchronized
    fun recordError(error: LocalizeMeException) {
        // One report per kind per start: a failing cache read would otherwise
        // report once per string lookup.
        if (error.kind.wireName in seenErrorKinds || errors.size >= MAX_ERRORS) return
        seenErrorKinds += error.kind.wireName
        errors += mapOf("type" to error.kind.wireName, "message" to (error.message ?: "").take(500))
    }

    @Synchronized
    fun recordMissingKey(key: String, language: String?) {
        if (missing.size >= MAX_MISSING_KEYS) return
        val id = "${language ?: ""}\u0000$key"
        if (id !in missing) {
            val entry = HashMap<String, String>()
            entry["key"] = key
            if (language != null) entry["language"] = language
            missing[id] = entry
        }
    }

    /** Everything buffered, cleared. Put it back with [restore] if sending fails. */
    @Synchronized
    fun drain(): Pair<List<Map<String, String>>, List<Map<String, String>>> {
        val out = Pair(errors.toList(), missing.values.toList())
        errors.clear()
        missing.clear()
        return out
    }

    @Synchronized
    fun restore(errors: List<Map<String, String>>, missingKeys: List<Map<String, String>>) {
        this.errors.addAll(0, errors)
        for (entry in missingKeys) {
            val id = "${entry["language"] ?: ""}\u0000${entry["key"] ?: ""}"
            missing.putIfAbsent(id, entry)
        }
    }

    val isEmpty: Boolean
        @Synchronized get() = errors.isEmpty() && missing.isEmpty()

    companion object {
        const val MAX_ERRORS = 20
        const val MAX_MISSING_KEYS = 200
    }
}
