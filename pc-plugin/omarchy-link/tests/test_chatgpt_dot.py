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

CFG = {"url": "https://chatgpt.com/dots/test-dot", "id": "test-dot", "name": "Jarvis", "transport": "browser"}


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
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis", "transport": "browser"}))
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

    def test_inbox_is_default_and_requires_reply_thread(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "relay.json"
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis"}))
            path.chmod(0o600)
            with self.assertRaises(dot.RelayError):
                dot.settings(path)
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis", "replyThreadId": "replies"}))
            cfg = dot.settings(path)
            self.assertEqual(cfg["transport"], "inbox")
            self.assertEqual(len(dot.request({"botId": cfg["id"], "prompt": "x" * 1000}, cfg)), 1000)
            with self.assertRaises(dot.RelayError):
                dot.request({"botId": cfg["id"], "prompt": "x" * 1001}, cfg)

    def test_inbox_correlation_post_preference_and_display_cap(self):
        for post in (True, False):
            for length in (900, 5000):
                with self.subTest(post=post, length=length), patch.object(dot, "Inbox") as cls, \
                        patch.object(dot.secrets, "token_hex", return_value="new"), patch.object(dot, "Browser") as browser:
                    client = cls.return_value
                    client.tools = {"t3_thread_post"} if post else {"t3_thread_send"}
                    client.deadline = dot.time.monotonic() + 30
                    direct = {"type": "user_message", "creationSource": "mcp", "textTruncated": False}
                    reply = "OMARCHYTV_REPLY new\n" + "x" * length + "\nOMARCHYTV_END new"
                    client.call.side_effect = [
                        {"thread": {"title": "OmarchyTV relay replies (approval-required)", "runtimeMode": "approval-required", "itemCount": 0}}, {},
                        {"items": [{**direct, "text": "Queued for Jarvis: new"},
                                   {**direct, "text": reply.replace("new", "old")},
                                   {**direct, "type": "assistant_message", "text": reply.replace("x", "y")},
                                   {**direct, "text": reply}], "hasMore": False},
                    ]
                    answer = dot.inbox_relay({"replyThreadId": "replies"}, "hello")
                    if length < 4000:
                        self.assertEqual(answer, "x" * length)
                    self.assertLessEqual(len(answer), 4000)
                    if length > 4000:
                        self.assertIn("full reply is in T3", answer)
                    sent = client.call.call_args_list[1]
                    self.assertEqual(sent.args[0], "t3_thread_post" if post else "t3_thread_send")
                    self.assertEqual(sent.kwargs["threadId"], dot.INBOX)
                    self.assertTrue(sent.kwargs["message"].startswith("Question from Chris via his OmarchyTV relay (request new)."))
                    self.assertIn("Answer in T3 thread replies with t3_thread_post.", sent.kwargs["message"])
                    self.assertNotIn("with t3_thread_send", sent.kwargs["message"])
                    self.assertIn("never t3_thread_send", sent.kwargs["message"])
                    self.assertIn("If t3_thread_post is not offered, wait", sent.kwargs["message"])
                    self.assertNotIn("not proof of speaker identity", sent.kwargs["message"])
                    self.assertIn("Commands follow your TV relay rule", sent.kwargs["message"])
                    self.assertIn("Other speakers", sent.kwargs["message"])
                    self.assertIn("explicit confirmation", sent.kwargs["message"])
                    if not post:
                        self.assertEqual(sent.kwargs["mode"], "queue")
                    browser.assert_not_called()

    def test_inbox_check_and_uncertain_submission_never_send_twice(self):
        with patch.object(dot, "Inbox") as cls:
            client = cls.return_value
            client.tools = {"t3_thread_post"}
            client.call.return_value = {"thread": {"title": "OmarchyTV relay replies (approval-required)", "runtimeMode": "approval-required", "itemCount": 0}}
            self.assertIn("no message sent", dot.inbox_relay({"replyThreadId": "replies"}))
            self.assertEqual(client.call.call_count, 1)
            client.call.return_value["thread"]["runtimeMode"] = "full-access"
            with self.assertRaises(dot.RelayError):
                dot.inbox_relay({"replyThreadId": "replies"}, "hello")
            self.assertEqual(client.call.call_count, 2)
            client.call.return_value["thread"]["runtimeMode"] = "approval-required"
            client.call.reset_mock()
            client.tools = {"t3_thread_post"}
            client.call.side_effect = [client.call.return_value, dot.RelayError("uncertain")]
            with self.assertRaises(dot.RelayError):
                dot.inbox_relay({"replyThreadId": "replies"}, "hello")
            self.assertEqual(client.call.call_count, 2)

    def test_keyring_reuse_validates_origin_and_expiry_without_leaking(self):
        from types import SimpleNamespace
        stored = {"server_name": "t3_omarchy", "url": dot.T3_URL,
                  "expires_at": (dot.time.time() + 60) * 1000,
                  "token_response": {"access_token": "secret-never-log"}}
        with patch.object(dot.subprocess, "run", return_value=SimpleNamespace(returncode=0, stdout=json.dumps(stored))) as run:
            self.assertEqual(dot.t3_token(), "secret-never-log")
            self.assertEqual(run.call_args.args[0][-1], "t3_omarchy|3cb85e40fad080dc")
        for field, value in (("url", "https://elsewhere/mcp"), ("expires_at", 0)):
            with patch.object(dot.subprocess, "run", return_value=SimpleNamespace(
                    returncode=0, stdout=json.dumps({**stored, field: value}))):
                with self.assertRaises(dot.RelayError) as error:
                    dot.t3_token()
                self.assertNotIn("secret-never-log", str(error.exception))

    def test_keyring_checks_alternate_entry_after_invalid_candidate(self):
        from types import SimpleNamespace
        good = {"server_name": "t3_omarchy", "url": dot.T3_URL,
                "expires_at": (dot.time.time() + 60) * 1000,
                "token_response": {"access_token": "alternate"}}
        for bad in ("malformed", json.dumps({**good, "expires_at": 0}),
                    json.dumps({**good, "url": "https://wrong/mcp"})):
            with self.subTest(bad=bad), patch.object(dot.subprocess, "run", side_effect=[
                    SimpleNamespace(returncode=0, stdout=bad),
                    SimpleNamespace(returncode=0, stdout=json.dumps(good))]) as run:
                self.assertEqual(dot.t3_token(), "alternate")
                self.assertEqual(run.call_count, 2)

    def test_session_delete_on_success_check_error_and_failed_initialization(self):
        from unittest.mock import MagicMock
        client = dot.Inbox.__new__(dot.Inbox)
        client.token, client.session, client.protocol = "private", "session", "2025-06-18"
        client.http = MagicMock()
        client.close()
        req = client.http.open.call_args.args[0]
        self.assertEqual(req.method, "DELETE")
        self.assertEqual(req.get_header("Mcp-session-id"), "session")
        self.assertIsNone(client.session)
        client.session = "session"
        client.http.open.side_effect = OSError("sensitive")
        client.close()
        self.assertIsNone(client.session)
        with patch.object(dot, "Inbox") as cls:
            cls.return_value.tools = {"t3_thread_post"}
            cls.return_value.call.return_value = {"thread": {"title": "OmarchyTV relay replies (approval-required)", "runtimeMode": "approval-required", "itemCount": 0}}
            dot.inbox_relay({"replyThreadId": "replies"})
            cls.return_value.close.assert_called_once()
            cls.return_value.close.reset_mock()
            cls.return_value.call.side_effect = dot.RelayError("failed")
            with self.assertRaises(dot.RelayError):
                dot.inbox_relay({"replyThreadId": "replies"})
            cls.return_value.close.assert_called_once()
        with patch.object(dot, "t3_token", return_value="private"), \
                patch.object(dot.Inbox, "initialize", side_effect=dot.RelayError("failed")), \
                patch.object(dot.Inbox, "close") as close:
            with self.assertRaises(dot.RelayError):
                dot.Inbox()
            close.assert_called_once()

    def test_capture_tail_before_send_and_reject_conflicting_paged_replies(self):
        direct = {"type": "user_message", "creationSource": "mcp", "textTruncated": False}
        def reply(text):
            return {**direct, "text": "OMARCHYTV_REPLY new\n" + text + "\nOMARCHYTV_END new"}
        with patch.object(dot, "Inbox") as cls, \
                patch.object(dot.secrets, "token_hex", return_value="new"):
            client = cls.return_value
            client.tools = {"t3_thread_post"}
            client.deadline = dot.time.monotonic() + 30
            client.call.side_effect = [
                {"thread": {"title": "OmarchyTV relay replies (approval-required)", "runtimeMode": "approval-required", "itemCount": 1000}, "hasMore": True, "nextPosition": 4}, {},
                {"items": [reply("first")], "hasMore": True, "nextPosition": 1000},
                {"items": [reply("corrected")], "hasMore": False, "nextPosition": 1001},
            ]
            with self.assertRaisesRegex(dot.RelayError, "Ambiguous"):
                dot.inbox_relay({"replyThreadId": "replies"}, "hello")
            calls = client.call.call_args_list
            self.assertEqual(calls[1].args[0], "t3_thread_post")
            self.assertEqual(calls[2].kwargs["afterPosition"], 999)
            self.assertEqual(calls[3].kwargs["afterPosition"], 1000)
            client.close.assert_called_once()

    def test_rpc_handles_json_sse_and_rejects_redirects_and_errors(self):
        from email.message import Message
        from unittest.mock import MagicMock
        for content_type in ("application/json", "text/event-stream"):
            client = dot.Inbox.__new__(dot.Inbox)
            client.token, client.serial, client.session = "private-token", 0, None
            client.deadline = dot.time.monotonic() + 10
            headers = Message()
            headers["Content-Type"] = content_type
            headers["Mcp-Session-Id"] = "existing-session"
            response = MagicMock()
            response.headers = headers
            result = json.dumps({"jsonrpc": "2.0", "id": 1, "result": {"ok": True}})
            response.read.return_value = (result if content_type == "application/json" else
                                          "event: message\ndata: " + result + "\n\n").encode()
            client.http = MagicMock()
            client.http.open.return_value.__enter__.return_value = response
            self.assertEqual(client.rpc("tools/list", {}), {"ok": True})
            self.assertEqual(client.session, "existing-session")
            client.http.open.side_effect = OSError("sensitive response private-token")
            with self.assertRaises(dot.RelayError) as error:
                client.rpc("tools/call", {})
            self.assertNotIn("private-token", str(error.exception))
        with self.assertRaises(dot.RelayError):
            dot.NoRedirect().redirect_request(None, None, None, None, None, None)

    def test_long_reply_follows_text_continuation_before_frame_and_tv_limit(self):
        full = "OMARCHYTV_REPLY new\n" + "x" * 60000 + "\nOMARCHYTV_END new"
        direct = {"type": "user_message", "creationSource": "mcp", "itemId": "answer",
                  "updatedAt": "unchanged"}
        with patch.object(dot, "Inbox") as cls, \
                patch.object(dot.secrets, "token_hex", return_value="new"):
            client = cls.return_value
            client.tools = {"t3_thread_send"}
            client.deadline = dot.time.monotonic() + 30
            client.call.side_effect = [
                {"thread": {"title": "OmarchyTV relay replies (approval-required)", "runtimeMode": "approval-required", "itemCount": 0}}, {},
                {"items": [{**direct, "text": full[:50000], "textTruncated": True,
                            "nextTextOffset": 50000}], "hasMore": False},
                {"items": [{**direct, "text": full[50000:], "textTruncated": False}]},
            ]
            answer = dot.inbox_relay({"replyThreadId": "replies"}, "hello")
            self.assertEqual(len(answer), 4000)
            self.assertTrue(answer.endswith("[TV limit; full reply is in T3.]"))
            continuation = client.call.call_args_list[-1]
            self.assertEqual(continuation.kwargs["itemId"], "answer")
            self.assertEqual(continuation.kwargs["textOffset"], 50000)
        with patch.object(dot, "Inbox") as cls:
            client = cls.return_value
            client.call.return_value = {"items": [{**direct, "text": "changed", "updatedAt": "edited"}]}
            with self.assertRaisesRegex(dot.RelayError, "changed"):
                dot.complete_reply(client, "replies", {**direct, "text": full[:50000],
                                   "textTruncated": True, "nextTextOffset": 50000})

    def test_inbox_pages_past_stale_messages_waits_and_times_out_without_resend(self):
        with patch.object(dot, "Inbox") as cls, patch.object(dot.time, "sleep"):
            client = cls.return_value
            client.tools = {"t3_thread_post"}
            client.deadline = dot.time.monotonic() + 30
            client.call.side_effect = [
                {"thread": {"title": "OmarchyTV relay replies (approval-required)", "runtimeMode": "approval-required", "itemCount": 0}}, {},
                {"items": [], "hasMore": True, "nextPosition": 99},
                {"items": [], "hasMore": False, "nextPosition": 100},
                dot.RelayError("timeout"),
            ]
            with self.assertRaises(dot.RelayError):
                dot.inbox_relay({"replyThreadId": "replies"}, "hello")
            calls = client.call.call_args_list
            self.assertEqual(calls[3].kwargs["afterPosition"], 99)
            self.assertEqual(calls[4].args[0], "t3_thread_wait")
            self.assertEqual(sum(c.args[0] == "t3_thread_post" for c in calls), 1)


if __name__ == "__main__":
    unittest.main()
