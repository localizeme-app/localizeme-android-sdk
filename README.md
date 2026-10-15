# LocalizeMe for Android

Over-the-air translations for Android apps. Approved strings in the
[LocalizeMe](https://localizeme.app/?src=github-android-sdk) dashboard reach the app on its next start,
without a Play Store release.

- minSdk 24. Kotlin. One dependency (`androidx.lifecycle:lifecycle-process`,
  for foreground detection).
- One conditional request per start; the usual answer is a 304 with no body.

## Install

The SDK is published through JitPack, built from the tags of https://github.com/localizeme-app/localizeme-android-sdk. Add the
repository in `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}
```

and the dependency:

```kotlin
dependencies {
    implementation("com.github.localizeme-app:localizeme-android-sdk:1.0.0")
}
```

To build from source instead, run `./gradlew :localizeme:publishToMavenLocal`
in this repository and depend on `app.localizeme:sdk:1.0.0` from
`mavenLocal()`.

## Use

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        LocalizeMe.start(this, "lzs_…")   // from the project's "Mobile SDK" tab
    }
}

class MainActivity : AppCompatActivity() {
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(LocalizeMe.wrap(base))
    }
}
```

Keys in the dashboard must match the `<string name="…">` entry names. A string
the dashboard does not have falls back to the app's own `res/values`.

### What is intercepted

Through the wrapped Activity (and every context derived from it, including
the ones AppCompat makes for night mode and per-app locales):

- `getString`, `getText` and `getString(id, args…)`, so also Compose's
  `stringResource()`.
- Views inflated from XML: `android:text` and `android:hint` on `TextView`
  and everything that extends it (`EditText`, `Button`, `CheckBox`, …),
  `android:contentDescription` on any view, `android:title`/`android:subtitle`
  on the framework `Toolbar`, and the `app:title`, `app:subtitle` and
  `app:hint` of AppCompat's `Toolbar`, `MaterialToolbar`,
  `CollapsingToolbarLayout` and `TextInputLayout`.
- Inline markup in a translation comes back styled from `getText`, as it does
  from `res/values`, for the tags Android's resource compiler styles: `<b>`,
  `<i>`, `<u>`, `<font>`, `<a>`, `<big>`, `<small>`, `<sup>`, `<sub>`,
  `<strike>`, `<tt>`. Any other tag (`<em>`, `<br>`, …) and any `&` or `<` in
  the text is shown as written, exactly as an exported strings.xml shows it.
  A value written as a CDATA section (`<![CDATA[<b>Hi</b><br>there]]>`) comes
  back as the text inside it, tags and all, the way aapt compiles it, for the
  app to render with `Html.fromHtml` as it does today.

### Out of scope

- Menu titles (`MenuInflater` reads them straight from the resource table)
  and any text set by a style rather than on the view itself.
- Plurals and string arrays: the bundle carries plain strings.
- Custom views named by their fully qualified class in XML when the Activity
  installs no `LayoutInflater` factory. `AppCompatActivity` installs one, so
  this only concerns a plain `Activity` or `ComponentActivity`.
- Custom `<drawable class="…">` resources loaded through the wrapped
  resources, a limit of every `Resources` wrapper of this kind.
- Text that is already on screen: it keeps its value until the view is
  inflated again (see below).

Strings with placeholders are checked before use: an OTA value whose
`%…` specifiers do not match the shipped string's (a `%d` turned into a
`%s`, a missing argument) is logged and the shipped string is used, so a
translation can never crash a `getString(id, args…)` call.

### AppCompat, night mode and `configChanges`

Nothing extra to do. AppCompat's night mode and `setApplicationLocales`
derive a new context from the wrapped one; the SDK wraps that too. The
wrapped resources follow every configuration change (rotation, font scale,
locale) on their own; activities that handle changes themselves with
`android:configChanges` are covered as well.

### Options

```kotlin
LocalizeMe.start(this, LocalizeMeConfig(
    sdkKey = "lzs_…",
    applyImmediately = true,          // swap strings in as they arrive (default: next start)
    language = "lt",                  // ignore the device language
    languageScope = LanguageScope.ALL,// download every language, not just the device's
    checkOnForeground = false,        // only check on process start
    minimumCheckIntervalMs = 300_000, // between automatic checks
    reportMissingKeys = false,        // don't tell the dashboard about unknown keys
    debugLogging = BuildConfig.DEBUG,
))
```

### Consent

Apps that ask the user first (a consent banner or a CMP such as Usercentrics,
OneTrust or Didomi) tell the SDK what it may do:

| Level | Network | Kept on the device | Sent |
|---|---|---|---|
| `Consent.FULL` (default) | yes | strings, a random install id | SDK key; SDK, app and OS version; language; install id; errors and missing keys |
| `Consent.STRINGS_ONLY` | yes | strings | SDK key and SDK version only |
| `Consent.OFF` | none | strings already downloaded | nothing |

Going below `FULL` deletes the install id and drops anything waiting to be
reported; granting again makes a new id. Leaving `OFF` checks straight away.
Already downloaded strings keep showing at every level, falling back to the
shipped ones.

The level is not remembered between starts, because the consent tool is the
record. Start at the level it last gave, or at `OFF` until it answers, and call
`setConsent` on every change. A `setConsent` before `start` is kept.

```kotlin
LocalizeMe.start(this, LocalizeMeConfig(sdkKey = "lzs_…", consent = Consent.OFF))

