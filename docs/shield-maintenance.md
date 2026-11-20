# Preserve Shield settings during maintenance

The `tools/shield_health.py` helper checks Android state through an injected shell
function. `inspect()` reports whether maintenance is safe. `reconcile()` restores
the configured display size and 12-hour clock only when `apply=True` and the TV is
idle. It checks foreground activity, power, all media sessions, and the caller's
external-stream flag. An unknown response prevents writes.

Install `tools/shield_runtime_health.py` and `tools/shield_health.py` beside the
existing private `shield.py` transport. The runtime wrapper records the current
result in `~/.local/state/omarchytv-watch/shield-health.json`. It preserves the last
observed boot change so the existing watch can verify the next normal reboot.
The wrapper requires the existing transport; it adds no service or scheduled job.

Call `run_health(apply=True)` from the existing companion's reconnect and return-to-idle
hooks, and before the nightly APK update. Use `inspect(shell, external_busy=streaming())`
as a fresh guard immediately before either APK installation or PC plugin replacement.
Check `streaming()` again after that native probe, immediately before the write.
Keep the existing nightly timers. Include `shield-health.json` failures in the
existing release-watch report.

The default `3839x2160` override is specific to the approved near-4K setting on the
living-room Shield. Other displays need their own `preferred_size` choice. Density,
wallpaper, pairing, and other preferences are outside the helper's write scope.

A successful readback proves current settings, not persistence across a future
reboot. Verify the recorded boot ID, settings, and Home screen after the next
normal reboot. Do not reboot merely to satisfy this check when the owner has
asked to wait.
