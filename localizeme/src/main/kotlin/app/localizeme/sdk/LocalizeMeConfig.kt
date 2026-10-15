package app.localizeme.sdk

/** Which languages to keep up to date on this device. */
enum class LanguageScope {
    /** Only the language the app shows; the shipped resources are the fallback. */
    DEVICE,
    /** Every language the project publishes. */
    ALL,
}

/**
 * How much of the SDK may run, for apps that ask the user first (a consent
 * banner or CMP such as Usercentrics, OneTrust or Didomi). Ordered from least
 * to most, so levels compare with `<`.
 */
enum class Consent {
    /**
     * No network at all. Strings already downloaded keep showing, falling back
     * to the shipped ones.
     */
    OFF,
    /**
     * Strings only. Nothing is stored on the device beyond the strings, and
     * requests carry the SDK key and SDK version and nothing about the install.
     * The install id is deleted and nothing is reported.
     */
    STRINGS_ONLY,
    /**
     * Strings plus what the dashboard shows about installs: a random install id
     * kept on the device, the app and OS version, the language, and error and
     * missing-key reports.
     */
    FULL,
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
    /**
     * What the SDK may do until [LocalizeMe.setConsent] says otherwise. Not
     * remembered between starts: the consent tool is the record, so start at
     * the level it last gave, or at [Consent.OFF] until it answers.
     */
    val consent: Consent = Consent.FULL,
    /** Log what the SDK is doing. Leave off in release builds. */
    val debugLogging: Boolean = false,
)
