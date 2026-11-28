#!/usr/bin/env python3
"""Local Mac summarizer for YouTube market commentary channels -> Firestore.

Designed to run on Mac mini (e.g. via launchd) using:
1. Official YouTube Atom RSS (free, zero auth)
2. yt-dlp local Japanese caption extraction (free, residential IP)
3. Google Gemini CLI (@google/gemini-cli) authenticated with Google AI Pro OAuth (no API key billing)
4. Firestore `stock_channel_briefs/{videoId}` consumed by `stock_pipeline.py`

Security note:
Logs only non-sensitive status/video IDs/titles; credentials remain outside git
(e.g. ~/.config/hibi/firebase-service-account.json).
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path
from typing import Any

SCRIPT_DIR = Path(__file__).resolve().parent
REPO_ROOT = SCRIPT_DIR.parents[1]
FUNCTIONS_STOCK_DIR = REPO_ROOT / "functions-stock"
if str(FUNCTIONS_STOCK_DIR) not in sys.path:
    sys.path.insert(0, str(FUNCTIONS_STOCK_DIR))

from youtube_channel import (  # noqa: E402
    COLLECTION_CHANNEL_BRIEFS,
    DEFAULT_YOUTUBE_CHANNELS,
    fetch_channel_rss,
    parse_iso_datetime,
)

DEFAULT_SERVICE_ACCOUNT_PATH = Path.home() / ".config" / "hibi" / "firebase-service-account.json"
DEFAULT_MAX_TRANSCRIPT_CHARS = 24000
DEFAULT_MAX_AGE_HOURS = 36

_VTT_TIME_RE = re.compile(r"^\d{2}:\d{2}:\d{2}\.\d{3}\s+-->\s+\d{2}:\d{2}:\d{2}\.\d{3}")
_VTT_SHORT_TIME_RE = re.compile(r"^\d{2}:\d{2}\.\d{3}\s+-->\s+\d{2}:\d{2}\.\d{3}")
_HTML_TAG_RE = re.compile(r"<[^>]+>")
_NOISE_TAG_RE = re.compile(r"\[(?:音楽|拍手|笑|Music|Applause)\]", re.IGNORECASE)


def clean_vtt_transcript(vtt_text: str, max_chars: int = DEFAULT_MAX_TRANSCRIPT_CHARS) -> str:
    """Strip WebVTT timestamps/tags and deduplicate rolling YouTube auto-caption lines."""
    if not (vtt_text or "").strip():
        return ""

    deduped: list[str] = []
    for raw_line in vtt_text.splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if line.startswith(("WEBVTT", "Kind:", "Language:", "NOTE", "STYLE")):
            continue
        if _VTT_TIME_RE.match(line) or _VTT_SHORT_TIME_RE.match(line):
            continue
        if line.isdigit():
            continue

        cleaned = _HTML_TAG_RE.sub("", line)
        cleaned = _NOISE_TAG_RE.sub("", cleaned)
        cleaned = re.sub(r"\s+", " ", cleaned).strip()
        if not cleaned:
            continue

        if deduped:
            prev = deduped[-1]
            if cleaned == prev or prev.endswith(cleaned):
                continue
            if cleaned.startswith(prev):
                deduped[-1] = cleaned
                continue
        deduped.append(cleaned)

    joined = "\n".join(deduped).strip()
    if len(joined) <= max_chars:
        return joined

    head_budget = int(max_chars * 0.65)
    tail_budget = max_chars - head_budget - 20
    return f"{joined[:head_budget].rstrip()}\n...(中略)...\n{joined[-tail_budget:].lstrip()}"


def resolve_binary(candidates: list[str | Path]) -> str | None:
    """Find the first executable binary among explicit paths and PATH."""
    for item in candidates:
        s = str(item)
        if os.path.isabs(s) and os.access(s, os.X_OK):
            return s
        found = shutil.which(s)
        if found:
            return found
    return None


def fetch_transcript_with_ytdlp(
    video_url: str,
    video_id: str,
    ytdlp_bin: str | None = None,
    node_bin: str | None = None,
    timeout_sec: int = 90,
) -> str:
    """Download Japanese auto/manual VTT captions via yt-dlp without downloading video."""
    ytdlp = ytdlp_bin or resolve_binary(
        [
            SCRIPT_DIR / ".venv" / "bin" / "yt-dlp",
            "yt-dlp",
        ]
    )
    if not ytdlp:
        print("[WARN] yt-dlp not found; skipping caption extraction.")
        return ""

    node = node_bin or resolve_binary(
        [
            Path.home() / ".local" / "share" / "mise" / "installs" / "node" / "24" / "bin" / "node",
            "/opt/homebrew/bin/node",
            "/usr/local/bin/node",
            "node",
        ]
    )

    with tempfile.TemporaryDirectory(prefix="hibi_yt_sub_") as tmpdir:
        cmd = [
            ytdlp,
            "--no-warnings",
            "--skip-download",
            "--write-auto-subs",
            "--write-subs",
            "--sub-langs",
            "ja",
            "--sub-format",
            "vtt",
            "-o",
            str(Path(tmpdir) / "%(id)s.%(ext)s"),
        ]
        if node:
            cmd.extend(["--js-runtimes", f"node:{node}"])
        cmd.append(video_url)

        try:
            res = subprocess.run(
                cmd,
                capture_output=True,
                text=True,
                timeout=timeout_sec,
            )
        except subprocess.TimeoutExpired:
            print(f"[WARN] yt-dlp timed out for {video_id}")
            return ""

        vtt_files = sorted(Path(tmpdir).glob(f"{video_id}*.vtt"))
        if not vtt_files:
            if res.returncode != 0:
                print(f"[WARN] yt-dlp exited {res.returncode} with no captions for {video_id}")
            return ""

        raw_vtt = vtt_files[0].read_text(encoding="utf-8", errors="ignore")
        return clean_vtt_transcript(raw_vtt)


def build_summary_prompt(entry: dict[str, Any], transcript: str) -> str:
    """Build the financial insight extraction prompt for Gemini CLI."""
    chapters = entry.get("chapters") or []
    chapters_block = "\n".join(f"- {c}" for c in chapters) if chapters else "（目次なし）"
    transcript_block = (
        transcript
        if transcript
        else "（字幕を取得できませんでした。タイトル・目次・URL情報から分かる範囲のみ要約し、推測は避けてください。）"
    )

    return (
        "あなたは個人投資家向けマーケットリサーチアシスタントです。\n"
        "以下の投資解説 YouTube 動画の文字起こし・目次から、毎日の株式投資判断（日経平均・米国株・保有/監視銘柄の売買判定）の参考になる要点だけを正確に抽出してください。\n"
        "※煽り表現や挨拶は除き、客観的な材料・指標・懸念リスク・言及された銘柄・タイムラインだけを簡潔にまとめてください。\n\n"
        f"- チャンネル: {entry.get('channelName', '')}\n"
        f"- タイトル: {entry.get('title', '')}\n"
        f"- 公開日時(UTC): {entry.get('published', '')}\n"
        f"- URL: {entry.get('url', '')}\n\n"
        f"## 概要欄の目次\n{chapters_block}\n\n"
        f"## 動画文字起こし\n{transcript_block}\n\n"
        "必ず次の JSON 形式だけを出力してください（マークダウンのコードブロックあり/なし不問、余計な前置き不要）：\n"
        "{\n"
        '  "marketMood": "強気 / やや強気 / 中立 / やや弱気 / 警戒 のいずれか",\n'
        '  "summary": "1) 地合い・指数/金利/為替の要点、2) 注目セクター・テーマ、3) 警戒リスク・イベント、4) 個別銘柄の材料まとめ を4〜6行の簡潔な日本語箇条書きで記述（最大600字）",\n'
        '  "mentionedSymbols": ["動画内で重要トピックとして言及された企業名やティッカーを最大10個"],\n'
        '  "keyDrivers": ["相場を動かしている主因を1〜3個（短文）"],\n'
        '  "risks": ["指摘されたリスク・注意点を1〜3個（短文）"]\n'
        "}"
    )


def parse_summary_output(raw: str) -> dict[str, Any]:
    """Parse Gemini CLI JSON/text output into normalized brief fields."""
    text = (raw or "").strip()
    if not text:
        return {"marketMood": "", "summary": "", "mentionedSymbols": [], "keyDrivers": [], "risks": []}

    # Unwrap `gemini -o json` outer envelope when present
    try:
        envelope = json.loads(text)
        if isinstance(envelope, dict) and isinstance(envelope.get("response"), str):
            text = envelope["response"].strip()
    except json.JSONDecodeError:
        pass

    candidate = text
    fence = re.search(r"```(?:json)?\s*\n(.*?)\n```", text, flags=re.DOTALL | re.IGNORECASE)
    if fence:
        candidate = fence.group(1).strip()
    else:
        start = candidate.find("{")
        end = candidate.rfind("}")
        if start != -1 and end > start:
            candidate = candidate[start : end + 1]

    try:
        parsed = json.loads(candidate)
        if isinstance(parsed, dict) and str(parsed.get("summary") or "").strip():
            return {
                "marketMood": str(parsed.get("marketMood") or "").strip(),
                "summary": str(parsed.get("summary") or "").strip(),
                "mentionedSymbols": [
                    str(x).strip() for x in (parsed.get("mentionedSymbols") or []) if str(x).strip()
                ][:12],
                "keyDrivers": [
                    str(x).strip() for x in (parsed.get("keyDrivers") or []) if str(x).strip()
                ][:5],
                "risks": [str(x).strip() for x in (parsed.get("risks") or []) if str(x).strip()][:5],
            }
    except json.JSONDecodeError:
        pass

    return {
        "marketMood": "",
        "summary": text[:780].strip(),
        "mentionedSymbols": [],
        "keyDrivers": [],
        "risks": [],
    }


def summarize_with_gemini_cli(
    prompt: str,
    gemini_bin: str | None = None,
    node_bin: str | None = None,
    model: str | None = None,
    timeout_sec: int = 180,
) -> str:
    """Run headless Gemini CLI passing the full prompt over stdin."""
    gemini = gemini_bin or resolve_binary(
        [
            Path.home() / ".local" / "share" / "mise" / "installs" / "node" / "24" / "bin" / "gemini",
            "/opt/homebrew/bin/gemini",
            "/usr/local/bin/gemini",
            "gemini",
        ]
    )
    if not gemini:
        raise RuntimeError("gemini CLI not found. Install with: npm install -g @google/gemini-cli")

    node = node_bin or resolve_binary(
        [
            Path.home() / ".local" / "share" / "mise" / "installs" / "node" / "24" / "bin" / "node",
            "/opt/homebrew/bin/node",
            "/usr/local/bin/node",
            "node",
        ]
    )
    env = os.environ.copy()
    if node:
        node_dir = str(Path(node).parent)
        env["PATH"] = f"{node_dir}:{env.get('PATH', '')}"

    cmd = [
        gemini,
        "-p",
        "上記の内容に基づき、指示されたJSON形式のみを出力してください。",
        "-o",
        "json",
        "--approval-mode",
        "plan",
        "--skip-trust",
    ]
    if model and model.strip():
        cmd.extend(["-m", model.strip()])

    with tempfile.TemporaryDirectory(prefix="hibi_gemini_cwd_") as empty_cwd:
        res = subprocess.run(
            cmd,
            input=prompt,
            cwd=empty_cwd,
            env=env,
            capture_output=True,
            text=True,
            timeout=timeout_sec,
        )

    if res.returncode != 0:
        err_hint = (res.stderr or res.stdout or "").strip()[:300]
        raise RuntimeError(f"gemini CLI exited {res.returncode}: {err_hint}")
    return res.stdout


def init_firestore_local():
    """Initialize Firestore using local service account JSON or Application Default Credentials."""
    import firebase_admin
    from firebase_admin import credentials, firestore

    if firebase_admin._apps:
        return firestore.client()

    sa_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip()
    if sa_json:
        cred = credentials.Certificate(json.loads(sa_json))
        firebase_admin.initialize_app(cred)
        return firestore.client()

    sa_path_candidates = [
        os.environ.get("FIREBASE_SERVICE_ACCOUNT_PATH", "").strip(),
        os.environ.get("GOOGLE_APPLICATION_CREDENTIALS", "").strip(),
        str(DEFAULT_SERVICE_ACCOUNT_PATH),
    ]
    for p in sa_path_candidates:
        if p and Path(p).expanduser().is_file():
            cred = credentials.Certificate(str(Path(p).expanduser()))
            firebase_admin.initialize_app(cred)
            return firestore.client()

    try:
        firebase_admin.initialize_app()
        client = firestore.client()
        return client
    except Exception as exc:
        raise RuntimeError(
            "Firestore credentials not found. Place Firebase Service Account JSON at "
            f"{DEFAULT_SERVICE_ACCOUNT_PATH} or set FIREBASE_SERVICE_ACCOUNT_PATH. ({exc})"
        ) from exc


def sync_channels(
    *,
    max_age_hours: int = DEFAULT_MAX_AGE_HOURS,
    limit: int = 2,
    force: bool = False,
    dry_run: bool = False,
    skip_llm: bool = False,
    model: str | None = None,
) -> int:
    """Fetch recent channel videos, extract local captions, summarize via Gemini CLI, store in Firestore."""
    now_utc = dt.datetime.now(dt.timezone.utc)
    cutoff = now_utc - dt.timedelta(hours=max_age_hours)
    db = None if dry_run else init_firestore_local()

    processed = 0
    for ch in DEFAULT_YOUTUBE_CHANNELS:
        entries = fetch_channel_rss(channel_id=ch["id"], channel_name=ch["name"])
        if not entries:
            print(f"[WARN] No RSS entries found for {ch['handle']}")
            continue

        recent_entries = []
        for e in entries:
            pub_dt = parse_iso_datetime(e.get("published") or "")
            if pub_dt is not None and pub_dt < cutoff:
                continue
            recent_entries.append(e)
        recent_entries = recent_entries[:limit]

        if not recent_entries:
            print(f"[INFO] No videos within {max_age_hours}h for {ch['handle']}.")
            continue

        for entry in recent_entries:
            vid = entry["videoId"]
            if db is not None and not force:
                existing = db.collection(COLLECTION_CHANNEL_BRIEFS).document(vid).get()
                if existing.exists and (existing.to_dict() or {}).get("summary"):
                    print(f"[SKIP] Already summarized: {vid} ({entry['title'][:40]})")
                    continue

            print(f"[INFO] Processing {vid}: {entry['title']}")
            transcript = fetch_transcript_with_ytdlp(entry["url"], vid)
            source_kind = "local_transcript_gemini_cli" if transcript else "local_metadata_gemini_cli"
            print(f"[INFO] Transcript chars={len(transcript)}, chapters={len(entry.get('chapters') or [])}")

            if skip_llm:
                parsed = {
                    "marketMood": "",
                    "summary": f"(skip-llm dry run) transcript={len(transcript)} chars",
                    "mentionedSymbols": [],
                    "keyDrivers": [],
                    "risks": [],
                }
            else:
                prompt = build_summary_prompt(entry, transcript)
                raw_out = summarize_with_gemini_cli(prompt, model=model)
                parsed = parse_summary_output(raw_out)

            if dry_run:
                print("--- DRY RUN SUMMARY ---")
                print(json.dumps({"videoId": vid, "title": entry["title"], **parsed}, ensure_ascii=False, indent=2))
                processed += 1
                continue

            utc_now = now_utc.isoformat()
            doc_payload = {
                "videoId": vid,
                "channelId": entry["channelId"],
                "channelName": entry["channelName"],
                "channelHandle": ch["handle"],
                "title": entry["title"],
                "url": entry["url"],
                "published": entry["published"],
                "chapters": entry.get("chapters") or [],
                "transcriptChars": len(transcript),
                "sourceKind": source_kind,
                "marketMood": parsed["marketMood"],
                "summary": parsed["summary"],
                "mentionedSymbols": parsed["mentionedSymbols"],
                "keyDrivers": parsed["keyDrivers"],
                "risks": parsed["risks"],
                "updatedAt": utc_now,
            }
            db.collection(COLLECTION_CHANNEL_BRIEFS).document(vid).set(doc_payload, merge=True)
            print(f"[OK] Saved {COLLECTION_CHANNEL_BRIEFS}/{vid} (symbols={len(parsed['mentionedSymbols'])})")
            processed += 1

    print(f"[DONE] YouTube local sync finished (processed={processed}).")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description="Local Mac YouTube channel summarizer for Hibi")
    parser.add_argument("--max-age-hours", type=int, default=DEFAULT_MAX_AGE_HOURS)
    parser.add_argument("--limit", type=int, default=2, help="Max recent videos per run")
    parser.add_argument("--force", action="store_true", help="Re-summarize even if already in Firestore")
    parser.add_argument("--dry-run", action="store_true", help="Do not read/write Firestore")
    parser.add_argument("--skip-llm", action="store_true", help="Skip Gemini CLI call (implies caption check only)")
    parser.add_argument("--model", default=os.environ.get("GEMINI_CLI_MODEL", ""), help="Optional Gemini CLI model override")
    args = parser.parse_args()

    return sync_channels(
        max_age_hours=args.max_age_hours,
        limit=args.limit,
        force=args.force,
        dry_run=args.dry_run,
        skip_llm=args.skip_llm,
        model=args.model or None,
    )


if __name__ == "__main__":
    raise SystemExit(main())
