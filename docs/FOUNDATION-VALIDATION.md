# Foundation validation · 2026-10-26

## Delivered build

- Package `dev.glgra.launcher`, version `0.1.0-foundation` (code 1).
- Minimum Android 14 / API 34; target API 37; compiled against stable SDK 37.2.
- Debug APK: **95,588 bytes** (about 93 KiB). No runtime dependencies; the unused built-in Kotlin compiler is disabled for this Java-only module. Gradle's downloaded distribution is checksum-pinned.
- APK signature verification passes using APK Signature Scheme v2, with the local Android debug certificate. This is an installable development build, not the future private release signing identity.
- Final clean `assembleDebug`, `assembleDebugAndroidTest` and `lintDebug`: **pass**, zero lint errors, 15 warnings. Remaining warnings concern English-only strings, the deliberate phone portrait orientation, editor-only constructors, backup metadata and build-tool upgrade suggestions; there is no lint baseline hiding errors. AGP also warns that its Kotlin opt-out is removed in AGP 10; revisit that setting when upgrading the build plugin.

## Runtime evidence

Executed on a hardware-accelerated x86_64 Android 14 / API 34 emulator with a Pixel 7 device profile. Both APKs install successfully. Instrumentation reports **all seven checks passed**, including with Launcher selected as the default Home app:

1. 250 varied placements: collision avoidance, six-row bound, fixed left edge and items wider than the viewport.
2. Measured Home viewport, layout save/load, saved horizontal position and repeated Home intent behavior.
3. Horizontal gesture movement, column snapping and left-boundary behavior.
4. Real system widget binding consent, provider configuration, committed widget binding and persistence.
5. A native RemoteViews PendingIntent tap opens Settings; a horizontal swipe starting over the widget moves Home.
6. Cancelling a second widget configuration releases its allocated ID.
7. A vertically scrolling child retains its vertical interaction while the canvas stays horizontally stationary. This is a scrollable interaction fixture, not a claim about every third-party collection widget.

Additional emulator checks:

- Android's HOME intent resolver selects `dev.glgra.launcher/.HomeActivity` after the role is assigned.
- Stored layout/position is byte-for-byte unchanged across a process restart and an ordinary same-signature APK replacement. The widget remains bound after both operations.
- Signature and manifest inspection confirm min/target SDK and no Internet permission.
- After resetting Android's rendering counters and waiting five idle seconds, `dumpsys gfxinfo` reports **0 rendered frames**. This short emulator observation does not establish S23 battery, CPU, memory or frame-time performance.
- No default assistant or ACTION_ASSIST handler is present on this AOSP emulator. Assistant query forwarding cannot be experimentally validated here and is omitted.

The final implementation explicitly permits the foreground widget configuration PendingIntent launch under Android 14's background-activity restrictions. Explicit position restoration cancels any earlier fling, so an animation cannot overwrite a restored/moved position.

Raw test output, APK signature details, idle rendering counters, checksum and an emulator screenshot are in `artifacts/`.

## Remaining acceptance work

No Galaxy S23 was connected. Samsung app-pair delivery, assistant text forwarding, One UI Home/Recents animations, Samsung live-activity identification and real Samsung/Google widget gesture compatibility remain device checks. Notification and usage access are deliberately not requested by this foundation APK; those integrations have documented API constraints, not runtime implementation proof. Widget move/resize controls are implemented, but the full real-provider resize/collection matrix and finger-driven drag editing remain pending.

No claim is made that the complete v1 launcher, updater, notification workspace, adaptive drawer, or Samsung-specific integrations are done. Follow DEVICE-CHECKS.md before treating the S23 foundation as accepted.
