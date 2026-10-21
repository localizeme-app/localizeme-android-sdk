# Changelog

## 1.0.2

- `getDisplayMetrics()` and `getConfiguration()` keep the 1.0.1 guard on
  while they read, so on MIUI a call syncs once instead of twice. Every
  `obtainStyledAttributes` and every new View reads the metrics.
- The guard exists before the object is registered, so a configuration
  change that arrives while another thread is still constructing one no
  longer trips over it.

## 1.0.1

- Fixes a crash on launch on Xiaomi phones (MIUI / HyperOS, Android 14–16):
  a `StackOverflowError` in `OtaResources.sync`. MIUI's
  `Resources.getDisplayMetrics()` reads `getConfiguration()`, which synced
  again and called back into the same getter until the stack ran out.

## 1.0.0

- The first stable release. From here on, only a new major version breaks
  source compatibility.
- Consent levels for apps that ask the user first (Usercentrics, OneTrust,
  Didomi…): full, strings only, and off, set in the configuration and with
  `LocalizeMe.setConsent`. See "Consent" in the README.
- **Source-breaking:** a new error kind, `ErrorKind.CONSENT_OFF`, for checks skipped while
  consent is off. A `switch`/`when` that lists every kind needs a branch for it.
