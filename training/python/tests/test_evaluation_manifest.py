from __future__ import annotations

import contextlib
import importlib
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from types import SimpleNamespace

import numpy as np

from minecraft_machines_training.compare import compare_evaluations, main as compare_main
from minecraft_machines_training.evaluate import (
    EvaluationMetricAccumulator,
    _load_and_validate_policy_metadata,
    _policy_record,
    _require_complete_manifest_for_promotion,
    _row,
)
from minecraft_machines_training.evaluation import evaluation_contract, held_out_point_goals
from minecraft_machines_training.training_metadata import model_metadata_path


_MISSING = object()


class EvaluationManifestTest(unittest.TestCase):
    def test_held_out_manifest_is_stable(self):
        manifest = held_out_point_goals()

        self.assertEqual(manifest.id, "duopod_held_out_point_goals_v3")
        self.assertEqual(len(manifest.scenarios), 21)
        self.assertEqual(manifest.scenarios[0].id, "bearing_m075_distance_06")
        self.assertEqual(manifest.scenarios[-1].id, "bearing_p075_distance_12")
        self.assertEqual(manifest.compatibility_hash(), "b3d9ccc73651bfe943aada11d30e9116b80cead6fc3270006c275520f06bd09f")
        forward, right = manifest.scenarios[3].target_offset()
        self.assertAlmostEqual(forward, 6.0)
        self.assertAlmostEqual(right, 0.0)

    def test_compare_rejects_mismatched_manifests(self):
        manifest = held_out_point_goals().to_dict()
        with tempfile.TemporaryDirectory() as tmp:
            left = Path(tmp) / "left.json"
            right = Path(tmp) / "right.json"
            left.write_text(json.dumps(_evaluation_payload(manifest, success_rate=1.0)), encoding="utf-8")
            mismatched = dict(manifest)
            mismatched["compatibility_hash"] = "different"
            right.write_text(json.dumps(_evaluation_payload(mismatched, success_rate=0.0)), encoding="utf-8")

            with self.assertRaises(SystemExit):
                compare_main(["--left", str(left), "--right", str(right)])

    def test_compare_rejects_incompatible_policy_schemas(self):
        manifest = held_out_point_goals().to_dict()
        with tempfile.TemporaryDirectory() as tmp:
            left = Path(tmp) / "left.json"
            right = Path(tmp) / "right.json"
            left.write_text(json.dumps(_evaluation_payload(manifest, success_rate=1.0)), encoding="utf-8")
            incompatible = _evaluation_payload(manifest, success_rate=1.0)
            incompatible["policy"]["observation_schema_hash"] = "different"
            right.write_text(json.dumps(incompatible), encoding="utf-8")

            with self.assertRaisesRegex(SystemExit, "schemas differ"):
                compare_main(["--left", str(left), "--right", str(right)])

    def test_compare_accepts_matching_manifests_and_reports_deltas(self):
        manifest = held_out_point_goals().to_dict()
        with tempfile.TemporaryDirectory() as tmp:
            left = Path(tmp) / "left.json"
            right = Path(tmp) / "right.json"
            output = Path(tmp) / "comparison.json"
            left.write_text(json.dumps(_evaluation_payload(manifest, success_rate=0.0, mean_return=2.0)), encoding="utf-8")
            right.write_text(json.dumps(_evaluation_payload(manifest, success_rate=1.0, mean_return=5.5)), encoding="utf-8")

            with contextlib.redirect_stdout(io.StringIO()) as stdout:
                self.assertEqual(compare_main([
                    "--left", str(left),
                    "--right", str(right),
                    "--left-label", "cem",
                    "--right-label", "ppo",
                    "--output", str(output),
                ]), 0)
            payload = json.loads(stdout.getvalue())
            written = json.loads(output.read_text(encoding="utf-8"))
            self.assertEqual(payload, written)
            self.assertEqual(payload["evaluation_contract"], evaluation_contract())
            self.assertEqual(payload["scenario_manifest"]["compatibility_hash"], manifest["compatibility_hash"])
            self.assertEqual(payload["left"]["label"], "cem")
            self.assertEqual(payload["right"]["label"], "ppo")
            self.assertEqual(payload["metrics"]["success_rate"]["delta"], 1.0)
            self.assertEqual(payload["metrics"]["success_rate"]["better"], "ppo")
            self.assertEqual(payload["metrics"]["mean_return"]["delta"], 3.5)
            self.assertEqual(len(payload["episodes"]), 21)
            self.assertEqual(payload["episodes"][0]["scenario_id"], manifest["scenarios"][0]["id"])
            self.assertEqual(payload["episodes"][0]["metrics"]["success"]["delta"], 1.0)
            self.assertEqual(payload["episodes"][0]["metrics"]["success"]["better"], "ppo")
            self.assertLess(payload["episodes"][0]["metrics"]["final_distance_to_target"]["delta"], 0.0)
            self.assertEqual(payload["episodes"][0]["metrics"]["final_distance_to_target"]["better"], "ppo")

    def test_compare_rejects_different_episode_order(self):
        manifest = held_out_point_goals().to_dict()
        with tempfile.TemporaryDirectory() as tmp:
            left = Path(tmp) / "left.json"
            right = Path(tmp) / "right.json"
            left.write_text(json.dumps(_evaluation_payload(manifest, success_rate=1.0)), encoding="utf-8")
            right_payload = _evaluation_payload(manifest, success_rate=1.0)
            right_payload["episodes"] = list(reversed(right_payload["episodes"]))
            right.write_text(json.dumps(right_payload), encoding="utf-8")

            with self.assertRaisesRegex(SystemExit, "does not match manifest scenario order"):
                compare_main(["--left", str(left), "--right", str(right)])

    def test_compare_rejects_legacy_artifact_without_evaluation_contract(self):
        manifest = held_out_point_goals().to_dict()
        with tempfile.TemporaryDirectory() as tmp:
            left = Path(tmp) / "left.json"
            right = Path(tmp) / "right.json"
            legacy = _evaluation_payload(manifest, success_rate=1.0)
            legacy.pop("evaluation_contract")
            left.write_text(json.dumps(legacy), encoding="utf-8")
            right.write_text(json.dumps(_evaluation_payload(manifest, success_rate=1.0)), encoding="utf-8")

            with self.assertRaisesRegex(SystemExit, "evaluation_contract"):
                compare_main(["--left", str(left), "--right", str(right)])

    def test_compare_rejects_geometrically_impossible_episode(self):
        manifest = held_out_point_goals().to_dict()
        with tempfile.TemporaryDirectory() as tmp:
            left = Path(tmp) / "left.json"
            right = Path(tmp) / "right.json"
            invalid = _evaluation_payload(manifest, success_rate=1.0)
            invalid["episodes"][0]["distance_travelled_blocks"] = 0.25
            left.write_text(json.dumps(invalid), encoding="utf-8")
            right.write_text(json.dumps(_evaluation_payload(manifest, success_rate=1.0)), encoding="utf-8")

            with self.assertRaisesRegex(SystemExit, "progress exceeds measured path length"):
                compare_main(["--left", str(left), "--right", str(right)])

    def test_compare_requires_full_current_v3_manifest_coverage(self):
        manifest = held_out_point_goals().to_dict()
        valid = _evaluation_payload(manifest, success_rate=1.0)
        partial = _evaluation_payload(manifest, success_rate=1.0)
        partial["episodes"].pop()
        partial["summary"]["episodes"] -= 1

        with self.assertRaisesRegex(SystemExit, "full ordered manifest coverage"):
            compare_evaluations(partial, valid)

        legacy_manifest = dict(manifest)
        legacy_manifest["id"] = "duopod_held_out_point_goals_v2"
        legacy_manifest["version"] = 2
        legacy = _evaluation_payload(legacy_manifest, success_rate=1.0)
        with self.assertRaisesRegex(SystemExit, "current v3 held-out manifest"):
            compare_evaluations(legacy, legacy)

    def test_compare_rejects_nonfinite_rates_steps_and_row_metrics(self):
        manifest = held_out_point_goals().to_dict()
        valid = _evaluation_payload(manifest, success_rate=1.0)

        invalid_rate = _evaluation_payload(manifest, success_rate=1.0)
        invalid_rate["summary"]["success_rate"] = float("nan")
        with self.assertRaisesRegex(SystemExit, "success_rate"):
            compare_evaluations(invalid_rate, valid)

        invalid_steps = _evaluation_payload(manifest, success_rate=1.0)
        invalid_steps["episodes"][0]["steps"] = 0
        with self.assertRaisesRegex(SystemExit, "positive integer field 'steps'"):
            compare_evaluations(invalid_steps, valid)

        invalid_return = _evaluation_payload(manifest, success_rate=1.0)
        invalid_return["episodes"][0]["return"] = float("inf")
        with self.assertRaisesRegex(SystemExit, "finite numeric field 'return'"):
            compare_evaluations(invalid_return, valid)

    def test_compare_cross_checks_summary_aggregates(self):
        manifest = held_out_point_goals().to_dict()
        valid = _evaluation_payload(manifest, success_rate=1.0)
        inconsistent = _evaluation_payload(manifest, success_rate=1.0)
        inconsistent["summary"]["mean_return"] += 0.25

        with self.assertRaisesRegex(SystemExit, "mean_return disagrees with episode rows"):
            compare_evaluations(inconsistent, valid)

    def test_compare_accepts_signed_negative_progress_with_zero_directness(self):
        manifest = held_out_point_goals().to_dict()
        payload = _evaluation_payload(manifest, success_rate=0.0)
        first = payload["episodes"][0]
        first["final_distance_to_target"] = first["initial_distance_to_target"] + 1.0
        first["progress_to_target_blocks"] = -1.0
        first["distance_travelled_blocks"] = 1.0
        first["path_directness"] = 0.0
        payload["summary"]["mean_final_distance"] = sum(
            row["final_distance_to_target"] for row in payload["episodes"]
        ) / len(payload["episodes"])
        payload["summary"]["mean_distance_travelled_blocks"] = sum(
            row["distance_travelled_blocks"] for row in payload["episodes"]
        ) / len(payload["episodes"])

        report = compare_evaluations(payload, payload)
        self.assertEqual(report["episodes"][0]["metrics"]["progress_to_target_blocks"]["left"], -1.0)

    def test_evaluation_metric_accumulator_tracks_path_load_and_command_error(self):
        scenario = held_out_point_goals().scenarios[3]
        accumulator = EvaluationMetricAccumulator(scenario)

        accumulator.reset({
            "distance_to_target": 6.0,
            "base_position_x": 0.0,
            "base_position_z": 0.0,
        })
        accumulator.observe({
            "base_position_x": 3.0,
            "base_position_z": 0.0,
            "desired_forward_velocity": 1.0,
            "local_forward_velocity": 0.25,
            "desired_yaw_rate": 0.2,
            "local_yaw_rate": -0.1,
            "mean_servo_load": 2.0,
            "peak_servo_load": 3.0,
        })
        accumulator.observe({
            "base_position_x": 6.0,
            "base_position_z": 0.0,
            "desired_forward_velocity": 1.0,
            "local_forward_velocity": 0.75,
            "desired_yaw_rate": 0.2,
            "local_yaw_rate": 0.4,
            "mean_servo_load": 4.0,
            "peak_servo_load": 5.0,
        })

        fields = accumulator.row_fields(final_distance_to_target=0.0)
        self.assertAlmostEqual(fields["initial_distance_to_target"], 6.0)
        self.assertAlmostEqual(fields["progress_to_target_blocks"], 6.0)
        self.assertAlmostEqual(fields["distance_travelled_blocks"], 6.0)
        self.assertAlmostEqual(fields["path_directness"], 1.0)
        self.assertAlmostEqual(fields["mean_servo_load"], 3.0)
        self.assertAlmostEqual(fields["peak_servo_load"], 5.0)
        self.assertAlmostEqual(fields["mean_forward_command_error"], 0.5)
        self.assertAlmostEqual(fields["mean_yaw_command_error"], 0.25)

    def test_evaluation_row_includes_richer_metrics_and_time_to_target(self):
        scenario = held_out_point_goals().scenarios[3]
        accumulator = EvaluationMetricAccumulator(scenario)
        accumulator.reset({
            "distance_to_target": 6.0,
            "base_position_x": 0.0,
            "base_position_z": 0.0,
        })
        accumulator.observe({"base_position_x": 6.0, "base_position_z": 0.0})

        row = _row(
            scenario,
            0,
            7,
            12.5,
            1.25,
            {"success": True, "termination_reason": "SUCCESS", "distance_to_target": 0.0},
            accumulator,
            timed_out=False)

        self.assertTrue(row["success"])
        self.assertEqual(row["time_to_target_steps"], 7)
        self.assertIn("distance_travelled_blocks", row)
        self.assertIn("path_directness", row)
        self.assertIn("mean_forward_command_error", row)
        self.assertEqual(row["initial_yaw_degrees"], scenario.initial_yaw_degrees)
        self.assertEqual(row["terrain_stage"], scenario.terrain_stage)

    def test_evaluation_rejects_reset_distance_that_disagrees_with_manifest(self):
        scenario = held_out_point_goals().scenarios[3]
        accumulator = EvaluationMetricAccumulator(scenario)

        with self.assertRaisesRegex(ValueError, "reset distance does not match manifest"):
            accumulator.reset({"distance_to_target": 0.75})

    def test_evaluation_requires_finite_reset_distance_and_planar_positions(self):
        scenario = held_out_point_goals().scenarios[3]

        with self.assertRaisesRegex(ValueError, "distance_to_target"):
            EvaluationMetricAccumulator(scenario).reset({
                "base_position_x": 0.0,
                "base_position_z": 0.0,
            })
        with self.assertRaisesRegex(ValueError, "base_position_z"):
            EvaluationMetricAccumulator(scenario).reset({
                "distance_to_target": 6.0,
                "base_position_x": 0.0,
            })

        accumulator = EvaluationMetricAccumulator(scenario)
        accumulator.reset({
            "distance_to_target": 6.0,
            "base_position_x": 0.0,
            "base_position_z": 0.0,
        })
        with self.assertRaisesRegex(ValueError, "base_position_x"):
            accumulator.observe({
                "base_position_x": float("nan"),
                "base_position_z": 0.0,
            })

    def test_evaluation_preserves_signed_progress_and_clamps_directness(self):
        scenario = held_out_point_goals().scenarios[3]
        accumulator = EvaluationMetricAccumulator(scenario)
        accumulator.reset({
            "distance_to_target": 6.0,
            "base_position_x": 0.0,
            "base_position_z": 0.0,
        })
        accumulator.observe({"base_position_x": 1.0, "base_position_z": 0.0})

        away = accumulator.row_fields(final_distance_to_target=7.0)
        self.assertEqual(away["progress_to_target_blocks"], -1.0)
        self.assertEqual(away["path_directness"], 0.0)

        within_tolerance = accumulator.row_fields(final_distance_to_target=4.99995)
        self.assertGreater(within_tolerance["progress_to_target_blocks"], 1.0)
        self.assertEqual(within_tolerance["path_directness"], 1.0)

    def test_evaluation_row_requires_finite_final_distance(self):
        scenario = held_out_point_goals().scenarios[3]
        accumulator = EvaluationMetricAccumulator(scenario)
        accumulator.reset({
            "distance_to_target": 6.0,
            "base_position_x": 0.0,
            "base_position_z": 0.0,
        })
        accumulator.observe({"base_position_x": 1.0, "base_position_z": 0.0})

        with self.assertRaisesRegex(ValueError, "distance_to_target"):
            _row(
                scenario,
                0,
                1,
                0.0,
                0.0,
                {"success": False, "termination_reason": "NONE"},
                accumulator,
                timed_out=False,
            )

    def test_evaluation_rejects_progress_larger_than_measured_path(self):
        scenario = held_out_point_goals().scenarios[3]
        accumulator = EvaluationMetricAccumulator(scenario)
        accumulator.reset({
            "distance_to_target": 6.0,
            "base_position_x": 0.0,
            "base_position_z": 0.0,
        })
        accumulator.observe({"base_position_x": 0.25, "base_position_z": 0.0})

        with self.assertRaisesRegex(ValueError, "geometry invariant failed"):
            accumulator.row_fields(final_distance_to_target=0.0)

    def test_best_promotion_requires_complete_manifest(self):
        manifest_size = len(held_out_point_goals().scenarios)

        _require_complete_manifest_for_promotion(
            requested_episodes=0,
            evaluated_episodes=manifest_size,
            manifest_episodes=manifest_size,
        )
        with self.assertRaisesRegex(ValueError, "complete held-out manifest"):
            _require_complete_manifest_for_promotion(
                requested_episodes=1,
                evaluated_episodes=1,
                manifest_episodes=manifest_size,
            )

    def test_evaluation_requires_policy_metadata_by_default(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.zip"

            with self.assertRaisesRegex(ValueError, "missing model metadata"):
                _load_and_validate_policy_metadata(
                    model_path,
                    morphology_id="minecraft_machines:duopod",
                    specs=_specs(),
                    allow_missing=False,
                )

            metadata, source = _load_and_validate_policy_metadata(
                model_path,
                morphology_id="minecraft_machines:duopod",
                specs=_specs(),
                allow_missing=True,
            )
            self.assertIsNone(metadata)
            self.assertIsNone(source)

    def test_evaluation_validates_policy_metadata_schema_hashes(self):
        with tempfile.TemporaryDirectory() as tmp:
            model_path = Path(tmp) / "model.zip"
            metadata_path = model_metadata_path(model_path)
            metadata_path.write_text(json.dumps({
                "morphology_id": "minecraft_machines:duopod",
                "observation_schema_hash": "obs-hash",
                "action_schema_hash": "act-hash",
            }), encoding="utf-8")

            metadata, source = _load_and_validate_policy_metadata(
                model_path,
                morphology_id="minecraft_machines:duopod",
                specs=_specs(),
                allow_missing=False,
            )
            self.assertEqual(source, metadata_path)
            self.assertEqual(metadata["observation_schema_hash"], "obs-hash")

            bad_specs = dict(_specs())
            bad_specs["actionSchemaHash"] = "changed"
            with self.assertRaisesRegex(ValueError, "action schema hash mismatch"):
                _load_and_validate_policy_metadata(
                    model_path,
                    morphology_id="minecraft_machines:duopod",
                    specs=bad_specs,
                    allow_missing=False,
                )

    def test_policy_record_includes_model_and_metadata_source(self):
        record = _policy_record(Path("runs/demo/model.zip"), {"format": "test"}, Path("runs/demo/model.metadata.json"))

        self.assertEqual(record["model_path"], "runs/demo/model.zip")
        self.assertEqual(record["metadata_path"], "runs/demo/model.metadata.json")
        self.assertEqual(record["metadata"], {"format": "test"})

    def test_evaluation_cli_writes_json_csv_and_policy_provenance(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            model_path = root / "model.zip"
            output_path = root / "evaluation.json"
            model_path.write_text("model", encoding="utf-8")
            model_metadata_path(model_path).write_text(json.dumps({
                "morphology_id": "minecraft_machines:duopod",
                "observation_schema_hash": "obs-hash",
                "action_schema_hash": "act-hash",
            }), encoding="utf-8")

            with fake_sb3():
                evaluation = importlib.import_module("minecraft_machines_training.evaluate")
                original_vec_env = evaluation.MinecraftMachinesVecEnv
                _FakeEvaluationVecEnv.reset_instances()
                evaluation.MinecraftMachinesVecEnv = _FakeEvaluationVecEnv
                try:
                    result = evaluation.main([
                        "--port", "1234",
                        "--token", "token",
                        "--model", str(model_path),
                        "--output", str(output_path),
                        "--episodes", "2",
                        "--max-steps", "2",
                    ])
                finally:
                    evaluation.MinecraftMachinesVecEnv = original_vec_env

            payload = json.loads(output_path.read_text(encoding="utf-8"))
            csv_text = output_path.with_suffix(".csv").read_text(encoding="utf-8")
            env = _FakeEvaluationVecEnv.instances[-1]

        self.assertEqual(result, 0)
        self.assertTrue(env.closed)
        self.assertEqual(_FakeEvaluationPPO.loaded_paths[-1], model_path)
        self.assertEqual(len(env.set_targets_calls), 1)
        self.assertEqual(env.reset_calls, 1)
        self.assertEqual(env.step_calls, 1)
        self.assertEqual(payload["policy"]["model_path"], str(model_path))
        self.assertEqual(payload["policy"]["metadata"]["observation_schema_hash"], "obs-hash")
        self.assertEqual(payload["evaluation_contract"], evaluation_contract())
        self.assertEqual(payload["scenario_manifest"]["compatibility_hash"], held_out_point_goals().compatibility_hash())
        self.assertEqual(payload["summary"]["episodes"], 2)
        self.assertTrue(payload["summary"]["evaluation_valid"])
        self.assertEqual(payload["summary"]["success_rate"], 1.0)
        self.assertEqual(len(payload["episodes"]), 2)
        self.assertIn("initial_yaw_degrees", payload["episodes"][0])
        self.assertEqual(payload["episodes"][0]["terrain_stage"], "minecraft_terrain_point_goals")
        self.assertIn("scenario_id", csv_text)
        self.assertIn("initial_yaw_degrees", csv_text)
        self.assertIn("terrain_stage", csv_text)
        self.assertIn("bearing_m075_distance_06", csv_text)

    def test_evaluation_cli_rejects_nonpositive_max_steps_before_opening_bridge(self):
        evaluation = importlib.import_module("minecraft_machines_training.evaluate")

        with self.assertRaisesRegex(ValueError, "--max-steps must be positive"):
            evaluation.main([
                "--port", "1234",
                "--token", "token",
                "--model", "missing.zip",
                "--max-steps", "0",
            ])


def _evaluation_payload(manifest: dict, *, success_rate: float, mean_return: float = 1.0) -> dict:
    success_count = round(success_rate * len(manifest["scenarios"]))
    episodes = []
    for index, scenario in enumerate(manifest["scenarios"]):
        success = index < success_count
        episodes.append({
            "scenario_id": scenario["id"],
            "target_bearing_degrees": scenario["target_bearing_degrees"],
            "target_distance_blocks": scenario["target_distance_blocks"],
            "initial_yaw_degrees": scenario.get("initial_yaw_degrees", 0.0),
            "terrain_stage": scenario.get("terrain_stage", "minecraft_terrain_point_goals"),
            "initial_distance_to_target": scenario["target_distance_blocks"],
            "steps": 12,
            "return": mean_return,
            "success": success,
            "termination_reason": "SUCCESS" if success else "EVALUATION_STEP_LIMIT",
            "final_distance_to_target": 0.0 if success else scenario["target_distance_blocks"],
            "progress_to_target_blocks": scenario["target_distance_blocks"] if success else 0.0,
            "distance_travelled_blocks": scenario["target_distance_blocks"] if success else 0.0,
            "path_directness": 1.0 if success else 0.0,
            "action_total_variation": 0.25,
            "time_to_target_steps": 12 if success else None,
            "mean_servo_load": 0.25,
            "peak_servo_load": 0.5,
            "mean_forward_command_error": 0.1,
            "mean_yaw_command_error": 0.2,
        })
    actual_success_rate = sum(row["success"] for row in episodes) / len(episodes)
    return {
        "evaluation_contract": evaluation_contract(),
        "scenario_manifest": manifest,
        "policy": {
            "policy_type": "test",
            "morphology_id": "minecraft_machines:duopod",
            "observation_schema_hash": "obs-hash",
            "action_schema_hash": "act-hash",
        },
        "summary": {
            "evaluation_valid": True,
            "episodes": len(episodes),
            "success_rate": actual_success_rate,
            "mean_return": mean_return,
            "worst_return": mean_return,
            "failure_rate": 0.0,
            "mean_final_distance": sum(row["final_distance_to_target"] for row in episodes) / len(episodes),
            "mean_distance_travelled_blocks": sum(row["distance_travelled_blocks"] for row in episodes) / len(episodes),
            "mean_path_directness": sum(row["path_directness"] for row in episodes) / len(episodes),
            "mean_servo_load": 0.25,
            "peak_servo_load": 0.5,
            "mean_forward_command_error": 0.1,
            "mean_yaw_command_error": 0.2,
            "mean_time_to_target_steps": 12.0 if success_count > 0 else None,
        },
        "episodes": episodes,
    }


class _FakeEvaluationModel:
    def __init__(self):
        self.calls = 0

    def predict(self, obs, deterministic=True):
        self.calls += 1
        batch = np.asarray(obs).shape[0]
        actions = np.zeros((batch, 2), dtype=np.float64)
        actions[:, 0] = 0.1 * self.calls
        actions[:, 1] = -0.1 * self.calls
        return actions, None


class _FakeEvaluationVecEnv:
    instances: list["_FakeEvaluationVecEnv"] = []

    @classmethod
    def reset_instances(cls):
        cls.instances = []

    def __init__(self, *args, **kwargs):
        self.num_envs = 2
        self.action_space = SimpleNamespace(shape=(2,))
        self.client = SimpleNamespace(specs=_specs())
        self.reset_infos: list[dict] = []
        self.set_targets_calls: list[list[tuple[float, float]]] = []
        self.reset_calls = 0
        self.step_calls = 0
        self.closed = False
        type(self).instances.append(self)

    def set_targets(self, targets):
        self.set_targets_calls.append(list(targets))

    def reset(self):
        self.reset_calls += 1
        self.reset_infos = [self._info(slot, event="reset", success=False) for slot in range(self.num_envs)]
        return np.zeros((self.num_envs, 2), dtype=np.float32)

    def step(self, actions):
        self.step_calls += 1
        infos = [self._info(slot, event="step", success=True) for slot in range(self.num_envs)]
        observations = np.full((self.num_envs, 2), float(self.step_calls), dtype=np.float32)
        rewards = np.array([1.0, 2.0], dtype=np.float32)
        dones = np.array([True, True], dtype=bool)
        return observations, rewards, dones, infos

    def close(self):
        self.closed = True

    def _info(self, slot: int, *, event: str, success: bool) -> dict:
        return {
            "event": event,
            "success": success,
            "termination_reason": "SUCCESS" if success else "NONE",
            "distance_to_target": 0.0 if success else 6.0,
            "base_position_x": float(slot + 6 * self.step_calls),
            "base_position_z": float(slot),
            "desired_forward_velocity": 1.0,
            "local_forward_velocity": 0.75,
            "desired_yaw_rate": 0.2,
            "local_yaw_rate": 0.1,
            "mean_servo_load": 0.5 + slot,
            "peak_servo_load": 1.0 + slot,
        }


@contextlib.contextmanager
def fake_sb3():
    saved = sys.modules.get("stable_baselines3", _MISSING)
    _FakeEvaluationPPO.reset()
    try:
        stable_baselines3 = types.ModuleType("stable_baselines3")
        stable_baselines3.PPO = _FakeEvaluationPPO
        sys.modules["stable_baselines3"] = stable_baselines3
        yield
    finally:
        if saved is _MISSING:
            sys.modules.pop("stable_baselines3", None)
        else:
            sys.modules["stable_baselines3"] = saved


class _FakeEvaluationPPO:
    loaded_paths: list[Path] = []

    @classmethod
    def reset(cls):
        cls.loaded_paths = []

    @classmethod
    def load(cls, path, env):
        cls.loaded_paths.append(Path(path))
        return _FakeEvaluationModel()


def _specs():
    return {
        "observationSchemaHash": "obs-hash",
        "actionSchemaHash": "act-hash",
    }


if __name__ == "__main__":
    unittest.main()
