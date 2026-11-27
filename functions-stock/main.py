"""Firebase Cloud Functions (2nd gen) entrypoints for scheduled stock analysis."""

from __future__ import annotations

import logging
from firebase_admin import initialize_app
from firebase_functions import options, scheduler_fn
from firebase_functions.params import SecretParam

from stock_pipeline import (
    DEFAULT_ACTION_MODEL,
    DEFAULT_MARKET_MODEL,
    GeminiClient,
    check_automation_guard,
    fetch_usd_jpy_rate,
    init_firestore,
    run_daily,
    run_weekly,
)

initialize_app()

GEMINI_API_KEY = SecretParam("GEMINI_API_KEY")


def _execute_scheduled(mode: str) -> None:
    api_key = (GEMINI_API_KEY.value or "").strip()
    if not api_key:
        logging.error("GEMINI_API_KEY secret is not configured.")
        return

    db = init_firestore()
    fx_rate = fetch_usd_jpy_rate()
    allowed, reason = check_automation_guard(db, usd_to_jpy=fx_rate)
    if not allowed:
        logging.info("Skipping scheduled run (%s): %s", mode, reason)
        return

    if mode == "weekly":
        weekly_gemini = GeminiClient(api_key=api_key, model=DEFAULT_MARKET_MODEL, use_google_search=True)
        run_weekly(db, weekly_gemini=weekly_gemini, usd_to_jpy=fx_rate)
    else:
        action_gemini = GeminiClient(api_key=api_key, model=DEFAULT_ACTION_MODEL, use_google_search=True)
        run_daily(db, action_gemini=action_gemini, usd_to_jpy=fx_rate)


@scheduler_fn.on_schedule(
    schedule="17 8 * * 1-5",
    timezone="Asia/Tokyo",
    timeout_sec=540,
    memory=options.MemoryOption.MB_512,
    secrets=[GEMINI_API_KEY],
)
def dailyStockAnalysis(event: scheduler_fn.ScheduledEvent) -> None:
    """Weekday 08:17 JST portfolio verdict & schedule check."""
    _execute_scheduled("daily")


@scheduler_fn.on_schedule(
    schedule="43 7 * * 1",
    timezone="Asia/Tokyo",
    timeout_sec=540,
    memory=options.MemoryOption.MB_512,
    secrets=[GEMINI_API_KEY],
)
def weeklyStockDiscovery(event: scheduler_fn.ScheduledEvent) -> None:
    """Monday 07:43 JST medium/long-term sector & Watch candidate discovery + knowledge reflection."""
    _execute_scheduled("weekly")
