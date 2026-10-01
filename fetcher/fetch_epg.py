#!/usr/bin/env python3
"""FCTV番組表アプリ用の番組データを作るスクリプト。

GitHub Actions から定期実行され、今日を含む7日分の番組表を
  <out>/epg/index.json          … チャンネル一覧と日付一覧
  <out>/epg/YYYY-MM-DD.json     … その日の番組
として出力する。アプリはこの JSON を GitHub Pages から読む。

取得元（source）
  nhk      : NHK番組表API v3（公式・要APIキー。環境変数 NHK_API_KEY）
  json_url : このアプリ形式の番組JSONを返すURL（自宅チューナー等、将来用）
"""
from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

JST = dt.timezone(dt.timedelta(hours=9))
DAYS = 7  # 今日を含む7日分
NHK_BASE = os.environ.get("NHK_API_BASE", "https://program-api.nhk.jp/v3")
NHK_DATE_ENDPOINT = os.environ.get("NHK_DATE_ENDPOINT", "papiPgDateTv")
USER_AGENT = "fctv-epg-fetcher/1.0 (personal use)"

# NHK のジャンルコード上2桁 → 表示用ジャンル
GENRE_MAP = {
    "00": "ニュース", "01": "スポーツ", "02": "情報", "03": "ドラマ",
    "04": "音楽", "05": "バラエティ", "06": "映画", "07": "アニメ",
    "08": "ドキュメンタリー", "09": "劇場", "10": "趣味・教育", "11": "福祉",
}


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def http_get_json(url: str, retries: int = 3) -> dict | list:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT, "Accept": "application/json"})
    last: Exception | None = None
    for i in range(retries):
        try:
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read().decode("utf-8"))
        except urllib.error.HTTPError as e:
            last = e
            if e.code in (400, 401, 403, 404):
                break  # リトライしても直らない
        except Exception as e:  # noqa: BLE001
            last = e
        time.sleep(2 * (i + 1))
    raise RuntimeError(f"GET failed: {redact(url)}: {last}")


def redact(url: str) -> str:
    """ログにAPIキーを出さない。"""
    p = urllib.parse.urlsplit(url)
    q = [(k, "***" if k == "key" else v) for k, v in urllib.parse.parse_qsl(p.query)]
    return urllib.parse.urlunsplit(p._replace(query=urllib.parse.urlencode(q)))


def to_iso(value: str) -> str:
    """ISO8601 文字列を +09:00 付きに正規化。"""
    d = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if d.tzinfo is None:
        d = d.replace(tzinfo=JST)
    return d.astimezone(JST).isoformat(timespec="seconds")


def pick_genre(item: dict) -> str:
    # v3 / v2 どちらの形でも拾えるようにしておく
    for key in ("genre", "genres"):
        g = item.get(key)
        if isinstance(g, list) and g:
            first = g[0]
            if isinstance(first, dict):
                code = str(first.get("id") or first.get("code") or "")
                name = first.get("name1") or first.get("name")
                if name:
                    return str(name)
                if code:
                    return GENRE_MAP.get(code[:2], "")
            else:
                return GENRE_MAP.get(str(first)[:2], "")
        if isinstance(g, str) and g:
            return GENRE_MAP.get(g[:2], g)
    return ""


def parse_nhk(payload: dict, service: str, channel_id: str) -> list[dict]:
    """NHK API のレスポンスを共通形式に変換。v3（publication）と v2（list）の両方に対応。"""
    items: list[dict] = []
    # v3: {"<service>": {"publication": [...]}}（data で包まれている場合もある）
    root = payload.get("data", payload) if isinstance(payload, dict) else {}
    svc = root.get(service) if isinstance(root, dict) else None
    if isinstance(svc, dict) and isinstance(svc.get("publication"), list):
        for p in svc["publication"]:
            items.append({
                "start": p.get("startDate"), "end": p.get("endDate"),
                "title": p.get("name") or "", "desc": p.get("description") or "",
                "genre": pick_genre(p),
            })
    # v2: {"list": {"<service>": [...]}}
    elif isinstance(payload.get("list"), dict):
        for p in payload["list"].get(service, []):
            items.append({
                "start": p.get("start_time"), "end": p.get("end_time"),
                "title": p.get("title") or "", "desc": p.get("subtitle") or p.get("content") or "",
                "genre": pick_genre(p),
            })
    else:
        raise ValueError(f"想定外のNHK APIレスポンス形式です（keys={list(payload)[:5]}）")

    programs = []
    for it in items:
        if not it["start"] or not it["end"]:
            continue
        start, end = to_iso(it["start"]), to_iso(it["end"])
        programs.append({
            "id": f"{channel_id}-{start[:16].replace('-', '').replace(':', '').replace('T', '')}",
            "channelId": channel_id, "start": start, "end": end,
            "title": it["title"].strip(), "desc": it["desc"].strip(), "genre": it["genre"],
        })
    return programs


