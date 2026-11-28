"""Gemini REST API client, model pricing, and response text extraction."""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

if TYPE_CHECKING:
    import requests

DEFAULT_MODEL = "gemini-4-hydrogen"
DEFAULT_MARKET_MODEL = "gemini-4-hydrogen"
DEFAULT_ACTION_MODEL = "gemini-4-neon"
FALLBACK_MODEL = "gemini-flash-latest"
DEFAULT_USD_TO_JPY = 159.0
DEFAULT_MONTHLY_BUDGET_JPY = 1000


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
    """Compute estimated USD cost for a single Gemini API call."""
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
                print(
                    f"[WARN] Search grounding unavailable on current tier (HTTP {resp.status_code}); retrying without search tool."
                )
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
