"""GrokBot Office on the PC (2.20): hiring Claude / ChatGPT bots, the per-provider spend, the TV payload.
No real API call: Context.http_json is mocked everywhere."""
import json
import os
import stat
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from unittest import mock

import helpers  # noqa: F401  (isolated HOME, no real keys)
from omarchy_link import ai, assistant, config, office

for k in ("ANTHROPIC_ADMIN_KEY", "OPENAI_ADMIN_KEY"):
    os.environ.pop(k, None)


class OfficeHire(unittest.TestCase):
    def setUp(self):
        self.path = helpers.TMP / "office-assistant.json"
        self.ai_file = helpers.TMP / "office-ai.json"
        for p in (self.path, self.ai_file):
            if p.exists():
                p.unlink()
        self.env = mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": "sk-ant-test-123", "OPENAI_API_KEY": "sk-test-456"})
        self.env.start()
        for k in ("ANTHROPIC_ADMIN_KEY", "OPENAI_ADMIN_KEY"):   # other tests may set them; patch.dict restores them
            os.environ.pop(k, None)
        self.calls = []

        def fake(ctx, method, url, headers, body=None):
            self.calls.append((method, url, headers))
            return {"data": []}
        self.http = mock.patch.object(ai.Context, "http_json", fake)
        self.http.start()

    def tearDown(self):
        self.http.stop()
        self.env.stop()

    def test_hire_checks_the_key_then_saves_a_private_entry_without_the_key(self):
        bot = office.hire("Trimmer", "claude", "Cuts token use", "Keep my prompts short.", path=self.path)
        self.assertEqual(bot["name"], "Trimmer")
        self.assertEqual(bot["provider"], "claude")
        self.assertEqual(bot["assistant"], "office-trimmer")
        self.assertEqual(self.calls[0][0], "GET")
        self.assertEqual(self.calls[0][1], "https://api.anthropic.com/v1/models")
        self.assertEqual(self.calls[0][2]["x-api-key"], "sk-ant-test-123")
        saved = json.loads(self.path.read_text())
        e = saved["providers"][0]
        self.assertEqual((e["type"], e["key"]), ("anthropic_api", "env:ANTHROPIC_API_KEY"))   # a reference, not the key
        self.assertIn("Trimmer", e["system"])
        self.assertIn("Cuts token use", e["system"])
        self.assertNotIn("sk-ant-test-123", self.path.read_text())
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertNotIn("key", json.dumps(office.bots(self.path)))

    def test_hire_follows_your_own_entry_of_the_same_type(self):
        config.write_json_private(self.path, {"providers": [{"type": "anthropic_api", "id": "mine", "key": "env:MY_CLAUDE_KEY",
                                                             "baseUrl": "https://proxy.example/anthropic", "model": "claude-x"}]})
        with mock.patch.dict(os.environ, {"MY_CLAUDE_KEY": "sk-ant-mine"}):
            office.hire("Trimmer", "claude", path=self.path)
        self.assertEqual(self.calls[0][1], "https://proxy.example/anthropic/v1/models")
        e = json.loads(self.path.read_text())["providers"][1]
        self.assertEqual((e["key"], e["model"]), ("env:MY_CLAUDE_KEY", "claude-x"))

    def test_chatgpt_uses_the_openai_models_endpoint(self):
        office.hire("Scout", "ChatGPT", path=self.path)
        self.assertEqual(self.calls[0][1], "https://api.openai.com/v1/models")
        self.assertEqual(self.calls[0][2]["Authorization"], "Bearer sk-test-456")

    def test_no_key_or_a_rejected_key_saves_nothing(self):
        with mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": ""}):
            with self.assertRaises(office.HireError) as c:
                office.hire("Trimmer", "claude", path=self.path)
        self.assertEqual(c.exception.code, "no_key")
        self.assertNotIn("sk-", str(c.exception))
        self.assertFalse(self.path.exists())

        def reject(ctx, method, url, headers, body=None):
            raise ai.ProviderError("HTTP 401 invalid x-api-key")
        with mock.patch.object(ai.Context, "http_json", reject):
            with self.assertRaises(office.HireError) as c:
                office.hire("Trimmer", "claude", path=self.path)
        self.assertEqual(c.exception.code, "key_rejected")
        self.assertFalse(self.path.exists())

    def test_bad_requests(self):
        for name, prov, code in (("", "claude", "bad_request"), ("X", "grok", "bad_provider"), ("X", "gemini", "bad_provider")):
            with self.assertRaises(office.HireError) as c:
                office.hire(name, prov, path=self.path)
            self.assertEqual(c.exception.code, code)
        office.hire("Trimmer", "claude", path=self.path)
        with self.assertRaises(office.HireError) as c:
            office.hire("trimmer", "chatgpt", path=self.path)
        self.assertEqual(c.exception.code, "exists")

    def test_sixteenth_hire_survives_the_cap_and_seventeenth_is_refused(self):
        entries = [{"type": "openai_api", "id": f"api-{i}"} for i in range(15)]
        placeholder = {"type": "claude_code", "id": "placeholder", "enabled": False}
        config.write_json_private(self.path, {"providers": [*entries, placeholder]})
        initial = assistant.configured(assistant.load_settings(self.path))
        self.assertEqual([p["id"] for p in initial if p.get("auto")], ["claude-code"])

        bot = office.hire("Last Slot", " GPT ", path=self.path)
        listing = assistant.Assistant(self.path).listing()
        self.assertEqual([p["id"] for p in listing["providers"]], [p["id"] for p in entries] + [bot["assistant"]])
        self.assertEqual(bot["assistant"], "office-last-slot")
        self.assertEqual(assistant.load_settings(self.path)["providers"][:-1], [*entries, placeholder])

        saved = self.path.read_bytes()
        with mock.patch.object(config, "write_json_private") as write:
            with self.assertRaises(office.HireError) as c:
                office.hire("Over Limit", "claude", path=self.path)
            write.assert_not_called()
        self.assertEqual(c.exception.code, "full")
        self.assertIn("Assistant registry", str(c.exception))
        self.assertEqual(self.path.read_bytes(), saved)
        self.assertEqual(len(self.calls), 1)

    def test_office_limit_still_counts_disabled_bots(self):
        entries = [{"type": "openai_api", "id": f"office-{i}", "office": {}, "enabled": False}
                   for i in range(11)]
        config.write_json_private(self.path, {"providers": entries})
        office.hire("Twelfth", "chatgpt", path=self.path)
        saved = self.path.read_bytes()
        with self.assertRaises(office.HireError) as c:
            office.hire("Thirteenth", "chatgpt", path=self.path)
        self.assertEqual(c.exception.code, "full")
        self.assertIn("12 hired bots", str(c.exception))
        self.assertEqual(self.path.read_bytes(), saved)
        self.assertEqual(len(self.calls), 1)

    def test_hire_rejects_id_collisions_with_existing_configs(self):
        for entry in ({"type": "openai_api", "id": "office-night-watch", "enabled": False},
                      {"type": "office_night_watch"}):
            with self.subTest(entry=entry):
                config.write_json_private(self.path, {"providers": [entry]})
                saved = self.path.read_bytes()
                with self.assertRaises(office.HireError) as c:
                    office.hire(" <Night>   Watch! ", "chatgpt", path=self.path)
                self.assertEqual(c.exception.code, "exists")
                self.assertEqual(self.path.read_bytes(), saved)
        self.assertEqual(self.calls, [])

    def test_concurrent_hires_keep_each_success_and_respect_capacity(self):
        for count, expected in ((14, ["hired", "hired"]), (15, ["full", "hired"])):
            with self.subTest(count=count):
                entries = [{"type": "openai_api", "id": f"api-{i}"} for i in range(count)]
                config.write_json_private(self.path, {"providers": entries})
                self.calls.clear()
                start = threading.Barrier(2, timeout=5)

                def hire(name):
                    start.wait()
                    try:
                        return "hired", office.hire(name, "chatgpt", path=self.path)["assistant"]
                    except office.HireError as e:
                        return e.code, None

                with ThreadPoolExecutor(max_workers=2) as pool:
                    outcomes = list(pool.map(hire, ("First", "Second")))
                self.assertEqual(sorted(status for status, _ in outcomes), expected)
                saved = assistant.load_settings(self.path)["providers"]
                self.assertEqual(saved[:count], entries)
                self.assertEqual(len(saved), 16)
                self.assertCountEqual([p["id"] for p in saved[count:]], [pid for status, pid in outcomes if status == "hired"])
                self.assertEqual(len(self.calls), 16 - count)

    def test_hired_bot_is_an_assistant_provider_with_its_role_as_system_prompt(self):
        office.hire("Trimmer", "claude", "Cuts token use", path=self.path)
        asst = assistant.Assistant(self.path)
        p = next(p for p in asst.providers(fresh=True) if p["id"] == "office-trimmer")
        self.assertEqual(p["name"], "Trimmer")
        seen = {}

        def fake_ask(cfg, req):
            seen["system"] = req.system
            return "Trimmed."
        with mock.patch.object(p["_mod"], "ask", fake_ask):
            res = asst.ask("make it shorter", provider="office-trimmer")
        self.assertEqual(res["text"], "Trimmed.")
        self.assertTrue(seen["system"].startswith("You are Trimmer"))
        self.assertIn(assistant.SYSTEM_PROMPT, seen["system"])
        self.assertTrue(office.fire("Trimmer", self.path))
        self.assertEqual(office.bots(self.path), [])

    def test_usage_only_with_an_admin_key_and_real_numbers(self):
        self.assertEqual(office.usage(self.ai_file), [])   # no admin key: no card
        config.write_json_private(self.ai_file, {"providers": [{"type": "anthropic", "key": "env:ANTHROPIC_ADMIN_KEY", "budget": 50}]})
        self.assertEqual(office.usage(self.ai_file), [])   # the entry is there, the key is not

        def cost(ctx, method, url, headers, body=None):
            if "anthropic" in url:
                return {"data": [{"results": [{"amount": "1234"}]}], "has_more": False}
            return {"data": [{"results": [{"amount": {"value": 3.5}}]}], "has_more": False}
        with mock.patch.dict(os.environ, {"ANTHROPIC_ADMIN_KEY": "sk-ant-admin-x", "OPENAI_ADMIN_KEY": "sk-admin-y"}), \
                mock.patch.object(ai.Context, "http_json", cost):
            u = {x["provider"]: x for x in office.usage(self.ai_file)}
        self.assertEqual(u["claude"]["spent"], 12.34)
        self.assertEqual(u["claude"]["budget"], 50)
        self.assertEqual(u["claude"]["pct"], 24.7)
        self.assertEqual(u["chatgpt"]["spent"], 3.5)
        self.assertNotIn("pct", u["chatgpt"])   # no budget: an amount, never a made-up percentage
        self.assertNotIn("sk-", json.dumps(u))

    def test_merge_tags_grok_bots_and_adds_the_hired_ones(self):
        office.hire("Trimmer", "claude", path=self.path)
        d = office.merge({"bots": [{"name": "Scout"}, {"name": "Pixel", "provider": "chatgpt"}]}, self.path, self.ai_file)
        self.assertEqual([(b["name"], b["provider"]) for b in d["bots"]], [("Scout", "grok"), ("Pixel", "chatgpt"), ("Trimmer", "claude")])
        self.assertNotIn("providers_usage", d)


