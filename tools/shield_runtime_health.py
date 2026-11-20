"""Use the existing private ADB route; do not create a poller or scheduler."""
import json
import os
from pathlib import Path
import subprocess
import sys
from shield_health import inspect, reconcile

ROOT = Path(__file__).resolve().parent
STATUS = Path.home() / ".local/state/omarchytv-watch/shield-health.json"

def shell(command):
    if (command.startswith("settings put ") or command.startswith("wm size ")) and streaming():
        raise RuntimeError("Desktop streaming started; settings restore deferred")
    run = subprocess.run([sys.executable, str(ROOT / "shield.py"), "shell", command],
                         capture_output=True, text=True, timeout=105)
    if run.returncode:
        raise RuntimeError("Existing Shield ADB route failed")
    return run.stdout

def streaming():
    lease = Path(os.environ.get("XDG_RUNTIME_DIR", "/run/user/1000")) / "vibepollo-virtual/lease.json"
    if lease.exists():
        return True
    try:
        entries = list(Path("/proc").iterdir())
    except OSError:
        return True
    for entry in entries:
        if not entry.name.isdigit() or int(entry.name) == os.getpid():
            continue
        try:
            args = (entry / "cmdline").read_bytes().decode().split("\0")
        except (OSError, UnicodeError):
            continue
        if any(arg.endswith("/mirror_sources.py") for arg in args):
            return True
        if "cast" in args and "--screen" in args and any(arg.endswith("/omarchy-link") for arg in args):
            return True
    return False

def run_health(apply=False):
    result = reconcile(shell, apply=apply, external_busy=streaming())
    import time
    result["observed_at"] = time.time()
    try:
        previous = json.loads(STATUS.read_text())
    except (OSError, ValueError):
        previous = {}
    if previous.get("last_boot_change"):
        result["last_boot_change"] = previous["last_boot_change"]
    last_boot = previous.get("last_good_boot_id") or previous.get("boot_id")
    result["last_good_boot_id"] = result.get("boot_id") or last_boot
    if last_boot and result.get("boot_id") and last_boot != result["boot_id"]:
        result["last_boot_change"] = {"from": last_boot, "to": result["boot_id"],
                                      "at": result["observed_at"]}
    STATUS.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    import tempfile
    with tempfile.NamedTemporaryFile(mode="w", dir=STATUS.parent, prefix=".health-", delete=False) as output:
        json.dump(result, output)
        name = output.name
    os.replace(name, STATUS)
    return result

if __name__ == "__main__":
    print(json.dumps(run_health(apply="--apply" in sys.argv)))
