from __future__ import annotations

import argparse
from dataclasses import dataclass
import importlib.util
import os
from pathlib import Path
import shlex
from typing import Callable

from .client import MinecraftMachinesBridgeClient
from .configuration import BridgeConnectionConfig
from .training_metadata import load_resume_metadata, resume_metadata_candidates, validate_metadata_compatibility


Mode = str


@dataclass(frozen=True)
class LiveRunbookSettings:
    host: str
    port: int
    token: str
    morphology: str
    slot_count: int
    model_path: Path | None
    cem_evaluation_path: Path
    run_name: str
    output_dir: Path
    timesteps: int
    python: str
    timeout_seconds: float


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Print and optionally preflight live Minecraft Machines PPO bridge commands.")
    parser.add_argument("--mode", choices=["basic", "ppo", "eval", "all"], default="all")
    parser.add_argument("--host", default=os.environ.get("MM_TRAINING_BRIDGE_HOST", "127.0.0.1"))
    parser.add_argument("--port", type=int, default=_env_int("MM_TRAINING_BRIDGE_PORT", 0))
    parser.add_argument("--token", default=os.environ.get("MM_TRAINING_BRIDGE_TOKEN", ""))
    parser.add_argument("--morphology", default=os.environ.get("MM_TRAINING_BRIDGE_MORPHOLOGY", "minecraft_machines:duopod"))
    parser.add_argument("--slot-count", type=int, default=32)
    parser.add_argument("--model", type=Path, default=_env_path("MM_TRAINING_BRIDGE_LIVE_MODEL"))
    parser.add_argument("--cem-evaluation", type=Path, default=_env_path("MM_DUOPOD_CEM_EVALUATION") or Path("minecraft_machines/duopod_cem_evaluation_latest.json"))
    parser.add_argument("--run-name", default="duopod_ppo")
    parser.add_argument("--output-dir", type=Path, default=Path("runs"))
    parser.add_argument("--timesteps", type=int, default=1_000_000)
    parser.add_argument("--python", default="python3")
    parser.add_argument("--timeout-seconds", type=float, default=float(os.environ.get("MM_TRAINING_BRIDGE_TIMEOUT_SECONDS", "180")))
    parser.add_argument("--check", action="store_true", help="validate local environment variables, dependencies, and model metadata")
    parser.add_argument("--connect", action="store_true", help="during --check, connect to the bridge and validate live schema compatibility")
    args = parser.parse_args(argv)

    settings = LiveRunbookSettings(
        host=args.host,
        port=args.port,
        token=args.token,
        morphology=args.morphology,
        slot_count=args.slot_count,
        model_path=args.model.expanduser() if args.model is not None else None,
        cem_evaluation_path=args.cem_evaluation.expanduser(),
        run_name=args.run_name,
        output_dir=args.output_dir,
        timesteps=args.timesteps,
        python=args.python,
        timeout_seconds=args.timeout_seconds,
    )
    modes = _selected_modes(args.mode)
    print(render_runbook(settings, modes))
    if not args.check and not args.connect:
        return 0

    issues = preflight(settings, modes, connect=args.connect)
    if issues:
        print("\nPreflight failed:")
        for issue in issues:
            print(f"- {issue}")
        return 2
    print("\nPreflight passed.")
    return 0


