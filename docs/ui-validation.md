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

The tests cover four through seven app columns, both app rows, scrolling, spatial D-pad navigation, app-position feedback, hidden widgets, read-only panels, panel-edit cancellation, app/menu callbacks, partial custom palettes, and old panel positions near the app window. `SurfacesTest` checks stock theme foreground contrast over black, white, gray, and saturated wallpaper colors.

The home window shows up to two app rows. Left and Right stay within the current row. Down moves to the next app row, and Up from the first row selects the nearest actionable widget. Down from a widget returns to the selected app. Additional app rows scroll into view.

Home panels stay dark with both dark and light themes. Theme accents and wallpapers remain configurable. The home panels use at least 90% opacity to protect text over bright wallpaper; the saved transparency value remains available to the rest of the launcher. Large app banners are capped at 360 px wide at 1080p so both rows clear the widget deck. Banner artwork keeps its 16:9 shape, with a label beneath it.

Saved widget positions are checked against the measured app window. A position that no longer fits moves to the nearest available grid cell for display, without overwriting the saved coordinates.

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
