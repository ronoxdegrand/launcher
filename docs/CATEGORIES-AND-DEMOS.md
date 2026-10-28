# App categories: platform support and launcher behaviour

Investigated 2026-10-29.

Android exposes ApplicationInfo.category, populated by an optional app manifest appCategory or a hint from the package installer. It has broad buckets: games, audio, video, images, social, news, maps, productivity and accessibility. It may be CATEGORY_UNDEFINED. This is not a complete Play Store taxonomy or a guaranteed classification service for every installed app. Only the installing app may set package-manager category hints; an ordinary launcher cannot fill missing platform metadata for unrelated apps.

The launcher already reads native categories through LauncherApps. It retains Uncategorized for missing metadata and allows immediate local manual overrides in Settings. The API 34 emulator exposed useful metadata for only 3 of 15 apps; all three were our explicitly categorized demo companions. The remaining 12 lacked a useful native value. The demo apps prove native category discovery works without uploading an app list.

Other launchers add their own sorting. Smart Launcher advertises automatic categories and states that enabling its optional automatic sorting requires sharing an app list. Its public pages do not establish the internal algorithm/database, so this project does not claim that every launcher uses the same mechanism. There is no new cloud lookup or app-list upload in this launcher. A richer local catalogue/classifier would be a separate product choice.

Sources:
- https://developer.android.com/reference/android/content/pm/ApplicationInfo#category
- https://developer.android.com/reference/android/content/pm/PackageManager#setApplicationCategoryHint(java.lang.String,int)
- https://www.smartlauncher.net/blog/introducing-smart-launcher-6-5

# Local notification playground

Run scripts/install-notification-demo.ps1 -ToolRoot <toolchain-folder> to build/install three clearly labelled local companion apps: Demo · Messages, Demo · Work and Demo · Journey. They post real native notifications from distinct app packages. Their native app categories are Social, Productivity and Maps. No account, network, real messages or telephony is involved. Companion APKs are not bundled into the launcher.

Each app offers controls to post examples, restart finite progress and clear its own examples. Fixtures include summaries with multiple children, ten-paragraph text, a content-free privacy placeholder, image previews, ordinary ongoing notifications and changing progress. Ongoing is not universally non-dismissible on Android 14. The ongoing-call example uses CallStyle with a temporary short foreground service; its fake hangup stops only that demo. The service stops itself after two minutes and can be restarted from the demo app. It does not make calls or play audio.

Hidden-content fixtures deliberately omit content; real notification redaction remains controlled by Android. API 34 progress is not a genuine Android 16+ system-promoted Live Update, so it cannot establish promoted-live-update deduplication. The launcher remains read-only throughout: native notification actions and dismissal belong to Android's shade and the demo apps.
