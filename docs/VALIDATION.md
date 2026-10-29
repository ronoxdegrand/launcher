# Validation · 2026-10-29

## Current preview

Package `dev.glgra.launcher`, version 0.2.5 (code 2005); minimum API 34, target API 37, SDK 37.2. Pure Java/framework Views with no runtime library dependencies. The published signed release is 195,793 bytes (approximately 191 KiB). Gradle distribution checksum and the public signing certificate fingerprint are pinned.

`assembleDebug`, `assembleDebugAndroidTest`, `lintDebug` and the local signed `assembleRelease` pass. Lint reports 0 errors and 24 warnings; no lint baseline hides errors. Remaining warnings include English-only strings, the intentional portrait phone UI, editor constructors, deprecated compatibility APIs and build-tool notices. Release signature verification checks the expected public certificate, rather than accepting any valid signer.

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

The [v0.2.2 release workflow](https://github.com/ronoxdegrand/launcher/actions/runs/40348724422) completed successfully. Its published 181,009-byte APK independently verifies against the pinned signing identity and checksum `76bf3e384cfc38191de726eae51f934d2ab1e099328348a73885dafff8fb408d`. The earlier v0.2.1 updater test remains historical evidence; v0.2.2 is the current UI build.

## Top search, real regional blur and notification playground · 0.2.3 · 2026-10-29

Opening source is now explicit. Search is pinned below the status bar for swipe, dock drag and Search-button entry. After gesture entry, keyboard dismissal reveals categories; after Search-button entry, search remains visible. Android HOME intents close search/drawer, folders/context popups and editing while retaining the canvas position.

SurfaceBlur uses public TYPE_APPLICATION_MEDIA child windows, behind Home and unable to take focus or touch, with Window.setBackgroundBlurRadius. The notification region tracks its clipped canvas bounds during scrolling; the dock region spans to the bottom of the screen behind native navigation controls. Lifecycle/blur-availability callbacks hide or dismiss regions. Neutral tint replaces cached white palette bitmaps. No screenshot sampling, wallpaper file access, hidden API or system overlay permission is used. Disabled blur receives a readable neutral fallback. The panel's left corners and dock's bottom corners are square.

Build/lint pass with zero errors and 24 warnings. Integrated checks pass with blur enabled and with system blur confirmed disabled, restored afterward. Checks include top search position before/after IME dismissal, both opening-source behaviours, HOME intents leaving both modes, dock-to-screen-bottom geometry, left panel corner radii, regional windows appearing/hiding, horizontal result alignment and real long-press drag stability. Existing notification read-only and widget gesture checks continue to pass. A five-second idle observation reports zero rendered frames; S23/GPU/battery claims remain unmeasured.

Three separate companion app packages post real local examples. Each has a summary and eight children while the temporary call-style service is active; the launcher displays 8 after summary deduplication. Native records verify CallStyle + ONGOING + NO_CLEAR + FOREGROUND_SERVICE flags. The shortService self-stops after 120 seconds, and the demo app can restart or stop it. Other examples cover grouped short messages, 10 paragraphs, deliberately absent content, ordinary ongoing, a generated image and bounded changing progress. The examples use no real call, audio, network or personal data. API 34 progress is not a genuine system-promoted Live Update.

Category discovery also passes: our three companions expose Social/Productivity/Maps through native metadata; only 3 of 15 installed emulator apps provide a native category. See CATEGORIES-AND-DEMOS.md for sources and platform limits. Images and raw results are saved in artifacts; companion APKs are built separately and never included in the launcher release.

The [v0.2.3 workflow](https://github.com/ronoxdegrand/launcher/actions/runs/40758177156) completed successfully. The published APK is 184,205 bytes, SHA-256 `e253a972b07e77869295db27a09c348e30647223f4724ccaeabb5443a216041f`; independent checksum and pinned-certificate verification pass. It is installed as a compatible update in the visible emulator, with the three companion apps reposted. Final review also fixed and tested stale context-menu cleanup on stop/destroy. Navigation-bar contrast enforcement is disabled after decor creation and in the theme, allowing the dock surface to continue behind the controls.

## Shared grid, blur stability, fuzzy prefixes and notification scrolling · 0.2.4

The seven-row/four-column metric is shared by Home, the dock, the one-row top search bar, horizontal results and two-row category cards. Home folders use the same outlined area. Home now has an icon-bearing search pill. Regional blur receives final geometry before showing; radius is 48dp capped at 150px, with a slight theme-aware veil and one-pixel outline. Home content is invisible beneath the drawer, leaving wallpaper alone behind its compositor blur. Font shadows are reduced and the font family is the native Android sans-serif.

The integrated suite checks measured row/column alignment, two-row categories, hidden Home content, background notification refresh preserving search focus, repeated drawer/blur-window restoration, and stable dock bounds during Home scrolling. Fuzzy matching includes 15 bounded regression cases for partial-word typos, transpositions, accents, unrelated inputs and exact-match priority. Native long-press drags, Home intents, keyboard entry modes and read-only notification checks remain covered. Normal compositor blur and Android-confirmed disabled blur are checked separately.

A separate measured notification runner passes with 14 real owned notifications: group and long-text expansion preserve the viewport, slow bidirectional swipes move locally without endpoint jumps, and an incoming event during touch preserves the gesture. It leaves native notifications active and removes only its own fixtures afterward. Native ScrollView fling is retained.

The widget suite passes all seven checks, including ordinary system binding consent, provider configuration, persistence, cancellation cleanup, RemoteViews taps and gesture interception. Its first attempts encountered a remembered binding grant and skipped the expected consent dialog; explicitly revoking the emulator user-0 grant restored the consent path. One integrated run timed out waiting for Home focus during concurrent build work; that observation is retained here and is not evidence of a resolved device-specific cause.

Local debug/device-test/release compilation and lint pass with zero errors and 24 warnings. The private release identity still matches the pinned public certificate. The optional companion service now also stops on the Android short-service timeout callback; it has no persistent service or network work. Main and pull-request CI now compile debug/device-test APKs and run lint without signing secrets; tags retain the separate signed-release pipeline.

The normal-blur rerun passed without a further app change. The [main build](https://github.com/ronoxdegrand/launcher/actions/runs/40827434475) and [v0.2.4 signed release](https://github.com/ronoxdegrand/launcher/actions/runs/40827439908) both completed successfully. The downloaded public APK is 188,617 bytes, SHA-256 `c2f2bb75a65b98f42b257161054b2d390ea1544d890b739b262768776b846d44`; its signature independently matches the pinned certificate and its package/version are `dev.glgra.launcher`, 0.2.4/code 2004. It installs as a compatible update. Only the emulator's debug fixture widget was removed before the release installation; real Home items and settings were preserved. A settled five-second Home observation reports zero rendered frames, without making a battery or physical-device performance claim.

## Research-led design pass · 0.2.5 · 2026-10-29

See DESIGN.md for primary sources, the product-specific decisions, and the visual iterations. Explicit type roles, fixed app-label geometry, category/folder gutters, a 48dp Home search target, readable Settings/menu surfaces and larger text-preview/collapse controls form one design language. The seven-row/four-column geometry and top horizontal search remain intact.

The dock-to-search transition now physically carries the four icons. A critically damped spring preserves current state when retargeted, honors system animation scale and schedules no frames when settled. Visual QA caught clipping that numerical position checks missed; results now travel in a separate layer. Additional screenshots cover categories, light/dark Settings, menus, editing and a content-sized folder overlay. A real long press exposed offscreen menu placement; measured safe-area positioning fixes it.

The measured design runner passes in normal, dark, 1.3 font-scale and animation-disabled configurations. It checks primary and secondary opaque reading palettes against 4.5:1 contrast, the Home search target, shared rows, intermediate icon visibility, interruption without position jump, category collapse, fully displayed menus, editor navigation clearance and a settled folder. The final geometry rerun passes. Glass over arbitrary wallpaper is not claimed to meet a universal contrast threshold; the input has local tonal backing.

The full integration suite passes with normal compositor blur and with Android-confirmed disabled blur, restored afterward. It includes native long-press dragging from dock and nonempty search, keyboard entry modes, HOME reset, stable regional blur bounds and real read-only notifications. The dedicated scrolling runner passes group/text expansion, small bidirectional drags and incoming events during touch. A settled five-second rendering observation reports zero frames. These are emulator observations; One UI/S23 appearance, motion and performance remain device acceptance.

Debug, instrumentation and signed release builds pass. Lint has zero errors and 24 warnings. No runtime dependency, permission, font download, wallpaper capture or background service was added by the design pass.

The [main build](https://github.com/ronoxdegrand/launcher/actions/runs/40860502011) and [v0.2.5 signed release](https://github.com/ronoxdegrand/launcher/actions/runs/40860508925) completed successfully. The downloaded public APK is 195,793 bytes, SHA-256 `32b4ccd4ebd4dceb2e9304c6d8e08c1762bac228adc2e6a4651f3374492d8529`. Independent checksum and pinned-certificate verification pass. That published APK is installed as a compatible update in the visible emulator, preserving its real layout and settings. The temporary folder used for design proof was never persisted.
