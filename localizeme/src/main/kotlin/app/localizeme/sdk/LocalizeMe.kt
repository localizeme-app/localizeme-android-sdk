package app.localizeme.sdk

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import java.io.File
import java.lang.ref.WeakReference
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Over-the-air translations from LocalizeMe.
 *
 * ```kotlin
 * // Application.onCreate
 * LocalizeMe.start(this, LocalizeMeConfig(sdkKey = "lzs_…"))
 *
 * // every Activity
 * override fun attachBaseContext(base: Context) {
 *     super.attachBaseContext(LocalizeMe.wrap(base))
 * }
 * ```
 *
 * After that, `getString`/`getText`, the text of views inflated from XML and
 * Compose's `stringResource` return the latest approved strings for the
 * device's language, falling back to what the app shipped with. The SDK checks
 * on start and when the app returns to the foreground, and applies changes on
 * the next process start unless `applyImmediately` is set or [applyNow] is
 * called.
 */
object LocalizeMe {
    @Volatile private var client: Client? = null
    private val main = Handler(Looper.getMainLooper())
    private val observing = AtomicBoolean(false)
    private val listening = AtomicBoolean(false)
    /** Every OtaResources alive, so a configuration change reaches them all at once. */
    private val resources = CopyOnWriteArrayList<WeakReference<OtaResources>>()
    /** The most recently wrapped UI context: its locales are the ones the app renders. */
    @Volatile private var uiContext: WeakReference<Context>? = null

    /** Called on the main thread whenever a new version arrives. */
    @Volatile var onUpdate: ((Long) -> Unit)? = null

    @JvmStatic
    fun start(context: Context, sdkKey: String) = start(context, LocalizeMeConfig(sdkKey))

    @JvmStatic
    fun start(context: Context, config: LocalizeMeConfig) {
        val app = context.applicationContext
        val prefix = config.sdkKey.take(10)
        val store = Store(File(File(app.cacheDir, "localizeme"), prefix))
        val api = Api(
            config = config,
            transport = UrlConnectionTransport(),
            installId = { InstallId.current(app) },
            appVersion = { appVersion(app) },
            osVersion = "Android ${Build.VERSION.RELEASE}",
        )
        val logger: (String) -> Unit = if (config.debugLogging) { message -> Log.d(TAG, message) } else { _ -> }
        val client = Client(
            config, store, api,
            preferredLanguages = { preferredLanguages(app) },
            matcher = LanguageResolver.systemMatcher,
            logger = logger,
        )
        install(client)
        if (observing.compareAndSet(false, true)) {
            main.post {
                ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) {
                        val current = this@LocalizeMe.client ?: return
                        if (current.config.checkOnForeground) current.check(force = false)
                    }
                })
            }
        }
        if (listening.compareAndSet(false, true)) {
            app.registerComponentCallbacks(object : ComponentCallbacks2 {
                override fun onConfigurationChanged(newConfig: Configuration) = syncResources()
                override fun onLowMemory() {}
                override fun onTrimMemory(level: Int) {}
            })
        }
    }

    /** Wrap an Activity's base context in `attachBaseContext`. Safe to call before [start]. */
    @JvmStatic
    fun wrap(base: Context): Context = LocalizeMeContextWrapper(base) { key -> client?.string(key) }

    /** Check for new strings now, ignoring the minimum interval. */
    @JvmStatic
    fun check(callback: ((Result<CheckOutcome>) -> Unit)? = null) {
        val client = client
        if (client == null) {
            callback?.invoke(Result.failure(LocalizeMeException(ErrorKind.NOT_STARTED, "call LocalizeMe.start first")))
            return
        }
        client.check(force = true) { result -> callback?.let { cb -> main.post { cb(result) } } }
    }

    /**
     * Swap a staged version in now, on the calling thread. True when the live
     * strings changed. Views already on screen keep their text until they are
     * inflated again, so follow it with `recreate()` or a redraw of your own.
     * [onUpdate] does not fire for this: the caller already knows.
     */
    @JvmStatic
    fun applyNow(): Boolean = client?.applyStaged() ?: false

    /** Whether a newer version is downloaded and waiting. */
    @JvmStatic
    val hasStagedUpdate: Boolean get() = client?.hasStagedUpdate ?: false

    /** Show this language instead of the device's. `null` follows the device again. */
    @JvmStatic
    fun setLanguage(code: String?) {
        client?.setLanguage(code)
    }

    /** The OTA value for a key, or null when the app should use its own. */
    @JvmStatic
    fun string(key: String): String? = client?.string(key)

    /**
     * The OTA value for a key, or [fallback]. A miss on both sides is reported
     * to the dashboard as a missing key.
     */
    @JvmStatic
    fun string(key: String, fallback: String?): String? {
        val hit = client?.string(key)
        if (hit == null && fallback == null) client?.noteMissing(key)
        return hit ?: fallback
    }

    /** The version of the strings in use, 0 before anything has been downloaded. */
    @JvmStatic
    val version: Long get() = client?.version ?: 0

    /** The language the live strings are in, if any. */
    @JvmStatic
    val language: String? get() = client?.language

    internal fun install(client: Client) {
        this.client?.shutdown()
        client.onUpdate = { version -> onUpdate?.let { cb -> main.post { cb(version) } } }
        this.client = client
        client.start()
        client.check(force = true)
    }

    internal fun reset() {
        client?.shutdown()
        client = null
    }

    internal fun register(ota: OtaResources) {
        resources.removeIf { it.get() == null }
        resources += WeakReference(ota)
    }

    internal fun noteUiContext(context: Context) {
        uiContext = WeakReference(context)
    }

    private fun syncResources() {
        for (reference in resources) {
            try {
                reference.get()?.sync()
            } catch (e: Exception) {
                Log.d(TAG, "sync failed: $e")
            }
        }
    }

    private fun appVersion(context: Context): String = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        "${info.versionName ?: ""} ($code)"
    } catch (e: Exception) {
        ""
    }

    /**
     * What the app renders in, then what the device is set to: the locales of
     * the most recently wrapped Activity (per-app language and AppCompat
     * overrides live there), else the Application's, followed by the system's.
     */
    private fun preferredLanguages(app: Context): List<String> {
        val out = LinkedHashSet<String>()
        fun add(locales: LocaleList) {
            for (i in 0 until locales.size()) locales[i]?.toLanguageTag()?.let { out += it }
        }
        runCatching { add((uiContext?.get() ?: app).resources.configuration.locales) }
        runCatching { add(Resources.getSystem().configuration.locales) }
        if (out.isEmpty()) out += Locale.getDefault().toLanguageTag()
        return out.toList()
    }

    private const val TAG = "LocalizeMe"
}
