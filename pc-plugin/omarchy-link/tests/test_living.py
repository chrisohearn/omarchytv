"""Living screensaver feed: notifications from D-Bus, GitHub events, X (notifications or API, with a budget)."""
import signal
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from unittest import mock

import helpers  # noqa: F401  (hermetic env + sys.path)
from omarchy_link import config, living, omastorm, session

BLOCK = '''method call time=1730563200.1 sender=:1.45 -> destination=org.freedesktop.Notifications serial=7 path=/org/freedesktop/Notifications; interface=org.freedesktop.Notifications; member=Notify
   string "Slack"
   uint32 0
   string ""
   string "Ola"
   string "ci sei?
seconda <b>riga</b>"
   array [
   ]
   int32 -1'''.split("\n")


class Notes(unittest.TestCase):
    def test_monitor_reaps_at_lease_expiry_without_polling(self):
        lv = living.Living()
        popen = living.subprocess.Popen
        children = []
        github_started = threading.Event()
        github_release = threading.Event()
        block = "\n".join(BLOCK)

        def launch(_args, **kwargs):
            script = (f"import signal, time; signal.signal(signal.SIGTERM, signal.SIG_IGN); "
                      f"print({block!r}, flush=True); time.sleep(30)")
            proc = popen([sys.executable, "-c", script], **kwargs)
            children.append(proc)
            return proc

        def github_items(_cfg):
            github_started.set()
            github_release.wait(10)
            return []

        def wait_for(predicate):
            deadline = time.monotonic() + 3
            while not predicate() and time.monotonic() < deadline:
                time.sleep(0.01)
            return predicate()

        prefs = {"notes": True, "x": "notify"}
        late = {"app": "Chromium", "title": "X", "body": "Synthetic late notification from x.com"}
        with mock.patch.object(living.shutil, "which", return_value="synthetic-monitor"), \
             mock.patch.object(living.subprocess, "Popen", side_effect=launch), \
             mock.patch.object(living, "github_items", side_effect=github_items):
            try:
                lv.set_prefs(prefs)
                self.assertIsNone(lv.notes_thread)
                lv.touch()
                self.assertTrue(wait_for(lambda: lv.notes))
                old = children[0]
                self.assertTrue(github_started.wait(3))
                lv._on_note(old, late)
                self.assertEqual(len(lv.payload()["x"]), 1)

                lv.touch(0.25)
                lv.touch()
                time.sleep(0.3)
                self.assertEqual(children, [old])
                self.assertIsNone(old.returncode)

                lv.touch(0.1)
                self.assertTrue(wait_for(lambda: old.returncode is not None),
                                "the monitor must be reaped without a TV poll")
                self.assertEqual(old.returncode, -signal.SIGKILL)
                self.assertTrue(wait_for(lambda: lv.notes_thread is None))
                lv._on_note(old, late)
                self.assertEqual((list(lv.notes), list(lv.x)), ([], []))

                lv.set_prefs(prefs)
                lv.touch()
                self.assertTrue(wait_for(lambda: lv.notes))
                self.assertEqual(len(children), 2)
                lv._on_note(old, late)
                pub = lv.payload()
                self.assertEqual(([n["title"] for n in pub["notes"]], pub["x"]), (["Ola"], []))
                lv.set_active(False)
                self.assertIsNotNone(children[1].returncode)
                self.assertEqual(lv.payload()["notes"], [])
            finally:
                github_release.set()
                lv.stop.set()
                lv.ensure()
                for thread in (lv.thread, lv.notes_thread):
                    if thread is not None:
                        thread.join(timeout=3)
                for proc in children:
                    proc.kill()
                    proc.wait(timeout=3)

    def test_parse_notify_multiline_and_markup(self):
        self.assertEqual(living.parse_notify(BLOCK), {"app": "Slack", "title": "Ola", "body": "ci sei? seconda riga"})
        self.assertIsNone(living.parse_notify(BLOCK[:3]))

    def test_x_from_browser_notification(self):
        n = {"app": "Chromium", "title": "X", "body": "@ana mentioned you: bella la TV x.com"}
        self.assertEqual(living.x_from_note(n)["kind"], "notify")
        self.assertIsNone(living.x_from_note({"app": "Chromium", "title": "GitHub", "body": "PR merged"}))
        self.assertIsNone(living.x_from_note({"app": "Slack", "title": "X", "body": "x.com link"}))

    def test_collector_keeps_what_the_tv_asked(self):
        cfg = {}
        lv = living.Living(lambda: cfg)
        note = {"app": "Slack", "title": "Ola", "body": "ciao"}
        with mock.patch.object(lv, "ensure"):
            lv.set_prefs({"notes": False, "x": "notify", "github": False})
            lv.touch()
            proc = lv.monitor.proc = mock.Mock()
            lv._on_note(proc, note)
            lv._on_note(proc, {"app": "Chromium", "title": "X", "body": "@ana replied on x.com"})
            p = lv.payload()
            self.assertEqual((p["notes"], len(p["x"]), p["xMode"]), ([], 1, "notify"))
            lv.set_prefs({"notes": True, "x": "off"})
            lv._on_note(proc, note)
            p = lv.payload()
            self.assertEqual((p["notes"][0]["title"], p["x"]), ("Ola", []))

            cfg["livingToTv"] = False
            lv._on_note(proc, note)
            self.assertEqual(len(lv.notes), 1)
            self.assertEqual(lv.payload()["notes"], [])
            lv.set_prefs({"notes": False, "github": False})
            proc.wait.assert_called_once()
            self.assertEqual(list(lv.notes), [])

    def test_prefs(self):
        self.assertEqual(living.parse_prefs(None), {"notes": False, "github": True, "x": "off", "radar": False})
        self.assertTrue(living.parse_prefs({"radar": True})["radar"])
        self.assertEqual(living.parse_prefs({"notes": "yes", "x": "scrape"})["x"], "off")


