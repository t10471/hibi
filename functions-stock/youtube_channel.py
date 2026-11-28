"""YouTube channel RSS fetching and Firestore brief formatting for Hibi Stock Analysis.

Kept dependency-light (stdlib + requests) so both Cloud Functions (`functions-stock/`)
and local Mac automation scripts (`scripts/stock-analysis/`) can share the same logic.
"""

from __future__ import annotations

import datetime as dt
import re
import xml.etree.ElementTree as ET
from typing import Any

USHIRODA_CHANNEL_ID = "UCgTMlZk7bL7e61Lfm3IXCvQ"
USHIRODA_CHANNEL_NAME = "後田泰孝のマーケット解説"
USHIRODA_CHANNEL_HANDLE = "@yasutakaushiroda"

DEFAULT_YOUTUBE_CHANNELS = [
    {
        "id": USHIRODA_CHANNEL_ID,
        "name": USHIRODA_CHANNEL_NAME,
        "handle": USHIRODA_CHANNEL_HANDLE,
    }
]

COLLECTION_CHANNEL_BRIEFS = "stock_channel_briefs"
MAX_INJECTED_CHANNEL_BRIEFS = 2
MAX_INJECTED_CHANNEL_CHARS = 1800

_ATOM_NS = {
    "atom": "http://www.w3.org/2005/Atom",
    "yt": "http://www.youtube.com/xml/schemas/2015",
    "media": "http://search.yahoo.com/mrss/",
}
_CHAPTER_LINE_RE = re.compile(r"^\s*[-・*]?\s*\d{1,2}:\d{2}(?::\d{2})?\s*[｜|].+$")


def channel_rss_url(channel_id: str) -> str:
    """Return the official public YouTube Atom feed URL for a channel ID."""
    return f"https://www.youtube.com/feeds/videos.xml?channel_id={channel_id.strip()}"


def extract_chapters_from_description(description: str, max_lines: int = 20) -> list[str]:
    """Extract timestamped chapter bullets from a YouTube video description."""
    chapters: list[str] = []
    for raw_line in (description or "").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        if _CHAPTER_LINE_RE.match(line):
            cleaned = re.sub(r"^\s*[-・*]\s*", "", line).strip()
            chapters.append(cleaned)
            if len(chapters) >= max_lines:
                break
    return chapters


def parse_iso_datetime(raw: str) -> dt.datetime | None:
    """Parse an ISO-8601 timestamp (including trailing 'Z') to a UTC datetime."""
    text = (raw or "").strip()
    if not text:
        return None
    if text.endswith("Z"):
        text = text[:-1] + "+00:00"
    try:
        parsed = dt.datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        return parsed.replace(tzinfo=dt.timezone.utc)
    return parsed.astimezone(dt.timezone.utc)


def parse_youtube_rss(xml_bytes: bytes, channel_name: str = "") -> list[dict[str, Any]]:
    """Parse a YouTube Atom XML feed into normalized video metadata dictionaries."""
    if not xml_bytes:
        return []
    root = ET.fromstring(xml_bytes)
    feed_author = (root.findtext("atom:author/atom:name", "", _ATOM_NS) or "").strip()
    resolved_name = channel_name.strip() or feed_author

    entries: list[dict[str, Any]] = []
    for entry in root.findall("atom:entry", _ATOM_NS):
        video_id = (entry.findtext("yt:videoId", "", _ATOM_NS) or "").strip()
        title = (entry.findtext("atom:title", "", _ATOM_NS) or "").strip()
        if not video_id or not title:
            continue
        channel_id = (entry.findtext("yt:channelId", "", _ATOM_NS) or "").strip()
        published = (entry.findtext("atom:published", "", _ATOM_NS) or "").strip()
        updated = (entry.findtext("atom:updated", "", _ATOM_NS) or "").strip()
        link_el = entry.find("atom:link[@rel='alternate']", _ATOM_NS)
        url = (link_el.attrib.get("href") if link_el is not None else "") or (
            f"https://www.youtube.com/watch?v={video_id}"
        )
        description = (entry.findtext("media:group/media:description", "", _ATOM_NS) or "").strip()
        chapters = extract_chapters_from_description(description)

        entries.append(
            {
                "videoId": video_id,
                "channelId": channel_id,
                "channelName": resolved_name,
                "title": title,
                "url": url,
                "published": published,
                "updated": updated,
                "description": description,
                "chapters": chapters,
            }
        )
    return entries


def fetch_channel_rss(
    channel_id: str = USHIRODA_CHANNEL_ID,
    channel_name: str = USHIRODA_CHANNEL_NAME,
    timeout: int = 10,
) -> list[dict[str, Any]]:
    """Fetch recent videos from the YouTube Atom RSS feed without scraping watch pages."""
    import requests

    try:
        resp = requests.get(channel_rss_url(channel_id), timeout=timeout)
        if not resp.ok:
            print(f"[WARN] YouTube RSS returned HTTP {resp.status_code} for {channel_id}")
            return []
        return parse_youtube_rss(resp.content, channel_name=channel_name)
    except Exception as exc:
        print(f"[WARN] Failed to fetch YouTube RSS for {channel_id}: {exc}")
        return []


