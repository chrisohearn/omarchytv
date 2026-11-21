import json, os, sys, tempfile, threading, unittest, urllib.error, urllib.request
import runpy
from types import SimpleNamespace
from unittest import mock
from http.server import HTTPServer
from pathlib import Path
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "lib"))
sys.path.insert(0, os.path.join(os.path.dirname(__file__), "..", "..", "omarchy-link", "lib"))
from omarchy_tv import office, tvupdate, webos
from omarchy_link import assistant, config as link_config, living, office as link_office, power
from omarchy_link.assistant.providers import openai_api


class IsolatedBridgeTest(unittest.TestCase):
    def patch(self, patcher):
        self.addCleanup(patcher.stop)
        return patcher.start()

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.home = self.root / "home"
        self.xdg = self.root / "config"
        self.tmpdir = self.root / "tmp"
        self.tmpdir.mkdir()
        self.patch(mock.patch.dict(os.environ, HOME=str(self.home), TMPDIR=str(self.tmpdir),
                                   XDG_CONFIG_HOME=str(self.xdg), XDG_STATE_HOME=str(self.root / "state"),
                                   XDG_CACHE_HOME=str(self.root / "cache"), XDG_DATA_HOME=str(self.root / "data"),
                                   XDG_RUNTIME_DIR=str(self.root / "run")))
        self.patch(mock.patch.object(tempfile, "tempdir", str(self.tmpdir)))
        self.settings = self.xdg / "omarchy-link/config.json"
        self.settings.parent.mkdir(parents=True)
        self.patch(mock.patch.object(link_config, "CONFIG_FILE", self.settings))
        self.patch(mock.patch.object(link_config, "AI_FILE", self.settings.with_name("ai.json")))
        self.patch(mock.patch.object(webos, "TOKEN_FILE", self.root / "token"))
        self.patch(mock.patch.object(webos, "ai_meter", return_value=[]))
        self.patch(mock.patch.object(webos, "now_playing", return_value=None))

    def configure(self, **settings):
        self.settings.write_text(json.dumps(settings), encoding="utf-8")

    def request(self, path, method="GET", body=None, token=None):
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode() if body is not None else None,
                                     method=method, headers={"X-Omarchy-Token": token} if token else {})
        try:
            with urllib.request.urlopen(req, timeout=5) as r:
                raw = r.read()
                return r.status, json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            with e:
                raw = e.read()
                return e.code, json.loads(raw) if raw else None


class WebOSBridgeTest(IsolatedBridgeTest):
    def test_status_and_themes_endpoints(self):
        srv = HTTPServer(("127.0.0.1", 0), webos.WebOSHandler)
        port = srv.server_port
        t = threading.Thread(target=srv.serve_forever, daemon=True)
        t.start()
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/status", timeout=5) as r:
                self.assertEqual(r.status, 200)
                self.assertEqual(r.headers.get("Access-Control-Allow-Origin"), "*")
                st = json.loads(r.read().decode("utf-8"))
                self.assertIn("cpu", st)
                self.assertIn("ram", st)
                self.assertIn("host", st)
                self.assertEqual(st.get("version"), webos.VERSION)   # the bridge reports the package it ships in
                self.assertIsInstance(st.get("ai"), list)

            with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/themes", timeout=5) as r:
                self.assertEqual(r.status, 200)
                th = json.loads(r.read().decode("utf-8"))
                self.assertIn("themes", th)
        finally:
            srv.shutdown()
            srv.server_close()


    def test_media_opt_out_stops_at_the_request_boundary(self):
        handler = webos.WebOSHandler.__new__(webos.WebOSHandler)
        handler._authorized = mock.Mock(return_value=True)
        handler._send_json = mock.Mock()
        handler.command = "GET"
        handler.path = "/v1/media/list"
        media = mock.Mock()
        self.configure(mediaFromTv=False)
        modules = {"media": media, "config": link_config}
        with mock.patch.object(webos, "_link_mod", side_effect=modules.get):
            handler.do_GET()
            for route in ("/v1/media/f/fixture/example", "/v1/media/s/fixture/example"):
                handler.path = route
                handler.do_GET()
            handler.command = "HEAD"
            handler.path = "/v1/media/f/fixture/example"
            handler.do_HEAD()
        self.assertEqual([call.args[1] for call in handler._send_json.call_args_list], [403] * 4)
        self.assertEqual(media.mock_calls, [])

    def test_media_listing_keeps_enabled_defaults_and_token_requirement(self):
        handler = webos.WebOSHandler.__new__(webos.WebOSHandler)
        handler.command = "GET"
        handler.path = "/v1/media/list"
        handler._authorized = mock.Mock(return_value=True)
        handler._send_json = mock.Mock()
        media = mock.Mock()
        media.listing.return_value = {"files": []}
        with mock.patch.object(webos, "bridge_token", return_value="fixture"), \
             mock.patch.object(webos, "_link_mod", side_effect={"media": media}.get):
            handler.do_GET()
            handler._send_json.assert_called_once_with({"files": []})
            handler._send_json.reset_mock()
            handler._authorized.return_value = False
            handler.do_GET()
        self.assertEqual(handler._send_json.call_args.args[1], 401)
        media.listing.assert_called_once_with("")


