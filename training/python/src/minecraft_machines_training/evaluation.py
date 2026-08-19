from __future__ import annotations

from dataclasses import dataclass
import hashlib
import math
from typing import Any


EVALUATION_CONTRACT_ID = "duopod_planar_evaluation_v1"
EVALUATION_CONTRACT_VERSION = 1
HELD_OUT_POINT_GOALS_ID = "duopod_held_out_point_goals_v3"
HELD_OUT_POINT_GOALS_VERSION = 3
HELD_OUT_POINT_GOALS_COMPATIBILITY_HASH = "b3d9ccc73651bfe943aada11d30e9116b80cead6fc3270006c275520f06bd09f"


def evaluation_contract() -> dict[str, Any]:
    """Describe the geometry and terminal-state semantics behind an evaluation."""
    return {
        "id": EVALUATION_CONTRACT_ID,
        "version": EVALUATION_CONTRACT_VERSION,
        "target_geometry": "world_horizontal_xz",
        "terminal_state": "first_terminal",
        "distance_units": "blocks",
    }


@dataclass(frozen=True)
class EvaluationScenario:
    id: str
    target_bearing_degrees: float
    target_distance_blocks: float
    initial_yaw_degrees: float = 0.0
    terrain_stage: str = "minecraft_terrain_point_goals"

    def target_offset(self) -> tuple[float, float]:
        bearing_rad = math.radians(self.target_bearing_degrees)
        return (
            math.cos(bearing_rad) * self.target_distance_blocks,
            math.sin(bearing_rad) * self.target_distance_blocks,
        )

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "target_bearing_degrees": self.target_bearing_degrees,
            "target_distance_blocks": self.target_distance_blocks,
            "initial_yaw_degrees": self.initial_yaw_degrees,
            "terrain_stage": self.terrain_stage,
        }


@dataclass(frozen=True)
class EvaluationManifest:
    id: str
    version: int
    scenarios: tuple[EvaluationScenario, ...]

    def compatibility_hash(self) -> str:
        digest = hashlib.sha256()
        _update(digest, self.id)
        _update(digest, str(self.version))
        for scenario in self.scenarios:
            _update(digest, scenario.id)
            _update(digest, _format_float(scenario.target_bearing_degrees))
            _update(digest, _format_float(scenario.target_distance_blocks))
            _update(digest, _format_float(scenario.initial_yaw_degrees))
            _update(digest, scenario.terrain_stage)
        return digest.hexdigest()

    def to_dict(self) -> dict[str, Any]:
        return {
            "id": self.id,
            "version": self.version,
            "compatibility_hash": self.compatibility_hash(),
            "scenarios": [scenario.to_dict() for scenario in self.scenarios],
        }


def held_out_point_goals() -> EvaluationManifest:
    # Training uses bearings -90/-60/-30/0/30/60/90 at 4/8 blocks.
    # Every v3 pair is therefore genuinely unseen during the enumerated
    # point-goal curriculum (new bearings, new distances, or both).
    bearings = (-75.0, -45.0, -15.0, 0.0, 15.0, 45.0, 75.0)
    distances = (6.0, 10.0, 12.0)
    scenarios: list[EvaluationScenario] = []
    for distance in distances:
        for bearing in bearings:
            scenarios.append(
                EvaluationScenario(
                    id=_scenario_id(bearing, distance),
                    target_bearing_degrees=bearing,
                    target_distance_blocks=distance,
                )
            )
    manifest = EvaluationManifest(
        HELD_OUT_POINT_GOALS_ID,
        HELD_OUT_POINT_GOALS_VERSION,
        tuple(scenarios),
    )
    actual_hash = manifest.compatibility_hash()
    if actual_hash != HELD_OUT_POINT_GOALS_COMPATIBILITY_HASH:
        raise RuntimeError(
            "current held-out manifest changed without a version/hash update: "
            f"{actual_hash} != {HELD_OUT_POINT_GOALS_COMPATIBILITY_HASH}"
        )
    return manifest


def _scenario_id(bearing_degrees: float, distance_blocks: float) -> str:
    sign = "p" if bearing_degrees >= 0 else "m"
    return f"bearing_{sign}{abs(bearing_degrees):03.0f}_distance_{distance_blocks:02.0f}"


def _format_float(value: float) -> str:
    return f"{value:.6f}"


def _update(digest: "hashlib._Hash", value: str) -> None:
    digest.update(value.encode("utf-8"))
    digest.update(b"\0")
