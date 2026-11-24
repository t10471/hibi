#!/usr/bin/env python3
"""Automated Stock Analysis Pipeline for Hibi.

Reads Agent Skills from `.agents/skills/*/SKILL.md`, calls the Gemini API with
optional Google Search grounding, writes private reports to Firebase Firestore
(`stock_reports` and `stock_scenarios`), records token/cost metrics to
`gemini_usage/{YYYY-MM}`, and sends FCM alerts on GO signals.

Security note (for Public repository execution):
Never print ticker symbols, scenario contents, API responses, or secrets to
stdout/stderr so GitHub Actions logs remain free of sensitive portfolio data.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import os
import re
import sys
from pathlib import Path
from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    import requests

JST = dt.timezone(dt.timedelta(hours=9))
REPO_ROOT = Path(__file__).resolve().parents[2]
SKILLS_DIR = REPO_ROOT / ".agents" / "skills"

DEFAULT_MODEL = "gemini-4-hydrogen"
DEFAULT_MARKET_MODEL = "gemini-4-hydrogen"
DEFAULT_ACTION_MODEL = "gemini-4-neon"
FALLBACK_MODEL = "gemini-flash-latest"
DEFAULT_USD_TO_JPY = 159.0
DEFAULT_MONTHLY_BUDGET_JPY = 1000


def now_jst() -> dt.datetime:
    return dt.datetime.now(JST)


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


def get_model_rates_usd_per_million(model: str) -> tuple[float, float, float]:
    """Return (input, cached_input, output) USD rates per 1M tokens."""
    m = (model or "").lower()
    if "neon" in m:
        return (3.00, 0.15, 15.00)
    if "hydrogen" in m:
        return (1.50, 0.075, 7.50)
    if "pro" in m:
        return (1.25, 0.3125, 10.0)
    if "3.8-flash" in m:
        return (0.75, 0.075, 3.75)
    if "3.5-flash-lite" in m:
        return (0.30, 0.03, 2.50)
    if "2.0-flash" in m or "flash-lite" in m:
        return (0.10, 0.025, 0.40)
    return (0.30, 0.03, 2.50)


def calculate_cost_usd(
    model: str,
    prompt_tokens: int,
    candidates_tokens: int,
    thinking_tokens: int,
    cached_tokens: int = 0,
) -> float:
    input_rate, cached_rate, output_rate = get_model_rates_usd_per_million(model)
    non_cached_input = max(0, prompt_tokens - cached_tokens)
    total_output = max(0, candidates_tokens) + max(0, thinking_tokens)
    return (
        non_cached_input * input_rate
        + max(0, cached_tokens) * cached_rate
        + total_output * output_rate
    ) / 1_000_000.0


def extract_answer_text(response_json: dict[str, Any]) -> str:
    """Extract final text parts from Gemini response, ignoring internal thought parts."""
    candidates = response_json.get("candidates") or []
    if not candidates:
        return ""
    parts = (candidates[0].get("content") or {}).get("parts") or []
    texts = [
        part.get("text", "")
        for part in parts
        if not part.get("thought", False) and isinstance(part.get("text"), str) and part.get("text").strip()
    ]
    return "\n\n".join(texts).strip()


VALID_VERDICTS = {"BUY", "ADD", "HOLD", "WATCH", "TRIM", "DROP", "GO", "WAIT", "ABORT"}


def effective_scenario_status(doc_dict: dict[str, Any]) -> str:
    raw = str(doc_dict.get("status") or "").upper().strip()
    if raw in {"HOLDING", "WATCH", "ARCHIVED"}:
        return raw
    steps = doc_dict.get("steps") or []
    return "HOLDING" if any(bool(s.get("isDone")) for s in steps if isinstance(s, dict)) else "WATCH"


def _resolve_notify_date(raw_date: str, offset_days: int, base_date: dt.date) -> str:
    cleaned = raw_date.strip()
    if re.match(r"^\d{4}-\d{2}-\d{2}$", cleaned):
        return cleaned
    return (base_date + dt.timedelta(days=offset_days)).strftime("%Y-%m-%d")


def normalize_steps(
    raw_steps: Any,
    start_index: int = 1,
    base_date: dt.date | None = None,
) -> list[dict[str, Any]]:
    """Normalize step objects into the Firestore BuyStep schema (always unpurchased)."""
    if not isinstance(raw_steps, list):
        return []
    ref_date = base_date or now_jst().date()
    steps: list[dict[str, Any]] = []
    idx = start_index
    for item in raw_steps:
        if not isinstance(item, dict):
            continue
        period = str(item.get("period", "")).strip()
        raw_notify = str(item.get("notifyDate", "")).strip()
        ratio = str(item.get("ratio", "")).strip()
        aim = str(item.get("aim", "")).strip()
        condition = str(item.get("condition", "")).strip()
        if not (aim or condition or period):
            continue
        offset = max(1, idx - start_index + 1) * 14 - 7
        notify_date = _resolve_notify_date(raw_notify, offset_days=offset, base_date=ref_date)
        steps.append(
            {
                "step": idx,
                "period": period or f"{notify_date} 頃（目安チェック）",
                "notifyDate": notify_date,
                "ratio": ratio or "33%",
                "aim": aim or condition or period,
                "condition": condition or aim or period,
                "isNotified": False,
                "isDone": False,
            }
        )
        idx += 1
    return steps


def default_split_steps(
    reason: str,
    target_horizon: str,
    base_date: dt.date | None = None,
) -> list[dict[str, Any]]:
    """Fallback 2-tranche schedule with concrete notification dates when AI omits steps."""
    ref_date = base_date or now_jst().date()
    d1 = (ref_date + dt.timedelta(days=7)).strftime("%Y-%m-%d")
    d2 = (ref_date + dt.timedelta(days=30)).strftime("%Y-%m-%d")
    cond = target_horizon or "支持帯での下げ止まり確認"
    return [
        {
            "step": 1,
            "period": f"{ref_date.strftime('%Y年%m月')}（1週間後チェック）",
            "notifyDate": d1,
            "ratio": "40%",
            "aim": reason or "地合い連動の初回打診エントリー",
            "condition": cond,
            "isNotified": False,
            "isDone": False,
        },
        {
            "step": 2,
            "period": f"{(ref_date + dt.timedelta(days=30)).strftime('%Y年%m月')}（決算・トレンド確認後）",
            "notifyDate": d2,
            "ratio": "60%",
            "aim": "業績モメンタム確認後の本玉追加",
            "condition": cond,
            "isNotified": False,
            "isDone": False,
        },
    ]


def merge_updated_steps(
    existing_steps: Any,
    raw_updated_steps: Any,
    base_date: dt.date | None = None,
) -> list[dict[str, Any]] | None:
    """Preserve already-purchased steps (isDone=True) and replace pending steps."""
    done_steps: list[dict[str, Any]] = []
    for s in existing_steps or []:
        if isinstance(s, dict) and s.get("isDone"):
            copied = dict(s)
            copied["step"] = len(done_steps) + 1
            done_steps.append(copied)

    pending = normalize_steps(raw_updated_steps, start_index=len(done_steps) + 1, base_date=base_date)
    if not pending:
        return None
    return done_steps + pending


def ensure_concrete_schedule(
    existing_steps: Any,
    target_horizon: str,
    reason: str,
    base_date: dt.date | None = None,
) -> list[dict[str, Any]] | None:
    """Guarantee >=2 steps and valid YYYY-MM-DD notifyDate when AI omits updatedSteps."""
    ref_date = base_date or now_jst().date()
    steps = [dict(s) for s in (existing_steps or []) if isinstance(s, dict)]
    if len(steps) <= 1:
        if len(steps) == 1 and steps[0].get("isDone"):
            done = dict(steps[0])
            done["step"] = 1
            extra = default_split_steps(reason, target_horizon, base_date=ref_date)[1]
            extra["step"] = 2
            return [done, extra]
        return default_split_steps(reason, target_horizon, base_date=ref_date)

    changed = False
    for idx, s in enumerate(steps):
        raw_notify = str(s.get("notifyDate") or "").strip()
        if not s.get("isDone") and not re.match(r"^\d{4}-\d{2}-\d{2}$", raw_notify):
            s["notifyDate"] = _resolve_notify_date(raw_notify, offset_days=(idx + 1) * 14 - 7, base_date=ref_date)
            changed = True
    return steps if changed else None


def normalize_symbol_key(raw: str) -> str:
    """Normalize ticker representations like '8766:TYO', '8766.T', or 'Vertiv (VRT)'."""
    s = (raw or "").strip().upper()
    if not s:
        return ""
    paren = re.search(r"\(([A-Z0-9._:-]+)\)", s)
    if paren:
        s = paren.group(1)
    s = s.split(":", 1)[0].strip()
    s = re.sub(r"\.(T|TYO|JP|US)$", "", s)
    return s


def _recover_partial_actions(raw_json: str) -> list[dict[str, Any]]:
    """Recover complete action objects from a truncated JSON payload if possible."""
    m = re.search(r'"actions"\s*:\s*\[', raw_json)
    if not m:
        return []
    pos = m.end()
    decoder = json.JSONDecoder()
    recovered: list[dict[str, Any]] = []
    n = len(raw_json)
    while pos < n:
        while pos < n and raw_json[pos] in " \t\r\n,":
            pos += 1
        if pos >= n or raw_json[pos] == "]":
            break
        try:
            obj, next_pos = decoder.raw_decode(raw_json, pos)
            if isinstance(obj, dict):
                recovered.append(obj)
            pos = next_pos
        except json.JSONDecodeError:
            break
    return recovered


def parse_action_payload(
    text: str,
    base_date: dt.date | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Extract trailing JSON action/candidate payload from markdown output."""
    matches = list(re.finditer(r"```(?:json)?\s*(\{.*?\}|\[.*?\])\s*```", text, flags=re.DOTALL | re.IGNORECASE))
    parsed: Any = None
    for m in reversed(matches):
        candidate_json = re.sub(r",\s*([}\]])", r"\1", m.group(1))
        try:
            obj = json.loads(candidate_json)
            if isinstance(obj, (list, dict)):
                parsed = obj
                break
        except Exception:
            continue

    if parsed is None:
        # Fallback for unclosed ```json fence or unfenced trailing JSON
        unclosed = re.search(r"```(?:json)?\s*(\{.*|\[.*)$", text, flags=re.DOTALL | re.IGNORECASE)
        raw_tail = unclosed.group(1) if unclosed else ""
        if not raw_tail:
            unfenced = re.search(r'(\{\s*"actions"\s*:.*)', text, flags=re.DOTALL)
            raw_tail = unfenced.group(1) if unfenced else ""
        if raw_tail:
            cleaned_tail = re.sub(r",\s*([}\]])", r"\1", raw_tail.strip())
            try:
                obj = json.loads(cleaned_tail)
                if isinstance(obj, (list, dict)):
                    parsed = obj
            except Exception:
                partial_actions = _recover_partial_actions(cleaned_tail)
                if partial_actions:
                    parsed = {"actions": partial_actions, "newWatchCandidates": []}

    if parsed is None:
        return ([], [])

    if isinstance(parsed, list):
        raw_actions = parsed
        raw_candidates: list[Any] = []
    elif isinstance(parsed, dict):
        raw_actions = parsed.get("actions") if isinstance(parsed.get("actions"), list) else []
        raw_candidates = (
            parsed.get("newWatchCandidates") if isinstance(parsed.get("newWatchCandidates"), list) else []
        )
    else:
        return ([], [])

    actions: list[dict[str, Any]] = []
    for item in raw_actions:
        if not isinstance(item, dict):
            continue
        verdict = str(item.get("verdict", "")).upper().strip()
        if verdict in VALID_VERDICTS:
            action_entry: dict[str, Any] = {
                "symbol": str(item.get("symbol", "")).strip(),
                "status": str(item.get("status", "")).upper().strip(),
                "verdict": verdict,
                "oneLiner": str(item.get("oneLiner", "")).strip(),
            }
            updated_steps = normalize_steps(item.get("updatedSteps"), base_date=base_date)
            if updated_steps:
                action_entry["updatedSteps"] = updated_steps
            actions.append(action_entry)

    candidates: list[dict[str, Any]] = []
    for item in raw_candidates:
        if not isinstance(item, dict):
            continue
        sym = str(item.get("symbol", "")).strip().upper()
        if not sym:
            continue
        target_horizon = str(item.get("targetHorizon", "")).strip()
        reason = str(item.get("reason", "")).strip()
        steps = normalize_steps(item.get("steps"), base_date=base_date) or default_split_steps(
            reason, target_horizon, base_date=base_date
        )
        candidates.append(
            {
                "symbol": sym,
                "market": (str(item.get("market", "NASDAQ")).strip() or "NASDAQ").upper(),
                "name": str(item.get("name", sym)).strip() or sym,
                "targetHorizon": target_horizon,
                "reason": reason,
                "steps": steps,
            }
        )

    return (actions, candidates)


