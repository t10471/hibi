"""Firestore data access, FX rate helper, usage/budget guard, and FCM alerts."""

from __future__ import annotations

import datetime as dt
import json
import re
from typing import Any

from gemini_client import (
    DEFAULT_MODEL,
    DEFAULT_MONTHLY_BUDGET_JPY,
    DEFAULT_USD_TO_JPY,
    calculate_cost_usd,
)
from scenario_parser import (
    AI_KNOWLEDGE_TAG,
    MAX_AI_LESSONS,
    MAX_INJECTED_KNOWLEDGE_CHARS,
    MAX_INJECTED_KNOWLEDGE_ITEMS,
    effective_scenario_status,
    normalize_symbol_key,
    now_jst,
)


def init_firestore(service_account_json: str = ""):
    """Initialize firebase_admin and return the Firestore client."""
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
        if rdate < cutoff or rdate > today_s:
            continue
        if rdate == today_s and rtype != "daily_action":
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
    """Save new AI lessons to stock_knowledge and prune only old unpinned AI notes."""
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


def record_usage_to_firestore(db: Any, meta: dict[str, Any], usd_to_jpy: float = DEFAULT_USD_TO_JPY) -> None:
    """Increment monthly Gemini token & USD cost counters in Firestore."""
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
    """Send a discreet FCM push notification when 1 or more scenarios reach BUY/ADD/GO."""
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
    """Fetch settings & monthly usage from Firestore and evaluate whether automation may run."""
    settings_snap = db.collection("stock_settings").document("automation").get()
    settings = settings_snap.to_dict() if settings_snap.exists else None
    month_key = now_jst().strftime("%Y-%m")
    usage_snap = db.collection("gemini_usage").document(month_key).get()
    usage = usage_snap.to_dict() if usage_snap.exists else None
    return should_run_automation(settings, usage, usd_to_jpy=usd_to_jpy)
