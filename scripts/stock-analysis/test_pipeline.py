import unittest
from pathlib import Path

from run_pipeline import (
    SKILLS_DIR,
    calculate_cost_usd,
    effective_scenario_status,
    extract_answer_text,
    load_skill_prompt,
    merge_updated_steps,
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
        # Fallback 2-step schedule with concrete YYYY-MM-DD notifyDate is generated when omitted
        self.assertEqual(len(candidates[0]["steps"]), 2)
        self.assertEqual(candidates[0]["steps"][0]["step"], 1)
        self.assertEqual(candidates[0]["steps"][1]["step"], 2)
        self.assertRegex(candidates[0]["steps"][0]["notifyDate"], r"^\d{4}-\d{2}-\d{2}$")
        self.assertRegex(candidates[0]["steps"][1]["notifyDate"], r"^\d{4}-\d{2}-\d{2}$")

    def test_watch_candidate_steps_and_merge_updated_steps(self) -> None:
        raw = (
            "## 判断\n本文\n\n"
            "```json\n"
            "{\n"
            '  "actions": [{\n'
            '    "symbol": "8766",\n'
            '    "status": "HOLDING",\n'
            '    "verdict": "HOLD",\n'
            '    "oneLiner": "押し目待ち",\n'
            '    "updatedSteps": [\n'
            '      {"step": 1, "period": "12月利上げ確認後", "ratio": "50%", "aim": "2回目買い増し", "condition": "550円付近維持"}\n'
            "    ]\n"
            "  }],\n"
            '  "newWatchCandidates": [{\n'
            '    "symbol": "AVGO",\n'
            '    "market": "NASDAQ",\n'
            '    "name": "Broadcom",\n'
            '    "targetHorizon": "$160〜180",\n'
            '    "reason": "AIネットワーク需要",\n'
            '    "steps": [\n'
            '      {"step": 1, "period": "初回打診", "ratio": "30%", "aim": "打診", "condition": "$170押し目"},\n'
            '      {"step": 2, "period": "決算後", "ratio": "40%", "aim": "本玉", "condition": "$160支持確認"},\n'
            '      {"step": 3, "period": "上抜け", "ratio": "30%", "aim": "追撃", "condition": "高値ブレイク"}\n'
            "    ]\n"
            "  }]\n"
            "}\n"
            "```"
        )
        actions, candidates = parse_action_payload(raw)
        self.assertEqual(len(candidates[0]["steps"]), 3)
        self.assertEqual(candidates[0]["steps"][2]["ratio"], "30%")
        existing = [
            {"step": 1, "period": "初回", "ratio": "40%", "aim": "打診済", "condition": "600円", "isDone": True, "isNotified": False},
            {"step": 2, "period": "旧予定", "ratio": "60%", "aim": "古い計画", "condition": "500円", "isDone": False, "isNotified": False},
        ]
        merged = merge_updated_steps(existing, actions[0]["updatedSteps"])
        self.assertIsNotNone(merged)
        assert merged is not None
        self.assertEqual(len(merged), 2)
        self.assertTrue(merged[0]["isDone"])
        self.assertEqual(merged[1]["step"], 2)
        self.assertEqual(merged[1]["condition"], "550円付近維持")
        self.assertFalse(merged[1]["isDone"])
        self.assertIsNone(merge_updated_steps(existing, []))


if __name__ == "__main__":
    unittest.main()
