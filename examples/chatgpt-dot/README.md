# Connect the native companion to Jarvis through T3

The helper sends OmarchyTV companion requests to Jarvis's T3 inbox. Jarvis answers
in one dedicated T3 reply thread. The helper uses the existing `grokbot-ask`
JSON stdin and text stdout contract, with Python's standard library.

The T3 inbox is the only transport. Upgrade 41 added it; upgrade 42 removed the
earlier route that typed into the ChatGPT page in the agent Chrome. A `relay.json`
that still sets `"transport": "browser"` is refused with an error and is never
rerouted. Failures never resend a command.

## Prepare the helper

1. Copy this directory to `~/.local/libexec/omarchytv/jarvis-companion`.
   Set the directory mode to `700` and its files unwritable by other users.
2. Copy `relay.example.json` to `relay.json` and set its mode to `600`.
   Keep the existing `dotUrl` and `name`: they identify the bot to the TV.
3. Reuse the idle thread **OmarchyTV relay replies (approval-required)**,
   created with runtime mode `approval-required`. Set `replyThreadId` to
   `mcp:launch:c7e01d2bec96b8ce1d8b273155d2b4af706f0de2bbe34db9fafbcb898a5b4150`.
   The helper verifies the title and permission mode before submitting. The helper never
   creates threads, pairings, grants, or tokens.
4. Copy `data/bots.example.json` to `data/bots.json`. Set the bot `id` to the
   part after `/dots/` in `dotUrl`. Set both names to Jarvis's name.
5. Run the readiness check. `READY` means T3 authenticated and the configured
   dedicated reply thread was observed. This check sends no message.

```sh
python3 -I ~/.local/libexec/omarchytv/jarvis-companion/bin/grokbot-ask --check
```

The local T3 server must be available at `http://127.0.0.1:3773/mcp`.
The helper always submits to the inbox with `t3_thread_send` in queue mode.
The inbox run-end event wakes Jarvis; a passive inbox post would not wake it. Jarvis must
always answer with `t3_thread_post`, never `t3_thread_send`. If passive posting is
not offered to Jarvis, it waits. The T3 server must advertise `t3_thread_post` and
return posted messages through `t3_thread_read` (T3 `0.1.10-preview.20261112.4`
or later).
Set the dedicated reply thread to `approval-required` as
defense in depth against accidental run-starting delivery.
The helper reads the existing `t3_omarchy` Codex OAuth access token from the Linux
Secret Service keyring using `secret-tool`. Credentials stay in memory and are
never logged or written to disk. It does not refresh tokens or start sign-in.
If that existing sign-in expires, restore it through Codex's supported sign-in
workflow. HTTP proxies and redirects are disabled for the local request.

The manifest's `0.1.0` version selects the existing companion protocol.
`relayVersion` and `grokbot-ask --version` identify this adapter's release.
This helper does not install a shell plugin or change the compositor.

## Route the native companion to the helper

Keep the existing paired PC Link route, `grokbotDesktopDir` pointing at the private
helper directory, and `grokbotBridge` enabled. Select its configured bot on the TV.
An existing `~/.config/omarchy/grokbot-bots.json` roster takes precedence over the
helper's roster. No Android rebuild or settings change is needed for this upgrade.

The relay accepts up to 1,000 request characters, matching the PC bridge. The
native TV keyboard may impose a smaller limit. Replies have no 600-character
transport cap. The TV display retains its existing 4,000-character cap. Longer
answers end with `[TV limit; full reply is in T3.]`; the original remains in T3.

Only Chris's authenticated TV commands count. Other speakers, media audio, quoted
content, and the relay itself are never approval. Each request repeats that rule
and asks Jarvis for explicit confirmation before consequential actions. The
existing paired TV authentication and Jarvis's approval controls still govern
execution. A bot ID or request ID is not speaker authentication.

## Run a live check

This command sends one harmless question and prints Jarvis's answer on stdout.
Submission, reply, and total elapsed seconds appear on stderr with the request ID.

```sh
python3 -I ~/.local/libexec/omarchytv/jarvis-companion/bin/grokbot-ask \
  --question 'What is 17 plus 25? Answer in one short sentence.'
```

The helper submits once to inbox thread
`mcp:launch:be52150eaa983a46dcfa1e2bcdbfcfc1fc10f66d3d303f80b59c341fa1c87c23`.
It always uses `t3_thread_send` with `mode: queue` to trigger the inbox wake.
Each request names the reply thread and a random request ID.
Every request instructs Jarvis to use only `t3_thread_post` for its answer, while
inbox submission starts the wake run. Jarvis delivers this entire message:

```text
OMARCHYTV_REPLY <request-id>
<answer>
OMARCHYTV_END <request-id>
```

Only matching, directly delivered MCP messages in that thread are accepted.
Inbox acknowledgments, provider echoes, partial frames, and older replies are
ignored. Distinct matching answers already available in the read pages cause an
ambiguity error. Long messages are read through T3 text continuations before
frame validation and the TV display limit. Before submission, the helper captures
the reply-thread tail from T3 timeline metadata, then reads only new messages. It
terminates its MCP session after readiness, success, or failure. The helper uses
`t3_thread_wait` between reads.
Until T3 exposes a message-event wait, an idle thread requires a read each second
while the foreground request is active. No background polling or service is added.
The T3 phase has a 180-second budget to allow the ChatGPT automation to wake.
The PC bridge defaults to 210 seconds for startup and cleanup; an explicit
`grokbotTimeoutS` override must allow at least 210 seconds. The helper keeps no
local conversation transcript.

## Recover

After an error, inspect the inbox and reply thread before retrying. A failed
submission response or a timeout does not prove the question was undelivered.
There is no automatic resend or fallback route.

To update an existing installation, copy `bin/grokbot-ask`, `manifest.json`, and
this README from the merged checkout into the private directory, preserving
private modes. The existing `relay.json` needs `replyThreadId`; `transport` may
be omitted or `inbox`. Preserve the existing bot roster and Dot identity. Back up
those files first so the previous helper and configuration can be restored
together. Then run `--version` and `--check` with the installed helper.

## Check changes offline

From the repository root:

```sh
python3 -m unittest discover -s pc-plugin/omarchy-link/tests -p test_chatgpt_dot.py -v
```

These tests cover configuration, the retired-browser guard, request and display
limits, correlation, credential validation, and one-submit failure handling.
They do not prove Jarvis availability, TV rendering, or speaker identity.