class OfficeHireEvent(unittest.TestCase):
    """TV -> PC "office_hire" on the event socket: the reply says what really happened."""

    def run_event(self, data):
        import io
        from omarchy_link import session
        s = session.Session(out=io.StringIO())
        s.assistant = assistant.Assistant(helpers.TMP / "office-event.json")
        s.client = None
        sent = []

        class Stream:
            def send(self, kind, payload):
                sent.append((kind, payload))
        s.handle_office_hire(Stream(), data)
        return sent

    def setUp(self):
        p = helpers.TMP / "office-event.json"
        if p.exists():
            p.unlink()

    def test_success_and_failure(self):
        with mock.patch.dict(os.environ, {"OPENAI_API_KEY": "sk-test-456"}), \
                mock.patch.object(ai.Context, "http_json", lambda *a, **k: {"data": []}):
            ok = self.run_event({"id": "r1", "name": "Scout", "provider": "chatgpt", "role": "Finds things"})
        self.assertEqual(ok[0][0], "office_hire_result")
        self.assertEqual(ok[0][1]["id"], "r1")
        self.assertTrue(ok[0][1]["ok"])
        self.assertEqual(ok[0][1]["bot"]["provider"], "chatgpt")
        with mock.patch.dict(os.environ, {"ANTHROPIC_API_KEY": ""}):
            bad = self.run_event({"id": "r2", "name": "Trimmer", "provider": "claude"})
        self.assertEqual(bad[0][1], {"id": "r2", "ok": False, "error": "no_key", "message": bad[0][1]["message"]})


if __name__ == "__main__":
    unittest.main()
