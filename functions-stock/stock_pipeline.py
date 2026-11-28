#!/usr/bin/env python3
"""Automated Stock Analysis Pipeline for Hibi.

Reads Agent Skills from `.agents/skills/*/SKILL.md`, invokes Gemini with optional
Google Search grounding, persists daily/weekly reports and schedule updates to
Firestore, and dispatches FCM alerts when BUY/ADD/GO triggers occur.

Security note (for Public repository execution):
Never print ticker symbols, scenario contents, API responses, or secrets to
stdout/stderr so CI/Cloud logs remain free of sensitive portfolio data.
"""

from __future__ import annotations

import argparse
import datetime as dt
import os
import re
import sys
from pathlib import Path
from typing import Any

from firestore_store import (
    check_automation_guard,
    fetch_usd_jpy_rate,
    format_knowledge_for_prompt,
    init_firestore,
    load_active_knowledge,
    load_active_scenarios,
    load_recent_reports_summary,
    record_usage_to_firestore,
    save_learned_knowledge,
    send_go_alert_push,
    should_run_automation,
)
from gemini_client import (
    DEFAULT_ACTION_MODEL,
    DEFAULT_MARKET_MODEL,
    DEFAULT_MODEL,
    DEFAULT_MONTHLY_BUDGET_JPY,
    DEFAULT_USD_TO_JPY,
    FALLBACK_MODEL,
    GeminiClient,
    calculate_cost_usd,
    extract_answer_text,
    get_model_rates_usd_per_million,
)
from scenario_parser import (
    AI_KNOWLEDGE_TAG,
    ALLOWED_KNOWLEDGE_CATEGORIES,
    JST,
    MAX_AI_LESSONS,
    MAX_INJECTED_KNOWLEDGE_CHARS,
    MAX_INJECTED_KNOWLEDGE_ITEMS,
    VALID_VERDICTS,
    default_split_steps,
    effective_scenario_status,
    ensure_concrete_schedule,
    merge_updated_steps,
    normalize_learned_knowledge,
    normalize_steps,
    normalize_symbol_key,
    parse_action_payload,
    parse_learned_knowledge,
    parse_verdicts_json,
    strip_verdicts_block,
)
from scenario_parser import (
    now_jst as _parser_now_jst,
)
from youtube_channel import load_recent_channel_briefs

REPO_ROOT = Path(__file__).resolve().parents[1]
_BUNDLED_SKILLS_DIR = Path(__file__).resolve().parent / "skills"
SKILLS_DIR = _BUNDLED_SKILLS_DIR if _BUNDLED_SKILLS_DIR.exists() else (REPO_ROOT / ".agents" / "skills")


def now_jst() -> dt.datetime:
    return _parser_now_jst()


def load_skill_prompt(skill_name: str, skills_dir: Path = SKILLS_DIR) -> str:
    """Load SKILL.md markdown body with YAML frontmatter stripped."""
    skill_path = skills_dir / skill_name / "SKILL.md"
    if not skill_path.exists():
        raise FileNotFoundError(f"Skill file not found: {skill_path}")
    raw = skill_path.read_text(encoding="utf-8")
    if raw.startswith("---"):
        match = re.match(r"^---\s*\n.*?\n---\s*\n?", raw, flags=re.DOTALL)
        if match:
            return raw[match.end():].strip()
    return raw.strip()


