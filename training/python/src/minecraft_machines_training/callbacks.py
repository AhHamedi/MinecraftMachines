from __future__ import annotations

from pathlib import Path
from typing import Any

from .metrics import write_json
from .training_metadata import update_training_metadata, write_model_metadata

try:
    from stable_baselines3.common.callbacks import BaseCallback
except ImportError:  # pragma: no cover
    class BaseCallback:  # type: ignore[no-redef]
        def __init__(self, verbose: int = 0):
            self.verbose = verbose
            self.model = None


class MetadataCheckpointCallback(BaseCallback):
    def __init__(
        self,
        metadata_path: Path,
        metadata: dict[str, Any],
        *,
        final_metadata_path: Path | None = None,
        checkpoint_dir: Path | None = None,
        checkpoint_interval_steps: int = 0,
        checkpoint_prefix: str = "ppo",
        verbose: int = 0,
    ):
        super().__init__(verbose)
        self.metadata_path = metadata_path
        self.metadata = dict(metadata)
        self.final_metadata_path = final_metadata_path
        self.checkpoint_dir = checkpoint_dir
        self.checkpoint_interval_steps = int(checkpoint_interval_steps)
        self.checkpoint_prefix = checkpoint_prefix
        self._last_checkpoint_step = 0
        self._latest_checkpoint_path: Path | None = None

    @property
    def latest_checkpoint_path(self) -> Path | None:
        return self._latest_checkpoint_path

    def _on_training_start(self) -> None:
        self._write_metadata(self.metadata_path, training_complete=False)

    def _on_training_end(self) -> None:
        self._write_metadata(self.metadata_path, training_complete=True)
        if self.final_metadata_path is not None and self.final_metadata_path != self.metadata_path:
            self._write_metadata(self.final_metadata_path, training_complete=True)

    def _on_step(self) -> bool:
        if self.checkpoint_interval_steps <= 0:
            return True
        step = self._current_timesteps()
        if step <= 0 or step - self._last_checkpoint_step < self.checkpoint_interval_steps:
            return True
        if self.checkpoint_dir is None:
            return True
        self.checkpoint_dir.mkdir(parents=True, exist_ok=True)
        checkpoint_path = self.checkpoint_dir / f"{self.checkpoint_prefix}_{step}_steps.zip"
        if self.model is not None:
            self.model.save(checkpoint_path)
        self._latest_checkpoint_path = checkpoint_path
        self._last_checkpoint_step = step
        checkpoint_metadata = self._metadata(training_complete=False, model_path=checkpoint_path)
        write_model_metadata(checkpoint_path, checkpoint_metadata)
        self._write_metadata(self.metadata_path, training_complete=False)
        return True

    def _write_metadata(self, path: Path, *, training_complete: bool) -> None:
        write_json(path, self._metadata(training_complete=training_complete))

    def _metadata(self, *, training_complete: bool, model_path: Path | None = None) -> dict[str, Any]:
        return update_training_metadata(
            self.metadata,
            num_timesteps=self._current_timesteps(),
            model_path=model_path,
            latest_checkpoint_path=self._latest_checkpoint_path,
            training_complete=training_complete,
        )

    def _current_timesteps(self) -> int:
        if self.model is not None and getattr(self.model, "num_timesteps", None) is not None:
            return int(getattr(self.model, "num_timesteps"))
        return int(getattr(self, "num_timesteps", 0) or 0)