def parse_verdicts_json(text: str) -> list[dict[str, str]]:
    actions, _ = parse_action_payload(text)
    return actions


def strip_verdicts_block(text: str) -> str:
    cleaned = re.sub(r"\n*```(?:json)?\s*(?:\{.*?\}|\[.*?\])\s*```\s*$", "", text, flags=re.DOTALL | re.IGNORECASE)
    cleaned = re.sub(r"\n*```(?:json)?\s*(?:\{|\[).*$", "", cleaned, flags=re.DOTALL | re.IGNORECASE)
    return cleaned.strip()


class GeminiClient:
    """Calls Gemini REST API with optional Google Search grounding and usage recording."""

    def __init__(self, api_key: str, model: str = DEFAULT_MODEL, use_google_search: bool = True) -> None:
        self.api_key = api_key.strip()
        self.model = model.strip() or DEFAULT_MODEL
        self.use_google_search = use_google_search

    def generate(self, system_prompt: str, user_prompt: str) -> tuple[str, dict[str, Any]]:
        import requests

        models_to_try = [self.model]
        if self.model != FALLBACK_MODEL:
            models_to_try.append(FALLBACK_MODEL)

        last_status = 0
        for idx, model in enumerate(models_to_try):
            use_search = self.use_google_search
            try:
                resp = self._post(model, system_prompt, user_prompt, use_search)
            except requests.exceptions.Timeout:
                if idx + 1 < len(models_to_try):
                    print(f"[WARN] Primary model {model} timed out; falling back.")
                    continue
                raise
            last_status = resp.status_code

            # Retry once without google_search if the key tier rejects search grounding
            if resp.status_code in (400, 403) and use_search:
                print(f"[WARN] Search grounding unavailable on current tier (HTTP {resp.status_code}); retrying without search tool.")
                try:
                    resp = self._post(model, system_prompt, user_prompt, False)
                except requests.exceptions.Timeout:
                    if idx + 1 < len(models_to_try):
                        print(f"[WARN] Primary model {model} timed out without search; falling back.")
                        continue
                    raise
                last_status = resp.status_code

            if resp.status_code in (400, 404) and idx + 1 < len(models_to_try):
                print(f"[WARN] Primary model returned HTTP {resp.status_code}; falling back.")
                continue

            if not resp.ok:
                raise RuntimeError(f"Gemini API request failed with HTTP {resp.status_code}")

            data = resp.json()
            answer = extract_answer_text(data)
            candidates = data.get("candidates") or []
            finish_reason = str(candidates[0].get("finishReason", "")) if candidates else ""
            if finish_reason == "MAX_TOKENS":
                print(f"[WARN] Gemini output truncated at maxOutputTokens (model={model}).")
            return answer, {
                "usageMetadata": data.get("usageMetadata") or {},
                "modelVersion": data.get("modelVersion") or model,
                "finishReason": finish_reason,
            }

        raise RuntimeError(f"All Gemini model attempts failed (last HTTP {last_status})")

    def _post(self, model: str, system_prompt: str, user_prompt: str, use_search: bool) -> "requests.Response":
        import requests

        is_hydrogen = "hydrogen" in model.lower()
        gen_config: dict[str, Any] = {"maxOutputTokens": 16384}
        if is_hydrogen:
            gen_config["thinkingConfig"] = {"thinkingLevel": "HIGH"}
        else:
            gen_config["temperature"] = 0.3

        payload: dict[str, Any] = {
            "systemInstruction": {"parts": [{"text": system_prompt}]},
            "contents": [{"role": "user", "parts": [{"text": user_prompt}]}],
            "generationConfig": gen_config,
        }
        if use_search:
            payload["tools"] = [{"google_search": {}}]

        url = f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent"
        return requests.post(
            url,
            headers={
                "Content-Type": "application/json",
                "X-goog-api-key": self.api_key,
            },
            json=payload,
            timeout=300,
        )