class FakeGrokbot:
    UPSTREAM, UPSTREAM_REF = "https://github.com/tcballard/omarchy-plugin-grokbot-desktop", "v0.2.0"
    PALETTE = {"orange": "#FF6700"}

    class GrokbotError(Exception):
        def __init__(self, code, msg):
            super().__init__(msg)
            self.code = code

    asked = []

    def plain(self, v, n):
        return str(v or "")[:n]

    def color_name(self, v):
        return "orange"

    def avatar_shape(self, v):
        return "teardrop"

    def current(self, cfg):
        return {"id": "b1", "name": "Bob", "shortName": "Bob", "avatarShape": "teardrop", "fill": "#FF6700",
                "dispatch": "webhook", "version": "0.2.0"}

    def roster(self, cfg):
        return [{"id": "b1", "name": "Bob", "avatarShape": "drop", "avatarColor": "orange"}]

    def ask(self, cfg, text, bot=None):
        if not text:
            raise self.GrokbotError("empty", "empty question")
        self.asked.append((text, bot))
        return {"bot": "Bob", "text": "hi " + text, "mode": "relay", "links": []}


class GrokbotBridgeTest(IsolatedBridgeTest):
    def setUp(self):
        IsolatedBridgeTest.setUp(self)
        self.fake = FakeGrokbot()
        self.fake.asked = []
        self.patch(mock.patch.object(webos, "_grokbot", return_value=self.fake))
        self.patch(mock.patch.object(webos, "_last_ask", 0.0))
        self.srv = HTTPServer(("127.0.0.1", 0), webos.WebOSHandler)
        threading.Thread(target=self.srv.serve_forever, daemon=True).start()
        self.base = f"http://127.0.0.1:{self.srv.server_port}"

    def tearDown(self):
        self.srv.shutdown()
        self.srv.server_close()

    def post(self, path, body, token=None):
        return self.request(path, "POST", body, token)

    def test_state_is_public_and_has_no_secrets(self):
        with urllib.request.urlopen(self.base + "/v1/grokbot", timeout=5) as r:
            d = json.loads(r.read())
        self.assertTrue(d["available"])
        self.assertEqual(d["bot"]["name"], "Bob")
        self.assertEqual(d["roster"][0]["avatarShape"], "teardrop")
        self.assertNotIn("token", json.dumps(d).lower())

    def test_ask_needs_the_code(self):
        self.assertEqual(self.post("/v1/grokbot/ask", {"text": "x"})[0], 401)
        self.assertEqual(self.post("/v1/grokbot/ask", {"text": "x"}, "WRONG123")[0], 401)
        self.assertEqual(self.fake.asked, [])
        tok = webos.bridge_token()
        self.assertEqual(oct(os.stat(webos.TOKEN_FILE).st_mode & 0o777), "0o600")
        st, d = self.post("/v1/grokbot/ask", {"text": "ciao", "bot": "Bob"}, tok.lower())
        self.assertEqual((st, d["text"]), (200, "hi ciao"))
        self.assertEqual(self.fake.asked, [("ciao", "Bob")])

    def test_rate_limit_and_errors(self):
        tok = webos.bridge_token()
        self.assertEqual(self.post("/v1/grokbot/ask", {"text": ""}, tok)[0], 400)
        self.assertEqual(self.post("/v1/grokbot/ask", {"text": "again"}, tok)[0], 429)
        webos._last_ask = 0.0
        self.assertEqual(self.post("/v1/nope", {}, tok)[0], 404)



