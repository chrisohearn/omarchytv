# Connect the native companion to Jarvis through T3

Upgrade 41 sends OmarchyTV companion requests to Jarvis's T3 inbox. Jarvis answers
in one dedicated T3 reply thread. The helper uses the existing `grokbot-ask`
JSON stdin and text stdout contract, with Python's standard library.

The default transport is `inbox`. The browser adapter remains available only
when `transport` is explicitly `browser`. Inbox failures never fall back to a
browser or resend a command. Upgrade 42 can remove the browser adapter after the
inbox route proves reliable.

## Prepare the helper

1. Copy this directory to `~/.local/libexec/omarchytv/jarvis-companion`.
   Set the directory mode to `700` and its files unwritable by other users.
2. Copy `relay.example.json` to `relay.json` and set its mode to `600`.
   Keep the existing `dotUrl` and `name` for bot identity and browser rollback.
3. Create one idle T3 thread titled **OmarchyTV relay replies**. Reuse that
   thread for every request. Set `replyThreadId` to its ID. The helper never
   creates threads, pairings, grants, or tokens.
4. Copy `data/bots.example.json` to `data/bots.json`. Set the bot `id` to the
   part after `/dots/` in `dotUrl`. Set both names to Jarvis's name.
5. Run the readiness check. `READY` means T3 authenticated and the configured
   dedicated reply thread was observed. This check sends no message.

```sh
python3 -I ~/.local/libexec/omarchytv/jarvis-companion/bin/grokbot-ask --check
```

The local T3 server must be available at `http://127.0.0.1:3773/mcp` and
advertise `t3_thread_post`. Replies use that passive tool: queueing a reply with
`t3_thread_send` would start an unwanted provider run on the answer text. Until
passive posting is available, readiness and requests fail before sending.
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
It uses `t3_thread_post` when advertised and otherwise `t3_thread_send` with
`mode: queue`. Each request names the reply thread and a random request ID.
Jarvis must use `t3_thread_post` to deliver the entire message in this format:

```text
OMARCHYTV_REPLY <request-id>
<answer>
OMARCHYTV_END <request-id>
```

Only matching, directly delivered MCP messages in that thread are accepted.
Inbox acknowledgments, provider echoes, partial frames, and older replies are
ignored. Distinct matching answers already available in the read pages cause an
ambiguity error. Before submission, the helper captures the reply-thread tail
with body previews limited to one character, then reads only new messages. It
terminates its MCP session after readiness, success, or failure. The helper uses
`t3_thread_wait` between reads.
Until T3 exposes a message-event wait, an idle thread requires a read each second
while the foreground request is active. No background polling or service is added.
The T3 phase has a 145-second budget. Keep the native bridge timeout at least
180 seconds. The helper keeps no local conversation transcript.

## Recover or roll back

After an error, inspect the inbox and reply thread before retrying. A failed
submission response or a timeout does not prove the question was undelivered.
There is no automatic resend or browser fallback.

To restore the retained browser route, set `transport` to `browser` in the private
`relay.json`. It requires the existing signed-in shared agent Chrome,
`agent-desktop`, `cua-driver`, and browser lease tools. That route preserves its
400-character request and requested 600-character reply limits. Its `--check`
observes the exact Dot and empty composer without sending. This upgrade's live
verification does not exercise that route.

To update an existing installation, copy `bin/grokbot-ask`, `manifest.json`, and
this README from the merged checkout into the private directory, preserving
private modes. Add `replyThreadId` and set `transport` to `inbox` in the existing
`relay.json`. Preserve the existing bot roster and Dot identity. Back up those
files first so the previous helper and configuration can be restored together.

## Check changes offline

From the repository root:

```sh
python3 -m unittest discover -s pc-plugin/omarchy-link/tests -p test_chatgpt_dot.py -v
```

These tests cover transport defaults, request and display limits, correlation,
credential validation, one-submit failure handling, and retained browser guards.
They do not prove Jarvis availability, TV rendering, or speaker identity.
