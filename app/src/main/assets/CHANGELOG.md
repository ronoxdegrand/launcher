v0.2.4

Home, dock, search, results and category grids share one seven-row/four-column layout. Search fills one row at the top; its horizontal results share an outlined area. Category tiles and Home folders use the same hairline outline. Home has a prominent search pill with an icon.

Stronger compositor blur uses a slight light/dark veil. Dock blur receives its final bounds before being shown. Search and the drawer blur wallpaper only; Home widgets and shortcuts are hidden beneath them. Text shadows are lighter and typography uses Android's native sans-serif system family.

Fuzzy search now handles misspelled partial words, including “masse” for Messages, while exact matches retain priority.

Notification expansion preserves the visible row. Incoming events wait for active scrolling to settle; native scrolling and fling remain intact. Background refresh cannot take search focus.

A separate main/pull-request build checks lint and compiles the APK and device tests. The historical v0.2.0 release failure came from a removed SDK tools package; later release builds passed.
