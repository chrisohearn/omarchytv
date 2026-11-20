#!/usr/bin/env python3
"""Choose a configured desktop on the TV, using its existing paired companion.

Loaded by the personal session adapter. The optional screenSources config holds
local, owner-configured recorder argv lists; no command comes from the TV.
"""
from __future__ import annotations

from pathlib import Path
import re
import signal
import subprocess
import sys
import threading


def sources(cfg):
    result = []
    for source in cfg.get("screenSources", [])[:3]:
        if not isinstance(source, dict):
            raise ValueError("screenSources entries must be objects")
        ident, label = source.get("id", ""), source.get("label", "")
        command = source.get("command")
        if not isinstance(ident, str) or not re.fullmatch(r"[a-z0-9-]{1,32}", ident):
            raise ValueError("invalid screen source id")
        if not isinstance(label, str) or not label.strip() or len(label) > 24 or label == "Cancel":
            raise ValueError("invalid screen source label")
        if command is not None and (not isinstance(command, list) or not command
                                    or any(not isinstance(x, str) or not x for x in command)):
            raise ValueError("screen source command must be a nonempty argv list")
        if any(x["id"] == ident or x["label"] == label for x in result):
            raise ValueError("duplicate screen source")
        result.append({"id": ident, "label": label, "command": command})
    return result


def choose_source(client, configured, cancelled):
    """Only an explicit answer to this request can select a recorder."""
    result = client.request("POST", "/v1/ask", {
        "question": "Which desktop would you like to mirror?",
        "choices": [x["label"] for x in configured] + ["Cancel"],
        "from": "PC screen", "timeout": 60,
    })
    while result.get("state") == "pending" and not cancelled.is_set():
        ident = result.get("id", "")
        if not isinstance(ident, str) or not re.fullmatch(r"[0-9a-f]{16}", ident):
            raise ValueError("invalid TV question id")
        result = client.request("GET", f"/v1/ask/{ident}?wait=25", timeout=35)
    if cancelled.is_set() or result.get("state") != "answered":
        return None
    return next((x for x in configured if x["label"] == result.get("answer")), None)


class MirrorSelector:
    def __init__(self, session):
        self.session = session
        self.lock = threading.Lock()
        self.cancelled = threading.Event()
        self.pending = False

    def handle(self, action):
        if action == "stop":
            with self.lock:
                self.cancelled.set()
                proc = getattr(self.session, "_mirror", None)
                if proc and proc.poll() is None:
                    proc.terminate()
                self.session._mirror = None
            return
        if action != "start" or self.session.cfg.get("screenMirrorFromTv") != "allow":
            return
        with self.lock:
            proc = getattr(self.session, "_mirror", None)
            if self.pending or (proc and proc.poll() is None):
                return
            configured = sources(self.session.cfg)
            if not configured:
                return
            self.pending = True
            self.cancelled = threading.Event()
            threading.Thread(target=self._choose, args=(configured, self.cancelled), daemon=True).start()

    def _choose(self, configured, cancelled):
        try:
            selected = choose_source(self.session._client(), configured, cancelled)
            with self.lock:
                if selected and not cancelled.is_set():
                    self.session._mirror = subprocess.Popen(
                        [sys.executable, str(Path(__file__).resolve()), selected["id"]],
                        stdin=subprocess.DEVNULL, start_new_session=True)
        except Exception as exc:
            self.session.log(f"Desktop selection failed: {exc}")
        finally:
            with self.lock:
                self.pending = False


def stream(source_id):
    sys.path.insert(0, str(Path.home() / ".local/share/omarchy-link/lib"))
    from omarchy_link import cast, client, config

    cfg = config.load_config()
    selected = next((x for x in sources(cfg) if x["id"] == source_id), None)
    if selected is None:
        raise ValueError("desktop source is not configured")
    if cfg.get("screenMirrorFromTv") != "allow":
        raise ValueError("direct desktop mirroring is not enabled")
    pairing = config.Pairing.load()
    c = client.Client(pairing.tv, pairing.pc_id, not cfg.get("allowOtherSubnets"))
    item = cast.screen_item({**cfg, **({"screenCommand": selected["command"]} if selected["command"] else {})})
    item["title"] = selected["label"] + " screen"
    # Let via_link's finally close the private listener and recorder on Back,
    # service shutdown, or an explicit stop from the session adapter.
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    result = cast.via_link(c, item, wait=True, out=lambda text: print(text, flush=True))
    print(result, flush=True)
    return 0 if result.get("state") in ("stopped", "ended") else 1


if __name__ == "__main__":
    raise SystemExit(stream(sys.argv[1]))