class GitHub(unittest.TestCase):
    def test_describe(self):
        push = {"type": "PushEvent", "repo": {"name": "piermark/omarchytv"}, "actor": {"login": "piermark"},
                "created_at": "2026-11-02T10:00:00Z",
                "payload": {"size": 2, "commits": [{"message": "a"}, {"message": "Boot screen: CRT\n\nbody"}]}}
        d = living.describe_event(push)
        self.assertEqual((d["what"], d["text"], d["repo"]), ("push 2", "Boot screen: CRT", "piermark/omarchytv"))
        star = {"type": "WatchEvent", "repo": {"name": "piermark/omarchytv"}, "actor": {"login": "fan"}, "payload": {}}
        self.assertEqual(living.describe_event(star)["text"], "fan")
        self.assertIsNone(living.describe_event({"type": "GollumEvent"}))

    def test_items_skip_bad_names(self):
        with mock.patch.object(living, "_gh_json", return_value=[]) as g, mock.patch.object(living.shutil, "which", return_value=None):
            living.github_items({"githubUser": "bad/../user", "githubRepos": ["ok/repo", "../etc"]})
        self.assertEqual([c[0][0] for c in g.call_args_list], ["repos/ok/repo/events?per_page=20"])


class CollectionSettings(unittest.TestCase):
    def test_each_background_action_checks_settings_and_collection_recovers(self):
        def bridge_settings():
            try:
                return config.read_config()
            except ValueError:
                return {"livingToTv": False}

        lv = living.Living(bridge_settings)
        with mock.patch.object(lv, "ensure"):
            lv.set_prefs({"github": True, "radar": True, "x": "api"})
            lv.touch()

        def tick():
            with mock.patch.object(lv.stop, "wait", side_effect=(False, True)):
                lv._loop()

        with tempfile.TemporaryDirectory() as temporary:
            settings = Path(temporary) / "config.json"
            settings.write_text('{"githubUser": "before"}', encoding="utf-8")

            def invalidate(_cfg):
                settings.write_text("[]", encoding="utf-8")
                return []

            with mock.patch.object(config, "CONFIG_FILE", settings), \
                 mock.patch.object(living, "github_items", side_effect=invalidate) as github, \
                 mock.patch.object(omastorm, "snapshot", return_value=None) as radar, \
                 mock.patch.object(living, "x_mentions", return_value=([], "")) as x:
                tick()
                github.assert_called_once_with({"githubUser": "before"})
                lv.last_gh = 0
                tick()
                github.assert_called_once()
                radar.assert_not_called()
                x.assert_not_called()

                restored = '{"githubUser": "after", "xApi": false}'
                settings.write_text(restored, encoding="utf-8")
                github.side_effect = None
                github.return_value = []
                tick()
                github.assert_called_with({"githubUser": "after", "xApi": False})
                radar.assert_called_once_with({"githubUser": "after", "xApi": False})
                x.assert_not_called()
                settings.write_text('{"xApi": true, "xMonthlyUsd": 0.25}', encoding="utf-8")
                tick()
                x.assert_called_once_with({"xApi": True, "xMonthlyUsd": 0.25})


