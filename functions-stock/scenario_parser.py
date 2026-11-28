"""Scenario normalization, schedule fallback, and trailing JSON payload recovery."""

from __future__ import annotations

import datetime as dt
import json
import re
from typing import Any

JST = dt.timezone(dt.timedelta(hours=9))
VALID_VERDICTS = {"BUY", "ADD", "HOLD", "WATCH", "TRIM", "DROP", "GO", "WAIT", "ABORT"}

AI_KNOWLEDGE_TAG = "AI自動振り返り"
MAX_INJECTED_KNOWLEDGE_ITEMS = 12
MAX_INJECTED_KNOWLEDGE_CHARS = 1800
MAX_AI_LESSONS = 8
ALLOWED_KNOWLEDGE_CATEGORIES = (
    "チャート・テクニカル",
    "ファンダメンタル",
    "リスク管理",
    "マクロ・指標",
    "その他",
)


def now_jst() -> dt.datetime:
    """Return the current datetime in Asia/Tokyo (JST)."""
    return dt.datetime.now(JST)


def effective_scenario_status(doc_dict: dict[str, Any]) -> str:
    """Resolve effective HOLDING/WATCH/ARCHIVED status from explicit status or done steps."""
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


def _extract_trailing_json_obj(text: str) -> Any:
    """Extract and parse the trailing JSON object/list from markdown output."""
    matches = list(re.finditer(r"```(?:json)?\s*(\{.*?\}|\[.*?\])\s*```", text, flags=re.DOTALL | re.IGNORECASE))
    for m in reversed(matches):
        candidate_json = re.sub(r",\s*([}\]])", r"\1", m.group(1))
        try:
            obj = json.loads(candidate_json)
            if isinstance(obj, (list, dict)):
                return obj
        except Exception:
            continue

    unclosed = re.search(r"```(?:json)?\s*(\{.*|\[.*)$", text, flags=re.DOTALL | re.IGNORECASE)
    raw_tail = unclosed.group(1) if unclosed else ""
    if not raw_tail:
        unfenced = re.search(r'(\{\s*"(?:actions|newWatchCandidates|learnedKnowledge)"\s*:.*)', text, flags=re.DOTALL)
        raw_tail = unfenced.group(1) if unfenced else ""
    if raw_tail:
        cleaned_tail = re.sub(r",\s*([}\]])", r"\1", raw_tail.strip())
        try:
            obj = json.loads(cleaned_tail)
            if isinstance(obj, (list, dict)):
                return obj
        except Exception:
            partial_actions = _recover_partial_actions(cleaned_tail)
            if partial_actions:
                return {"actions": partial_actions, "newWatchCandidates": []}
    return None


def parse_action_payload(
    text: str,
    base_date: dt.date | None = None,
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Extract trailing JSON action/candidate payload from markdown output."""
    parsed = _extract_trailing_json_obj(text)
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


def parse_verdicts_json(text: str) -> list[dict[str, Any]]:
    """Backward-compatible wrapper returning parsed actions list."""
    actions, _ = parse_action_payload(text)
    return actions


def normalize_learned_knowledge(raw_lessons: Any, max_items: int = 2) -> list[dict[str, str]]:
    """Normalize AI-distilled investment lessons into safe bounded StockKnowledge entries."""
    if not isinstance(raw_lessons, list):
        return []
    lessons: list[dict[str, str]] = []
    for item in raw_lessons:
        if not isinstance(item, dict):
            continue
        title = str(item.get("title", "")).strip()[:80]
        content = str(item.get("content", "")).strip()[:300]
        if not (title and content):
            continue
        cat_raw = str(item.get("category", "")).strip()
        category = cat_raw if cat_raw in ALLOWED_KNOWLEDGE_CATEGORIES else "その他"
        lessons.append({"title": title, "category": category, "content": content})
        if len(lessons) >= max_items:
            break
    return lessons


def parse_learned_knowledge(text: str) -> list[dict[str, str]]:
    """Extract optional learnedKnowledge array from trailing JSON payload."""
    parsed = _extract_trailing_json_obj(text)
    if not isinstance(parsed, dict):
        return []
    return normalize_learned_knowledge(parsed.get("learnedKnowledge"))


def strip_verdicts_block(text: str) -> str:
    """Remove trailing JSON block from markdown before persisting report content."""
    cleaned = re.sub(r"\n*```(?:json)?\s*(?:\{.*?\}|\[.*?\])\s*```\s*$", "", text, flags=re.DOTALL | re.IGNORECASE)
    cleaned = re.sub(r"\n*```(?:json)?\s*(?:\{|\[).*$", "", cleaned, flags=re.DOTALL | re.IGNORECASE)
    return cleaned.strip()
