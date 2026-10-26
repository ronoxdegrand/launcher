# Validation · 2026-10-26

## Current preview

Package `dev.glgra.launcher`, version 0.2.0; minimum API 34, target API 37, SDK 37.2. Pure Java/framework Views with no runtime library dependencies. The signed release is approximately 167 KiB. Gradle distribution checksum and the public signing certificate fingerprint are pinned.

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

GitHub workflow execution, real public asset download and native installer smoke results are recorded below when completed; local signing alone does not prove that whole chain.

## Remaining device acceptance

No Galaxy S23 is connected. Samsung/Google real widgets, One UI navigation/animation behavior, Samsung pair delivery, assistant prefilled text, API-specific live updates, notification privacy, long-term usage patterns and physical-device performance remain pending. No measured battery or memory claim is made. Assistant forwarding is omitted until verified; Samsung pairs use only standard pin requests where available.

Android 14 restricts wallpaper pixels for ordinary launchers. Glass surfaces use cached blurred wallpaper colors as an explicit fallback. Full photographic wallpaper blur is not claimed. The dock retains four slots; fewer than four eligible installed apps leave empty slots rather than duplicating or revealing hidden apps.