class FakeTheme:
    def __init__(self):
        self.asked = []

    def set_theme(self, name):
        self.asked.append(name)
        return {"gruvbox": "ok", "nord": "same", "busy": "busy"}.get(name, "unknown")


class ThemeSetBridgeTest(IsolatedBridgeTest):
    post = GrokbotBridgeTest.post   # reuse the server and helper, not the grokbot tests

    def setUp(self):
        GrokbotBridgeTest.setUp(self)
        self.th = FakeTheme()
        self.patch(mock.patch.object(webos, "_link_theme", return_value=self.th))

    def tearDown(self):
        GrokbotBridgeTest.tearDown(self)

    def test_needs_the_code(self):
        self.assertEqual(self.post("/v1/theme/set", {"name": "gruvbox"})[0], 401)
        self.assertEqual(self.th.asked, [])

    def test_results_map_to_status_codes(self):
        tok = webos.bridge_token()
        self.assertEqual(self.post("/v1/theme/set", {"name": "gruvbox"}, tok), (200, {"result": "ok"}))
        self.assertEqual(self.post("/v1/theme/set", {"name": "nord"}, tok), (200, {"result": "same"}))
        self.assertEqual(self.post("/v1/theme/set", {"name": "x"}, tok), (404, {"result": "unknown"}))
        self.assertEqual(self.post("/v1/theme/set", {"name": "busy"}, tok)[0], 429)

    def test_disabled_on_the_pc_and_not_installed(self):
        tok = webos.bridge_token()
        self.configure(themeFromTv=False)
        self.assertEqual(self.post("/v1/theme/set", {"name": "gruvbox"}, tok)[0], 403)
        self.assertEqual(self.th.asked, [])
        webos._link_theme.return_value = None
        self.assertEqual(self.post("/v1/theme/set", {"name": "gruvbox"}, tok)[0], 503)


