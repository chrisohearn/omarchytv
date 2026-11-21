import importlib.util
from pathlib import Path
import unittest
from types import SimpleNamespace
from unittest import mock

spec = importlib.util.spec_from_file_location("mirror_sources", Path(__file__).parents[1] / "tools" / "mirror_sources.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class Client:
    def __init__(self, result):
        self.result = result
        self.calls = []

    def request(self, *args, **kwargs):
        self.calls.append((args, kwargs))
        return self.result


class Sources(unittest.TestCase):
    def setUp(self):
        self.sources = module.sources({"screenSources": [
            {"id": "pc", "label": "PC"},
            {"id": "mac", "label": "Mac", "command": ["ssh", "mac", "recorder"]},
        ]})

    def test_explicit_choice_routes_only_selected_source(self):
        c = Client({"state": "answered", "answer": "Mac"})
        selected = module.choose_source(c, self.sources)
        self.assertEqual(selected["id"], "mac")
        self.assertEqual(c.calls[0][0][2]["choices"], ["PC", "Mac", "Cancel"])

    def test_cancel_expiry_unknown_answer_never_start(self):
        for result in ({"state": "expired"}, {"state": "answered", "answer": "Cancel"},
                       {"state": "answered", "answer": "ssh another-host"}):
            self.assertIsNone(module.choose_source(Client(result), self.sources))

    def test_ambiguous_or_shell_string_config_rejected(self):
        for bad in ([{"id": "pc", "label": "PC", "command": "ssh mac recorder"}],
                    [{"id": "pc", "label": "PC"}, {"id": "mac", "label": "PC"}]):
            with self.assertRaises(ValueError):
                module.sources({"screenSources": bad})

    def test_replacement_waits_for_old_stream_and_ignores_legacy_stops(self):
        old = mock.Mock()
        old.poll.return_value = None
        new = mock.Mock()
        session = SimpleNamespace(_mirror=old, cfg={"screenMirrorFromTv": "allow", "screenSources": self.sources},
                                  _client=lambda: Client({"state": "answered", "answer": "Mac"}))
        selector = module.MirrorSelector(session)
        with mock.patch.object(module.threading, "Thread") as thread, \
                mock.patch.object(module.subprocess, "Popen", return_value=new) as spawn:
            selector.handle("start")
            selector.handle("start")
            thread.assert_called_once()
            selector.handle("stop")
            work = thread.call_args.kwargs
            work["target"](*work["args"])
            old.wait.assert_called_once_with(timeout=6)
            spawn.assert_called_once()
            self.assertEqual(spawn.call_args.args[0][-1], "mac")
            selector.handle("stop")
            selector.handle("stop")
        self.assertIs(session._mirror, new)
        old.terminate.assert_not_called()
        new.terminate.assert_not_called()


if __name__ == "__main__":
    unittest.main()
