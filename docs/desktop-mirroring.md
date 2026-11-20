# Choose a desktop on the TV

The optional `tools/mirror_sources.py` adapter adds a computer picker to the
existing **PC screen → Mirror screen** action on OmarchyTV 2.21 or later.
It uses the paired companion and the launcher's question dialog. The launcher
APK and pairing remain unchanged.

1. Copy `tools/mirror_sources.py` beside your existing custom session adapter.
2. Add `screenSources` to the companion's `config.json`. Use distinct IDs and
   labels. Each optional `command` is an argument list for a recorder that writes
   H.264 MPEG-TS to standard output. Omit it to use the local screen recorder.

   ```json
   {
     "screenMirrorFromTv": "allow",
     "screenAudio": true,
     "screenPickerDialog": true,
     "screenSources": [
       {"id": "pc", "label": "PC"},
       {"id": "laptop", "label": "Laptop", "command": ["/path/to/recorder"]}
     ]
   }
   ```

3. For the optional question dialog, verify that **AI agent alerts while you
   watch** is already enabled on the TV. Do not enable that setting implicitly.
   Without it, keep the existing Mirror action and expose source commands through
   the launcher's existing PC shortcuts instead.
4. Delegate `handle_screen_mirror(action)` in the custom `Session` subclass to
   one `MirrorSelector(self)` instance. Keep the original handler when
   `screenSources` is absent.
5. Restart that companion while no stream is active.
6. On the TV, open **PC screen → Mirror screen** and choose a computer.
   **Cancel** leaves the desktop untouched. **Back** stops the selected stream.

Configure direct mirroring only after the desktop owner approves it. The TV
selects a configured label; it cannot supply a command. The companion serves
one temporary, randomly named URL to the paired TV's IP. No remote keyboard or
mouse control is added.

For a remote computer, use an existing authenticated connection and an explicit
recorder command. Keep credentials out of the argument list. Confirm that the
remote recorder exits when its connection closes. The remote computer and the
paired companion must both be available throughout playback.

If the stream is blank, test the configured recorder locally. A computer can
have a recorder installed without having a supported GPU encoder. A software
encoder may work at a lower frame rate. Audio capture also requires a working
desktop audio output.

Keep a copy of the previous configuration and adapter before installation. To
remove the picker, restore those copies and restart the companion while idle.
