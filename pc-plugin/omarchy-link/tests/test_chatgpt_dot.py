"""Replay native helper orchestration without a browser, account, TV, or home change."""
import importlib.machinery
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[3] / "examples/chatgpt-dot/bin/grokbot-ask"
loader = importlib.machinery.SourceFileLoader("chatgpt_dot", str(SCRIPT))
spec = importlib.util.spec_from_loader(loader.name, loader)
dot = importlib.util.module_from_spec(spec)
loader.exec_module(dot)

CFG = {"url": "https://chatgpt.com/dots/test-dot", "id": "test-dot", "name": "Jarvis"}


def state(text="", **extra):
    return {"status": "ok", "page": {"url": CFG["url"], "title": "Jarvis"},
            "snapshot": {"complete": True}, "outline": text, "refs": [], **extra}


class DotRelayTests(unittest.TestCase):
    def test_private_exact_destination_and_wrong_bot(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "relay.json"
            for url in ("http://chatgpt.com/dots/a", "https://chatgpt.com.evil/dots/a",
                        "https://chatgpt.com/c/a", "https://chatgpt.com/dots/a?next=elsewhere"):
                path.write_text(json.dumps({"dotUrl": url, "name": "Jarvis"}))
                path.chmod(0o600)
                with self.assertRaises(dot.RelayError):
                    dot.settings(path)
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis"}))
            self.assertEqual(dot.settings(path), CFG)
            path.chmod(0o644)
            with self.assertRaises(dot.RelayError):
                dot.settings(path)
        with self.assertRaises(dot.RelayError):
            dot.request({"botId": "another-dot", "prompt": "hello"}, CFG)
        self.assertEqual(dot.request({"botId": "test-dot", "prompt": "hello"}, CFG), "hello")

    def test_response_correlation_never_returns_old_reply_or_outgoing_instruction(self):
        prefix, suffix = "TVREPLY_new: ", " :END_new"
        for text in ("Old reply", "TVREPLY_old: stale :END_old",
                     "Please reply starting with TVREPLY_new: and ending with :END_new", "TVREPLY_new: partial"):
            self.assertIsNone(dot.reply_text(state('- statictext ' + json.dumps(text)), prefix, suffix))
        answer = '- statictext "TVREPLY_new: A fresh answer :END_new"'
        self.assertEqual(dot.reply_text(state(answer), prefix, suffix), "A fresh answer")
        with self.assertRaises(dot.RelayError):
            dot.reply_text(state(answer + '\n- statictext "TVREPLY_new: Different answer :END_new"'), prefix, suffix)
        split = '- paragraph\n  - statictext "TVREPLY_new"\n  - statictext ": A fresh answer "\n  - statictext ":END_new"'
        self.assertEqual(dot.reply_text(state(split), prefix, suffix), "A fresh answer")
        self.assertIsNone(dot.reply_text(state(split.rsplit('\n', 1)[0]), prefix, suffix))
        normalized = '- paragraph\n  - statictext "TVREPLY_new"\n  - statictext ": A fresh answer"\n  - statictext ":END_new"'
        self.assertEqual(dot.reply_text(state(normalized), prefix, suffix), "A fresh answer")
        clipped = '- paragraph\n  - statictext "TVREPLY_new"\n  - statictext ' + json.dumps('x' * 1000) + '\n  - statictext ":END_new"'
        with self.assertRaises(dot.RelayError):
            dot.reply_text(state(clipped), prefix, suffix)

    def test_composer_observation_preserves_existing_drafts(self):
        empty = '- statictext "Send a message"\n- textbox "Message"\n  - paragraph\n    - linebreak'
        self.assertEqual(dot.composer_text(state(empty)), "")
        draft = empty + '\n  - statictext "Unsent draft"'
        self.assertEqual(dot.composer_text(state(draft)), "Unsent draft")

    def test_redirect_login_and_incomplete_observation_refuse(self):
        browser = dot.Browser(CFG)
        for page in ({"url": "https://chatgpt.com/auth/login", "title": "ChatGPT"},
                     {"url": "https://chatgpt.com/dots/another", "title": "Jarvis"},
                     {"url": CFG["url"], "title": "Someone else"}):
            with patch.object(browser, "cua", return_value=state(page=page)):
                with self.assertRaises(dot.RelayError):
                    browser.snapshot(query="Message")
        with patch.object(browser, "cua", return_value=state(snapshot={"complete": False})):
            with self.assertRaises(dot.RelayError):
                browser.snapshot(query="Message")

    def test_check_never_sends_and_failure_never_retries(self):
        for check in (True, False):
            with patch.object(dot, "Browser") as cls:
                browser = cls.return_value
                browser.open.return_value = "current-ref"
                browser.wait.return_value = "fresh answer"
                output = dot.relay(CFG, None if check else "What is the weather?")
                browser.close.assert_called_once()
                if check:
                    browser.send.assert_not_called()
                    self.assertIn("no message sent", output)
                else:
                    self.assertEqual(output, "fresh answer")
                    browser.send.assert_called_once()
                    self.assertIn("explicit confirmation", browser.send.call_args.args[1])
        for phase in ("open", "send", "wait"):
            with patch.object(dot, "Browser") as cls:
                browser = cls.return_value
                getattr(browser, phase).side_effect = dot.RelayError("uncertain")
                with self.assertRaises(dot.RelayError):
                    dot.relay(CFG, "hello")
                self.assertLessEqual(browser.send.call_count, 1)
                browser.close.assert_called_once()

    def test_initial_load_waits_for_exact_dot_before_using_controls(self):
        browser = dot.Browser(CFG)
        loading = state(page={"url": CFG["url"], "title": "ChatGPT"})
        ready = state()
        with patch.object(browser, "cua", side_effect=[loading, ready]) as cua, patch.object(dot.time, "sleep"):
            self.assertEqual(browser.snapshot(query="Message", wait_ready=True), ready)
            self.assertEqual([c.args[0] for c in cua.call_args_list], ["get_browser_state", "get_browser_state"])

    def test_open_selects_the_owned_marker_window_and_refuses_ambiguity(self):
        marker = "about:blank?agent-tab=owned-unique-marker"
        field = {"role": "textbox", "name": "Message", "ref": "input", "actions": ["type"]}
        own = {"pid": 12, "window_id": 34, "app_name": "Google-chrome", "title": marker}
        for other_title, succeeds in (("Another Chrome window", True), (marker, False)):
            with self.subTest(other_title=other_title):
                browser = dot.Browser(CFG)
                windows = [{**own, "pid": 56, "window_id": 78, "title": other_title}, own]
                bound = {"binding_quality": "exact", "mutation_allowed": True,
                         "target_id": "owned-browser", "tabs": [{"url": marker, "tab_id": "owned-tab"}]}
                with patch.object(browser, "renew"), patch.object(browser, "command", side_effect=[
                    {"status": "ACQUIRED", "token": "chrome"},
                    {"status": "ACQUIRED", "token": "computer-use"}, {"url": marker},
                ]), patch.object(browser, "cua", side_effect=[{"windows": windows}, {}, bound, {}]) as cua, \
                        patch.object(browser, "snapshot", side_effect=[
                            state(refs=[field]), state('- textbox "Message"', refs=[field]),
                        ]):
                    if succeeds:
                        self.assertEqual(browser.open(), "input")
                        self.assertEqual(cua.call_args_list[1].kwargs["window_id"], 34)
                        self.assertEqual((browser.target, browser.tab), ("owned-browser", "owned-tab"))
                    else:
                        with self.assertRaises(dot.RelayError):
                            browser.open()
                        self.assertEqual([c.args[0] for c in cua.call_args_list], ["list_windows"])

    def test_submit_uses_one_current_send_control(self):
        browser = dot.Browser(CFG)
        field = {"role": "textbox", "name": "Message", "ref": "new-field", "actions": ["type"]}
        send = {"role": "button", "name": "Send", "ref": "new-send", "actions": ["click"]}
        snapshots = [state(refs=[field]), state('- textbox "Message"\n  - statictext "hello"'), state(refs=[send])]
        with patch.object(browser, "snapshot", side_effect=snapshots), patch.object(browser, "cua") as cua:
            browser.send("old-field", "hello")
            self.assertEqual([c.args[0] for c in cua.call_args_list], ["browser_type", "browser_click"])
            self.assertEqual(cua.call_args_list[-1].kwargs["ref"], "new-send")

    def test_reply_collection_pages_to_the_correlated_paragraph(self):
        browser = dot.Browser(CFG)
        matched = state('- statictext "TVREPLY_new"')
        first = state(snapshot={"complete": False, "continuation": "next-page"})
        second = state('- paragraph\n  - statictext "TVREPLY_new"\n  - statictext ": Fresh reply :END_new"')
        with patch.object(browser, "snapshot", side_effect=[matched, first, second]) as observe:
            self.assertEqual(browser.wait("TVREPLY_new: ", " :END_new"), "Fresh reply")
            self.assertEqual(observe.call_args_list[-1].kwargs["continuation"], "next-page")

    def test_cleanup_releases_both_leases_even_when_tab_close_fails(self):
        browser = dot.Browser(CFG)
        browser.created = True
        browser.leases = {"chrome": "first", "computer-use": "second"}
        with patch.object(browser, "renew"), patch.object(browser, "cua") as cua:
            with patch.object(browser, "command", side_effect=[dot.RelayError("closed failed"), {}, {}]) as command:
                with self.assertRaises(dot.RelayError):
                    browser.close()
                cua.assert_called_once_with("end_session")
                releases = [c.args[0] for c in command.call_args_list[1:]]
                self.assertEqual([c[-3] for c in releases], ["computer-use", "chrome"])


if __name__ == "__main__":
    unittest.main()
