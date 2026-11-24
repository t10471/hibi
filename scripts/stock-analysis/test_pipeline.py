import unittest
from pathlib import Path

from run_pipeline import (
    SKILLS_DIR,
    calculate_cost_usd,
    effective_scenario_status,
    extract_answer_text,
    load_skill_prompt,
    parse_action_payload,
    parse_verdicts_json,
    should_run_automation,
    strip_verdicts_block,
)


class StockPipelineTest(unittest.TestCase):
    def test_load_all_five_skills(self) -> None:
        expected = [
            "stock-market-analyzer",
            "sector-analyzer",
            "stock-screener",
            "midterm-stock-planner",
            "stock-risk-gatekeeper",
        ]
        for name in expected:
            prompt = load_skill_prompt(name, SKILLS_DIR)
            self.assertTrue(len(prompt) > 200, f"Skill {name} should not be empty")
            self.assertFalse(prompt.startswith("---"), f"Frontmatter should be stripped for {name}")

    def test_calculate_cost_usd_models(self) -> None:
        neon = calculate_cost_usd("gemini-4-neon", 1_000_000, 500_000, 500_000)
        self.assertAlmostEqual(neon, 18.0, places=4)

        hydrogen = calculate_cost_usd("gemini-4-hydrogen", 1_000_000, 500_000, 500_000)
        self.assertAlmostEqual(hydrogen, 9.0, places=4)

        flash38 = calculate_cost_usd("gemini-3.8-flash", 1_000_000, 500_000, 500_000)
        self.assertAlmostEqual(flash38, 4.5, places=4)

        flash35 = calculate_cost_usd("gemini-3.5-flash-lite", 1_000_000, 600_000, 400_000)
        self.assertAlmostEqual(flash35, 2.8, places=4)

    def test_should_run_automation_guard(self) -> None:
        self.assertEqual(should_run_automation(None, None)[0], True)
        self.assertEqual(should_run_automation({"enabled": False}, None)[0], False)
        self.assertEqual(
            should_run_automation({"enabled": True, "monthlyBudgetJpy": 1000}, {"estimatedCostUsd": 6.30}, usd_to_jpy=159.0)[0],
            False,
        )
        self.assertEqual(
            should_run_automation({"enabled": True, "monthlyBudgetJpy": 1000}, {"estimatedCostUsd": 3.0}, usd_to_jpy=159.0)[0],
            True,
        )
        self.assertEqual(
            should_run_automation({"enabled": True, "monthlyBudgetJpy": 0}, {"estimatedCostUsd": 20.0})[0],
            True,
        )

    def test_extract_answer_text_filters_thoughts(self) -> None:
        payload = {
            "candidates": [
                {
                    "content": {
                        "parts": [
                            {"thought": True, "text": "internal reasoning"},
                            {"text": "Final market summary"},
                        ]
                    }
                }
            ]
        }
        self.assertEqual(extract_answer_text(payload), "Final market summary")

    def test_parse_and_strip_verdicts_json(self) -> None:
        raw = (
            "## 判定結果\n本文\n\n"
            "```json\n"
            '[{"symbol": "NFLX", "verdict": "wait", "oneLiner": "様子見"}, {"symbol": "INVALID", "verdict": "MAYBE"}]\n'
            "```"
        )
        verdicts = parse_verdicts_json(raw)
        self.assertEqual(len(verdicts), 1)
        self.assertEqual(verdicts[0]["symbol"], "NFLX")
        self.assertEqual(verdicts[0]["verdict"], "WAIT")
        self.assertEqual(strip_verdicts_block(raw), "## 判定結果\n本文")

    def test_parse_action_payload_and_effective_status(self) -> None:
        self.assertEqual(effective_scenario_status({"status": "HOLDING"}), "HOLDING")
        self.assertEqual(effective_scenario_status({"steps": [{"isDone": True}]}), "HOLDING")
        self.assertEqual(effective_scenario_status({"steps": [{"isDone": False}]}), "WATCH")

        raw = (
            "## アクション判断\n本文\n\n"
            "```json\n"
            '{"actions": [{"symbol": "GOOGL", "status": "HOLDING", "verdict": "add", "oneLiner": "押し目"}], '
            '"newWatchCandidates": [{"symbol": "TSM", "market": "NYSE", "name": "TSMC", "targetHorizon": "$180", "reason": "AI需要"}]}\n'
            "```"
        )
        actions, candidates = parse_action_payload(raw)
        self.assertEqual(len(actions), 1)
        self.assertEqual(actions[0]["verdict"], "ADD")
        self.assertEqual(len(candidates), 1)
        self.assertEqual(candidates[0]["symbol"], "TSM")
        self.assertEqual(strip_verdicts_block(raw), "## アクション判断\n本文")

    def test_parse_market_watch_candidates_json(self) -> None:
        raw = (
            "## 本日のマーケット地合いとセクター動向\n"
            "半導体・電力インフラへ資金流入。\n\n"
            "```json\n"
            '{\n'
            '  "newWatchCandidates": [\n'
            '    {"symbol": "nvda", "market": "nasdaq", "name": "NVIDIA", "targetHorizon": "$125押し目", "reason": "AI GPU需要継続"},\n'
            '    {"symbol": "CEG", "market": "NASDAQ", "name": "Constellation Energy", "targetHorizon": "決算後調整待ち", "reason": "データセンター電力需要"}\n'
            '  ]\n'
            '}\n'
            "```"
        )
        actions, candidates = parse_action_payload(raw)
        self.assertEqual(actions, [])
        self.assertEqual(len(candidates), 2)
        self.assertEqual(candidates[0]["symbol"], "NVDA")
        self.assertEqual(candidates[0]["market"], "NASDAQ")
        self.assertEqual(candidates[1]["symbol"], "CEG")
        self.assertEqual(
            strip_verdicts_block(raw),
            "## 本日のマーケット地合いとセクター動向\n半導体・電力インフラへ資金流入。",
        )


if __name__ == "__main__":
    unittest.main()
