# LocalizeMe for Android

Over-the-air translations. Approved strings in the LocalizeMe dashboard reach
the app on its next start, without a Play Store release.

- minSdk 24. Kotlin. One dependency (`androidx.lifecycle:lifecycle-process`,
  for foreground detection).
- One conditional request per start; the usual answer is a 304 with no body.

## Install

While the SDK is in beta it is published through JitPack, built from the
tags of https://github.com/localizeme-app/localizeme-android-sdk. Add the
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
    implementation("com.github.localizeme-app:localizeme-android-sdk:0.1.0-beta.1")
}
```

To build from source instead, run `./gradlew :localizeme:publishToMavenLocal`
in this repository and depend on `app.localizeme:sdk:0.1.0-beta.1` from
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

The SDK also sends an install id it made up (a random UUID in
SharedPreferences), the app, SDK and OS versions, and any errors it hit or keys
it could not find. Nothing that identifies a person or a device. An error in
the SDK is reported, never thrown into the app.

## Building and testing

```bash
./gradlew :localizeme:testDebugUnitTest         # JVM tests, no emulator needed
./gradlew :localizeme:assembleRelease           # localizeme/build/outputs/aar/
./gradlew :localizeme:connectedDebugAndroidTest # on an emulator: a wrapped AppCompat activity end to end
```

Needs a JDK 17+ on `JAVA_HOME` and the Android SDK on `ANDROID_HOME`
(Android Studio's bundled JDK works: `Android Studio.app/Contents/jbr/Contents/Home`).