def render_runbook(settings: LiveRunbookSettings, modes: set[Mode]) -> str:
    model_path = str(settings.model_path) if settings.model_path is not None else "runs/duopod_ppo/model.zip"
    evaluation_output = settings.output_dir / settings.run_name / "evaluation.json"
    comparison_output = settings.output_dir / settings.run_name / "cem_vs_ppo_comparison.json"
    target_change_output = settings.output_dir / settings.run_name / "target_change_validation.json"
    lines = [
        "Minecraft Machines live PPO bridge runbook",
        "",
        "Start the bridge in-game:",
        _code(f"/mm train bridge start duopod {settings.slot_count}"),
        "",
        "Start the bridge from a dedicated-server console or command block with an explicit spawn origin:",
        _code(f"/mm train bridge start duopod_at <x> <y> <z> north {settings.slot_count}"),
    ]
    if "basic" in modes:
        lines.extend([
            "",
            "Run basic live bridge reset/step/UPDATE_TARGETS smoke tests:",
            _code(_env_command(settings, {"MM_TRAINING_BRIDGE_LIVE": "1"}, f"{settings.python} -m unittest training/python/tests/test_live_bridge.py")),
        ])
    if "ppo" in modes:
        lines.extend([
            "",
            "Run the tiny live PPO training smoke test:",
            _code(_env_command(settings, {
                "MM_TRAINING_BRIDGE_LIVE": "1",
                "MM_TRAINING_BRIDGE_LIVE_PPO": "1",
            }, f"{settings.python} -m unittest training/python/tests/test_live_bridge.py")),
            "",
            "Run a full PPO training job:",
            _code(
                f"{settings.python} -m minecraft_machines_training.train_ppo \\\n"
                f"  --host {_shell(settings.host)} \\\n"
                f"  --port {settings.port} \\\n"
                f"  --token {_shell(_display_token(settings.token))} \\\n"
                f"  --morphology {_shell(settings.morphology)} \\\n"
                f"  --envs {settings.slot_count} \\\n"
                f"  --timesteps {settings.timesteps} \\\n"
                f"  --curriculum minecraft_terrain_commands \\\n"
                f"  --run-name {_shell(settings.run_name)} \\\n"
                f"  --output-dir {_shell(str(settings.output_dir))}"
            ),
        ])
    if "eval" in modes:
        lines.extend([
            "",
            "Run saved-model live evaluation and target-change test wrappers:",
            _code(_env_command(settings, {
                "MM_TRAINING_BRIDGE_LIVE": "1",
                "MM_TRAINING_BRIDGE_LIVE_EVAL": "1",
                "MM_TRAINING_BRIDGE_LIVE_MODEL": model_path,
            }, f"{settings.python} -m unittest training/python/tests/test_live_bridge.py")),
            "",
            "Evaluate the saved PPO model directly:",
            _code(
                f"{settings.python} -m minecraft_machines_training.evaluate \\\n"
                f"  --host {_shell(settings.host)} \\\n"
                f"  --port {settings.port} \\\n"
                f"  --token {_shell(_display_token(settings.token))} \\\n"
                f"  --morphology {_shell(settings.morphology)} \\\n"
                f"  --model {_shell(model_path)} \\\n"
                f"  --output {_shell(str(evaluation_output))}"
            ),
            "",
            "Compare the latest Java CEM evaluation against the PPO evaluation:",
            _code(
                f"{settings.python} -m minecraft_machines_training.compare \\\n"
                f"  --left {_shell(str(settings.cem_evaluation_path))} \\\n"
                f"  --right {_shell(str(evaluation_output))} \\\n"
                f"  --left-label cem \\\n"
                f"  --right-label ppo \\\n"
                f"  --output {_shell(str(comparison_output))}"
            ),
            "",
            "Validate no-reset target changes directly:",
            _code(
                f"{settings.python} -m minecraft_machines_training.validate_target_change \\\n"
                f"  --host {_shell(settings.host)} \\\n"
                f"  --port {settings.port} \\\n"
                f"  --token {_shell(_display_token(settings.token))} \\\n"
                f"  --morphology {_shell(settings.morphology)} \\\n"
                f"  --model {_shell(model_path)} \\\n"
                f"  --output {_shell(str(target_change_output))} \\\n"
                f"  --switch-step 32 \\\n"
                f"  --max-steps 96"
            ),
        ])
    lines.extend([
        "",
        "Run this preflight without touching Minecraft state:",
        _code(_preflight_command(settings, modes, connect=False)),
        "",
        "Run this schema preflight only after the in-game bridge is already running:",
        _code(_preflight_command(settings, modes, connect=True)),
    ])
    return "\n".join(lines)