def init_firestore(service_account_json: str):
    import firebase_admin
    from firebase_admin import credentials, firestore

    cred_dict = json.loads(service_account_json)
    if not firebase_admin._apps:
        cred = credentials.Certificate(cred_dict)
        firebase_admin.initialize_app(cred)
    return firestore.client()


def fetch_usd_jpy_rate(fallback: float = DEFAULT_USD_TO_JPY) -> float:
    """Fetch current USD/JPY spot rate from free public endpoints, falling back safely."""
    import requests

    endpoints = [
        ("https://api.frankfurter.dev/v1/latest?base=USD&symbols=JPY", lambda d: (d.get("rates") or {}).get("JPY")),
        ("https://open.er-api.com/v6/latest/USD", lambda d: (d.get("rates") or {}).get("JPY")),
    ]
    for url, extractor in endpoints:
        try:
            resp = requests.get(url, timeout=5)
            if resp.status_code == 200:
                val = extractor(resp.json())
                rate = float(val) if val is not None else 0.0
                if 80.0 <= rate <= 300.0:
                    return round(rate, 2)
        except Exception:
            continue
    return fallback


def record_usage_to_firestore(db: Any, meta: dict[str, Any], usd_to_jpy: float = DEFAULT_USD_TO_JPY) -> None:
    from firebase_admin import firestore

    usage = meta.get("usageMetadata") or {}
    if not usage:
        return
    model_version = str(meta.get("modelVersion") or DEFAULT_MODEL)
    prompt_tokens = int(usage.get("promptTokenCount") or 0)
    candidates_tokens = int(usage.get("candidatesTokenCount") or 0)
    thinking_tokens = int(usage.get("thoughtsTokenCount") or 0)
    cached_tokens = int(usage.get("cachedContentTokenCount") or 0)
    output_tokens = candidates_tokens + thinking_tokens
    cost_usd = calculate_cost_usd(model_version, prompt_tokens, candidates_tokens, thinking_tokens, cached_tokens)

    now = now_jst()
    month_key = now.strftime("%Y-%m")
    ref = db.collection("gemini_usage").document(month_key)
    ref.set(
        {
            "month": month_key,
            "requestCount": firestore.Increment(1),
            "promptTokens": firestore.Increment(prompt_tokens),
            "outputTokens": firestore.Increment(output_tokens),
            "thinkingTokens": firestore.Increment(thinking_tokens),
            "cachedTokens": firestore.Increment(cached_tokens),
            "estimatedCostUsd": firestore.Increment(cost_usd),
            "stockRequests": firestore.Increment(1),
            "stockCostUsd": firestore.Increment(cost_usd),
            "batchRequests": firestore.Increment(1),
            "batchCostUsd": firestore.Increment(cost_usd),
            "usdToJpyRate": usd_to_jpy,
            "lastModel": model_version,
            "updatedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
        },
        merge=True,
    )


