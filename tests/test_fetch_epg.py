"""fetch_epg.py のテスト（ネット接続なしで動く）。 python -m unittest discover tests"""
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "fetcher"))
import fetch_epg  # noqa: E402


def v3_payload(service, date):
    return {service: {"publication": [
        {"name": "ニュース7", "description": "今日のニュース", "startDate": f"{date}T19:00:00+09:00",
         "endDate": f"{date}T19:30:00+09:00", "genre": [{"id": "0000", "name1": "ニュース／報道"}]},
        {"name": "深夜番組", "description": "", "startDate": f"{date}T23:40:00+09:00",
         "endDate": f"{date}T24:00:00+09:00".replace("T24:00", "T23:59")},
    ]}}


class ParseTest(unittest.TestCase):
    def test_v3(self):
        progs = fetch_epg.parse_nhk(v3_payload("g1", "2026-09-24"), "g1", "nhk-g1")
        self.assertEqual(len(progs), 2)
        self.assertEqual(progs[0]["title"], "ニュース7")
        self.assertEqual(progs[0]["start"], "2026-09-24T19:00:00+09:00")
        self.assertEqual(progs[0]["genre"], "ニュース／報道")
        self.assertEqual(progs[0]["id"], "nhk-g1-202609241900")

    def test_v2_and_utc(self):
        payload = {"list": {"e1": [{"title": "t", "subtitle": "s", "start_time": "2026-09-24T10:00:00Z",
                                     "end_time": "2026-09-24T10:30:00Z", "genres": ["0700"]}]}}
        p = fetch_epg.parse_nhk(payload, "e1", "nhk-e1")[0]
        self.assertEqual(p["start"], "2026-09-24T19:00:00+09:00")
        self.assertEqual(p["genre"], "アニメ")

    def test_unknown_shape(self):
        with self.assertRaises(ValueError):
            fetch_epg.parse_nhk({"foo": 1}, "g1", "x")

    def test_redact(self):
        self.assertNotIn("SECRET", fetch_epg.redact("https://a/b?service=g1&key=SECRET"))


class MainTest(unittest.TestCase):
    def run_main(self, out, extra=()):
        def fake_get(url, retries=3):
            from urllib.parse import parse_qs, urlsplit
            q = parse_qs(urlsplit(url).query)
            return v3_payload(q["service"][0], q["date"][0])
        argv = ["x", "--out", out, "--today", "2026-09-24", *extra]
        with mock.patch.object(fetch_epg, "http_get_json", side_effect=fake_get), \
             mock.patch.object(fetch_epg.time, "sleep"), \
             mock.patch.dict("os.environ", {"NHK_API_KEY": "k"}), \
             mock.patch.object(sys, "argv", argv):
            return fetch_epg.main()

    def test_writes_seven_days(self):
        with tempfile.TemporaryDirectory() as d:
            self.assertEqual(self.run_main(d, ["--full"]), 0)
            idx = json.loads((Path(d) / "epg/index.json").read_text("utf-8"))
            self.assertEqual(len(idx["days"]), 7)
            self.assertEqual(idx["days"][0], "2026-09-24")
            self.assertEqual(idx["days"][-1], "2026-09-30")
            day = json.loads((Path(d) / "epg/2026-09-30.json").read_text("utf-8"))
            enabled = [c for c in idx["channels"]]
            self.assertEqual(len(day["programs"]), 2 * len(enabled))

    def test_failure_keeps_previous(self):
        with tempfile.TemporaryDirectory() as d:
            self.run_main(d, ["--full"])
            with mock.patch.object(fetch_epg, "http_get_json", side_effect=RuntimeError("down")), \
                 mock.patch.object(fetch_epg.time, "sleep"), \
                 mock.patch.dict("os.environ", {"NHK_API_KEY": "k"}), \
                 mock.patch.object(sys, "argv", ["x", "--out", d, "--today", "2026-09-24", "--full"]):
                self.assertEqual(fetch_epg.main(), 1)
            day = json.loads((Path(d) / "epg/2026-09-24.json").read_text("utf-8"))
            self.assertTrue(day["programs"])  # 前回分が残る


if __name__ == "__main__":
    unittest.main()