class UpdatePowerBridgeTest(IsolatedBridgeTest):
    post = GrokbotBridgeTest.post

    def setUp(self):
        GrokbotBridgeTest.setUp(self)
        self.power_calls = []
        outer = self

        class P:
            @staticmethod
            def tv_power(state, cfg, log):
                outer.power_calls.append(state)
                return "scheduled"
        self.patch(mock.patch.object(webos, "_link_mod", side_effect={"power": P, "config": link_config}.get))

    def tearDown(self):
        GrokbotBridgeTest.tearDown(self)

    def test_power_needs_the_code_and_reaches_omarchy_link(self):
        self.assertEqual(self.post("/v1/power", {"state": "standby"})[0], 401)
        tok = webos.bridge_token()
        self.assertEqual(self.post("/v1/power", {"state": "standby"}, tok), (200, {"result": "scheduled"}))
        self.assertEqual(self.power_calls, ["standby"])
        webos._link_mod.side_effect = {"config": link_config}.get
        self.assertEqual(self.post("/v1/power", {"state": "on"}, tok)[0], 503)

    def test_update_check_install_and_off(self):
        tok = webos.bridge_token()
        self.assertEqual(self.post("/v1/update/tv", {"action": "check", "platform": "webos"})[0], 401)
        with mock.patch.object(tvupdate, "check", return_value={"current": "2.13.11", "latest": "2.13.12", "name": "x"}):
            st, d = self.post("/v1/update/tv", {"action": "check", "platform": "webos", "version": "2.13.11"}, tok)
        self.assertEqual((st, d["latest"]), (200, "2.13.12"))
        with mock.patch.object(tvupdate, "start", return_value="started") as stt:
            st, d = self.post("/v1/update/tv", {"action": "install", "platform": "tizen", "version": "2.13.11",
                                                "want": "2.13.12"}, tok)
        self.assertEqual((st, d), (200, {"result": "started"}))
        self.assertEqual(stt.call_args[0][:4], ("tizen", "2.13.11", "2.13.12", "127.0.0.1"))
        with mock.patch.object(tvupdate, "check", side_effect=tvupdate.UpdateError("bad_platform")):
            self.assertEqual(self.post("/v1/update/tv", {"platform": "x"}, tok)[0], 400)
        self.configure(updateFromTv=False)
        self.assertEqual(self.post("/v1/update/tv", {"action": "install"}, tok)[0], 403)

    def test_living_needs_the_code_and_returns_the_feed(self):
        class FakeLiving:
            def __init__(self, *a):
                self.prefs = None
                self.touched = False

            def set_prefs(self, p):
                self.prefs = p

            def touch(self):
                self.touched = True

            def payload(self):
                return {"github": [], "notes": [{"app": "Slack", "title": "Ola", "body": "ciao"}], "x": [], "xMode": "off"}

        class Mod:
            Living = FakeLiving
        self.patch(mock.patch.object(webos, "_living_obj", None))
        webos._link_mod.side_effect = {"living": Mod, "config": link_config}.get
        self.assertEqual(self.post("/v1/living", {"notes": True})[0], 401)
        tok = webos.bridge_token()
        st, d = self.post("/v1/living", {"notes": True, "x": "notify"}, tok)
        self.assertEqual((st, d["notes"][0]["title"]), (200, "Ola"))
        self.assertEqual(webos._living_obj.prefs, {"notes": True, "x": "notify"})
        self.assertTrue(webos._living_obj.touched)
        self.configure(livingToTv=False)
        self.assertEqual(self.post("/v1/living", {}, tok)[0], 403)

    def test_pulse_state_for_the_tv(self):   # 2.15 Pulse on the TV
        class PS:
            st = {"v": "1.6.0", "agents": [{"name": "codex"}]}

            @staticmethod
            def read():
                return PS.st
        webos._link_mod.side_effect = {"pulse_state": PS, "config": link_config}.get
        self.assertEqual(self.post("/v1/pulse", {})[0], 401)
        tok = webos.bridge_token()
        st, d = self.post("/v1/pulse", {}, tok)
        self.assertEqual((st, d["agents"][0]["name"]), (200, "codex"))
        PS.st = None
        self.assertEqual(self.post("/v1/pulse", {}, tok)[0], 404)   # Pulse not running on the PC

    def test_status_has_liftoff(self):
        with urllib.request.urlopen(self.base + "/v1/status", timeout=5) as r:
            self.assertIn("liftoff", json.loads(r.read()))


