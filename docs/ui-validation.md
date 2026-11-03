# Validate the launcher UI

Run these checks from `launcher/` with JDK 17 and `ANDROID_HOME` pointing to an Android SDK with platform 34 and build tools:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`HomeUiTest` renders the actual Android home views with Robolectric native graphics at 1920×1080. It uses bundled wallpaper and synthetic app, weather, and usage data. It does not start launcher services, call a network API, or launch another app.

The tests write PNGs to `launcher/app/build/ui-evidence/`:

- `light-theme-dark-panels.png`: light-theme accents on dark panels, focused app, aligned widget cards.
- `dark-app-focus.png`: dark theme and two rows of labeled app banners.
- `dark-widget-focus.png`: an actionable widget selected with the D-pad.
- `six-app-home.png`: one app row, compact greeting, and the weather/PC stack beside AI credits.
- `bounded-app-scroll.png`: a later app row selected without drawing over the header or widgets.
- `four-k-six-app-home.png`: the native 3840×2160 layout, uncaptioned artwork, and the selected app named in the header.

The tests cover four through seven app columns, both app rows, scrolling, spatial D-pad navigation, app-position feedback, hidden widgets, read-only panels, panel-edit cancellation, app/menu callbacks, partial custom palettes, and old panel positions near the app window. `SurfacesTest` checks stock theme foreground contrast over black, white, gray, and saturated wallpaper colors.

The home window fits its visible apps, showing one row when they fit and up to two rows otherwise. Left and Right stay within the current row. Down moves to the next app row, and Up from the first row selects the nearest actionable widget. Down from a widget returns to the selected app. Additional app rows scroll inside the app panel; pixel comparisons check that they do not draw over its header or the widgets.

Home panels stay dark with both dark and light themes. Theme accents and wallpapers remain configurable. The home panels use at least 90% opacity to protect text over bright wallpaper; the saved transparency value remains available to the rest of the launcher. Large app banners are capped at 360 px wide at 1080p so both rows clear the widget deck. Banner artwork keeps its 16:9 shape without captions. The header names the selected app, and cards without artwork display their name inside the tile. Each card retains its accessibility label.

Weather and PC stats share a column beside AI credits. PC stats fit their content, and weather puts the location and condition on separate lines. The greeting combines network and uptime on one line. Text remains at least 24 px at 1080p.

All saved widget offsets are restored before collision checks. A position that no longer fits the measured app window falls back to its aligned default, or the nearest available grid cell when that default is occupied, without overwriting the saved coordinates.

Use the existing hidden-app setting to remove unused tiles. Hiding apps preserves their installation and data; Settings → Hidden apps restores them. Personal app selections belong in the device configuration, not the source defaults.

These renders prove local layout and focus behavior. They do not establish hardware performance, overscan, remote long-press timing, or readability at a particular viewing distance.

## Run a separate TV pilot after approval

Obtain approval for a named device, test window, candidate APK, and rollback before installation. Confirm that media playback can be interrupted during that window.

1. Record the installed version, signing certificate, APK, and launcher configuration. Keep credentials and pairing data private.
2. Verify that the candidate preserves the installed signing identity and update path. Stop if they differ.
3. Install without clearing application data. Keep the TV on and preserve volume and device settings.
4. Check one light and one dark theme, the current wallpaper, four and seven app columns, and the existing widget positions.
5. Navigate both app rows, the widgets, and the bar with the remote. Check focus visibility, scrolling, OK, long OK, Back, and the return from an app.
6. If the pilot fails, restore the recorded compatible APK and configuration using the agreed rollback. If a downgrade needs data removal, stop for a decision.

Merge and TV installation are separate approvals. This source change does not change signing, pairing, assistant identity, or the companion call flow.