def run_daily(
    db: Any,
    action_gemini: GeminiClient,
    usd_to_jpy: float = DEFAULT_USD_TO_JPY,
) -> None:
    """Single-call weekday morning check for HOLDING/WATCH verdicts and schedule updates."""
    now = now_jst()
    date_str = now.strftime("%Y-%m-%d")
    ts_label = now.strftime("%Y年%m月%d日 (%a) %H:%M JST")
    utc_now = dt.datetime.now(dt.timezone.utc).isoformat()

    ctx = load_active_scenarios(db)
    active_count: int = ctx["active_count"]
    holding_lines: list[str] = ctx["holding_lines"]
    watch_lines: list[str] = ctx["watch_lines"]
    symbol_to_doc_ref: dict[str, Any] = ctx["symbol_to_doc_ref"]
    symbol_to_existing_steps: dict[str, list[dict[str, Any]]] = ctx["symbol_to_existing_steps"]
    symbol_to_horizon: dict[str, str] = ctx["symbol_to_horizon"]

    if active_count == 0:
        print("[INFO] No active HOLDING/WATCH scenarios; skipping daily action check.")
        return

    print(f"[INFO] Running daily portfolio action check (holding={len(holding_lines)}, watch={len(watch_lines)})...")
    gatekeeper_skill = load_skill_prompt("stock-risk-gatekeeper")
    knowledge_text, _ = load_active_knowledge(db)
    knowledge_section = (
        f"## 投資原則・蓄積ナレッジ（判定時に必ず考慮すること）\n{knowledge_text}\n\n"
        if knowledge_text
        else ""
    )
    channel_briefs = load_recent_channel_briefs(db, max_age_hours=48)
    channel_section = (
        "## 参考YouTubeチャンネルの最新解説（後田泰孝のマーケット解説）\n"
        "※以下は相場の見方・注目材料・リスクの「参考ヒント」です。盲信せず、上記の投資原則および Web 検索による最新株価・決算・一次情報と必ず照合して判定してください。\n"
        f"{channel_briefs}\n\n"
        if channel_briefs
        else ""
    )

    holdings_section = "\n\n".join(holding_lines) if holding_lines else "（現在、保有中として登録された銘柄はありません）"
    watch_section = "\n\n".join(watch_lines) if watch_lines else "（現在、注目中として登録された未保有銘柄はありません）"

    action_prompt = (
        f"本日の基準日時: {ts_label}\n\n"
        "Web検索で最新の市場環境（日米指数・長期金利・ドル円・直近重要イベント）と各登録銘柄の最新株価・材料を確認し、"
        "「保有中の買い増し/撤退判断」と「Watch銘柄の買い時/継続/削除提案」を判定してください。\n"
        "※本ジョブではセクターローテーション分析や新規候補の発掘は不要です（週次レポートで実施します）。\n\n"
        f"{knowledge_section}"
        f"{channel_section}"
        f"## 1. 💼 保有中銘柄（すでに購入済み → 買い増し ADD / 保有継続 HOLD / 縮小撤退 TRIM を判定）\n{holdings_section}\n\n"
        f"## 2. 👀 注目中（WATCH）銘柄（未保有 → 購入開始 BUY / 監視継続 WATCH / 削除提案 DROP を判定）\n{watch_section}\n\n"
        "## 出力ルール\n"
        "- 0) 冒頭に『本日の地合いサマリー（日米指数・金利・為替・直近イベント）』を3〜5行で簡潔にまとめてください（参考YouTubeチャンネル情報がある場合は、裏取りできた論点や注意点を1行添えてください）。\n"
        "- 1) 保有中銘柄ごとに最新株価・前提条件を確認し、`ADD`（買い増し好機）/ `HOLD`（静観維持）/ `TRIM`（一部利確・撤退警戒）を判定してください。すでに保有中の銘柄を「未購入」と誤認しないでください。\n"
        "- 2) 注目中（WATCH）銘柄ごとに `BUY`（初回買い好機）/ `WATCH`（監視継続）/ `DROP`（削除提案）を判定してください。※削除の最終決定・実行は人間が行うため、前提崩壊が見られる場合のみ根拠を添えて『削除提案 (`DROP`)』を出してください。\n"
        "- 3) 各銘柄の `oneLiner` は必ず **『現在株価と、今買うべきか／いくらまで待つか／いつ再確認するか』** を一目でわかる1〜2文で書いてください（例: `現在592円（目標555〜575円まで約4%調整待ち）→ 12/15日銀会合前の押し目待ち`）。\n"
        "- 4) 『⚠️ スケジュール未整備』と付記されている銘柄（`DROP` 以外）や、最新株価に対して水準や日程が古くなっている銘柄は、JSON内の `updatedSteps` に **具体的な時期・`notifyDate` (`YYYY-MM-DD`, 本日以降)・価格条件入りの未購入ステップ（2〜3回分）** を必ず入れてください（すでに購入済みのステップは自動で保持されます。`DROP` または変更不要の場合のみ省略可）。\n"
        "- ※本文は「全銘柄のサマリー表 + 各銘柄の要点（3〜4行）」で簡潔にまとめ、末尾の ```json ... ``` ブロックを途中で切らさず必ず閉じてください。Markdownテーブルは行間に空行を入れないでください。\n\n"
        "出力の最後に必ず以下のJSON形式で1つだけブロックを含めてください：\n"
        "```json\n"
        "{\n"
        '  "actions": [\n'
        '    {\n'
        '      "symbol": "ティッカー",\n'
        '      "status": "HOLDING または WATCH",\n'
        '      "verdict": "ADD または HOLD または TRIM または BUY または WATCH または DROP",\n'
        '      "oneLiner": "現在株価〇〇 → 買値〇〇まで待ち／今買い好機などの一言結論",\n'
        '      "updatedSteps": [\n'
        '        {"step": 1, "period": "例: 2026年12月上旬（次回押し目）", "notifyDate": "YYYY-MM-DD", "ratio": "40%", "aim": "初回打診", "condition": "例: 575円以下への押し目確認"},\n'
        '        {"step": 2, "period": "例: 2026年12月下旬〜1月（決算/会合後）", "notifyDate": "YYYY-MM-DD", "ratio": "60%", "aim": "本玉追加", "condition": "例: 555円支持線確認または利上げ通過"}\n'
        '      ]\n'
        '    }\n'
        "  ]\n"
        "}\n"
        "```"
    )

    action_raw, action_meta = action_gemini.generate(gatekeeper_skill, action_prompt)
    record_usage_to_firestore(db, action_meta, usd_to_jpy=usd_to_jpy)

    verdicts, extra_candidates = parse_action_payload(action_raw, base_date=now.date())
    action_clean_md = strip_verdicts_block(action_raw)
    report_verdicts = [
        {k: v[k] for k in ("symbol", "status", "verdict", "oneLiner") if k in v}
        for v in verdicts
    ]

    go_count = sum(1 for v in verdicts if v["verdict"] in {"BUY", "ADD", "GO"})
    db.collection("stock_reports").document(f"action_{date_str}").set(
        {
            "id": f"action_{date_str}",
            "type": "daily_action",
            "date": date_str,
            "title": f"🎯 本日の売買・買い増し＆Watch入替 ({date_str})",
            "content": action_clean_md,
            "verdicts": report_verdicts,
            "newWatchCandidates": extra_candidates,
            "goCount": go_count,
            "scenarioCount": active_count,
            "model": action_meta.get("modelVersion", ""),
            "createdAt": utc_now,
            "updatedAt": utc_now,
        }
    )

    matched_count = 0
    steps_updated_count = 0
    handled_syms: set[str] = set()
    for v in verdicts:
        sym_u = normalize_symbol_key(v["symbol"])
        ref = symbol_to_doc_ref.get(sym_u)
        if ref is not None:
            matched_count += 1
            handled_syms.add(sym_u)
            patch: dict[str, Any] = {
                "latestVerdict": v["verdict"],
                "latestVerdictReason": v["oneLiner"],
                "latestCheckedAt": date_str,
            }
            if v["verdict"] != "DROP":
                existing_steps = symbol_to_existing_steps.get(sym_u)
                merged = (
                    merge_updated_steps(existing_steps, v["updatedSteps"], base_date=now.date())
                    if v.get("updatedSteps")
                    else None
                )
                if merged is None:
                    merged = ensure_concrete_schedule(
                        existing_steps,
                        symbol_to_horizon.get(sym_u, ""),
                        v["oneLiner"],
                        base_date=now.date(),
                    )
                if merged:
                    patch["steps"] = merged
                    steps_updated_count += 1
            ref.update(patch)

    # Backfill concrete schedule/notifyDate on any active scenario omitted from verdicts
    for sym_u, ref in symbol_to_doc_ref.items():
        if sym_u in handled_syms:
            continue
        ensured = ensure_concrete_schedule(
            symbol_to_existing_steps.get(sym_u),
            symbol_to_horizon.get(sym_u, ""),
            "",
            base_date=now.date(),
        )
        if ensured:
            ref.update({"steps": ensured})
            steps_updated_count += 1

    print(
        f"[OK] Saved daily action report (doc=action_{date_str}, checked={active_count}, "
        f"verdicts={len(verdicts)}, matched={matched_count}, steps_updated={steps_updated_count}, "
        f"extra_candidates={len(extra_candidates)})."
    )
    send_go_alert_push(db, go_count)