class SettingsBridgeTest(IsolatedBridgeTest):
    setUp = GrokbotBridgeTest.setUp
    tearDown = GrokbotBridgeTest.tearDown
    post = GrokbotBridgeTest.post

    def test_default_and_custom_roots_with_current_older_and_no_link(self):
        token = webos.bridge_token()
        check_body = {"action": "check", "platform": "webos", "version": "2.13.11"}
        install_body = {**check_body, "action": "install", "want": "2.13.12", "host": "192.0.2.9"}
        default = self.home / ".config/omarchy-link/config.json"
        default.parent.mkdir(parents=True)
        for root, path in (("", default), (str(self.xdg), self.settings)):
            with mock.patch.dict(os.environ, XDG_CONFIG_HOME=root):
                canonical = SimpleNamespace(**runpy.run_path(link_config.__file__))
                self.assertEqual(canonical.CONFIG_FILE, path)
                older = SimpleNamespace(CONFIG_FILE=canonical.CONFIG_FILE,
                                        load_config=mock.Mock(return_value={}))
                if root:
                    default.write_text('{"updateFromTv": false}', encoding="utf-8")
                for mode, mod in (("paired", canonical), ("older Link", older), ("standalone", None)):
                    with self.subTest(root=root or "default", mode=mode), \
                         mock.patch.object(webos, "_link_mod", side_effect={"config": mod}.get), \
                         mock.patch.object(tvupdate, "check", return_value={"latest": "2.13.12"}) as check, \
                         mock.patch.object(tvupdate, "start", return_value="started") as start:
                        path.unlink(missing_ok=True)
                        self.assertEqual(self.post("/v1/update/tv", check_body, token), (200, {"latest": "2.13.12"}))
                        check.assert_called_once_with("webos", "2.13.11")
                        path.write_text('{"updateFromTv": false}', encoding="utf-8")
                        self.assertEqual(self.post("/v1/update/tv", check_body, token)[0], 403)
                        path.write_text('[]', encoding="utf-8")
                        status, reply = self.post("/v1/update/tv", install_body, token)
                        self.assertEqual((status, reply["error"]), (503, "config_unavailable"))
                        start.assert_not_called()
                        settings = {"webosDevice": "fixture-tv", "tizenProfile": "fixture-profile"}
                        path.write_text(json.dumps(settings), encoding="utf-8")
                        for platform in ("webos", "tizen"):
                            self.assertEqual(self.post("/v1/update/tv", {**install_body, "platform": platform}, token),
                                             (200, {"result": "started"}))
                            self.assertEqual(start.call_args.args[:5],
                                             (platform, "2.13.11", "2.13.12", "127.0.0.1", settings))
                older.load_config.assert_not_called()

    def test_invalid_selected_config_rejects_all_settings_consumers_before_effects(self):
        token = webos.bridge_token()
        actions = {
            "/v1/grokbot/ask": {"text": "hello"}, "/v1/grokbot/sync": {},
            "/v1/ai/providers": {}, "/v1/ai/ask": {"text": "hello"},
            "/v1/office/hire": {"name": "Scout"}, "/v1/theme/set": {"name": "nord"},
            "/v1/update/tv": {"action": "check"}, "/v1/power": {"state": "standby"},
            "/v1/living": {"notes": True},
        }
        media = mock.Mock()
        modules = {"config": link_config, "media": media}
        routes = ("/v1/grokbot", "/v1/office", "/v1/media/list", "/v1/media/f/signed/film",
                  "/v1/media/s/signed/subtitle")
        with mock.patch.object(webos, "_link_mod", side_effect=modules.get) as find_module, \
             mock.patch.object(webos, "_grokbot") as grokbot, mock.patch.object(webos, "_assistant") as asst, \
             mock.patch.object(webos, "_link_theme") as theme, \
             mock.patch.object(webos, "_living_collector") as collector, \
             mock.patch.object(office, "payload") as office_payload, \
             mock.patch.object(tvupdate, "check") as check, mock.patch.object(tvupdate, "start") as start:
            for raw in (b"{", b"null", b'{"livingToTv": NaN}', b"\xff", None):
                with self.subTest(raw=raw):
                    if raw is None:
                        self.settings.unlink()
                        self.settings.mkdir()  # an existing entry that cannot hold JSON
                    else:
                        self.settings.write_bytes(raw)
                    for path, body in actions.items():
                        self.assertEqual(self.post(path, body)[0], 401, path)
                        status, reply = self.post(path, body, token)
                        self.assertEqual((status, reply["error"]), (503, "config_unavailable"), path)
                    self.assertEqual(self.post("/v1/update/tv", {"action": "install"}, token)[0], 503)
                    self.assertEqual(self.request("/v1/media/list")[0], 401)
                    for path in routes:
                        status, reply = self.request(path, token=token)
                        self.assertEqual((status, reply["error"]), (503, "config_unavailable"), path)
                    self.assertEqual(self.request("/v1/media/f/signed/film", method="HEAD"), (503, None))
            for backend in (grokbot, asst, theme, collector, office_payload, check, start):
                backend.assert_not_called()
            self.assertEqual(media.mock_calls, [])
            self.assertTrue(all(call.args[0] in ("config", "media") for call in find_module.call_args_list))
            link_config.AI_FILE.write_text('{"claude": "12.34"}', encoding="utf-8")
            with mock.patch.object(link_config, "read_config", wraps=link_config.read_config) as read_settings:
                status, payload = self.request("/v1/status")
            read_settings.assert_not_called()
            self.assertEqual((status, payload["version"], payload["claude"]), (200, webos.VERSION, "12.34"))

    def test_permission_failure_blocks_standby_but_authenticated_on_cancels(self):
        token = webos.bridge_token()
        self.configure(powerFromTv=True, powerGraceS=42)
        read_text = Path.read_text

        def deny_settings(path, *args, **kwargs):
            if path == self.settings:
                raise PermissionError("synthetic read denial")
            return read_text(path, *args, **kwargs)

        with mock.patch.object(webos.ui, "say"), mock.patch.object(power, "_timer", None), \
             mock.patch.object(power.threading, "Timer") as timer, mock.patch.object(power, "_suspend") as suspend:
            path = "/v1/power"
            self.assertEqual(self.post(path, {"state": "standby"}, token), (200, {"result": "scheduled"}))
            self.assertEqual(timer.call_args.args[0], 42)
            with mock.patch.object(Path, "read_text", autospec=True, side_effect=deny_settings):
                self.assertEqual(self.post(path, {"state": "standby"}, token)[0], 503)
                self.assertEqual(self.post(path, {"state": "on"})[0], 401)
                self.assertTrue(power.pending())
                self.assertEqual(self.post(path, {"state": "on"}, token), (200, {"result": "cancelled"}))
                self.assertFalse(power.pending())
                timer.return_value.cancel.assert_called_once()
                timer.assert_called_once()
            self.configure(powerFromTv=False)
            self.assertEqual(self.post(path, {"state": "standby"}, token), (200, {"result": "disabled"}))
            self.configure(powerFromTv=True)
            self.assertEqual(self.post(path, {"state": "standby"}, token)[0], 200)
            self.settings.write_text('[]', encoding="utf-8")
            timer.call_args.args[1]()  # the pending timer also rechecks the selected settings
            suspend.assert_not_called()
            self.assertFalse(power.pending())

    def test_active_living_stops_accepting_notes_and_recovers(self):
        token = webos.bridge_token()
        self.patch(mock.patch.object(webos, "_living_obj", None))
        path = "/v1/living"
        prefs = {"notes": True, "github": False}
        note = {"app": "Synthetic", "title": "Fixture", "body": "test"}

        def wait_for(event):
            self.assertTrue(event.wait(5))
            event.clear()

        started, stopped = threading.Event(), threading.Event()

        def start(monitor):
            monitor.proc = monitor.proc or object()
            started.set()

        def stop(monitor):
            if monitor.proc is not None:
                monitor.proc = None
                stopped.set()

        with mock.patch.object(living.NoteMonitor, "start", autospec=True, side_effect=start), \
             mock.patch.object(living.NoteMonitor, "stop", autospec=True, side_effect=stop):
            self.assertEqual(self.post(path, prefs, token)[0], 200)
            collector = webos._living_obj
            try:
                wait_for(started)
                proc = collector.monitor.proc
                collector._on_note(proc, note)
                self.assertEqual(len(collector.notes), 1)
                self.settings.write_text('[]', encoding="utf-8")
                collector._on_note(proc, note)
                self.assertLessEqual(len(collector.notes), 1)
                self.assertEqual(self.post(path, prefs, token)[0], 503)
                wait_for(stopped)
                self.assertEqual(list(collector.notes), [])
                started.clear()
                self.configure(livingToTv=True)
                self.assertEqual(self.post(path, prefs, token)[0], 200)
                wait_for(started)
                collector._on_note(collector.monitor.proc, note)
                self.assertEqual(self.post(path, prefs, token)[1]["notes"][0]["title"], "Fixture")
            finally:
                collector.stop.set()
                worker = collector.notes_thread
                collector.set_active(False)
                if worker:
                    worker.join(timeout=5)
                    self.assertFalse(worker.is_alive())

