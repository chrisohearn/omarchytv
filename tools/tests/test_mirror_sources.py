import importlib.util
from pathlib import Path
import threading
import unittest

spec = importlib.util.spec_from_file_location("mirror_sources", Path(__file__).parents[1] / "mirror_sources.py")
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
        selected = module.choose_source(c, self.sources, threading.Event())
        self.assertEqual(selected["id"], "mac")
        self.assertEqual(c.calls[0][0][2]["choices"], ["PC", "Mac", "Cancel"])

    def test_cancel_expiry_unknown_answer_never_start(self):
        for result in ({"state": "expired"}, {"state": "answered", "answer": "Cancel"},
                       {"state": "answered", "answer": "ssh another-host"}):
            self.assertIsNone(module.choose_source(Client(result), self.sources, threading.Event()))
        cancelled = threading.Event()
        cancelled.set()
        self.assertIsNone(module.choose_source(Client({"state": "answered", "answer": "PC"}), self.sources, cancelled))

    def test_ambiguous_or_shell_string_config_rejected(self):
        for bad in ([{"id": "pc", "label": "PC", "command": "ssh mac recorder"}],
                    [{"id": "pc", "label": "PC"}, {"id": "mac", "label": "PC"}]):
            with self.assertRaises(ValueError):
                module.sources({"screenSources": bad})


if __name__ == "__main__":
    unittest.main()