def run_weekly(db: Any, weekly_gemini: GeminiClient, usd_to_jpy: float = DEFAULT_USD_TO_JPY) -> None:
    """Single-call Monday weekly sector rotation & new Watch candidate discovery (medium-long term)."""
    now = now_jst()
    date_str = now.strftime("%Y-%m-%d")
    ts_label = now.strftime("%Y年%m月%d日 (%a) %H:%M JST")
    utc_now = dt.datetime.now(dt.timezone.utc).isoformat()

    ctx = load_active_scenarios(db)
    existing_symbols: list[str] = ctx["existing_symbols"]
    existing_list_text = ", ".join(existing_symbols) if existing_symbols else "なし"
    knowledge_text, existing_knowledge_titles = load_active_knowledge(db)
    recent_history = load_recent_reports_summary(db, base_date=now.date())
    channel_briefs = load_recent_channel_briefs(db, max_age_hours=72)

    knowledge_section = (
        f"## 投資原則・蓄積ナレッジ（必ず踏まえること / 重複タイトルは除外）\n{knowledge_text}\n\n"
        if knowledge_text
        else ""
    )
    channel_section = (
        "## 参考YouTubeチャンネルの解説（後田泰孝のマーケット解説）\n"
        "※注目テーマ・市場認識の参考情報です。必ず一次データ・業績バリュエーションで裏取りして選定してください。\n"
        f"{channel_briefs}\n\n"
        if channel_briefs
        else ""
    )
    history_section = (
        f"## 過去1〜4週間の提案・判定履歴（答え合わせ対象）\n{recent_history}\n\n"
        if recent_history
        else ""
    )
    existing_titles_text = ", ".join(existing_knowledge_titles) if existing_knowledge_titles else "なし"

    print(f"[INFO] Running weekly sector rotation + Watch candidate screener ({date_str})...")
    market_skill = load_skill_prompt("stock-market-analyzer")
    sector_skill = load_skill_prompt("sector-analyzer")
    screener_skill = load_skill_prompt("stock-screener")
    weekly_system = f"{market_skill}\n\n---\n\n{sector_skill}\n\n---\n\n{screener_skill}"

    weekly_prompt = (
        f"本日の基準日時: {ts_label}\n"
        "対象市場: 米国株および日本株の両方 / 投資スパン: 6ヶ月〜2〜3年の中長期（押し目分割買い）\n"
        f"現在すでに登録済みの保有/Watch銘柄（重複候補は除外すること）: {existing_list_text}\n"
        f"既存のナレッジタイトル（重複作成禁止）: {existing_titles_text}\n\n"
        f"{knowledge_section}"
        f"{channel_section}"
        f"{history_section}"
        "最新データを検索・確認し、短期的な値動きに振られない中長期視点で以下をまとめてください：\n"
        "1. **マクロ環境と中長期セクター動向**: 金利・為替・業績モメンタムを踏まえた追い風セクター（2〜3業種）と逆風セクター\n"
        "2. **過去提案の振り返り（該当履歴がある場合）**: 過去1〜4週間の Watch 候補や判定がその後どう推移したかを現在値で確認し、学びや反省を簡潔に整理してください。\n"
        "3. **中長期の次期 Watch 候補（2〜3銘柄）**:\n"
        "   - 既存登録銘柄と重複しないクオリティ/成長銘柄を選び、現在株価・業績モメンタム・バリュエーション・なぜ中長期で追い風セクターに合致するかを明記してください。\n"
        "※Markdownテーブルを書く場合は行間に空行を入れないでください。\n\n"
        "出力の最後に必ず以下のJSON形式で1つだけブロックを含めてください（`newWatchCandidates` には必ず2〜3銘柄と `notifyDate` 付き分割 `steps` を必須出力。`learnedKnowledge` は過去の振り返りから一般化できる新しい教訓がある場合のみ 0〜2件出力してください）：\n"
        "```json\n"
        "{\n"
        '  "newWatchCandidates": [\n'
        '    {\n'
        '      "symbol": "ティッカー",\n'
        '      "market": "NASDAQ や NYSE や TYO など",\n'
        '      "name": "企業名",\n'
        '      "targetHorizon": "現在値$xxx / 狙い目$yyy〜zzz（期間目安）",\n'
        '      "reason": "中長期セクターの追い風と企業優位性の要約（1〜2行）",\n'
        '      "steps": [\n'
        '        {"step": 1, "period": "例: 2026年12月上旬（直近押し目）", "notifyDate": "YYYY-MM-DD（1〜2週間後のチェック日）", "ratio": "30%", "aim": "初回打診エントリー", "condition": "例: 現在$185→$172以下または25日線タッチ"},\n'
        '        {"step": 2, "period": "例: 2026年12月中旬〜1月（決算・イベント通過後）", "notifyDate": "YYYY-MM-DD（次イベント目安日）", "ratio": "40%", "aim": "本玉追加", "condition": "例: $165〜$170支持確認またはガイダンス維持"},\n'
        '        {"step": 3, "period": "例: 2027年1月〜2月（トレンド確定後）", "notifyDate": "YYYY-MM-DD（1〜2ヶ月後）", "ratio": "30%", "aim": "上昇トレンド確認後の上乗せ", "condition": "例: 直近高値ブレイク＋出来高増加"}\n'
        '      ]\n'
        '    }\n'
        "  ],\n"
        '  "learnedKnowledge": [\n'
        '    {"title": "短い教訓タイトル（既存と重複しないこと）", "category": "チャート・テクニカル / ファンダメンタル / リスク管理 / マクロ・指標 / その他", "content": "今後の銘柄判定・選定に活かせる具体的な教訓（1〜2文）"}\n'
        "  ]\n"
        "}\n"
        "```"
    )

    weekly_raw, weekly_meta = weekly_gemini.generate(weekly_system, weekly_prompt)
    record_usage_to_firestore(db, weekly_meta, usd_to_jpy=usd_to_jpy)

    _, weekly_candidates = parse_action_payload(weekly_raw, base_date=now.date())
    lessons = parse_learned_knowledge(weekly_raw)
    k_stats = save_learned_knowledge(db, lessons) if lessons else {"added": 0, "pruned": 0}
    weekly_clean_md = strip_verdicts_block(weekly_raw)

    db.collection("stock_reports").document(f"weekly_{date_str}").set(
        {
            "id": f"weekly_{date_str}",
            "type": "weekly_screener",
            "date": date_str,
            "title": f"🔭 週次セクター＆次期 Watch 候補 ({date_str})",
            "content": weekly_clean_md,
            "newWatchCandidates": weekly_candidates,
            "model": weekly_meta.get("modelVersion", ""),
            "createdAt": utc_now,
            "updatedAt": utc_now,
        }
    )
    print(
        f"[OK] Saved weekly screener report (doc=weekly_{date_str}, candidates={len(weekly_candidates)}, "
        f"knowledge_added={k_stats['added']}, knowledge_pruned={k_stats['pruned']})."
    )