class FakeAssistantMod:
    class NoProvider(Exception): pass
    class RateLimited(Exception): pass


class FakeAssistant:
    def __init__(self):
        self.asked = []

    def listing(self):
        return {"providers": [{"id": i, "name": n} for i, n in (("claude-code", "Claude"), ("codex", "ChatGPT (Codex)"),
                ("grok-cli", "Grok"), ("hermes-cli", "Hermes"))], "default": "claude-code"}

    def ask(self, text, provider=None, conversation=None, lang=None):
        if not text:
            raise ValueError("empty text")
        if provider == "nope":
            raise FakeAssistantMod.NoProvider("provider 'nope' is not available on the PC")
        self.asked.append((text, provider, lang))
        return {"text": provider + " says hi", "provider": provider, "model": None, "conversationId": conversation or "abcd1234"}


class AiAgentsBridgeTest(IsolatedBridgeTest):
    post = GrokbotBridgeTest.post

    def setUp(self):
        GrokbotBridgeTest.setUp(self)
        self.old_a = webos._assistant
        self.asst = FakeAssistant()
        webos._assistant = lambda: (FakeAssistantMod, self.asst)

    def tearDown(self):
        webos._assistant = self.old_a
        GrokbotBridgeTest.tearDown(self)

    def test_agents_list_and_ask(self):
        self.assertEqual(self.post("/v1/ai/providers", {})[0], 401)
        tok = webos.bridge_token()
        st, d = self.post("/v1/ai/providers", {}, tok)
        self.assertEqual(st, 200)
        self.assertEqual([p["id"] for p in d["providers"]], ["claude-code", "codex", "grok-cli", "hermes-cli"])
        st, d = self.post("/v1/ai/ask", {"text": "ciao", "provider": "hermes-cli", "lang": "it-IT"}, tok)
        self.assertEqual((st, d["text"], d["session"]), (200, "hermes-cli says hi", "abcd1234"))
        self.assertEqual(self.asst.asked, [("ciao", "hermes-cli", "it-IT")])
        self.assertEqual(self.post("/v1/ai/ask", {"text": "x", "provider": "nope"}, tok)[0], 404)
        self.assertEqual(self.post("/v1/ai/ask", {"text": ""}, tok)[0], 400)
        self.configure(assistant=False)
        self.assertEqual(self.post("/v1/ai/providers", {}, tok)[0], 403)
        self.assertEqual(self.post("/v1/ai/ask", {"text": "ciao", "provider": "hermes-cli"}, tok)[0], 403)
        self.assertEqual(self.asst.asked, [("ciao", "hermes-cli", "it-IT")])


