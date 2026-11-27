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
REPO_ROOT = Path(__file__).resolve().parents[1]
_BUNDLED_SKILLS_DIR = Path(__file__).resolve().parent / "skills"
SKILLS_DIR = _BUNDLED_SKILLS_DIR if _BUNDLED_SKILLS_DIR.exists() else (REPO_ROOT / ".agents" / "skills")

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


def parse_verdicts_json(text: str) -> list[dict[str, str]]:
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


def format_knowledge_for_prompt(
    knowledge_docs: list[dict[str, Any]],
    max_items: int = MAX_INJECTED_KNOWLEDGE_ITEMS,
    max_chars: int = MAX_INJECTED_KNOWLEDGE_CHARS,
) -> str:
    """Format pinned principles first, then recent knowledge, within strict token bounds."""
    sorted_docs = sorted(
        knowledge_docs,
        key=lambda d: (
            1 if d.get("isPinned") else 0,
            str(d.get("updatedAt") or d.get("createdAt") or ""),
        ),
        reverse=True,
    )
    lines: list[str] = []
    total_chars = 0
    for d in sorted_docs:
        title = str(d.get("title") or "").strip()
        content = re.sub(r"\s+", " ", str(d.get("content") or "")).strip()
        if not title:
            continue
        if len(content) > 220:
            content = content[:217] + "..."
        cat = str(d.get("category") or "その他").strip()
        pin_prefix = "📌 固定原則 | " if d.get("isPinned") else ""
        line = f"- [{pin_prefix}{cat}] {title}: {content}" if content else f"- [{pin_prefix}{cat}] {title}"
        if lines and total_chars + len(line) + 1 > max_chars:
            break
        lines.append(line)
        total_chars += len(line) + 1
        if len(lines) >= max_items:
            break
    return "\n".join(lines)


def load_active_knowledge(db: Any) -> tuple[str, list[str]]:
    """Load stock_knowledge docs and return (prompt_block, existing_titles)."""
    docs = []
    titles: list[str] = []
    for snap in db.collection("stock_knowledge").stream():
        d = snap.to_dict() or {}
        d["id"] = snap.id
        docs.append(d)
        t = str(d.get("title") or "").strip()
        if t:
            titles.append(t)
    return (format_knowledge_for_prompt(docs), titles)


def load_recent_reports_summary(
    db: Any,
    base_date: dt.date,
    max_days: int = 28,
    max_reports: int = 8,
) -> str:
    """Summarize past weekly candidates and daily verdicts from the last 1-4 weeks."""
    cutoff = (base_date - dt.timedelta(days=max_days)).strftime("%Y-%m-%d")
    today_s = base_date.strftime("%Y-%m-%d")
    rows: list[dict[str, Any]] = []
    for snap in db.collection("stock_reports").stream():
        d = snap.to_dict() or {}
        rtype = str(d.get("type") or "")
        rdate = str(d.get("date") or "")
        if rtype not in {"weekly_screener", "daily_action", "daily_market"}:
            continue
        if not (cutoff <= rdate < today_s):
            continue
        rows.append(d)

    rows.sort(key=lambda r: (str(r.get("date") or ""), str(r.get("updatedAt") or "")), reverse=True)
    lines: list[str] = []
    for d in rows[:max_reports]:
        rdate = str(d.get("date") or "")
        rtype = str(d.get("type") or "")
        parts: list[str] = []
        cands = d.get("newWatchCandidates") or []
        if isinstance(cands, list) and cands:
            cand_s = ", ".join(
                f"{c.get('symbol')}:{c.get('market')}({c.get('targetHorizon')})"
                for c in cands
                if isinstance(c, dict) and c.get("symbol")
            )
            if cand_s:
                parts.append(f"提案候補=[{cand_s}]")
        verdicts = d.get("verdicts") or []
        if isinstance(verdicts, list) and verdicts:
            v_s = ", ".join(
                f"{v.get('symbol')}={v.get('verdict')}({str(v.get('oneLiner') or '')[:40]})"
                for v in verdicts
                if isinstance(v, dict) and v.get("symbol")
            )
            if v_s:
                parts.append(f"判定=[{v_s}]")
        if parts:
            lines.append(f"- {rdate} ({rtype}): " + " / ".join(parts))
    return "\n".join(lines)


