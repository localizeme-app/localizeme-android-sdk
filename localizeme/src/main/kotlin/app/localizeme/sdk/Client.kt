package app.localizeme.sdk

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

/**
 * The SDK's state: which strings are live, what is staged, and when to check.
 *
 * Pure JVM, so it is unit-tested without Android. Checks run on a
 * single-thread executor; the few methods that change [current] and [staged]
 * are synchronized because [applyStaged] runs on the caller's thread. String
 * lookups read an immutable map, so they never wait on a download.
 *
 * Nothing here may kill the host app: every executor task and every check
 * catches [Exception], and the SDK thread logs an uncaught error instead of
 * handing it to the process's default handler.
 */
internal class Client(
    val config: LocalizeMeConfig,
    val store: Store,
    val api: Api,
    var preferredLanguages: () -> List<String>,
    private val matcher: LanguageResolver.Matcher? = null,
    executor: ExecutorService? = null,
    private val logger: (String) -> Unit = {},
) {
    val reporter = Reporter()

    private val executor: ExecutorService = executor ?: Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "localizeme-sdk").apply {
            isDaemon = true
            setUncaughtExceptionHandler { _, error -> logger("uncaught on the SDK thread: $error") }
        }
    }

    private class Live(val strings: Map<String, String>, val language: String?, val version: Long)

    private val live = AtomicReference(Live(emptyMap(), null, 0))

    @Volatile var current: Snapshot = store.loadSnapshot("current") ?: Snapshot.EMPTY
        private set
    @Volatile var staged: Snapshot? = store.loadSnapshot("staged")
        private set
    @Volatile private var manifest: Manifest? = null
    @Volatile private var languageOverride: String? = config.language
    private var lastCheck = 0L
    @Volatile private var stopped = false

    /** Called on the SDK thread with the new version; the facade hops to main. */
    @Volatile var onUpdate: ((Long) -> Unit)? = null

    @Synchronized
    fun start() {
        // A staged snapshot is what "next start" means: promote it now. When
        // the promotion cannot be saved it stays staged for the next start.
        val next = staged
        if (next != null) {
            try {
                promote(next, clearStaged = true)
                return
            } catch (e: Exception) {
                record(e, "promote")
            }
        }
        loadLiveStrings()
    }

    /** Stops checking and drops queued work. The live strings stay readable. */
    fun shutdown() {
        stopped = true
        onUpdate = null
        executor.shutdownNow()
    }

    // MARK: lookup

    fun string(key: String): String? = live.get().strings[key]

    val version: Long get() = live.get().version
    val language: String? get() = live.get().language
    val hasStagedUpdate: Boolean get() = staged != null

    fun noteMissing(key: String) {
        if (config.reportMissingKeys) reporter.recordMissingKey(key, language)
    }

    fun setLanguage(code: String?) {
        enqueue {
            languageOverride = code
            synchronized(this) { loadLiveStrings() }
            // The new language may not be on disk yet.
            runCheck(force = true)
        }
    }

    private fun resolvedLanguage(snapshot: Snapshot): String? {
        val available = snapshot.bundles.keys.associateWith { code -> snapshot.codes[code] ?: listOf(code) }
        return LanguageResolver.resolve(preferredLanguages(), available, languageOverride, matcher)
    }

    private fun wantedLanguages(manifest: Manifest): List<String> = when (config.languageScope) {
        LanguageScope.ALL -> manifest.languages.keys.toList()
        LanguageScope.DEVICE ->
            listOfNotNull(LanguageResolver.resolve(preferredLanguages(), manifest.codes, languageOverride, matcher))
    }

    private fun loadLiveStrings() {
        var strings: Map<String, String> = emptyMap()
        var chosen: String? = null
        val snapshot = current
        val language = resolvedLanguage(snapshot)
        val sha = language?.let { snapshot.bundles[it] }
        if (language != null && sha != null) {
            val loaded = store.loadBundle(sha)
            if (loaded != null) {
                strings = loaded
                chosen = language
            } else {
                // A snapshot points at a bundle the cache no longer has: start over.
                reporter.recordError(LocalizeMeException(ErrorKind.STORAGE, "cache_corrupt"))
                store.wipe()
                current = Snapshot.EMPTY
                staged = null
                manifest = null
            }
        }
        api.language = chosen
        live.set(Live(strings, chosen, current.version))
        logger("live: ${strings.size} strings, language ${chosen ?: "-"}, version ${current.version}")
    }

    // MARK: checking

    /** Check on the SDK thread; [callback] runs there too. */
    fun check(force: Boolean, callback: ((Result<CheckOutcome>) -> Unit)? = null) {
        enqueue {
            val result = runCheck(force)
            callback?.invoke(result)
        }
    }

    /** Runs a check on the calling thread and waits for it. Tests and [check] use this. */
    fun runCheck(force: Boolean): Result<CheckOutcome> {
        val now = System.currentTimeMillis()
        if (!force && (stopped || now - lastCheck < config.minimumCheckIntervalMs)) {
            return Result.success(CheckOutcome.Throttled)
        }
        lastCheck = now
        return try {
            var result = api.fetchManifest(store.manifestETag)
            if (result is Api.ManifestResult.NotModified && manifest == null) {
                manifest = store.loadManifest()
                // An ETag without the manifest it stands for: ask again without it.
                if (manifest == null) result = api.fetchManifest(null)
            }
            val outcome = when (result) {
                is Api.ManifestResult.NotModified -> {
                    logger("up to date at version ${current.version}")
                    manifest?.let { fill(it) } ?: CheckOutcome.UpToDate
                }
                is Api.ManifestResult.Fresh -> {
                    manifest = result.manifest
                    store.saveManifest(result.manifest)
                    val wanted = wantedLanguages(result.manifest).filter { code -> !onDisk(result.manifest, code) }
                    download(result.manifest, wanted, result.etag)
                }
            }
            sendReports()
            Result.success(outcome)
        } catch (e: Exception) {
            val error = record(e, "check")
            if (error.kind == ErrorKind.UNAUTHORIZED) {
                // A revoked key: keep what we have and stop asking.
                stopped = true
            }
            runCatching { sendReports() }
            Result.failure(error)
        }
    }

    /**
     * The manifest is current, but the device may want a language the pending
     * snapshot does not have yet (the device language changed). A staged
     * version is the one that will show next, so it is the one to complete.
     */
    private fun fill(manifest: Manifest): CheckOutcome {
        val target = staged ?: current
        val missing = wantedLanguages(manifest).filter { code -> target.bundles[code] == null }
        if (missing.isEmpty()) return CheckOutcome.UpToDate
        return download(manifest, missing.filter { code -> !onDisk(manifest, code) }, store.manifestETag)
    }

    private fun onDisk(manifest: Manifest, code: String): Boolean {
        val language = manifest.languages[code] ?: return true
        return store.hasBundle(language.sha256)
    }

    private fun download(manifest: Manifest, languages: List<String>, etag: String?): CheckOutcome {
        for (code in languages) {
            val language = manifest.languages[code] ?: continue
            val bytes = api.fetchBundle(language)
            store.saveBundle(bytes, language.sha256)
        }
        return commit(manifest, etag)
    }

    @Synchronized
    private fun commit(manifest: Manifest, etag: String?): CheckOutcome {
        val bundles = LinkedHashMap<String, String>()
        for ((code, language) in manifest.languages) {
            if (store.hasBundle(language.sha256)) bundles[code] = language.sha256
        }
        val snapshot = Snapshot(manifest.version, manifest.sourceLanguage, bundles, manifest.codes)
        store.manifestETag = etag

        if (snapshot == current || snapshot == staged) return CheckOutcome.UpToDate

        val outcome: CheckOutcome
        if (snapshot.version == current.version) {
            // Same strings, one more language on disk — the one just asked for.
            // It goes live now; a staged newer version stays staged.
            promote(snapshot, clearStaged = false)
            outcome = CheckOutcome.Applied(snapshot.version)
        } else if (config.applyImmediately || current.bundles.isEmpty()) {
            // First run has nothing to disturb: apply straight away.
            promote(snapshot, clearStaged = true)
            outcome = CheckOutcome.Applied(snapshot.version)
        } else {
            store.saveSnapshot(snapshot, "staged")
            staged = snapshot
            outcome = CheckOutcome.Staged(snapshot.version)
        }
        val keep = HashSet(current.bundles.values)
        staged?.let { keep.addAll(it.bundles.values) }
        store.pruneBundles(keep)
        logger("version ${snapshot.version} ${if (outcome is CheckOutcome.Applied) "applied" else "staged"}")
        onUpdate?.invoke(snapshot.version)
        return outcome
    }

    /**
     * Makes [snapshot] the live one. Throws when it cannot be saved, in which
     * case nothing has changed.
     */
    private fun promote(snapshot: Snapshot, clearStaged: Boolean) {
        store.saveSnapshot(snapshot, "current")
        current = snapshot
        if (clearStaged) {
            staged = null
            store.removeSnapshot("staged")
        }
        loadLiveStrings()
    }

    /**
     * Promotes the staged snapshot on the calling thread. True when the live
     * strings changed. The caller asked for this, so [onUpdate] does not fire.
     */
    @Synchronized
    fun applyStaged(): Boolean {
        val next = staged ?: return false
        return try {
            promote(next, clearStaged = true)
            store.pruneBundles(HashSet(current.bundles.values))
            true
        } catch (e: Exception) {
            record(e, "apply")
            false
        }
    }

    private fun record(e: Exception, what: String): LocalizeMeException {
        val error = e as? LocalizeMeException
            ?: LocalizeMeException(ErrorKind.STORAGE, "${e.javaClass.simpleName}: ${e.message}")
        reporter.recordError(error)
        logger("$what failed: ${error.message}")
        return error
    }

    private fun sendReports() {
        if (reporter.isEmpty) return
        val (errors, missing) = reporter.drain()
        if (!api.report(errors, missing)) {
            reporter.restore(errors, missing)
        }
    }

    /** Runs [block] on the SDK thread; whatever it throws is logged, never propagated. */
    private fun enqueue(block: () -> Unit) {
        try {
            executor.execute {
                try {
                    block()
                } catch (e: Exception) {
                    logger("sdk task failed: $e")
                }
            }
        } catch (e: RejectedExecutionException) {
            logger("sdk stopped; dropped a task")
        }
    }

    /** Waits for everything queued so far. Tests use it. */
    fun drainQueue() {
        executor.submit {}.get()
    }
}
