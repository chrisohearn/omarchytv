"""Replay the T3 inbox relay without a T3 server, account, TV, or home change."""
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

CFG = {"url": "https://chatgpt.com/dots/test-dot", "id": "test-dot", "name": "Jarvis", "replyThreadId": "replies"}


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
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis", "replyThreadId": "replies"}))
            self.assertEqual(dot.settings(path), CFG)
            path.chmod(0o644)
            with self.assertRaises(dot.RelayError):
                dot.settings(path)
        with self.assertRaises(dot.RelayError):
            dot.request({"botId": "another-dot", "prompt": "hello"}, CFG)
        self.assertEqual(dot.request({"botId": "test-dot", "prompt": "hello"}, CFG), "hello")

    def test_retired_browser_transport_fails_without_rerouting(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "relay.json"
            for transport, message in (("browser", "retired in upgrade 42"), ("email", "must be inbox")):
                path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis", "transport": transport,
                                            "replyThreadId": "replies"}))
                path.chmod(0o600)
                with self.assertRaisesRegex(dot.RelayError, message):
                    dot.settings(path)
            # The retired-route message must win even when the old browser config lacks a reply thread.
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis", "transport": "browser"}))
            with self.assertRaisesRegex(dot.RelayError, "retired in upgrade 42"):
                dot.settings(path)
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis", "transport": "inbox",
                                        "replyThreadId": "replies"}))
            self.assertEqual(dot.settings(path), CFG)
            for name in ("relay", "Browser", "composer", "reply_text"):
                self.assertFalse(hasattr(dot, name), name)
            with patch.object(dot, "inbox_relay") as inbox, patch.object(dot.sys, "argv",
                    ["grokbot-ask", "--config", str(path), "--check"]), \
                    patch.dict(dot.os.environ, {"XDG_RUNTIME_DIR": tmp}), patch.object(dot.signal, "signal"):
                inbox.return_value = "READY"
                self.assertEqual(dot.main(), 0)
                inbox.assert_called_once_with(CFG, None)
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis", "transport": "browser",
                                        "replyThreadId": "replies"}))
            with patch.object(dot, "inbox_relay") as inbox, patch.object(dot.sys, "argv",
                    ["grokbot-ask", "--config", str(path), "--question", "hello"]), \
                    patch.dict(dot.os.environ, {"XDG_RUNTIME_DIR": tmp}), patch.object(dot.signal, "signal"):
                self.assertEqual(dot.main(), 2)
                inbox.assert_not_called()

    def test_inbox_is_default_and_requires_reply_thread(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "relay.json"
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis"}))
            path.chmod(0o600)
            with self.assertRaises(dot.RelayError):
                dot.settings(path)
            path.write_text(json.dumps({"dotUrl": CFG["url"], "name": "Jarvis", "replyThreadId": "replies"}))
            cfg = dot.settings(path)
            self.assertEqual(cfg, CFG)
            self.assertEqual(len(dot.request({"botId": cfg["id"], "prompt": "x" * 1000}, cfg)), 1000)
            with self.assertRaises(dot.RelayError):
                dot.request({"botId": cfg["id"], "prompt": "x" * 1001}, cfg)

    def test_inbox_queue_submission_post_only_reply_and_display_cap(self):
        for post in (True, False):
            for length, mode in ((900, "approval-required"), (5000, "full-access")):
                with self.subTest(post=post, length=length, mode=mode), patch.object(dot, "Inbox") as cls, \
                        patch.object(dot.secrets, "token_hex", return_value="new"):
                    client = cls.return_value
                    client.tools = {"t3_thread_send", "t3_thread_post"} if post else {"t3_thread_send"}
                    client.deadline = dot.time.monotonic() + 30
                    direct = {"type": "user_message", "creationSource": "mcp", "textTruncated": False}
                    reply = "OMARCHYTV_REPLY new\n" + "x" * length + "\nOMARCHYTV_END new"
                    client.call.side_effect = [
                        {"thread": {"title": "OmarchyTV relay replies (approval-required)", "runtimeMode": mode, "itemCount": 0}}, {},
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
                    self.assertEqual(sent.args[0], "t3_thread_send")
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
                    self.assertEqual(sent.kwargs["mode"], "queue")

    def test_inbox_check_and_uncertain_submission_never_send_twice(self):
        with patch.object(dot, "Inbox") as cls:
            client = cls.return_value
            client.tools = {"t3_thread_send", "t3_thread_post"}
            client.call.return_value = {"thread": {"title": "OmarchyTV relay replies (approval-required)", "runtimeMode": "approval-required", "itemCount": 0}}
            self.assertIn("no message sent", dot.inbox_relay({"replyThreadId": "replies"}))
            self.assertEqual(client.call.call_count, 1)
            client.call.return_value["thread"]["runtimeMode"] = "auto"
            with self.assertRaisesRegex(dot.RelayError, "'auto' is not allowed"):
                dot.inbox_relay({"replyThreadId": "replies"}, "hello")
            self.assertEqual(client.call.call_count, 2)
            client.call.return_value["thread"]["runtimeMode"] = "approval-required"
            client.call.reset_mock()
            client.tools = {"t3_thread_send", "t3_thread_post"}
            client.call.side_effect = [client.call.return_value, dot.RelayError("uncertain")]
            with self.assertRaises(dot.RelayError):
                dot.inbox_relay({"replyThreadId": "replies"}, "hello")
            self.assertEqual(client.call.call_count, 2)

    def test_reply_thread_accepts_full_access_and_prefix_title_only(self):
        def check(thread):
            with patch.object(dot, "Inbox") as cls:
                client = cls.return_value
                client.call.return_value = {"thread": {"itemCount": 0, **thread}}
                try:
                    return dot.inbox_relay({"replyThreadId": "replies"})
                finally:
                    self.assertEqual(client.call.call_count, 1)
                    self.assertEqual(client.call.call_args.args[0], "t3_thread_read")

        for title in ("OmarchyTV relay replies (approval-required)", "OmarchyTV relay replies",
                      "OmarchyTV relay replies (full-access)"):
            for mode in ("approval-required", "full-access"):
                with self.subTest(title=title, mode=mode):
                    self.assertIn("no message sent", check({"title": title, "runtimeMode": mode}))
        good = "OmarchyTV relay replies"
        for mode in ("auto", "auto-accept-edits", None, ""):
            with self.subTest(mode=mode), self.assertRaisesRegex(
                    dot.RelayError, r"\(use approval-required or full-access\); no message sent"):
                check({"title": good, "runtimeMode": mode})
        with self.assertRaisesRegex(dot.RelayError, "use approval-required or full-access"):
            check({"title": good})
        for thread in ({"title": "Jarvis inbox", "runtimeMode": "full-access"},
                       {"title": "omarchytv relay replies", "runtimeMode": "full-access"},
                       {"title": None, "runtimeMode": "full-access"},
                       {"runtimeMode": "full-access"},
                       {"title": good, "runtimeMode": "full-access", "archived": True}):
            with self.subTest(thread=thread), self.assertRaisesRegex(
                    dot.RelayError, "dedicated OmarchyTV relay replies thread; no message sent"):
                check(thread)

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
            client.tools = {"t3_thread_send", "t3_thread_post"}
            client.deadline = dot.time.monotonic() + 30
            client.call.side_effect = [
                {"thread": {"title": "OmarchyTV relay replies (approval-required)", "runtimeMode": "approval-required", "itemCount": 1000}, "hasMore": True, "nextPosition": 4}, {},
                {"items": [reply("first")], "hasMore": True, "nextPosition": 1000},
                {"items": [reply("corrected")], "hasMore": False, "nextPosition": 1001},
            ]
            with self.assertRaisesRegex(dot.RelayError, "Ambiguous"):
                dot.inbox_relay({"replyThreadId": "replies"}, "hello")
            calls = client.call.call_args_list
            self.assertEqual(calls[1].args[0], "t3_thread_send")
            self.assertEqual(calls[1].kwargs["mode"], "queue")
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
            client.tools = {"t3_thread_send", "t3_thread_post"}
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
            self.assertEqual(sum(c.args[0] == "t3_thread_send" for c in calls), 1)
            self.assertFalse(any(c.args[0] == "t3_thread_post" for c in calls))


if __name__ == "__main__":
    unittest.main()