def fetch_nhk(ch: dict, area: str, date: str, key: str) -> list[dict]:
    q = urllib.parse.urlencode({"service": ch["service"], "area": area, "date": date, "key": key})
    url = f"{NHK_BASE}/{NHK_DATE_ENDPOINT}?{q}"
    return parse_nhk(http_get_json(url), ch["service"], ch["id"])


def fetch_json_url(ch: dict, date: str) -> list[dict]:
    """url の {date} を置換して取得。返り値は programs 配列か {"programs": [...]}。"""
    data = http_get_json(ch["url"].replace("{date}", date))
    progs = data.get("programs", []) if isinstance(data, dict) else data
    out = []
    for p in progs:
        start, end = to_iso(p["start"]), to_iso(p["end"])
        out.append({
            "id": p.get("id") or f"{ch['id']}-{start[:16].replace('-', '').replace(':', '').replace('T', '')}",
            "channelId": ch["id"], "start": start, "end": end,
            "title": p.get("title", ""), "desc": p.get("desc", ""), "genre": p.get("genre", ""),
        })
    return out


def load_existing(path: Path) -> dict[str, list[dict]]:
    """前回出力分をチャンネル別に読む（取得失敗時の保険）。"""
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text("utf-8"))
    except Exception:  # noqa: BLE001
        return {}
    by_ch: dict[str, list[dict]] = {}
    for p in data.get("programs", []):
        by_ch.setdefault(p["channelId"], []).append(p)
    return by_ch


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--channels", default=str(Path(__file__).with_name("channels.json")))
    ap.add_argument("--out", default="site")
    ap.add_argument("--today", help="YYYY-MM-DD（テスト用）")
    ap.add_argument("--full", action="store_true", help="7日分すべて取り直す")
    args = ap.parse_args()

    conf = json.loads(Path(args.channels).read_text("utf-8"))
    channels = [c for c in conf["channels"] if c.get("enabled")]
    area = conf.get("nhk", {}).get("area", "180")
    key = os.environ.get("NHK_API_KEY", "")

    now = dt.datetime.now(JST)
    today = dt.date.fromisoformat(args.today) if args.today else now.date()
    dates = [(today + dt.timedelta(days=i)).isoformat() for i in range(DAYS)]
    # 深夜（3〜5時台）の実行か --full 指定時は7日分、それ以外は今日・明日分だけ取り直す（API回数節約）
    full = args.full or 3 <= now.hour <= 5

    out_dir = Path(args.out) / "epg"
    out_dir.mkdir(parents=True, exist_ok=True)
    errors = 0
    attempts = 0

    for date in dates:
        path = out_dir / f"{date}.json"
        existing = load_existing(path)
        refresh = full or date in dates[:2] or not path.exists()
        programs: list[dict] = []
        for ch in channels:
            got: list[dict] | None = None
            if refresh and (ch["source"] == "nhk" or ch.get("url")):
                attempts += 1
                try:
                    if ch["source"] == "nhk":
                        if not key:
                            raise RuntimeError("NHK_API_KEY が設定されていません")
                        got = fetch_nhk(ch, area, date, key)
                        time.sleep(1)  # API に優しく
                    elif ch["source"] == "json_url" and ch.get("url"):
                        got = fetch_json_url(ch, date)
                except Exception as e:  # noqa: BLE001
                    errors += 1
                    log(f"[WARN] {ch['id']} {date}: {e}")
            if got is None:
                got = existing.get(ch["id"], [])  # 失敗・未更新なら前回分を使う
            programs.extend(got)
        programs.sort(key=lambda p: (p["channelId"], p["start"]))
        path.write_text(json.dumps({
            "date": date, "generatedAt": now.isoformat(timespec="seconds"), "programs": programs,
        }, ensure_ascii=False, separators=(",", ":")), "utf-8")
        log(f"{date}: {len(programs)} programs{' (refreshed)' if refresh else ''}")

    # 昨日より前のファイルは削除
    keep = {(today - dt.timedelta(days=1)).isoformat(), *dates}
    for f in out_dir.glob("????-??-??.json"):
        if f.stem not in keep:
            f.unlink()

    index = {
        "generatedAt": now.isoformat(timespec="seconds"),
        "days": dates,
        "channels": [
            {k: c[k] for k in ("id", "band", "number", "name")} for c in channels
        ],
    }
    (out_dir / "index.json").write_text(json.dumps(index, ensure_ascii=False, indent=1), "utf-8")
    (Path(args.out) / ".nojekyll").touch()

    if attempts and errors >= attempts:
        log("すべての取得に失敗しました")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
