# Connect the native companion to an existing ChatGPT Dot

This experimental PC helper sends a native OmarchyTV companion request through
your signed-in ChatGPT Dot page. It uses the existing `grokbot-ask` stdin/stdout
contract. It does not create another assistant or call an undocumented ChatGPT API.

## Prepare the PC helper

Use an Omarchy PC with the existing shared `agent-desktop` Chrome, `cua-driver`,
and Revotech Engineering browser leases installed. Sign in to your Dot through
that browser first. The helper does not sign in, handle credentials, change the
browser profile, or approve site tool requests. It uses Python's standard library.

1. Copy this directory to a private PC directory, such as
   `~/.local/libexec/omarchytv/jarvis-companion`. Set the directory mode to `700`.
2. Copy `relay.example.json` to `relay.json`. Set `dotUrl` to the exact
   `https://chatgpt.com/dots/...` URL and `name` to the page's exact title.
   Set `relay.json` mode to `600` and keep it owned by your user.
3. Copy `data/bots.example.json` to `data/bots.json`. Set the bot `id` to the
   part after `/dots/` in that URL. Set both names to the Dot's name.
4. Keep `bin/grokbot-ask` owned by you and unwritable by other users.
5. Run the check below. `READY` means that the exact Dot and an empty composer
   were observed. This check sends no message.

```sh
python3 -I ~/.local/libexec/omarchytv/jarvis-companion/bin/grokbot-ask --check
```

The manifest ID and `0.1.0` version select OmarchyTV's existing companion helper
protocol. This directory is a protocol adapter, not a copy of the desktop Grok Bot
plugin. It does not install a shell plugin or change the compositor.

## Route the native pet to the helper

Back up your existing PC and TV settings before activation. Set the PC Link
configuration's `grokbotDesktopDir` to the private directory and keep
`grokbotBridge` enabled. An existing `~/.config/omarchy/grokbot-bots.json` roster
takes precedence over this directory's roster. Reconcile it before activation.

Select the native companion's Grok Bot bridge route and the configured bot on
the TV, rather than the separate Ask route. Keep `voiceKey` set to `assistant`
to preserve the normal Google microphone button. No Android rebuild is needed.
The stock native avatar shapes, branding, and Home-only overlay still apply.
This helper does not reproduce a ChatGPT pet sprite or place an overlay over Plex.

For a real test, submit one harmless question through the native pet and verify
its reply. Follow your household's approval rules before operating the TV.
The helper also accepts the native JSON object on stdin (`botId`, `prompt`, and
optional metadata). A wrong bot ID is refused before opening the browser.
Prompts are limited to 400 characters, covering the native TV keyboard's
240-character limit. The helper asks for replies of at most 600 characters.

## Recover from a failed request

The helper opens one owned tab, checks its exact Dot URL and title, preserves an
existing draft, and submits once. Each turn asks for a uniquely marked, brief
reply. An old answer cannot match the new turn. Sign-in, page changes, an
incomplete response frame, a clipped text node, a busy browser lease, and missing reply framing return
an error. The helper closes its tab and session and releases its browser leases.
If cleanup fails, it reports an error instead of claiming success.

After an error during submission or reply collection, inspect the Dot before
retrying: the request may already have arrived. There is no automatic resend.
The browser phase has a 135-second budget, followed by a 20-second cleanup budget.
Keep the native bridge timeout at least 180 seconds.

TV conversations remain in the existing ChatGPT Dot conversation. The helper
keeps no transcript, cookie, or token store. Each turn reminds the Dot to request
explicit confirmation before consequential actions. That reminder is an assistant
instruction, not a technical authorization gate. Existing Dot permissions and
confirmation controls still govern its tools.

To roll back, restore the previous `grokbotDesktopDir`, roster, and TV companion
settings. Disable the native route before removing the private helper directory.
Leave the shared agent browser, its sign-in, and other agents' tabs intact.

## Check changes offline

From the repository root, run:

```sh
python3 -m unittest discover -s pc-plugin/omarchy-link/tests -p test_chatgpt_dot.py -v
```

These tests exercise routing guards, draft preservation, response correlation,
one-submit behavior, and cleanup with browser calls replaced. They do not prove
ChatGPT availability, native TV rendering, or household control.