def _format_jst_label(published: str) -> str:
    parsed = parse_iso_datetime(published)
    if parsed is None:
        return published or ""
    jst = parsed.astimezone(dt.timezone(dt.timedelta(hours=9)))
    return jst.strftime("%Y-%m-%d %H:%M JST")


def format_channel_briefs_for_prompt(
    briefs: list[dict[str, Any]],
    max_items: int = MAX_INJECTED_CHANNEL_BRIEFS,
    max_chars: int = MAX_INJECTED_CHANNEL_CHARS,
) -> str:
    """Format stored YouTube video briefs or RSS chapter fallbacks within strict char bounds."""
    sorted_briefs = sorted(
        briefs,
        key=lambda b: str(b.get("published") or b.get("updatedAt") or ""),
        reverse=True,
    )

    blocks: list[str] = []
    total_chars = 0
    for item in sorted_briefs:
        title = str(item.get("title") or "").strip()
        if not title:
            continue
        channel = str(item.get("channelName") or USHIRODA_CHANNEL_NAME).strip()
        pub_label = _format_jst_label(str(item.get("published") or ""))
        source_kind = str(item.get("sourceKind") or "summary").strip()

        header = f"### [{channel}] {title} ({pub_label})"
        body_parts: list[str] = []

        summary_md = re.sub(r"\n{3,}", "\n\n", str(item.get("summary") or "")).strip()
        if summary_md:
            if len(summary_md) > 780:
                summary_md = summary_md[:777].rstrip() + "..."
            body_parts.append(summary_md)

        symbols = [str(s).strip() for s in (item.get("mentionedSymbols") or []) if str(s).strip()]
        if symbols:
            body_parts.append("言及銘柄: " + ", ".join(symbols[:12]))

        if not summary_md:
            chapters = [str(c).strip() for c in (item.get("chapters") or []) if str(c).strip()]
            if chapters:
                chap_text = " / ".join(chapters[:12])
                if len(chap_text) > 550:
                    chap_text = chap_text[:547] + "..."
                body_parts.append(f"動画目次 ({source_kind}): {chap_text}")

        if not body_parts:
            body_parts.append("（※目次・要約なし：タイトル材料のみ参照）")

        block = header + "\n" + "\n".join(body_parts)
        if blocks and total_chars + len(block) + 2 > max_chars:
            break
        if not blocks and len(block) > max_chars:
            block = block[: max_chars - 3].rstrip() + "..."
        blocks.append(block)
        total_chars += len(block) + 2
        if len(blocks) >= max_items:
            break

    return "\n\n".join(blocks)


def load_recent_channel_briefs(
    db: Any,
    max_age_hours: int = 48,
    now_utc: dt.datetime | None = None,
    rss_fallback: bool = True,
    channels: list[dict[str, str]] | None = None,
) -> str:
    """Load recent summarized YouTube briefs from Firestore, falling back to free RSS metadata."""
    ref_now = now_utc or dt.datetime.now(dt.timezone.utc)
    cutoff = ref_now - dt.timedelta(hours=max_age_hours)

    collected: dict[str, dict[str, Any]] = {}
    if db is not None:
        try:
            for snap in db.collection(COLLECTION_CHANNEL_BRIEFS).stream():
                d = snap.to_dict() or {}
                vid = str(d.get("videoId") or snap.id).strip()
                pub_dt = parse_iso_datetime(str(d.get("published") or d.get("updatedAt") or ""))
                if pub_dt is not None and pub_dt < cutoff:
                    continue
                if not d.get("summary") and not d.get("chapters"):
                    continue
                d["videoId"] = vid
                collected[vid] = d
        except Exception as exc:
            print(f"[WARN] Failed reading {COLLECTION_CHANNEL_BRIEFS}: {exc}")

    # Supplement with fresh RSS entries if not yet summarized locally (e.g. Mac was asleep)
    if rss_fallback:
        for ch in channels or DEFAULT_YOUTUBE_CHANNELS:
            rss_entries = fetch_channel_rss(
                channel_id=ch["id"],
                channel_name=ch["name"],
            )
            for entry in rss_entries[:4]:
                vid = entry["videoId"]
                pub_dt = parse_iso_datetime(entry.get("published") or "")
                if pub_dt is not None and pub_dt < cutoff:
                    continue
                if vid not in collected:
                    collected[vid] = {
                        **entry,
                        "sourceKind": "rss_chapters" if entry.get("chapters") else "rss_title",
                    }

    if not collected:
        return ""
    return format_channel_briefs_for_prompt(list(collected.values()))