def save_learned_knowledge(
    db: Any,
    lessons: list[dict[str, str]],
    max_ai_unpinned: int = MAX_AI_LESSONS,
) -> dict[str, int]:
    """Save new AI lessons to stock_knowledge and prune only old unpinned AI notes.

    Human-created entries (without AI_KNOWLEDGE_TAG) and pinned entries (isPinned=True)
    are NEVER deleted automatically.
    """
    col = db.collection("stock_knowledge")
    existing_snaps = list(col.stream())
    existing_titles = {
        str((s.to_dict() or {}).get("title") or "").strip().lower()
        for s in existing_snaps
    }
    ai_unpinned_docs: list[tuple[str, Any]] = []
    for s in existing_snaps:
        d = s.to_dict() or {}
        tags = d.get("tags") or []
        is_ai = isinstance(tags, list) and AI_KNOWLEDGE_TAG in [str(t) for t in tags]
        if is_ai and not bool(d.get("isPinned")):
            ts = str(d.get("updatedAt") or d.get("createdAt") or "")
            ai_unpinned_docs.append((ts, s.reference))

    now_iso = dt.datetime.now(dt.timezone.utc).isoformat()
    added = 0
    for lesson in lessons:
        title_key = lesson["title"].strip().lower()
        if not title_key or title_key in existing_titles:
            continue
        existing_titles.add(title_key)
        res = col.add(
            {
                "title": lesson["title"],
                "category": lesson["category"],
                "tags": [AI_KNOWLEDGE_TAG],
                "content": lesson["content"],
                "images": [],
                "isPinned": False,
                "createdAt": now_iso,
                "updatedAt": now_iso,
            }
        )
        doc_ref = res[1] if isinstance(res, tuple) and len(res) == 2 else res
        ai_unpinned_docs.append((now_iso, doc_ref))
        added += 1

    pruned = 0
    if len(ai_unpinned_docs) > max_ai_unpinned:
        ai_unpinned_docs.sort(key=lambda pair: pair[0])
        excess = len(ai_unpinned_docs) - max_ai_unpinned
        for _, ref in ai_unpinned_docs[:excess]:
            if hasattr(ref, "delete"):
                ref.delete()
                pruned += 1

    return {"added": added, "pruned": pruned}


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


def init_firestore(service_account_json: str = ""):
    import firebase_admin
    from firebase_admin import credentials, firestore

    if not firebase_admin._apps:
        if service_account_json.strip():
            cred = credentials.Certificate(json.loads(service_account_json))
            firebase_admin.initialize_app(cred)
        else:
            firebase_admin.initialize_app()
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


def load_active_scenarios(db: Any) -> dict[str, Any]:
    """Load and classify active (non-ARCHIVED) scenarios from Firestore."""
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
        for s in raw_steps:
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

    return {
        "holding_lines": holding_lines,
        "watch_lines": watch_lines,
        "existing_symbols": existing_symbols,
        "symbol_to_doc_ref": symbol_to_doc_ref,
        "symbol_to_existing_steps": symbol_to_existing_steps,
        "symbol_to_horizon": symbol_to_horizon,
        "active_count": active_count,
    }


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

    holdings_section = "\n\n".join(holding_lines) if holding_lines else "（現在、保有中として登録された銘柄はありません）"
    watch_section = "\n\n".join(watch_lines) if watch_lines else "（現在、注目中として登録された未保有銘柄はありません）"

    action_prompt = (
        f"本日の基準日時: {ts_label}\n\n"
        "Web検索で最新の市場環境（日米指数・長期金利・ドル円・直近重要イベント）と各登録銘柄の最新株価・材料を確認し、"
        "「保有中の買い増し/撤退判断」と「Watch銘柄の買い時/継続/削除提案」を判定してください。\n"
        "※本ジョブではセクターローテーション分析や新規候補の発掘は不要です（週次レポートで実施します）。\n\n"
        f"{knowledge_section}"
        f"## 1. 💼 保有中銘柄（すでに購入済み → 買い増し ADD / 保有継続 HOLD / 縮小撤退 TRIM を判定）\n{holdings_section}\n\n"
        f"## 2. 👀 注目中（WATCH）銘柄（未保有 → 購入開始 BUY / 監視継続 WATCH / 削除提案 DROP を判定）\n{watch_section}\n\n"
        "## 出力ルール\n"
        "- 0) 冒頭に『本日の地合いサマリー（日米指数・金利・為替・直近イベント）』を3〜5行で簡潔にまとめてください。\n"
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

    knowledge_section = (
        f"## 投資原則・蓄積ナレッジ（必ず踏まえること / 重複タイトルは除外）\n{knowledge_text}\n\n"
        if knowledge_text
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


if __name__ == "__main__":
    raise SystemExit(main())