class XApi(unittest.TestCase):
    def setUp(self):
        try:
            living.X_FILE.unlink()
        except OSError:
            pass

    def test_not_set_up(self):
        self.assertEqual(living.x_mentions({}), ([], "x_not_set_up"))

    def test_mentions_since_id_and_spend(self):
        living.x_save({"bearer": "T", "username": "MarcoPorro"})
        calls = []

        def get(bearer, path, params):
            calls.append((path, dict(params)))
            if path.startswith("users/by/username"):
                return {"data": {"id": "42"}}
            return {"data": [{"id": "9", "text": "@MarcoPorro bella!", "author_id": "7", "created_at": "2026-11-02T10:00:00Z"}],
                    "includes": {"users": [{"id": "7", "username": "ana"}]}, "meta": {"newest_id": "9"}}
        items, err = living.x_mentions({}, get)
        self.assertEqual((err, items[0]["user"], items[0]["text"]), ("", "@ana", "@MarcoPorro bella!"))
        d = living.x_load()
        self.assertEqual((d["userId"], d["sinceMention"]), ("42", "9"))
        self.assertAlmostEqual(living.x_spent(d), 0.01 + 0.005 + 0.01)
        living.x_mentions({}, get)
        self.assertEqual(calls[-1][1]["since_id"], "9")
        self.assertEqual(len([c for c in calls if c[0].startswith("users/by")]), 1)
        self.assertEqual(living.x_mentions({"xMonthlyUsd": 0.01}, get), ([], "x_budget"))

    def test_token_file_is_private(self):
        living.x_save({"bearer": "T", "username": "u"})
        self.assertEqual(oct(living.X_FILE.stat().st_mode & 0o777), "0o600")


class SessionLiving(unittest.TestCase):
    def test_prefs_event_and_push(self):
        s = session.Session.__new__(session.Session)
        s.cfg, s.logs, s.state = {}, [], {"screensaver": True}
        s.log = s.logs.append
        s.stream = mock.Mock()
        with mock.patch.object(living.Living, "ensure"):
            s.handle_event({"type": "living_prefs", "data": {"notes": True, "github": False}})
            s.living.set_active(True)
            proc = s.living.monitor.proc = mock.Mock()
            s.living._on_note(proc, {"app": "Slack", "title": "Ola", "body": "ciao"})
            s.push_living()
            s.push_living()
        self.assertEqual(s.stream.send.call_count, 1)
        kind, pub = s.stream.send.call_args[0]
        self.assertEqual((kind, pub["notes"][0]["title"]), ("living", "Ola"))
        self.assertTrue(s.living.active())


if __name__ == "__main__":
    unittest.main()