def preflight(
    settings: LiveRunbookSettings,
    modes: set[Mode],
    *,
    connect: bool = False,
    dependency_available: Callable[[str], bool] | None = None,
) -> list[str]:
    dependency_available = dependency_available or _dependency_available
    issues: list[str] = []
    if not settings.host:
        issues.append("missing bridge host")
    if settings.port <= 0:
        issues.append("missing bridge port; set MM_TRAINING_BRIDGE_PORT or pass --port")
    if not settings.token:
        issues.append("missing bridge token; set MM_TRAINING_BRIDGE_TOKEN or pass --token")
    if settings.slot_count <= 0:
        issues.append("slot count must be positive")
    if settings.timesteps <= 0:
        issues.append("timesteps must be positive")

    if "ppo" in modes or "eval" in modes:
        for module in ("stable_baselines3", "torch"):
            if not dependency_available(module):
                issues.append(f"missing optional Python dependency: {module}")

    metadata = None
    if "eval" in modes:
        if settings.model_path is None:
            issues.append("missing saved model path; set MM_TRAINING_BRIDGE_LIVE_MODEL or pass --model")
        elif not settings.model_path.is_file():
            issues.append(f"saved model does not exist: {settings.model_path}")
        else:
            metadata, metadata_path = load_resume_metadata(settings.model_path)
            if metadata is None:
                candidates = ", ".join(str(path) for path in resume_metadata_candidates(settings.model_path))
                issues.append(f"missing model metadata sidecar; expected one of: {candidates}")
            elif metadata_path is None:
                issues.append("model metadata sidecar lookup failed")

    if connect and not issues:
        config = BridgeConnectionConfig(
            settings.host,
            settings.port,
            settings.token,
            settings.morphology,
            timeout_seconds=settings.timeout_seconds,
        )
        client = MinecraftMachinesBridgeClient(config)
        try:
            specs = client.get_specs()
            if specs.get("morphologyId") != settings.morphology:
                issues.append(f"bridge morphology mismatch: {specs.get('morphologyId')} != {settings.morphology}")
            live_slot_count = _optional_int(specs.get("slotCount"))
            if live_slot_count is not None and live_slot_count != settings.slot_count:
                issues.append(f"bridge slot count mismatch: {live_slot_count} != {settings.slot_count}")
            if metadata is not None:
                try:
                    validate_metadata_compatibility(metadata, morphology_id=settings.morphology, specs=specs)
                except ValueError as exc:
                    issues.append(str(exc))
        except Exception as exc:  # pragma: no cover - exercised only with a live bridge
            issues.append(f"bridge connection failed: {exc}")
        finally:
            client.close()
    return issues


def _selected_modes(mode: str) -> set[Mode]:
    if mode == "all":
        return {"basic", "ppo", "eval"}
    return {mode}


def _env_command(settings: LiveRunbookSettings, extra_env: dict[str, str], command: str) -> str:
    env = {
        **extra_env,
        "MM_TRAINING_BRIDGE_HOST": settings.host,
        "MM_TRAINING_BRIDGE_PORT": str(settings.port) if settings.port > 0 else "<port>",
        "MM_TRAINING_BRIDGE_TOKEN": _display_token(settings.token),
        "MM_TRAINING_BRIDGE_MORPHOLOGY": settings.morphology,
        "PYTHONPATH": "training/python/src",
    }
    return " \\\n".join(f"{key}={_shell(value)}" for key, value in env.items()) + f" \\\n{command}"


def _preflight_command(settings: LiveRunbookSettings, modes: set[Mode], *, connect: bool) -> str:
    mode = "all" if len(modes) == 3 else next(iter(modes))
    lines = [
        f"{settings.python} -m minecraft_machines_training.live_runbook \\",
        f"  --mode {_shell(mode)} \\",
        f"  --host {_shell(settings.host)} \\",
        f"  --port {settings.port if settings.port > 0 else '<port>'} \\",
        f"  --token {_shell(_display_token(settings.token))} \\",
        f"  --morphology {_shell(settings.morphology)} \\",
        f"  --slot-count {settings.slot_count} \\",
        f"  --run-name {_shell(settings.run_name)} \\",
        f"  --output-dir {_shell(str(settings.output_dir))} \\",
        f"  --timesteps {settings.timesteps}",
    ]
    if settings.model_path is not None:
        lines[-1] += " \\"
        lines.append(f"  --model {_shell(str(settings.model_path))}")
    lines[-1] += " \\"
    lines.append("  --check")
    if connect:
        lines[-1] += " \\"
        lines.append("  --connect")
    return "\n".join(lines)


def _display_token(token: str) -> str:
    return token if token else "<token>"


def _code(text: str) -> str:
    return f"```bash\n{text}\n```"


def _shell(value: str) -> str:
    if value.startswith("<") and value.endswith(">"):
        return value
    return shlex.quote(value)


def _dependency_available(module: str) -> bool:
    return importlib.util.find_spec(module) is not None


def _optional_int(value: object) -> int | None:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def _env_int(name: str, default: int) -> int:
    value = os.environ.get(name)
    if value is None or value == "":
        return default
    try:
        return int(value)
    except ValueError:
        return default


def _env_path(name: str) -> Path | None:
    value = os.environ.get(name)
    if not value:
        return None
    return Path(value).expanduser()


if __name__ == "__main__":  # pragma: no cover
    raise SystemExit(main())