def main() -> int:
    parser = argparse.ArgumentParser(description="Hibi Stock Analysis Pipeline")
    parser.add_argument("--mode", choices=["daily", "weekly", "all"], default="daily")
    parser.add_argument("--model", default=os.environ.get("GEMINI_MODEL", ""))
    parser.add_argument("--weekly-model", "--market-model", dest="weekly_model", default=os.environ.get("GEMINI_MARKET_MODEL", DEFAULT_MARKET_MODEL))
    parser.add_argument("--action-model", default=os.environ.get("GEMINI_ACTION_MODEL", DEFAULT_ACTION_MODEL))
    args = parser.parse_args()

    gemini_key = os.environ.get("GEMINI_API_KEY", "").strip()
    sa_json = os.environ.get("FIREBASE_SERVICE_ACCOUNT_JSON", "").strip()
    use_search = os.environ.get("USE_GOOGLE_SEARCH", "true").lower() != "false"

    if not gemini_key:
        print("[ERROR] GEMINI_API_KEY is not set.", file=sys.stderr)
        return 1
    if not sa_json:
        print("[ERROR] FIREBASE_SERVICE_ACCOUNT_JSON is not set.", file=sys.stderr)
        return 1

    db = init_firestore(sa_json)
    fx_rate = fetch_usd_jpy_rate()
    allowed, reason = check_automation_guard(db, usd_to_jpy=fx_rate)
    if not allowed:
        print(f"[SKIP] {reason}")
        return 0

    override_model = args.model.strip()
    weekly_model = override_model or args.weekly_model.strip() or DEFAULT_MARKET_MODEL
    action_model = override_model or args.action_model.strip() or DEFAULT_ACTION_MODEL

    if args.mode in ("weekly", "all"):
        weekly_gemini = GeminiClient(api_key=gemini_key, model=weekly_model, use_google_search=use_search)
        run_weekly(db, weekly_gemini=weekly_gemini, usd_to_jpy=fx_rate)

    if args.mode in ("daily", "all"):
        if args.mode == "all":
            allowed, reason = check_automation_guard(db, usd_to_jpy=fx_rate)
            if not allowed:
                print(f"[SKIP] {reason}")
                return 0
        action_gemini = GeminiClient(api_key=gemini_key, model=action_model, use_google_search=use_search)
        run_daily(db, action_gemini=action_gemini, usd_to_jpy=fx_rate)

    print("[DONE] Stock analysis pipeline completed.")
    return 0


