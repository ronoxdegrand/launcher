# Personal Android Launcher

A widget-first launcher for Android 14 and later, built with native Android views, original app icons and public platform APIs. Primary acceptance device: Samsung Galaxy S23 / One UI.

## Try it

Install signed `launcher.apk` from [GitHub Releases](https://github.com/ronoxdegrand/launcher/releases). The installed Launcher icon opens Settings; **Open Home** previews the canvas. Select it as the default Home app through Android.

- Swipe horizontally on the continuous four-column, six-row canvas. It snaps to columns and remembers its position.
- Swipe up or tap Search for the drawer and keyboard. Dismiss the keyboard to browse categories. Back closes the drawer.
- Long-press empty Home to edit. Add widgets/apps, move and resize items, or scroll down to settings.
- Long-press apps for native shortcuts, pinning, Add to Home and App Info. Continue dragging to place an app on Home; drop one app onto another to create a folder.
- Usage access enables adaptive ranking; notification access enables read-only notification previews. Both are optional. Hidden apps remain searchable by name.

Debug prototypes use a different certificate from signed releases. Android requires uninstalling a debug prototype before the first signed release, removing its local layout. Subsequent same-signature updates preserve local data.

## Implemented

Persistent horizontal grid, native widget binding/configuration and resizing, collision reflow, drag editing, adaptive dock, fuzzy app search, categories and manual overrides, hidden apps, folders, pinned shortcuts, read-only notifications and badges, haptics, system themes, optional wallpaper parallax, daily Wi-Fi APK downloads and Android installer flow.

No analytics, accounts or cloud sync. Usage summaries stay local. Notifications are event driven; widgets follow Android scheduling. Only the updater needs networking. Debug builds do not schedule update checks.

## Acceptance status

This is a development preview. No Galaxy S23 was connected during implementation. Assistant query forwarding is omitted pending proof that the selected assistant accepts text. Samsung pairs require One UI to deliver a standard pinned shortcut. One UI privileged navigation animation parity is outside the public API contract. Android wallpaper restrictions require cached blurred wallpaper-color surfaces, rather than photograph blur. Badges count active notification records, not messages within a record.

See [integration findings](docs/INTEGRATIONS.md), [validation](docs/VALIDATION.md), and [device acceptance checks](docs/DEVICE-CHECKS.md).

## Build and test

JDK 17, SDK Platform 37.2 and Build Tools 37.0.0. Gradle 9.6.0 and Android Gradle Plugin 9.4.0 are pinned. Set `JAVA_HOME` and `ANDROID_HOME`.

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell pm grant dev.glgra.launcher android.permission.POST_NOTIFICATIONS
adb shell cmd notification allow_listener dev.glgra.launcher/dev.glgra.launcher.NotificationRepository
adb shell settings put secure show_ime_with_hard_keyboard 1
adb shell am instrument -w dev.glgra.launcher.test/dev.glgra.launcher.IntegrationTests
```

Use a disposable emulator: instrumentation changes layout, grants test access and posts temporary notifications. Fixtures are excluded from release builds. For the additional widget suite, build with `-PtestRunner=dev.glgra.launcher.FoundationTests`, install the test APK and run that class.

## Signed releases

Tags `vX.Y.Z` on main trigger `.github/workflows/release.yml`. Version code is `X*1000000 + Y*1000 + Z`; each component is 0–999. The workflow checks, signs, verifies the pinned public certificate, and publishes `launcher.apk` and `SHA256SUMS.txt`.

Configure Actions secrets `LAUNCHER_KEYSTORE_BASE64`, `LAUNCHER_STORE_PASSWORD`, `LAUNCHER_KEY_ALIAS` and `LAUNCHER_KEY_PASSWORD` before tagging. Back up the private key and passwords securely; compatible updates require the same identity. The public fingerprint is in `signing-certificate.sha256`. Use `scripts/sign-release.ps1` for local signing with the same key.

The updater binds APK downloads to validated Wi-Fi and checks checksum, package, version, SDK and certificate. It preserves the previous verified artifact until replacement succeeds. Installation requires a tap, Android authorization for this source, and Android confirmation.

[MIT license](LICENSE), copyright 2026 ronoxdegrand.