class OfficeAssistantBridgeTest(IsolatedBridgeTest):
    post = GrokbotBridgeTest.post

    def test_last_slot_hire_refreshes_the_registry_and_the_next_hire_is_refused(self):
        with tempfile.TemporaryDirectory() as td:
            root = webos.Path(td)
            settings = root / "assistant.json"
            ids = [f"existing-{i}" for i in range(15)]
            settings.write_text(json.dumps({"autoDetect": False, "minIntervalSeconds": 0,
                                            "providers": [{"id": pid, "type": "openai_api"} for pid in ids]}))
            with mock.patch.object(assistant, "ASSISTANT_FILE", settings), \
                 mock.patch.object(link_office, "check_key") as check_key, \
                 mock.patch.object(openai_api, "available", return_value={"model": "test-model"}), \
                 mock.patch.object(openai_api, "ask", side_effect=["first reply", "new bot reply"]) as ask, \
                 mock.patch.object(webos, "_assistant_obj", None), \
                 mock.patch.object(webos, "_last_ask", 0.0), \
                 mock.patch.object(webos, "ASK_GAP_S", 0), \
                 mock.patch.object(webos, "TOKEN_FILE", root / "token"), \
                 mock.patch.object(webos, "build_office_payload", side_effect=lambda _cfg: {"bots": link_office.bots()}), \
                 HTTPServer(("127.0.0.1", 0), webos.WebOSHandler) as srv:
                self.base = f"http://127.0.0.1:{srv.server_port}"
                threading.Thread(target=srv.serve_forever, daemon=True).start()
                try:
                    token = webos.bridge_token()
                    status, reply = self.post("/v1/ai/ask", {"text": "first", "provider": ids[0]}, token)
                    self.assertEqual((status, reply["text"]), (200, "first reply"))
                    asst = webos._assistant()[1]
                    self.assertEqual([p["id"] for p in asst.providers()], ids)

                    status, hire = self.post("/v1/office/hire", {"name": "Scout", "provider": "chatgpt"}, token)
                    self.assertEqual(status, 200)
                    bot_id = hire["bot"]["assistant"]
                    status, followup = self.post("/v1/ai/ask", {"text": "second", "provider": bot_id,
                                                                  "session": reply["session"]}, token)
                    self.assertEqual((status, followup.get("provider"), followup.get("text")),
                                     (200, bot_id, "new bot reply"))
                    self.assertEqual(followup["session"], reply["session"])
                    self.assertIs(webos._assistant()[1], asst)
                    self.assertEqual(ask.call_args.args[1].history, [
                        {"role": "user", "content": "first"},
                        {"role": "assistant", "content": "first reply"},
                    ])
                    self.assertEqual([p["id"] for p in asst.providers()], [*ids, bot_id])
                    saved = settings.read_bytes()
                    status, refused = self.post("/v1/office/hire", {"name": "Over Limit", "provider": "chatgpt"}, token)
                    self.assertEqual((status, refused.get("ok"), refused.get("error")), (400, False, "full"))
                    self.assertNotIn("bot", refused)
                    self.assertEqual(settings.read_bytes(), saved)
                    check_key.assert_called_once()
                finally:
                    srv.shutdown()


