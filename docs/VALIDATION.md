# Validation · 2026-10-26

## Current preview

Package `dev.glgra.launcher`, version 0.2.1 (code 2001); minimum API 34, target API 37, SDK 37.2. Pure Java/framework Views with no runtime library dependencies. The signed release is 170,729 bytes (approximately 167 KiB). Gradle distribution checksum and the public signing certificate fingerprint are pinned.

`assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` and the local signed `assembleRelease` pass. Lint reports 0 errors and 22 warnings; no lint baseline hides errors. Remaining warnings include English-only strings, the intentional portrait phone UI, editor constructors, deprecated compatibility APIs and build-tool notices. Release signature verification checks the expected public certificate, rather than accepting any valid signer.

## Emulator runtime proof

Hardware-accelerated Android 14/API 34 x86_64 emulator, Pixel 7 profile. The integrated suite passes:

1. Folder self-drop, app uniqueness, folder dissolution/relocation and notification reservation resizing.
2. Nested layouts, stable IDs, folder names and shortcut identities survive persistence.
3. App discovery, lexical/fuzzy search order, hidden-app matching, empty results and denied-usage fallback.
4. Compatible candidate metadata accepted; package/version/signature/minimum-SDK mismatches rejected.
5. Upward gestures over icons/empty Home open the drawer; vertical widget gestures remain with the child.
6. Search focus and soft keyboard, empty Enter, keyboard-dismissed categories and close/reset behavior.
7. Real timed touchscreen long-press drags from dock and nonempty search place unique apps in the intended Home cells.
8. Real NotificationListener events, summary deduplication, record counts, and read-only expansion without notification actions or dismissal.

A first native search-drag attempt failed to open its context menu. Two subsequent runs passed without an app behavior change; the evidence does not yet establish the cause of that intermittent failure. Device gesture acceptance remains necessary.

The separate widget-hosting suite covers 250 placement cases, measured viewport/persistence, scrolling/snapping, Android widget binding consent, actual provider configuration, native RemoteViews taps, cancellation cleanup and vertical child interaction. The current suite passes all seven checks. See the historical foundation report for the earlier milestone and current raw logs in artifacts.

## Signing and distribution

A private stable RSA4096 signing identity is stored outside Git and outside the OneDrive source repository. Its password is protected locally with Windows DPAPI. The public fingerprint is `signing-certificate.sha256`. GitHub has four encrypted repository Actions secrets for the tag-triggered release workflow. No private signing material is committed.

The updater binds checksum/APK requests and redirects to the selected validated Wi-Fi network, bounds downloads, checks SHA-256 and Android package metadata/signature, fsyncs the file, and durably commits a pointer to an immutable verified APK. Previous verified downloads survive replacement failure; unreferenced files are pruned after checks. Cancellation resets displayed work state. Installation requires an explicit tap and Android consent.

The tag-triggered [v0.2.1 workflow](https://github.com/ronoxdegrand/launcher/actions/runs/40308029554) completed successfully: clean Linux build, lint, signing, pinned-certificate verification and public APK/checksum publication. The initial v0.2.0 attempt failed before compilation because the old Android setup action requested the removed `tools` package; v0.2.1 uses the current action with explicit platform-tools.

A disposable older signed install, 0.1.999/code 1999, downloaded the actual public v0.2.1/code 2001 APK over Wi-Fi. Instrumentation verified its SHA-256, package, supported SDK, higher version and matching installed certificate, then reached Android packageinstaller with an enabled Update button. The test did not accept installation. A subsequent `adb install -r` of the independently downloaded public APK succeeded, and Settings/Home reopened as 0.2.1. This proves the download-to-consent path and a compatible APK replacement; clicking the native confirmation and checking a real S23 update remain device acceptance work.

Public APK SHA-256: `ba44fceb5a6f218ac35c968d42c1c9f25d9b9eb98876a8455c379eaefab5cf55`. Signature and updater results are in `artifacts/signature-current.txt` and `artifacts/update-smoke-tests.txt`.

After rendering counters were reset and the current app was left idle for five seconds, the API 34 emulator reported **0 rendered frames**. This is a short rendering observation, not a CPU/GPU/battery or physical-device performance measurement. See `artifacts/idle-rendering-current.txt`.

## Remaining device acceptance

No Galaxy S23 is connected. Samsung/Google real widgets, One UI navigation/animation behavior, Samsung pair delivery, assistant prefilled text, API-specific live updates, notification privacy, long-term usage patterns and physical-device performance remain pending. No measured battery or memory claim is made. Assistant forwarding is omitted until verified; Samsung pairs use only standard pin requests where available.

Android 14 restricts wallpaper pixels for ordinary launchers. Glass surfaces use cached blurred wallpaper colors as an explicit fallback. Full photographic wallpaper blur is not claimed. The dock retains four slots; fewer than four eligible installed apps leave empty slots rather than duplicating or revealing hidden apps.

## UI update · 0.2.2

Dock and search now share a four-column AppTile component. The dock rectangle expands in both dimensions, rounded corners settle into the full drawer sheet, app names fade in, and the horizontal result strip follows keyboard insets. Extra matches scroll horizontally. Back/early-release reverse the reveal; disabled system animations use final states directly. Controls, surfaces and badges have consistent rounded styling and quieter colors.

The integrated suite passes with system blur enabled and again with system blur confirmed disabled (`Blur enabled: false`), followed by restoration of the emulator setting. It checks measured dock/search column alignment, a horizontal default row, visible result names, the blur availability contract and clearing blur/restoring the dock on close. Real dock/search drags now force a metadata refresh while the finger is held and pass: touch targets are retained until the gesture finishes. This identifies and fixes the intermittent tile-detachment failure noted in the earlier preview.

Final debug build and lint pass: 0 errors, 23 warnings. Modern screenshots and both test logs are in `artifacts/modern-*`. A short Home idle rendering check again reports zero frames after five seconds; no physical-device performance claim is made. Public system backdrop blur is used for the drawer; in-canvas surfaces and the dock retain cached wallpaper-color glass.