// Usercentrics: LocalizeMe is a service in your configuration; use its template id.
fun applyConsents(consents: List<UsercentricsServiceConsent>) {
    val granted = consents.firstOrNull { it.templateId == "YOUR_LOCALIZEME_TEMPLATE_ID" }?.status ?: false
    LocalizeMe.setConsent(if (granted) Consent.FULL else Consent.STRINGS_ONLY)
}
Usercentrics.isReady({ status -> applyConsents(status.consents) }, { })
UsercentricsEvent.onConsentUpdated { applyConsents(Usercentrics.instance.getConsents()) }
```

Map a refusal to `Consent.OFF` instead if a refused service must not reach the
network at all.

### Applying an update without a restart

By default a new version is downloaded and staged, and goes live on the next
process start so nothing changes under the user's finger. To apply it now:

```kotlin
LocalizeMe.onUpdate = { version ->
    if (LocalizeMe.applyNow()) {
        activity.recreate()   // views set from XML keep their text until re-inflated
    }
}
```

`applyNow()` is synchronous: when it returns `true` the new strings are
already what `getString` answers with. It does not call `onUpdate` again.

### Manual lookups

```kotlin
LocalizeMe.string("home_title")            // String? — null when the app should use its own
LocalizeMe.string("home_title", "Home")    // with a fallback; a miss on both sides is reported
LocalizeMe.check { result -> … }           // force a check now
LocalizeMe.setLanguage("lt")               // in-app language switch
LocalizeMe.version                         // 0 until something has been downloaded
```

### Releases

Apps get releases: numbered snapshots of the project's approved strings,
published from the Mobile SDK tab, by hand or automatically as strings are
approved. The version the SDK logs and reports (`LocalizeMe.version`) is the
release number, and 0 means no release covers this app yet.

A release can be limited to apps from a minimum version. The SDK sends the
app's version with every check, as `versionName` plus `versionCode`, and gets
the newest release whose minimum it meets; the minimum is compared with
`versionName`. An older app keeps the last release that covers it, or its own
shipped strings when none does. Nothing to configure in the app.

## How it works

1. On start the SDK loads the last bundle from `cacheDir/localizeme/` and
   swaps it in on the calling thread, then asks
   `GET /ota/v1/manifest?platform=android` with `If-None-Match` on its own thread.
2. A 304 means done. A 200 lists a content-addressed bundle per language; the
   SDK downloads the device's language, verifies its SHA-256 and writes it
   atomically. The manifest is kept, so a later change of device language is
   served from the same version without another 200.
3. The new snapshot is staged (or applied, see above). Old bundle files are
   pruned.

The language is chosen the way the OS chooses resources: the app's locales
(the wrapped Activity's, so per-app language settings count), then the
system's, matched against every code a language answers to in the dashboard
(`nb-NO` finds Norwegian published as `no`), most specific first.

With full consent the SDK also sends an install id it made up (a random UUID in
SharedPreferences), the app, SDK and OS versions, and any errors it hit or keys
it could not find. Nothing that identifies a person or a device. Below full
consent it sends none of that; see [Consent](#consent). An error in
the SDK is reported, never thrown into the app.

## Building and testing

```bash
./gradlew :localizeme:testDebugUnitTest         # JVM tests, no emulator needed
./gradlew :localizeme:assembleRelease           # localizeme/build/outputs/aar/
./gradlew :localizeme:connectedDebugAndroidTest # on an emulator: a wrapped AppCompat activity end to end
```

Needs a JDK 17+ on `JAVA_HOME` and the Android SDK on `ANDROID_HOME`
(Android Studio's bundled JDK works: `Android Studio.app/Contents/jbr/Contents/Home`).

## About LocalizeMe

[LocalizeMe](https://localizeme.app/?src=github-android-sdk) is a localization platform
for product teams: every string your apps ship, in every language, in one
place.
Translators work in an editor with review statuses, screenshots that show
where a string appears, key groups and the full history of every change.

- **Unlimited keys and languages on every plan, the free one included.** Paid
  plans are one flat price per workspace, not per seat or per string.
- **The files every platform uses:** Android `strings.xml`, iOS `.xcstrings`
  and `.strings`, Flutter ARB, JSON, YAML, CSV, XLIFF 1.2, gettext PO and Java
  `.properties`, with a separate value per platform where iOS and Android need
  to differ.
- **Strings over the air:** this SDK and the
  [iOS SDK](https://github.com/localizeme-app/localizeme-ios-sdk)
  update an app's text without a store release.
- **For developers and their agents:** the
  [CLI](https://github.com/localizeme-app/localizeme-cli)
  for builds and CI, a REST API, and an MCP server that Claude, Cursor and
  other coding agents can work with.

[Start free](https://localizeme.app/register?src=github-android-sdk), no credit card needed ·
[Pricing](https://localizeme.app/pricing?src=github-android-sdk) ·
[Developers](https://localizeme.app/developers?src=github-android-sdk)

## License

Source-available under the [PolyForm Shield License 1.0.0](LICENSE). Put
it in any app, commercial or not, and read, change and ship it with your
app as you need. What it does not allow is using the SDK, or anything made
from it, to provide a product that competes with the SDK or with
LocalizeMe. Keep the `LICENSE` file, or its link and the `Required Notice`
line, with any copy you pass on.

Versions up to and including 0.1.0-beta.1 were released under the MIT License,
and stay available under it.
