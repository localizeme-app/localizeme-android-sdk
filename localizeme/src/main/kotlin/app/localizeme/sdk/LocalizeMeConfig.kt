package app.localizeme.sdk

/** Which languages to keep up to date on this device. */
enum class LanguageScope {
    /** Only the language the app shows; the shipped resources are the fallback. */
    DEVICE,
    /** Every language the project publishes. */
    ALL,
}

/**
 * Settings for [LocalizeMe.start]. Only [sdkKey] is required.
 *
 * The SDK always asks for Android strings: a value the dashboard scopes to
 * Android wins over the one for all platforms.
 */
data class LocalizeMeConfig(
    /** A project SDK key (`lzs_…`) from the project's "Mobile SDK" tab in the dashboard. */
    val sdkKey: String,
    /** The API host. Only the sandbox or a self-hosted API changes this. */
    val baseUrl: String = "https://api.localizeme.app",
    /**
     * Swap a downloaded bundle in as soon as it arrives. Off by default: new
     * strings apply on the next process start, so a screen never changes under
     * the user's finger. [LocalizeMe.applyNow] applies a staged bundle on demand.
     */
    val applyImmediately: Boolean = false,
    /** Force a language instead of following the device's locales. */
    val language: String? = null,
    val languageScope: LanguageScope = LanguageScope.DEVICE,
    /** Check again when the app returns to the foreground. */
    val checkOnForeground: Boolean = true,
    /** The shortest gap between two automatic checks, in milliseconds. */
    val minimumCheckIntervalMs: Long = 60_000,
    /** Tell the dashboard about keys the app asked for that nobody had. */
    val reportMissingKeys: Boolean = true,
    /** Log what the SDK is doing. Leave off in release builds. */
    val debugLogging: Boolean = false,
)
