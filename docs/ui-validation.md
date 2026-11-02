# Validate the launcher UI

Run these checks from `launcher/` with JDK 17 and `ANDROID_HOME` pointing to an Android SDK with platform 34 and build tools:

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

`HomeUiTest` renders the actual Android home views with Robolectric native graphics at 1920×1080. It uses bundled wallpaper and synthetic app, weather, and usage data. It does not start launcher services, call a network API, or launch another app.

The tests write PNGs to `launcher/app/build/ui-evidence/`:

- `light-app-focus.png`: light theme, focused app, aligned widget cards.
- `dark-app-focus.png`: dark theme and app-strip scrolling.
- `dark-widget-focus.png`: an actionable widget selected with the D-pad.

The tests cover app sizes from four to seven columns, focus at strip edges, app-position feedback, hidden widgets, read-only panels, panel-edit cancellation, and app/menu callbacks. `SurfacesTest` checks stock theme foreground contrast over black, white, gray, and saturated wallpaper colors.

These renders prove local layout and focus behavior. They do not establish hardware performance, overscan, remote long-press timing, or readability at a particular viewing distance.

## Run a separate TV pilot after approval

Obtain approval for a named device, test window, candidate APK, and rollback before installation. Confirm that media playback can be interrupted during that window.

1. Record the installed version, signing certificate, APK, and launcher configuration. Keep credentials and pairing data private.
2. Verify that the candidate preserves the installed signing identity and update path. Stop if they differ.
3. Install without clearing application data. Keep the TV on and preserve volume and device settings.
4. Check one light and one dark theme, the current wallpaper, four and seven app columns, and the existing widget positions.
5. Navigate the strip, widgets, and bar with the remote. Check focus visibility, scrolling, OK, long OK, Back, and the return from an app.
6. If the pilot fails, restore the recorded compatible APK and configuration using the agreed rollback. If a downgrade needs data removal, stop for a decision.

Merge and TV installation are separate approvals. This source change does not change signing, pairing, assistant identity, or the companion call flow.