def send_go_alert_push(db: Any, go_count: int) -> None:
    """Send a discreet FCM push notification when 1 or more scenarios reach GO."""
    if go_count <= 0:
        return
    from firebase_admin import messaging

    tokens = [d.id for d in db.collection("fcm_tokens").stream()]
    if not tokens:
        print("[INFO] No FCM tokens registered; skipping push alert.")
        return

    msg = messaging.MulticastMessage(
        tokens=tokens,
        notification=messaging.Notification(
            title=f"📈 株アクション通知: 買い・買い増しサイン ({go_count}件)",
            body="本日の地合い連動チェックで BUY / ADD 判定の銘柄があります。Hibi の AIレポートを確認してください。",
        ),
        android=messaging.AndroidConfig(
            priority="high",
            notification=messaging.AndroidNotification(channel_id="stock_reminders"),
        ),
        data={"nav_tab": "stock"},
    )
    res = messaging.send_each_for_multicast(msg)
    print(f"[INFO] Sent GO push alert (success={res.success_count}, failure={res.failure_count}).")


def run_daily(
    db: Any,
    market_gemini: GeminiClient,
    action_gemini: GeminiClient | None = None,
    usd_to_jpy: float = DEFAULT_USD_TO_JPY,
) -> None:
    if action_gemini is None:
        action_gemini = market_gemini
    now = now_jst()
    date_str = now.strftime("%Y-%m-%d")
    ts_label = now.strftime("%Y年%m月%d日 (%a) %H:%M JST")
    utc_now = dt.datetime.now(dt.timezone.utc).isoformat()

    # Load active scenarios first so Step 1 can avoid proposing duplicates
    scenario_docs = list(db.collection("stock_scenarios").stream())
    holding_lines: list[str] = []
    watch_lines: list[str] = []
    existing_symbols: list[str] = []
    symbol_to_doc_ref: dict[str, Any] = {}
    symbol_to_existing_steps: dict[str, list[dict[str, Any]]] = {}
    symbol_to_horizon: dict[str, str] = {}
    active_count = 0

    for doc in scenario_docs:
        d = doc.to_dict() or {}
        st = effective_scenario_status(d)
        if st == "ARCHIVED":
            continue
        active_count += 1
        symbol = str(d.get("symbol", "")).strip()
        market = str(d.get("market", "")).strip()
        name = str(d.get("name", "")).strip()
        holding_qty = str(d.get("holdingQuantity", "")).strip()
        average_cost = str(d.get("averageCost", "")).strip()
        holding_note = str(d.get("holdingNote", "")).strip()
        raw_steps = [s for s in (d.get("steps") or []) if isinstance(s, dict)]
        if symbol:
            sym_key = normalize_symbol_key(symbol)
            symbol_to_doc_ref[sym_key] = doc.reference
            symbol_to_existing_steps[sym_key] = raw_steps
            symbol_to_horizon[sym_key] = str(d.get("targetHorizon", "")).strip()
            existing_symbols.append(f"{name} ({symbol}:{market}) [{st}]")

        steps_desc = []
        for s in d.get("steps") or []:
            done_label = "購入済" if s.get("isDone") else "未購入"
            steps_desc.append(
                f"  - 第{s.get('step')}回 ({s.get('ratio')} / 通知予定:{s.get('notifyDate') or '-'} / {done_label}): "
                f"{s.get('period')} / 狙い: {s.get('aim')} / 条件: {s.get('condition')}"
            )
        holding_parts = []
        if holding_qty:
            holding_parts.append(f"保有数: {holding_qty}")
        if average_cost:
            holding_parts.append(f"平均取得: {average_cost}")
        if holding_note:
            holding_parts.append(f"メモ: {holding_note}")
        holding_line = f"- 保有状況: {' / '.join(holding_parts)}\n" if holding_parts else ""
        needs_schedule = (
            len(raw_steps) <= 1
            or any(not re.match(r"^\d{4}-\d{2}-\d{2}$", str(s.get("notifyDate") or "").strip()) for s in raw_steps)
        )
        schedule_note = (
            "- 購入スケジュール状態: ⚠️ スケジュール未整備（DROP以外なら updatedSteps に具体的な日付・notifyDate・価格条件入りで2〜3回の分割ステップを必ず出力すること）\n"
            if needs_schedule
            else ""
        )
        entry = (
            f"### {name} ({symbol}:{market}) [ステータス: {st}]\n"
            + holding_line
            + schedule_note
            + f"- 目標・期間: {d.get('targetHorizon')}\n"
            + f"- 要約: {d.get('summary')}\n"
            + "\n".join(steps_desc)
        )
        if st == "HOLDING":
            holding_lines.append(entry)
        else:
            watch_lines.append(entry)

    # 1. Market & sector flow analyzer + next Watch candidates
    print(f"[INFO] Running market + sector flow analyzer ({date_str})...")
    market_skill = load_skill_prompt("stock-market-analyzer")
    sector_skill = load_skill_prompt("sector-analyzer")
    screener_skill = load_skill_prompt("stock-screener")
    step1_system = f"{market_skill}\n\n---\n\n{sector_skill}"
    existing_list_text = ", ".join(existing_symbols) if existing_symbols else "なし"
    market_prompt = (
        f"本日の基準日時: {ts_label}\n"
        "対象市場: 米国株および日本株の両方\n"
        f"現在すでに登録済みの保有/Watch銘柄（重複候補は除外すること）: {existing_list_text}\n\n"
        "最新データを検索・確認し、以下を簡潔かつ構造化してまとめてください：\n"
        "1. 本日の相場地合い・主要指標・変動要因・注目イベント\n"
        "2. いま資金が向かっている強いセクター/テーマ（2〜3個）と逆風のセクター\n"
        "3. **上記の地合い・強いセクターから直結する「次の Watch 候補（2〜3銘柄）」**\n"
        "   - すでに登録済みの銘柄とは重複しない具体的な上場銘柄を選び、現在株価水準・なぜ今の地合いに合うか・狙い目条件を明記してください。\n"
        "※Markdownテーブルを書く場合は行間に空行を入れないでください。\n\n"
        "出力の最後に必ず以下のJSON形式で1つだけブロックを含めてください（`newWatchCandidates` には必ず2〜3銘柄を含め、各銘柄に「いつ・いくらなら買うか」が明確になるよう具体的な時期と `notifyDate` (`YYYY-MM-DD`, 本日以降のリマインド日) を入れた2〜3回の分割 `steps` を必須で設定してください）：\n"
        "```json\n"
        "{\n"
        '  "newWatchCandidates": [\n'
        '    {\n'
        '      "symbol": "ティッカー",\n'
        '      "market": "NASDAQ や NYSE や TYO など",\n'
        '      "name": "企業名",\n'
        '      "targetHorizon": "現在値$xxx / 狙い目$yyy〜zzz（期間目安）",\n'
        '      "reason": "今日の地合い・強いセクターからなぜ次のWatch候補か（1〜2行）",\n'
        '      "steps": [\n'
        '        {"step": 1, "period": "例: 2026年11月下旬〜12月上旬（直近押し目）", "notifyDate": "YYYY-MM-DD（1〜2週間後のチェック日）", "ratio": "30%", "aim": "初回打診エントリー", "condition": "例: 現在$185→$172以下または25日線タッチ"},\n'
        '        {"step": 2, "period": "例: 2026年12月中旬〜1月（決算・イベント通過後）", "notifyDate": "YYYY-MM-DD（次イベント目安日）", "ratio": "40%", "aim": "本玉追加", "condition": "例: $165〜$170支持確認またはガイダンス維持"},\n'
        '        {"step": 3, "period": "例: 2027年1月〜2月（トレンド確定後）", "notifyDate": "YYYY-MM-DD（1〜2ヶ月後）", "ratio": "30%", "aim": "上昇トレンド確認後の上乗せ", "condition": "例: 直近高値ブレイク＋出来高増加"}\n'
        '      ]\n'
        '    }\n'
        "  ]\n"
        "}\n"
        "```"
    )
    market_raw, market_meta = market_gemini.generate(step1_system, market_prompt)
    record_usage_to_firestore(db, market_meta, usd_to_jpy=usd_to_jpy)

    _, market_candidates = parse_action_payload(market_raw)
    market_clean_md = strip_verdicts_block(market_raw)

    db.collection("stock_reports").document(f"market_{date_str}").set(
        {
            "id": f"market_{date_str}",
            "type": "daily_market",
            "date": date_str,
            "title": f"🌍 マーケット地合い＆セクター動向 ({date_str})",
            "content": market_clean_md,
            "newWatchCandidates": market_candidates,
            "model": market_meta.get("modelVersion", ""),
            "createdAt": utc_now,
            "updatedAt": utc_now,
        }
    )
    print(f"[OK] Saved daily market report (doc=market_{date_str}, candidates={len(market_candidates)}).")

    # 2. Chained portfolio action + Watch review
    print(f"[INFO] Running chained daily action pipeline (holding={len(holding_lines)}, watch={len(watch_lines)})...")
    gatekeeper_skill = load_skill_prompt("stock-risk-gatekeeper")
    step2_system = f"{gatekeeper_skill}\n\n---\n\n{screener_skill}"

    holdings_section = "\n\n".join(holding_lines) if holding_lines else "（現在、保有中として登録された銘柄はありません）"
    watch_section = "\n\n".join(watch_lines) if watch_lines else "（現在、注目中として登録された未保有銘柄はありません）"

    market_candidates_note = ""
    if market_candidates:
        cand_lines = [f"- {c['name']} ({c['symbol']}:{c['market']}): {c['reason']}" for c in market_candidates]
        market_candidates_note = "\n\n## Step 1 で抽出された次の Watch 候補\n" + "\n".join(cand_lines)

    action_prompt = (
        f"本日の基準日時: {ts_label}\n\n"
        "以下は直前に分析した【本日のマーケット地合い＆セクター動向】です。必ずこの地合いを踏まえて、"
        "「保有中の買い増し/撤退判断」と「Watch銘柄の見直し（継続/格上げ/削除提案）」を一貫して判定してください。\n\n"
        f"## 前提：本日のマーケット地合い＆セクター動向\n{market_clean_md}{market_candidates_note}\n\n"
        f"## 1. 💼 保有中銘柄（すでに購入済み → 買い増し ADD / 保有継続 HOLD / 縮小撤退 TRIM を判定）\n{holdings_section}\n\n"
        f"## 2. 👀 注目中（WATCH）銘柄（未保有 → 購入開始 BUY / 監視継続 WATCH / 削除提案 DROP を判定）\n{watch_section}\n\n"
        "## 出力ルール\n"
        "- 1) 保有中銘柄ごとに最新株価・地合い適合度を確認し、`ADD`（買い増し好機）/ `HOLD`（静観維持）/ `TRIM`（一部利確・撤退警戒）を明記してください。すでに保有中の銘柄を「未購入」と誤認しないでください。\n"
        "- 2) 注目中（WATCH）銘柄ごとに `BUY`（初回買い好機）/ `WATCH`（監視継続）/ `DROP`（削除提案）を判定してください。※削除の最終決定・実行は人間が行うため、前提崩壊や地合い不適合が見られる場合は根拠を添えて『削除提案 (`DROP`)』として提案してください。\n"
        "- 3) 各銘柄の `oneLiner` は必ず **『現在株価と、今買うべきか／いくらまで待つか／いつ再確認するか』** を一目でわかる1〜2文で書いてください（例: `現在592円（目標555〜575円まで約4%調整待ち）→ 12/15日銀会合前の押し目待ち`）。\n"
        "- 4) 『⚠️ スケジュール未整備』と付記されている銘柄（`DROP` 以外）や、最新株価・地合いに対して水準や日程が古くなっている銘柄は、JSON内の `updatedSteps` に **具体的な時期・`notifyDate` (`YYYY-MM-DD`, 本日以降)・価格条件入りの未購入ステップ（2〜3回分）** を必ず入れてください（すでに購入済みのステップは自動で保持されます。`DROP` または変更不要の場合のみ省略可）。\n"
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

    action_raw, action_meta = action_gemini.generate(step2_system, action_prompt)
    record_usage_to_firestore(db, action_meta, usd_to_jpy=usd_to_jpy)

    verdicts, extra_candidates = parse_action_payload(action_raw)
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

    # Also backfill concrete schedule/notifyDate on any active scenario omitted from verdicts
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


def run_weekly(db: Any, gemini: GeminiClient, usd_to_jpy: float = DEFAULT_USD_TO_JPY) -> None:
    now = now_jst()
    date_str = now.strftime("%Y-%m-%d")
    ts_label = now.strftime("%Y年%m月%d日 (%a) %H:%M JST")
    utc_now = dt.datetime.now(dt.timezone.utc).isoformat()

    # 1. Sector analyzer
    print(f"[INFO] Running sector-analyzer ({date_str})...")
    sector_skill = load_skill_prompt("sector-analyzer")
    sector_prompt = (
        f"本日の基準日時: {ts_label}\n"
        "対象市場: 米国株・日本株 / 投資スパン: 6ヶ月〜2年\n"
        "最新データを検索してマクロ環境とセクターローテーションを分析し、優先セクター（2〜3業種）と stock-screener への引き継ぎ事項をまとめてください。"
    )
    sector_md, sector_meta = gemini.generate(sector_skill, sector_prompt)
    record_usage_to_firestore(db, sector_meta, usd_to_jpy=usd_to_jpy)

    # 2. Stock screener chained from sector analyzer output
    print(f"[INFO] Running stock-screener ({date_str})...")
    screener_skill = load_skill_prompt("stock-screener")
    screener_prompt = (
        f"本日の基準日時: {ts_label}\n"
        "投資スタイル: 中期成長・クオリティ重視（2〜3年保有前提）\n"
        "以下は直前に実施したセクター分析の結果です。この優先セクターから有望候補3〜5社を抽出し、"
        "最新財務・競争力・バリュエーション・マクロ適合性の4軸で比較したうえで、最有力1〜2社を選定してください。\n\n"
        f"【セクター分析結果】\n{sector_md}"
    )
    screener_md, screener_meta = gemini.generate(screener_skill, screener_prompt)
    record_usage_to_firestore(db, screener_meta, usd_to_jpy=usd_to_jpy)

    combined_md = (
        "## 1. セクターローテーション分析\n\n"
        f"{sector_md}\n\n"
        "---\n\n"
        "## 2. 有望銘柄スクリーニング・4軸比較\n\n"
        f"{screener_md}"
    )

    db.collection("stock_reports").document(f"weekly_{date_str}").set(
        {
            "id": f"weekly_{date_str}",
            "type": "weekly_screener",
            "date": date_str,
            "title": f"🔭 週次セクター＆銘柄発掘 ({date_str})",
            "content": combined_md,
            "model": screener_meta.get("modelVersion", ""),
            "createdAt": utc_now,
            "updatedAt": utc_now,
        }
    )
    print(f"[OK] Saved weekly screener report (doc=weekly_{date_str}).")


def should_run_automation(
    settings: dict[str, Any] | None,
    usage: dict[str, Any] | None,
    usd_to_jpy: float = DEFAULT_USD_TO_JPY,
) -> tuple[bool, str]:
    """Check Firestore kill switch (`stock_settings/automation`) and monthly JPY cap."""
    s = settings or {}
    if not bool(s.get("enabled", True)):
        return (False, "Automation is paused via stock_settings/automation.")

    raw_budget = s.get("monthlyBudgetJpy")
    budget_jpy = DEFAULT_MONTHLY_BUDGET_JPY if raw_budget is None else int(raw_budget)
    if budget_jpy > 0:
        current_usd = float((usage or {}).get("estimatedCostUsd") or 0.0)
        rate = float((usage or {}).get("usdToJpyRate") or usd_to_jpy)
        current_jpy = current_usd * rate
        if current_jpy >= budget_jpy:
            return (False, f"Monthly Gemini budget cap reached ({current_jpy:.0f} JPY >= {budget_jpy} JPY).")

    return (True, "ok")


def check_automation_guard(db: Any, usd_to_jpy: float = DEFAULT_USD_TO_JPY) -> tuple[bool, str]:
    settings_snap = db.collection("stock_settings").document("automation").get()
    settings = settings_snap.to_dict() if settings_snap.exists else None
    month_key = now_jst().strftime("%Y-%m")
    usage_snap = db.collection("gemini_usage").document(month_key).get()
    usage = usage_snap.to_dict() if usage_snap.exists else None
    return should_run_automation(settings, usage, usd_to_jpy=usd_to_jpy)


def main() -> int:
    parser = argparse.ArgumentParser(description="Hibi Stock Analysis Pipeline")
    parser.add_argument("--mode", choices=["daily", "weekly", "all"], default="daily")
    parser.add_argument("--model", default=os.environ.get("GEMINI_MODEL", ""))
    parser.add_argument("--market-model", default=os.environ.get("GEMINI_MARKET_MODEL", DEFAULT_MARKET_MODEL))
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
    market_model = override_model or args.market_model.strip() or DEFAULT_MARKET_MODEL
    action_model = override_model or args.action_model.strip() or DEFAULT_ACTION_MODEL

    market_gemini = GeminiClient(api_key=gemini_key, model=market_model, use_google_search=use_search)
    action_gemini = (
        market_gemini
        if action_model == market_model
        else GeminiClient(api_key=gemini_key, model=action_model, use_google_search=use_search)
    )

    if args.mode in ("daily", "all"):
        run_daily(db, market_gemini=market_gemini, action_gemini=action_gemini, usd_to_jpy=fx_rate)
    if args.mode in ("weekly", "all"):
        if args.mode == "all":
            allowed, reason = check_automation_guard(db, usd_to_jpy=fx_rate)
            if not allowed:
                print(f"[SKIP] {reason}")
                return 0
        run_weekly(db, action_gemini, usd_to_jpy=fx_rate)

    print("[DONE] Stock analysis pipeline completed.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
