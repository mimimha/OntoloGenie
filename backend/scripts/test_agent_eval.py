"""Offline tests for evaluation setup and report generation."""

import argparse
import hashlib
import json
import re
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import agent_eval
import review_diverse
import stability_report


class AgentEvalTests(unittest.TestCase):
    def test_stability_distinguishes_variable_and_consistently_failed_cases(self):
        rows = []
        for run in range(1, 4):
            for case in ("variable", "failed"):
                passed = case == "variable" and run != 2
                rows.append(
                    {
                        "model": "test",
                        "run": run,
                        "caseId": case,
                        "passed": passed,
                        "status": "ANSWERED",
                        "elapsedMs": 100,
                        "factsOk": passed,
                    }
                )
        summary = stability_report.analyze(rows, ["test"], ["variable", "failed"], 3)[
            "models"
        ][0]
        self.assertEqual(summary["passed"], 2)
        self.assertEqual([row["passed"] for row in summary["rounds"]], [1, 0, 1])
        self.assertEqual(summary["alwaysPassed"], [])
        self.assertEqual(summary["variable"][0]["passed"], [True, False, True])
        self.assertEqual(summary["alwaysFailed"][0]["caseId"], "failed")
        with self.assertRaisesRegex(ValueError, "완성"):
            stability_report.analyze(rows[:-1], ["test"], ["variable", "failed"], 3)
        with self.assertRaisesRegex(ValueError, "중복"):
            stability_report.analyze(
                rows + rows[:1], ["test"], ["variable", "failed"], 3
            )

    def test_diverse_period_and_missing_value_patterns_match_meaning(self):
        suite = json.loads(
            (
                agent_eval.BACKEND
                / "src/test/resources/agent-eval/diverse-questions.json"
            ).read_text(encoding="utf-8")
        )
        cases = {turn["id"]: turn for turn in suite["turns"]}
        pattern = cases["august-period"]["forbiddenPatterns"][0]
        self.assertIsNone(re.search(pattern, "※ DT-0501은 9월 기록이라 제외했습니다."))
        self.assertIsNotNone(re.search(pattern, "- DT-0501: 코일 대기, 45분"))
        missing = cases["missing-end-time"]["patterns"][0]
        self.assertIsNotNone(re.search(missing, "종료 시각이 등록되지 않았습니다."))
        self.assertIsNotNone(re.search(missing, "비가동 시간: 확인되지 않음"))
        self.assertIsNotNone(
            re.search(missing, "종료 시각과 비가동 시간이 입력되어 있지 않습니다.")
        )
        self.assertIsNotNone(
            re.search(missing, "종료 시각과 비가동 시간이 기록되어 있지 않습니다.")
        )
        self.assertIsNone(re.search(missing, "종료 14:00, 비가동 180분입니다."))

    def test_review_uses_displayed_candidate_and_keeps_raw_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            turns = [
                {
                    "id": "ambiguous-name",
                    "question": "공용 프레스 상태",
                    "scenario": "ambiguity",
                    "status": "CLARIFY",
                    "patterns": [],
                },
                {
                    "id": "pick-first-candidate",
                    "question": "첫 번째",
                    "scenario": "ambiguity",
                    "status": "ANSWERED",
                    "patterns": ["가동"],
                },
            ]
            spec = json.dumps({"turns": turns}, ensure_ascii=False).encode("utf-8")
            (root / "questions-original.json").write_bytes(spec)
            (root / "questions.json").write_bytes(spec)
            agent_eval.write_json(
                root / "manifest.json",
                {
                    "questionsSha256": hashlib.sha256(spec).hexdigest(),
                    "snapshot": {"snapshotSha256": "fixture"},
                    "repeat": 1,
                    "models": ["test"],
                },
            )
            agent_eval.write_json(
                root / "gold.json",
                {"id": 4, "status": "정지", "snapshotSha256": "fixture"},
            )
            base = {
                "model": "test",
                "run": 1,
                "scenario": "ambiguity",
                "targetOk": True,
                "toolsOk": True,
                "factsOk": True,
                "periodOk": True,
                "forbiddenOk": True,
                "passed": True,
                "elapsedMs": 1,
            }
            rows = [
                {
                    **base,
                    "caseId": "ambiguous-name",
                    "status": "CLARIFY",
                    "answer": "번호 4, 번호 5",
                    "conditions": {"referenceObjectIds": []},
                },
                {
                    **base,
                    "caseId": "pick-first-candidate",
                    "status": "ANSWERED",
                    "answer": "정지",
                    "targetOk": False,
                    "factsOk": False,
                    "passed": False,
                    "conditions": {"referenceObjectIds": [4]},
                    "toolCalls": [
                        {
                            "tool": "OBJECT_DETAIL",
                            "objectId": 4,
                            "succeeded": True,
                            "category": "FOUND",
                        }
                    ],
                },
            ]
            raw = "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows)
            (root / "outcomes.jsonl").write_text(raw, encoding="utf-8")
            reviewed = review_diverse.review(
                root, root / "questions.json", root / "gold.json"
            )
            self.assertTrue(reviewed[1]["passed"])
            self.assertFalse(reviewed[1]["originalPassed"])
            self.assertEqual((root / "questions-reviewed.json").read_bytes(), spec)
            self.assertEqual((root / "outcomes.jsonl").read_text(encoding="utf-8"), raw)
            rows[1]["conditions"]["referenceObjectIds"] = [5]
            (root / "outcomes.jsonl").write_text(
                "".join(json.dumps(row) + "\n" for row in rows), encoding="utf-8"
            )
            self.assertFalse(
                review_diverse.review(
                    root, root / "questions.json", root / "gold.json"
                )[1]["passed"]
            )

    def test_refreshes_local_port_after_container_restart(self):
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory)
            reader = work / ".reader.env"
            reader.write_text(
                "DB_URL=jdbc:postgresql://127.0.0.1:50001/axcore_eval\n"
                "DB_USERNAME=axcore_eval_reader\nDB_PASSWORD=local-test-only\n",
                encoding="utf-8",
            )
            record = work / "snapshot.json"
            agent_eval.write_json(
                record,
                {
                    "container": "axcore-agent-eval-test",
                    "port": 50001,
                    "readerEnv": str(reader),
                },
            )
            with (
                patch.object(agent_eval, "WORK", work),
                patch.object(agent_eval, "STATE", record),
                patch.object(
                    agent_eval, "text_command", side_effect=["true", "127.0.0.1:50002"]
                ),
            ):
                result = agent_eval.state()
            self.assertEqual(result["port"], 50002)
            self.assertEqual(
                agent_eval.load_env(reader)["DB_URL"],
                "jdbc:postgresql://127.0.0.1:50002/axcore_eval",
            )
            self.assertEqual(
                agent_eval.load_env(reader)["DB_PASSWORD"], "local-test-only"
            )

    def test_rejects_non_loopback_published_port(self):
        with tempfile.TemporaryDirectory() as directory:
            record = Path(directory) / "snapshot.json"
            agent_eval.write_json(
                record, {"container": "axcore-agent-eval-test", "port": 50001}
            )
            with (
                patch.object(agent_eval, "STATE", record),
                patch.object(
                    agent_eval, "text_command", side_effect=["true", "0.0.0.0:50001"]
                ),
                self.assertRaisesRegex(ValueError, "localhost"),
            ):
                agent_eval.state()

    def test_env_is_parsed_without_shell_execution(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text(
                "export A='hello world'\nB=$(do-not-run)\n# IGNORE=secret\n",
                encoding="utf-8",
            )
            self.assertEqual(
                agent_eval.load_env(path), {"A": "hello world", "B": "$(do-not-run)"}
            )

    def test_rejects_credentials_for_other_destination(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / ".env"
            path.write_text(
                "AXCORE_AGENT_LLM_API_KEY=dummy\nAXCORE_AGENT_LLM_BASE_URL=https://example.com/v1\n",
                encoding="utf-8",
            )
            with (
                patch.dict("os.environ", {}, clear=True),
                self.assertRaisesRegex(ValueError, "gms.ssafy.io"),
            ):
                agent_eval.gms_settings(path)

    def test_rejects_overwriting_existing_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            state = Path(directory) / "snapshot.json"
            state.write_text("{}", encoding="utf-8")
            with (
                patch.object(agent_eval, "WORK", Path(directory)),
                patch.object(agent_eval, "STATE", state),
                self.assertRaisesRegex(ValueError, "덮어쓰지"),
            ):
                agent_eval.clone(argparse.Namespace(dump="unused"))

    def test_rejects_text_dump_before_starting_docker(self):
        with tempfile.TemporaryDirectory() as directory:
            dump = Path(directory) / "backup.dump"
            dump.write_text("not a PostgreSQL archive", encoding="utf-8")
            with (
                patch.object(agent_eval, "WORK", Path(directory)),
                patch.object(agent_eval, "STATE", Path(directory) / "state.json"),
                patch.object(agent_eval, "command") as command,
            ):
                with self.assertRaisesRegex(ValueError, "custom"):
                    agent_eval.clone(argparse.Namespace(dump=str(dump)))
                command.assert_not_called()

    def test_uses_discovered_models_and_keeps_manual_order(self):
        with tempfile.TemporaryDirectory() as directory:
            catalog = Path(directory) / "models.json"
            catalog.write_text(
                json.dumps(
                    {
                        "models": [
                            {"id": "model-a", "candidateChat": True},
                            {"id": "embedding-a", "candidateChat": False},
                        ]
                    }
                ),
                encoding="utf-8",
            )
            self.assertEqual(agent_eval.choose_models(None, catalog), ["model-a"])
            self.assertEqual(
                agent_eval.choose_models("model-b,model-a,model-b", catalog),
                ["model-b", "model-a"],
            )
            with self.assertRaises(ValueError):
                agent_eval.choose_models("model;command", catalog)

    def test_summary_preserves_failures_in_denominator(self):
        rows = [
            {
                "model": "a",
                "passed": True,
                "status": "ANSWERED",
                "elapsedMs": 100,
                "promptTokens": 10,
                "completionTokens": 5,
            },
            {
                "model": "a",
                "passed": False,
                "status": "ERROR",
                "elapsedMs": 300,
                "promptTokens": 0,
                "completionTokens": 0,
            },
        ]
        summary = agent_eval.summarize(rows)
        self.assertIn("| a | 2 | 1 | 1 | 200 | 10 | 5 |", summary)
        self.assertIn("비용으로 환산하지", summary)


if __name__ == "__main__":
    unittest.main()
