from __future__ import annotations

import json
import math
from pathlib import Path
import tempfile
import unittest

from minecraft_machines_training.best_evaluation import (
    evaluation_score,
    promote_best_evaluation,
    should_promote_evaluation,
)
from minecraft_machines_training.evaluation import evaluation_contract, held_out_point_goals
from minecraft_machines_training.training_metadata import model_metadata_path


class BestEvaluationTest(unittest.TestCase):
    def test_evaluation_score_prefers_success_then_return_then_distance(self):
        self.assertGreater(
            evaluation_score({"success_rate": 0.75, "mean_return": 1.0, "mean_final_distance": 10.0}),
            evaluation_score({"success_rate": 0.50, "mean_return": 100.0, "mean_final_distance": 0.0}),
        )
        self.assertGreater(
            evaluation_score({"success_rate": 0.75, "mean_return": 2.0, "mean_final_distance": 10.0}),
            evaluation_score({"success_rate": 0.75, "mean_return": 1.0, "mean_final_distance": 0.0}),
        )
        self.assertGreater(
            evaluation_score({"success_rate": 0.75, "mean_return": 2.0, "mean_final_distance": 1.0}),
            evaluation_score({"success_rate": 0.75, "mean_return": 2.0, "mean_final_distance": 2.0}),
        )

    def test_should_promote_when_no_previous_or_previous_is_weaker(self):
        current = {"success_rate": 1.0, "mean_return": 3.0, "mean_final_distance": 0.25}
        previous = _complete_evaluation(success_rate=0.5, mean_return=10.0, mean_final_distance=2.0)
        stronger = _complete_evaluation(**current)

        self.assertTrue(should_promote_evaluation(current, None))
        self.assertTrue(should_promote_evaluation(current, previous))
        self.assertFalse(should_promote_evaluation(previous["summary"], stronger))

    def test_legacy_incompatible_incomplete_and_nonfinite_bests_cannot_block_promotion(self):
        current = {"success_rate": 0.1, "mean_return": -10.0, "mean_final_distance": 10.0}
        invalid_payloads = {
            "legacy": {"summary": {"success_rate": 1.0, "mean_return": 999.0, "mean_final_distance": 0.0}},
            "wrong_contract": _complete_evaluation(evaluation_contract_override={"id": "old", "version": 1}),
            "wrong_manifest": _complete_evaluation(manifest_override={"id": "old", "version": 1, "scenarios": []}),
            "not_valid": _complete_evaluation(evaluation_valid=False),
            "incomplete": _complete_evaluation(episode_limit=1),
            "wrong_order": _complete_evaluation(reverse_episodes=True),
            "nonfinite_success": _complete_evaluation(success_rate=math.inf),
            "nonfinite_return": _complete_evaluation(mean_return=math.nan),
            "nonfinite_distance": _complete_evaluation(mean_final_distance=math.inf),
        }

        for name, previous in invalid_payloads.items():
            with self.subTest(name=name):
                self.assertTrue(should_promote_evaluation(current, previous))

    def test_promote_best_evaluation_copies_model_metadata_and_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_path = root / "model.zip"
            model_path.write_text("model", encoding="utf-8")
            best_model_path = root / "best" / "best_model.zip"
            best_evaluation_path = root / "best" / "best_evaluation.json"
            metadata = {
                "format": "minecraft_machines_ppo_training_metadata_v1",
                "morphology_id": "minecraft_machines:duopod",
                "evaluation_metrics": {},
            }
            payload = {
                "summary": {"success_rate": 1.0, "mean_return": 3.0, "mean_final_distance": 0.25},
                "episodes": [],
            }

            result = promote_best_evaluation(
                model_path=model_path,
                model_metadata=metadata,
                evaluation_payload=payload,
                best_model_path=best_model_path,
                best_evaluation_path=best_evaluation_path,
            )

            self.assertTrue(result["promoted"])
            self.assertEqual(best_model_path.read_text(encoding="utf-8"), "model")
            best_payload = json.loads(best_evaluation_path.read_text(encoding="utf-8"))
            self.assertEqual(best_payload["summary"], payload["summary"])
            self.assertTrue(best_payload["best_checkpoint"]["promoted"])
            best_metadata = json.loads(model_metadata_path(best_model_path).read_text(encoding="utf-8"))
            self.assertEqual(best_metadata["model_path"], str(best_model_path))
            self.assertEqual(best_metadata["evaluation_metrics"], payload["summary"])
            self.assertEqual(best_metadata["best_evaluation_path"], str(best_evaluation_path))

    def test_promote_best_evaluation_keeps_stronger_existing_best(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_path = root / "model.zip"
            model_path.write_text("weaker", encoding="utf-8")
            best_model_path = root / "best_model.zip"
            best_model_path.write_text("stronger", encoding="utf-8")
            best_evaluation_path = root / "best_evaluation.json"
            best_evaluation_path.write_text(json.dumps(_complete_evaluation(
                success_rate=1.0,
                mean_return=10.0,
                mean_final_distance=0.0,
            )), encoding="utf-8")
            payload = {
                "summary": {"success_rate": 0.5, "mean_return": 100.0, "mean_final_distance": 0.0},
                "episodes": [],
            }

            result = promote_best_evaluation(
                model_path=model_path,
                model_metadata=None,
                evaluation_payload=payload,
                best_model_path=best_model_path,
                best_evaluation_path=best_evaluation_path,
            )

            self.assertFalse(result["promoted"])
            self.assertTrue(result["previous_evaluation_valid"])
            self.assertEqual(best_model_path.read_text(encoding="utf-8"), "stronger")

    def test_promote_best_evaluation_replaces_legacy_best_regardless_of_legacy_score(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            model_path = root / "model.zip"
            model_path.write_text("current", encoding="utf-8")
            best_model_path = root / "best_model.zip"
            best_model_path.write_text("legacy", encoding="utf-8")
            best_evaluation_path = root / "best_evaluation.json"
            best_evaluation_path.write_text(json.dumps({
                "summary": {"success_rate": 1.0, "mean_return": 999.0, "mean_final_distance": 0.0},
            }), encoding="utf-8")
            payload = _complete_evaluation(
                success_rate=0.0,
                mean_return=-10.0,
                mean_final_distance=12.0,
            )

            result = promote_best_evaluation(
                model_path=model_path,
                model_metadata=None,
                evaluation_payload=payload,
                best_model_path=best_model_path,
                best_evaluation_path=best_evaluation_path,
            )

            self.assertTrue(result["promoted"])
            self.assertFalse(result["previous_evaluation_valid"])
            self.assertIsNone(result["previous_score"])
            self.assertEqual(best_model_path.read_text(encoding="utf-8"), "current")
            written = json.loads(best_evaluation_path.read_text(encoding="utf-8"))
            self.assertEqual(written["evaluation_contract"], evaluation_contract())


def _complete_evaluation(
        *,
        success_rate: float = 1.0,
        mean_return: float = 3.0,
        mean_final_distance: float = 0.25,
        evaluation_valid: bool = True,
        evaluation_contract_override=None,
        manifest_override=None,
        episode_limit: int | None = None,
        reverse_episodes: bool = False,
) -> dict:
    manifest = manifest_override or held_out_point_goals().to_dict()
    scenarios = manifest.get("scenarios", [])
    episodes = [{"scenario_id": scenario["id"]} for scenario in scenarios]
    if episode_limit is not None:
        episodes = episodes[:episode_limit]
    if reverse_episodes:
        episodes.reverse()
    return {
        "evaluation_contract": evaluation_contract_override or evaluation_contract(),
        "scenario_manifest": manifest,
        "summary": {
            "evaluation_valid": evaluation_valid,
            "episodes": len(episodes),
            "success_rate": success_rate,
            "mean_return": mean_return,
            "mean_final_distance": mean_final_distance,
        },
        "episodes": episodes,
    }


if __name__ == "__main__":
    unittest.main()
