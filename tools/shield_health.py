"""Conservative, injected-shell checks for Shield display settings."""

import re
import uuid


_HOME = {
    "com.omarchytv.home/.MainActivity",
    "com.omarchytv.home/com.omarchytv.home.MainActivity",
    "com.omarchytv.home/.DreamActivity",
    "com.omarchytv.home/com.omarchytv.home.DreamActivity",
    "com.omarchytv.home/.OmarchyDream",
    "com.omarchytv.home/com.omarchytv.home.OmarchyDream",
}
_SIZE = re.compile(r"[1-9][0-9]*x[1-9][0-9]*\Z")


def _wakefulness(shell):
    wake = re.findall(r"\bmWakefulness=(\w+)", shell("dumpsys power"))
    if len(set(wake)) != 1 or wake[0] not in ("Awake", "Asleep", "Dreaming", "Dozing"):
        raise ValueError("unknown wakefulness")
    return wake[0]


def inspect(shell, external_busy=False):
    """Read state; unknown or failed evidence is never treated as idle."""
    result = {"idle": False, "clock": None, "size": None, "boot_id": None,
              "actions": [], "status": "failed", "errors": []}
    try:
        if not isinstance(external_busy, bool):
            raise ValueError("external_busy must be a boolean")
        boot = shell("cat /proc/sys/kernel/random/boot_id").strip()
        result["boot_id"] = str(uuid.UUID(boot))
        clock = shell("settings get system time_12_24").strip()
        if clock not in ("12", "24", "null"):
            raise ValueError("unknown clock setting")
        result["clock"] = clock
        size = shell("wm size")
        physical = re.findall(r"^Physical size: ([1-9][0-9]*x[1-9][0-9]*)\s*$", size, re.M)
        override = re.findall(r"^Override size: ([1-9][0-9]*x[1-9][0-9]*)\s*$", size, re.M)
        if len(physical) != 1 or len(override) > 1 or any(
            not re.fullmatch(r"(?:Physical|Override) size: [1-9][0-9]*x[1-9][0-9]*", line.strip())
            for line in size.splitlines() if line.strip()
        ):
            raise ValueError("unknown display size")
        result["size"] = override[0] if override else physical[0]
        wake = _wakefulness(shell)
        activities = shell("dumpsys activity activities")
        foreground_lines = [line for line in activities.splitlines()
                            if re.search(r"\b(?:mResumedActivity|topResumedActivity)\s*[:=]", line)]
        foreground = []
        for line in foreground_lines:
            components = re.findall(r"\b[\w.]+/[\w.$]+", line)
            if len(components) == 1:
                foreground.append(components[0])
            elif not components and re.search(r"(?:mResumedActivity|topResumedActivity)\s*[:=]\s*null\s*$", line):
                foreground.append(None)
            else:
                raise ValueError("unknown foreground activity")
        if not foreground:
            raise ValueError("unknown foreground activity")
        media = shell("dumpsys media_session")
        counts = re.findall(r"^\s*Sessions Stack - have (\d+) sessions?:?\s*$", media, re.M)
        states = re.findall(r"\bstate=PlaybackState\s*\{\s*state=(\d+)\b", media)
        if not counts or sum(map(int, counts)) != len(states) or re.search(
            r"\bstate=PlaybackState\s*\{\s*state=(?!\d+\b)", media
        ):
            raise ValueError("unknown media sessions or playback state")
        wake_after = _wakefulness(shell)
        if (wake != wake_after or external_busy
                or any(activity is not None and activity not in _HOME for activity in foreground)
                or any(int(state) not in (0, 1, 2, 7) for state in states)):
            result["status"] = "deferred"
        elif len(set(foreground)) != 1 or (wake == "Awake" and foreground[0] is None):
            raise ValueError("unknown foreground activity")
        else:
            result["idle"] = True
            result["status"] = "idle"
    except Exception as exc:
        result["errors"].append(str(exc) or type(exc).__name__)
    return result


def reconcile(shell, apply=False, external_busy=False, preferred_size="3839x2160"):
    """Report drift or restore the two approved settings while idle."""
    if not isinstance(apply, bool) or not isinstance(preferred_size, str) or not _SIZE.fullmatch(preferred_size):
        return {"idle": False, "clock": None, "size": None, "boot_id": None,
                "actions": [], "status": "failed", "errors": ["invalid apply or preferred_size"]}
    result = inspect(shell, external_busy)
    if not result["idle"]:
        return result
    boot_id = result["boot_id"]
    actions = []
    for key, target, command in (
        ("clock", "12", "settings put system time_12_24 12"),
        ("size", preferred_size, "wm size " + preferred_size),
    ):
        if result[key] == target:
            continue
        if not apply:
            actions.append({"setting": key, "from": result[key], "to": target, "status": "needed"})
            continue
        result = inspect(shell, external_busy)
        if not result["idle"] or result["boot_id"] != boot_id:
            if result["boot_id"] != boot_id:
                result["idle"] = False
                result["status"] = "failed"
                result["errors"].append("boot changed during reconciliation")
            result["actions"] = actions
            return result
        if result[key] == target:
            continue
        action = {"setting": key, "from": result[key], "to": target, "status": "failed"}
        actions.append(action)
        try:
            shell(command)
        except Exception as exc:
            result["idle"] = False
            result["status"] = "failed"
            result["errors"].append(str(exc) or type(exc).__name__)
            result["actions"] = actions
            return result
        result = inspect(shell, external_busy)
        if result["boot_id"] != boot_id or result[key] != target or result["status"] == "failed":
            result["idle"] = False
            result["status"] = "failed"
            result["errors"].append("setting readback failed or boot changed: " + key)
            result["actions"] = actions
            return result
        action["status"] = "restored"
        if not result["idle"]:
            result["actions"] = actions
            return result
    result["actions"] = actions
    result["status"] = "restored" if any(a["status"] == "restored" for a in actions) else (
        "drift" if actions else "ok")
    return result