class LiveThemePathTest(unittest.TestCase):
    def test_theme_name_and_wallpaper_prefer_the_state_dir(self):
        with tempfile.TemporaryDirectory() as td:
            home = webos.Path(td)
            state = home / ".local/state/omarchy/current"
            legacy = home / ".config/omarchy/current"
            state.mkdir(parents=True)
            legacy.mkdir(parents=True)
            (state / "theme.name").write_text("nord\n")
            (legacy / "theme.name").write_text("tokyo-night\n")
            live = state / "wall.png"
            live.write_bytes(b"png")
            (state / "background").symlink_to(live.name)
            old = legacy / "old.png"
            old.write_bytes(b"old")
            (legacy / "background").symlink_to(old.name)
            with mock.patch.object(webos.Path, "home", return_value=home):
                self.assertEqual(webos.current_theme_name(), "nord")
                self.assertEqual(webos.current_wallpaper_path(), live.resolve())

    def test_theme_name_falls_back_to_the_config_dir(self):
        with tempfile.TemporaryDirectory() as td:
            home = webos.Path(td)
            legacy = home / ".config/omarchy/current"
            legacy.mkdir(parents=True)
            (legacy / "theme.name").write_text("gruvbox\n")
            with mock.patch.object(webos.Path, "home", return_value=home):
                self.assertEqual(webos.current_theme_name(), "gruvbox")


if __name__ == "__main__":
    unittest.main()