__all__ = [
    "AI_KNOWLEDGE_TAG",
    "ALLOWED_KNOWLEDGE_CATEGORIES",
    "DEFAULT_ACTION_MODEL",
    "DEFAULT_MARKET_MODEL",
    "DEFAULT_MODEL",
    "DEFAULT_MONTHLY_BUDGET_JPY",
    "DEFAULT_USD_TO_JPY",
    "FALLBACK_MODEL",
    "GeminiClient",
    "JST",
    "MAX_AI_LESSONS",
    "MAX_INJECTED_KNOWLEDGE_CHARS",
    "MAX_INJECTED_KNOWLEDGE_ITEMS",
    "REPO_ROOT",
    "SKILLS_DIR",
    "VALID_VERDICTS",
    "calculate_cost_usd",
    "check_automation_guard",
    "default_split_steps",
    "effective_scenario_status",
    "ensure_concrete_schedule",
    "extract_answer_text",
    "fetch_usd_jpy_rate",
    "format_knowledge_for_prompt",
    "get_model_rates_usd_per_million",
    "init_firestore",
    "load_active_knowledge",
    "load_active_scenarios",
    "load_recent_reports_summary",
    "load_skill_prompt",
    "main",
    "merge_updated_steps",
    "normalize_learned_knowledge",
    "normalize_steps",
    "normalize_symbol_key",
    "now_jst",
    "parse_action_payload",
    "parse_learned_knowledge",
    "parse_verdicts_json",
    "record_usage_to_firestore",
    "run_daily",
    "run_weekly",
    "save_learned_knowledge",
    "send_go_alert_push",
    "should_run_automation",
    "strip_verdicts_block",
]


if __name__ == "__main__":
    raise SystemExit(main())
