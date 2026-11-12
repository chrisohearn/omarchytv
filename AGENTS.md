# OmarchyTV

Chris's fork (`chrisohearn/omarchytv`) of the OmarchyTV project: a small Android TV launcher plus the tools, PC plugins and web-app builds around it. The project is beta. There is no CI in this repo, so run the checks below yourself.

## Launcher updates

Before building or installing an update, follow the dated-build and live-verification rules in [PROJECT.md](PROJECT.md#delivery-expectation).

## Repo map

| Path | What it is |
|---|---|
| `launcher/` | Android TV launcher (Gradle, package `com.omarchytv.home`). Plain Java, no Kotlin, no Compose. |
| `mobile-companion/` | Phone installer app. |
| `pc-plugin/` | PC side: `omarchy-link` and `omarchy-tv`. |
| `homewatch/` | Python service. |
| `webos/`, `tizen/` | Web-app builds for LG and Samsung TVs. |
| `tools/` | TV helper scripts (`tv.py`, `b64push.py`, disable and restore scripts). |
| `examples/<brand>-<model>/` | One folder per TV model with its `debloat.txt`. |
| `docs/` | [`docs/ui-validation.md`](docs/ui-validation.md) covers launcher UI checks. |

## Commands

| Task | Command |
|---|---|
| Launcher checks | From `launcher/`: `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug` (JDK 17, `ANDROID_HOME` with platform 34 and build-tools 34.0.0) |
| Remote mappings check | From `launcher/`: `./gradlew :app:testReleaseUnitTest` (validates `launcher/app/src/main/assets/remotes.json`) |
| Build and install on a TV | `TV_HOST=<ip> launcher/build-and-install.sh` |
| PC plugin tests | From `pc-plugin/`: `python3 -m unittest discover -s omarchy-tv/tests -v` |
| PC agent feed tests | From `pc-plugin/omarchy-link/tests`: `python3 -m unittest test_agents` |
| Homewatch tests | From `homewatch/`: `python3 -m unittest discover -s tests -v` |
| Mobile companion ADB protocol test | From `mobile-companion/`: `python3 test_adb_protocol.py` |
| TV web-app tests | `node --test tizen/*.test.cjs webos/tests/*.test.js` |

Launcher UI tests write PNG evidence to `launcher/app/build/ui-evidence/`.

## Rules

- The launcher targets TVs with 1 GB of RAM: no blur and no video backgrounds, and add no new polling, background services or network use. The existing networked features (Omarchy Link, weather, casting, Grok Bot) are approved; keep them working. Anything specific to one TV goes in `launcher/app/src/main/res/values/config.xml`, not in code.
- No new launcher dependencies without Chris's agreement.
- Debloat lists only disable packages with `pm disable-user`, which is reversible. Never list packages to uninstall.
- Read [CONTRIBUTING.md](CONTRIBUTING.md) before changing the launcher, remote mappings or debloat lists.

## Ownership

This codebase is yours: what it's for and how it's built are your call, and you'll keep building on it.
Exercise this ownership within established authorization, preserving more-specific user decisions, product boundaries, and required source, review, release, and safety controls.
