import importlib.util
from pathlib import Path
import unittest


spec = importlib.util.spec_from_file_location("shield_health", Path(__file__).resolve().parents[1] / "tools" / "shield_health.py")
health = importlib.util.module_from_spec(spec)
spec.loader.exec_module(health)


class FakeShell:
    def __init__(self):
        self.clock = "24"
        self.size = "1920x1080"
        self.boot = "12345678-1234-4234-8234-123456789abc"
        self.power = "mWakefulness=Awake"
        self.activity = "mResumedActivity: ActivityRecord{abcd u0 com.omarchytv.home/.MainActivity t1}"
        self.media = "Sessions Stack - have 0 sessions:"
        self.commands = []
        self.fail = None
        self.ignore_writes = False

    def __call__(self, command):
        self.commands.append(command)
        if command == self.fail:
            raise RuntimeError("read/write failed")
        if command == "cat /proc/sys/kernel/random/boot_id":
            return self.boot
        if command == "settings get system time_12_24":
            return self.clock
        if command == "wm size":
            return "Physical size: 3840x2160\nOverride size: " + self.size
        if command == "dumpsys power":
            return self.power
        if command == "dumpsys activity activities":
            return self.activity
        if command == "dumpsys media_session":
            return self.media
        if command == "settings put system time_12_24 12":
            if not self.ignore_writes:
                self.clock = "12"
            return ""
        if command.startswith("wm size "):
            if not self.ignore_writes:
                self.size = command.removeprefix("wm size ")
            return ""
        raise AssertionError(command)

    def writes(self):
        return [c for c in self.commands if c.startswith("settings put") or c.startswith("wm size ")]


class HealthTests(unittest.TestCase):
    def setUp(self):
        self.shell = FakeShell()

    def test_restore_and_readback_only_approved_settings(self):
        result = health.reconcile(self.shell, apply=True)
        self.assertEqual(result["status"], "restored")
        self.assertEqual((result["clock"], result["size"]), ("12", "3839x2160"))
        self.assertEqual([a["status"] for a in result["actions"]], ["restored", "restored"])
        self.assertEqual(self.shell.writes(), ["settings put system time_12_24 12", "wm size 3839x2160"])

    def test_dry_run_and_idempotence(self):
        result = health.reconcile(self.shell)
        self.assertEqual(result["status"], "drift")
        self.assertEqual(len(result["actions"]), 2)
        self.assertFalse(self.shell.writes())
        self.shell.clock, self.shell.size = "12", "3839x2160"
        self.assertEqual(health.reconcile(self.shell, apply=True)["status"], "ok")
        self.assertFalse(self.shell.writes())

    def test_known_nonplaying_sessions_and_sleep(self):
        self.shell.power = "mWakefulness=Asleep"
        self.shell.activity = ""
        self.shell.media = "Sessions Stack - have 4 sessions:\n" + "\n".join(
            f"state=PlaybackState {{state={state}, position=0}}" for state in (0, 1, 2, 7))
        self.assertTrue(health.inspect(self.shell)["idle"])
        self.shell.media = "Sessions Stack - have 0 sessions"
        self.assertTrue(health.inspect(self.shell)["idle"])

    def test_busy_player_browser_or_external_stream_never_write(self):
        for cause in ("media", "activity", "external"):
            with self.subTest(cause=cause):
                shell = FakeShell()
                if cause == "media":
                    shell.media = "Sessions Stack - have 1 sessions:\nstate=PlaybackState {state=3, position=0}"
                if cause == "activity":
                    shell.activity = "topResumedActivity=ActivityRecord{abc u0 com.omarchytv.home/.CastPlayerActivity t1}"
                result = health.reconcile(shell, apply=True, external_busy=cause == "external")
                self.assertEqual(result["status"], "deferred")
                self.assertFalse(shell.writes())

    def test_ambiguous_or_failed_reads_never_write(self):
        for field, value in (("boot", "bad"), ("clock", "garbage"), ("size", "bad"),
                             ("power", "mWakefulness=Dozing"), ("activity", ""),
                             ("activity", "mResumedActivity: ActivityRecord{abc u0 com.omarchytv.home/.MainActivity t1}\ntopResumedActivity=null"),
                             ("media", ""), ("media", "Sessions Stack - have 1 sessions:\nstate=null"),
                             ("media", "Sessions Stack - have 1 sessions:\nstate=PlaybackState {state=unknown}"),
                             ("fail", "dumpsys media_session")):
            with self.subTest(field=field, value=value):
                shell = FakeShell()
                setattr(shell, field, value)
                result = health.reconcile(shell, apply=True)
                self.assertEqual(result["status"], "failed")
                self.assertFalse(shell.writes())

    def test_recheck_stops_when_playback_starts(self):
        shell = self.shell
        original = shell.__class__.__call__
        reads = 0
        def changing(command):
            nonlocal reads
            if command == "dumpsys media_session":
                reads += 1
                if reads == 2:
                    shell.media = "Sessions Stack - have 1 sessions:\nstate=PlaybackState {state=3, position=0}"
            return original(shell, command)
        result = health.reconcile(changing, apply=True)
        self.assertEqual(result["status"], "deferred")
        self.assertFalse(shell.writes())

    def test_playback_after_first_write_defers_second(self):
        shell = self.shell
        original = shell.__class__.__call__
        def changing(command):
            output = original(shell, command)
            if command == "settings put system time_12_24 12":
                shell.media = "Sessions Stack - have 1 sessions:\nstate=PlaybackState {state=3, position=0}"
            return output
        result = health.reconcile(changing, apply=True)
        self.assertEqual(result["status"], "deferred")
        self.assertEqual(result["actions"][0]["status"], "restored")
        self.assertEqual(shell.writes(), ["settings put system time_12_24 12"])

    def test_failed_write_or_readback_prevents_further_writes(self):
        for failure in ("write", "readback"):
            with self.subTest(failure=failure):
                shell = FakeShell()
                if failure == "write":
                    shell.fail = "settings put system time_12_24 12"
                else:
                    shell.ignore_writes = True
                result = health.reconcile(shell, apply=True)
                self.assertEqual(result["status"], "failed")
                self.assertEqual(len(shell.writes()), 1)
                self.assertEqual(result["actions"][0]["status"], "failed")

    def test_invalid_input_cannot_be_shell_command(self):
        result = health.reconcile(self.shell, apply=True, preferred_size="3839x2160; reboot")
        self.assertEqual(result["status"], "failed")
        self.assertFalse(self.shell.commands)


if __name__ == "__main__":
    unittest.main()
