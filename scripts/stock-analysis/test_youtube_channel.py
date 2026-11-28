#!/usr/bin/env python3
"""Unit tests for YouTube RSS parsing, VTT cleaning, brief formatting, and local sync helpers."""

from __future__ import annotations

import datetime as dt
import sys
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "functions-stock"))
sys.path.insert(0, str(ROOT / "scripts" / "stock-analysis"))

import youtube_channel as yc  # noqa: E402
import youtube_local_sync as yls  # noqa: E402


SAMPLE_ATOM_XML = b"""<?xml version="1.0" encoding="UTF-8"?>
<feed xmlns:yt="http://www.youtube.com/xml/schemas/2015"
      xmlns:media="http://search.yahoo.com/mrss/"
      xmlns="http://www.w3.org/2005/Atom">
  <author><name>\xe5\xbe\x8c\xe7\x94\xb0\xe6\xb3\xb0\xe5\xad\x9d\xe3\x81\xae\xe3\x83\x9e\xe3\x83\xbc\xe3\x82\xb1\xe3\x83\x83\xe3\x83\x88\xe8\xa7\xa3\xe8\xaa\xac</name></author>
  <entry>
    <yt:videoId>vid123</yt:videoId>
    <yt:channelId>UCgTMlZk7bL7e61Lfm3IXCvQ</yt:channelId>
    <title>\xe3\x80\x9011/28 \xe6\x9c\x9d\xe4\xbc\x9aLIVE\xe3\x80\x91\xe5\x8d\x8a\xe5\xb0\x8e\xe4\xbd\x93\xe6\xa0\xaa\xe5\x8f\x8d\xe7\x99\xba</title>
    <link rel="alternate" href="https://www.youtube.com/watch?v=vid123"/>
    <published>2026-11-28T01:30:00+00:00</published>
    <updated>2026-11-28T02:00:00+00:00</updated>
    <media:group>
      <media:description>
\xe3\x80\x90\xe7\x9b\xae\xe6\xac\xa1\xe3\x80\x91
- 00:00\xef\xbd\x9c\xe3\x82\xaa\xe3\x83\xbc\xe3\x83\x97\xe3\x83\x8b\xe3\x83\xb3\xe3\x82\xb0
- 12:34\xef\xbd\x9c\xe3\x82\xad\xe3\x82\xaa\xe3\x82\xaf\xe3\x82\xb7\xe3\x82\xa2\xe3\x81\xa8\xe5\x8d\x8a\xe5\xb0\x8e\xe4\xbd\x93\xe6\xa0\xaa
\xe3\x83\x81\xe3\x83\xa3\xe3\x83\xb3\xe3\x83\x8d\xe3\x83\xab\xe7\x99\xbb\xe9\x8c\xb2\xe3\x82\x82\xe3\x81\x8a\xe9\xa1\x98\xe3\x81\x84\xe3\x81\x97\xe3\x81\xbe\xe3\x81\x99
      </media:description>
    </media:group>
  </entry>
</feed>
"""


class FakeDocSnap:
    def __init__(self, doc_id: str, data: dict):
        self.id = doc_id
        self._data = data
        self.exists = True

    def to_dict(self):
        return dict(self._data)


class FakeCollection:
    def __init__(self, snaps):
        self._snaps = snaps

    def stream(self):
        return list(self._snaps)


class FakeDB:
    def __init__(self, collections):
        self._collections = collections

    def collection(self, name: str):
        return FakeCollection(self._collections.get(name, []))


class YouTubeChannelTests(unittest.TestCase):
    def test_parse_youtube_rss_extracts_metadata_and_chapters(self):
        entries = yc.parse_youtube_rss(SAMPLE_ATOM_XML)
        self.assertEqual(len(entries), 1)
        e = entries[0]
        self.assertEqual(e["videoId"], "vid123")
        self.assertEqual(e["channelName"], "後田泰孝のマーケット解説")
        self.assertEqual(
            e["chapters"],
            ["00:00｜オープニング", "12:34｜キオクシアと半導体株"],
        )

    def test_clean_vtt_transcript_deduplicates_rolling_lines_and_tags(self):
        raw_vtt = """WEBVTT
Kind: captions
Language: ja

00:00:02.000 --> 00:00:05.000
[音楽]

00:00:05.000 --> 00:00:08.000
おはようございます<00:00:06.100><c> 今日は</c>

00:00:08.000 --> 00:00:11.000
おはようございます 今日は日経平均が大幅高です

00:00:11.000 --> 00:00:13.000
おはようございます 今日は日経平均が大幅高です

00:00:13.000 --> 00:00:16.000
NVIDIAの公聴会に注意が必要です
"""
        cleaned = yls.clean_vtt_transcript(raw_vtt)
        lines = cleaned.splitlines()
        self.assertEqual(
            lines,
            [
                "おはようございます 今日は日経平均が大幅高です",
                "NVIDIAの公聴会に注意が必要です",
            ],
        )

    def test_parse_summary_output_unwraps_cli_envelope_and_fence(self):
        cli_json = (
            '{"response": "```json\\n'
            '{\\"marketMood\\": \\"やや強気\\", \\"summary\\": \\"- 半導体が反発\\", '
            '\\"mentionedSymbols\\": [\\"NVDA\\", \\"285A\\"], \\"keyDrivers\\": [\\"金利低下\\"], \\"risks\\": [\\"BIS規制\\"]}'
            '\\n```"}'
        )
        parsed = yls.parse_summary_output(cli_json)
        self.assertEqual(parsed["marketMood"], "やや強気")
        self.assertEqual(parsed["summary"], "- 半導体が反発")
        self.assertEqual(parsed["mentionedSymbols"], ["NVDA", "285A"])

    def test_load_recent_channel_briefs_filters_age_and_formats(self):
        now = dt.datetime(2026, 11, 28, 12, 0, tzinfo=dt.timezone.utc)
        fresh = FakeDocSnap(
            "fresh1",
            {
                "videoId": "fresh1",
                "channelName": "後田泰孝のマーケット解説",
                "title": "11/28 朝会",
                "published": "2026-11-28T01:30:00+00:00",
                "summary": "- SOX反発、来週の雇用統計に注意",
                "mentionedSymbols": ["NVDA", "AMD"],
            },
        )
        stale = FakeDocSnap(
            "stale1",
            {
                "videoId": "stale1",
                "channelName": "後田泰孝のマーケット解説",
                "title": "古い動画",
                "published": "2026-11-20T01:30:00+00:00",
                "summary": "- 古い内容",
            },
        )
        db = FakeDB({yc.COLLECTION_CHANNEL_BRIEFS: [fresh, stale]})
        out = yc.load_recent_channel_briefs(
            db, max_age_hours=48, now_utc=now, rss_fallback=False
        )
        self.assertIn("11/28 朝会", out)
        self.assertIn("SOX反発", out)
        self.assertIn("NVDA, AMD", out)
        self.assertNotIn("古い動画", out)


if __name__ == "__main__":
    unittest.main()
