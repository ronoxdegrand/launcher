# Galaxy S23 acceptance checks

Record Android/One UI version, navigation mode and provider versions alongside results. An emulator pass does not establish Samsung compatibility.

1. Install signed release; select default Home. Verify repeated Home retains position, returning from apps honors the notification-position setting, reboot restores widgets and layout, and the app icon opens Settings.
2. Add Samsung Clock, Weather, Calendar and a vertically scrolling collection widget. Complete/cancel binding and configuration. Native taps and vertical scrolling work; horizontal swipes move Home. Resize, move, remove and test oversized widgets partly outside the viewport.
3. Fling across multiple columns; verify left boundary, column snapping, no overlap, preserved sizes during reflow, remembered position, and no idle animation. Try diagonal/reversing gestures and interrupted flings.
4. Long-press dock/search/category apps and continue dragging to Home. Check native shortcut menus, Remove target, Add to Home, dragging folder children, merging folders, dissolution to one remaining app, package/shortcut uniqueness and automatic expansion at the right edge.
5. Test search button/home swipe/dock tracked swipe, keyboard focus, typo matching, empty Enter, first-match Enter, keyboard dismissal to categories and Back. Verify one expanded category, full-width four-column layout and reset on closing. Hidden apps appear only with matching queries.
6. Grant/revoke usage access. Pin 0–4 apps; check no duplicates, foreground-time ranking, missing-history fallback, hidden-app exclusion and daily/hourly adaptation over a 14-day locally accumulated history.
7. Grant/revoke notification access. Test grouped messages, silent/persistent/media/private notifications and available promoted-live-update flags. Groups reorder by newest record, expansion survives arrivals and resets on leaving Home. Taps must never open, reply, act on or dismiss notifications. Counts refer to active records.
8. Check light/dark modes, large font/display scale, cutouts/navigation insets and reduced animation. Editing hides dock/search/status bar and settings appear below the workspace. Test haptic levels and system vibration off; drawer opening has no added haptic or sound.
9. With this launcher default, use Samsung native split-screen pair save. Record whether One UI offers Home and delivers a standard pinned-shortcut request. Skip if unsupported; no custom split-screen workaround.
10. Verify assistant prefilled text with a harmless unique phrase using the configured assistant. Opening an assistant without preserved text is not a pass. Query forwarding remains omitted until proven.
11. Test gesture Home/Recents and app switching. Record animation differences from One UI Home; privileged animation parity is not promised.
12. Verify signed update with newer compatible APK: Wi-Fi automatic download, cellular/Wi-Fi loss behavior, progress and changelog, hash/signature/package rejection, native unknown-source/installer consent, retained widgets/settings after install. Failed downloads wait until the next scheduled check.
13. Leave idle and profile actual CPU/GPU/frame activity, memory and battery on the S23. No specific battery/memory claim is accepted without measurement.

For 0.2.4, specifically compare dock blur while scrolling and returning from the drawer; verify the seven-row/four-column alignment at the S23's actual display/font scale. Confirm only wallpaper appears behind search, and try a typo prefix such as “masse” for Messages. Expand a long notification from the top and halfway down, then make small drags in both directions while new notifications arrive; the viewport should remain local.
