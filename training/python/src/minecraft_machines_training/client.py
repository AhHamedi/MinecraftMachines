from __future__ import annotations

import itertools
import math
import socket
from typing import Any, Sequence

from .configuration import BridgeConnectionConfig
from .protocol import (
    BridgeError,
    BridgeMessageType,
    PROTOCOL_VERSION,
    ProtocolError,
    ProtocolEnvelope,
    read_envelope,
    write_envelope,
)


class MinecraftMachinesBridgeClient:
    def __init__(self, config: BridgeConnectionConfig):
        config.validate()
        self.config = config
        self._sock: socket.socket | None = None
        self._request_counter = itertools.count(1)
        self.session_id: str | None = None
        self.specs: dict[str, Any] | None = None
        self.observation_hash: str | None = None
        self.action_hash: str | None = None

    def connect(self) -> None:
        if self._sock is not None:
            return
        self._sock = socket.create_connection((self.config.host, self.config.port), timeout=self.config.timeout_seconds)
        self._sock.settimeout(self.config.timeout_seconds)

    def close(self) -> None:
        try:
            if self._sock is not None and self.session_id is not None:
                self.request(BridgeMessageType.CLOSE_SESSION, {"sessionId": self.session_id})
        finally:
            if self._sock is not None:
                self._sock.close()
            self._sock = None
            self.session_id = None

    def hello(self) -> dict[str, Any]:
        response = self.request(BridgeMessageType.HELLO, {})
        self._validate_specs(response)
        return response

    def create_session(self) -> dict[str, Any]:
        payload = {"token": self.config.token, "morphologyId": self.config.morphology}
        response = self.request(BridgeMessageType.CREATE_SESSION, payload)
        self._validate_specs(response)
        self.session_id = response["sessionId"]
        if "reset" in response:
            self._validate_reset_payload(response["reset"], label="create_session.reset")
        return response

    def get_specs(self) -> dict[str, Any]:
        response = self.request(BridgeMessageType.GET_SPECS, {})
        self._validate_specs(response)
        return response

    def reset_all(self) -> dict[str, Any]:
        self._require_session()
        response = self.request(BridgeMessageType.RESET_ALL, {"sessionId": self.session_id})
        self._validate_reset_payload(response, label="reset_all")
        return response

    def reset_mask(self, mask: Sequence[bool]) -> dict[str, Any]:
        self._require_session()
        response = self.request(BridgeMessageType.RESET_MASK, {"sessionId": self.session_id, "mask": self._validate_mask(mask)})
        self._validate_reset_payload(response, label="reset_mask")
        return response

    def step(self, actions: Sequence[Sequence[float]]) -> dict[str, Any]:
        self._require_session()
        payload = {
            "sessionId": self.session_id,
            "observationSchemaHash": self.observation_hash,
            "actionSchemaHash": self.action_hash,
            "actions": self._validate_action_batch(actions),
        }
        response = self.request(BridgeMessageType.STEP, payload)
        self._validate_step_payload(response)
        return response

    def set_curriculum(self, stage: str) -> dict[str, Any]:
        self._require_session()
        response = self.request(BridgeMessageType.SET_CURRICULUM, {"sessionId": self.session_id, "curriculumStage": stage})
        if "reset" in response:
            self._validate_reset_payload(response["reset"], label="set_curriculum.reset")
        return response

    def set_targets(self, targets: Sequence[Sequence[float]]) -> dict[str, Any]:
        self._require_session()
        response = self.request(BridgeMessageType.SET_TARGETS, {
            "sessionId": self.session_id,
            "targets": self._validate_numeric_matrix(targets, label="targets", expected_columns=2),
        })
        if "reset" in response:
            self._validate_reset_payload(response["reset"], label="set_targets.reset")
        return response

    def set_commands(self, commands: Sequence[Sequence[float]]) -> dict[str, Any]:
        self._require_session()
        response = self.request(BridgeMessageType.SET_TARGETS, {
            "sessionId": self.session_id,
            "commands": self._validate_numeric_matrix(commands, label="commands", expected_columns=3),
        })
        if "reset" in response:
            self._validate_reset_payload(response["reset"], label="set_commands.reset")
        return response

    def update_targets(self, targets: Sequence[Sequence[float]]) -> dict[str, Any]:
        self._require_session()
        response = self.request(BridgeMessageType.UPDATE_TARGETS, {
            "sessionId": self.session_id,
            "targets": self._validate_numeric_matrix(targets, label="targets", expected_columns=2),
        })
        if "update" in response:
            self._validate_reset_payload(response["update"], label="update_targets.update")
        return response

    def update_commands(self, commands: Sequence[Sequence[float]]) -> dict[str, Any]:
        self._require_session()
        response = self.request(BridgeMessageType.UPDATE_TARGETS, {
            "sessionId": self.session_id,
            "commands": self._validate_numeric_matrix(commands, label="commands", expected_columns=3),
        })
        if "update" in response:
            self._validate_reset_payload(response["update"], label="update_commands.update")
        return response

    def get_metrics(self) -> dict[str, Any]:
        self._require_session()
        return self.request(BridgeMessageType.GET_METRICS, {"sessionId": self.session_id})

    def ping(self) -> dict[str, Any]:
        return self.request(BridgeMessageType.PING, {})

    def request(self, message_type: BridgeMessageType, payload: dict[str, Any]) -> dict[str, Any]:
        request_id = f"py-{next(self._request_counter)}"
        try:
            self.connect()
            assert self._sock is not None
            write_envelope(self._sock, ProtocolEnvelope(message_type, request_id, payload))
            response = read_envelope(self._sock)
        except TimeoutError as exc:
            self._mark_disconnected()
            raise BridgeError(f"bridge request timed out for {message_type.value}") from exc
        except ProtocolError as exc:
            self._mark_disconnected()
            raise BridgeError(f"bridge protocol failed during {message_type.value}: {exc}") from exc
        except (EOFError, OSError) as exc:
            self._mark_disconnected()
            raise BridgeError(f"bridge connection failed during {message_type.value}: {exc}") from exc
        if response.requestId != request_id:
            self._mark_disconnected()
            raise BridgeError(f"request id mismatch: expected {request_id}, got {response.requestId}")
        if response.type == BridgeMessageType.ERROR:
            raise BridgeError(response.payload.get("message", "bridge error"))
        return response.payload

    def _validate_specs(self, payload: dict[str, Any]) -> None:
        if not isinstance(payload, dict):
            raise BridgeError("bridge specs payload must be an object")
        if payload.get("protocolVersion") != PROTOCOL_VERSION:
            raise BridgeError(f"unsupported bridge protocol version {payload.get('protocolVersion')}")
        if payload.get("morphologyId") != self.config.morphology:
            raise BridgeError(f"unexpected morphology {payload.get('morphologyId')}")
        self._validate_positive_int(payload.get("slotCount"), "slotCount")
        self._validate_positive_int(payload.get("controlTicks"), "controlTicks")
        self._validate_nonblank_string(payload.get("observationSchemaHash"), "observationSchemaHash")
        self._validate_nonblank_string(payload.get("actionSchemaHash"), "actionSchemaHash")
        self._validate_vector_spec(payload.get("observationSpec"), "observationSpec")
        self._validate_vector_spec(payload.get("actionSpec"), "actionSpec")
        self.specs = payload
        self.observation_hash = payload["observationSchemaHash"]
        self.action_hash = payload["actionSchemaHash"]

    def _validate_reset_payload(self, payload: dict[str, Any], *, label: str) -> None:
        slot_count = self._slot_count()
        observations = self._require_list(payload, "observations", label)
        infos = self._require_list(payload, "infos", label)
        if len(observations) != slot_count:
            raise BridgeError(f"{label}.observations slot count mismatch: {len(observations)} != {slot_count}")
        if len(infos) != slot_count:
            raise BridgeError(f"{label}.infos slot count mismatch: {len(infos)} != {slot_count}")
        for slot, observation in enumerate(observations):
            self._validate_observation(observation, f"{label}.observations[{slot}]")
        for slot, info in enumerate(infos):
            if not isinstance(info, dict):
                raise BridgeError(f"{label}.infos[{slot}] must be an object")

    def _validate_step_payload(self, payload: dict[str, Any]) -> None:
        slot_count = self._slot_count()
        observations = self._require_list(payload, "observations", "step")
        rewards = self._require_list(payload, "rewards", "step")
        terminated = self._require_list(payload, "terminated", "step")
        truncated = self._require_list(payload, "truncated", "step")
        infos = self._require_list(payload, "infos", "step")
        reset_observations = self._require_list(payload, "resetObservations", "step")
        for name, values in {
            "observations": observations,
            "rewards": rewards,
            "terminated": terminated,
            "truncated": truncated,
            "infos": infos,
            "resetObservations": reset_observations,
        }.items():
            if len(values) != slot_count:
                raise BridgeError(f"step.{name} slot count mismatch: {len(values)} != {slot_count}")
        for slot, observation in enumerate(observations):
            self._validate_observation(observation, f"step.observations[{slot}]")
        for slot, reward in enumerate(rewards):
            self._validate_finite_number(reward, f"step.rewards[{slot}]")
        for slot, value in enumerate(terminated):
            if not isinstance(value, bool):
                raise BridgeError(f"step.terminated[{slot}] must be a boolean")
        for slot, value in enumerate(truncated):
            if not isinstance(value, bool):
                raise BridgeError(f"step.truncated[{slot}] must be a boolean")
        for slot, info in enumerate(infos):
            if not isinstance(info, dict):
                raise BridgeError(f"step.infos[{slot}] must be an object")
        for slot, observation in enumerate(reset_observations):
            if not isinstance(observation, list):
                raise BridgeError(f"step.resetObservations[{slot}] must be a list")
            if observation:
                self._validate_observation(observation, f"step.resetObservations[{slot}]")

    def _validate_observation(self, observation: Any, label: str) -> None:
        fields = self._observation_fields()
        if not isinstance(observation, list):
            raise BridgeError(f"{label} must be a list")
        if len(observation) != len(fields):
            raise BridgeError(f"{label} size mismatch: {len(observation)} != {len(fields)}")
        for index, value in enumerate(observation):
            self._validate_finite_number(value, f"{label}[{index}]")
            field = fields[index]
            minimum = float(field["minimum"])
            maximum = float(field["maximum"])
            if value < minimum or value > maximum:
                raise BridgeError(f"{label}[{index}] out of bounds for {field['name']}: {value} not in [{minimum}, {maximum}]")

    def _validate_finite_number(self, value: Any, label: str) -> float:
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(float(value)):
            raise BridgeError(f"{label} must be finite")
        return float(value)

    def _validate_action_batch(self, actions: Sequence[Sequence[float]]) -> list[list[float]]:
        action_fields = self._action_fields()
        rows = self._validate_numeric_matrix(actions, label="actions", expected_columns=len(action_fields))
        for row_index, row in enumerate(rows):
            for column, value in enumerate(row):
                field = action_fields[column]
                minimum = float(field["minimum"])
                maximum = float(field["maximum"])
                if value < minimum or value > maximum:
                    raise BridgeError(
                        f"actions[{row_index}][{column}] out of bounds for {field['name']}: "
                        f"{value} not in [{minimum}, {maximum}]"
                    )
        return rows

    def _validate_numeric_matrix(
            self,
            matrix: Sequence[Sequence[float]],
            *,
            label: str,
            expected_columns: int
    ) -> list[list[float]]:
        try:
            rows = [list(row) for row in matrix]
        except TypeError as exc:
            raise BridgeError(f"{label} must be a matrix") from exc
        expected_rows = self._slot_count()
        if len(rows) != expected_rows:
            raise BridgeError(f"{label} row count mismatch: {len(rows)} != {expected_rows}")
        for row_index, row in enumerate(rows):
            if len(row) != expected_columns:
                raise BridgeError(f"{label}[{row_index}] column count mismatch: {len(row)} != {expected_columns}")
            for column, value in enumerate(row):
                row[column] = self._validate_finite_number(value, f"{label}[{row_index}][{column}]")
        return rows

    def _validate_mask(self, mask: Sequence[bool]) -> list[bool]:
        try:
            values = list(mask)
        except TypeError as exc:
            raise BridgeError("mask must be a boolean sequence") from exc
        expected_rows = self._slot_count()
        if len(values) != expected_rows:
            raise BridgeError(f"mask length mismatch: {len(values)} != {expected_rows}")
        for index, value in enumerate(values):
            if not isinstance(value, bool):
                raise BridgeError(f"mask[{index}] must be a boolean")
        return values

    def _require_list(self, payload: dict[str, Any], key: str, label: str) -> list[Any]:
        value = payload.get(key)
        if not isinstance(value, list):
            raise BridgeError(f"{label}.{key} must be a list")
        return value

    def _slot_count(self) -> int:
        if self.specs is None:
            raise BridgeError("bridge specs are not available")
        return int(self.specs["slotCount"])

    def _observation_fields(self) -> list[dict[str, Any]]:
        if self.specs is None:
            raise BridgeError("bridge specs are not available")
        return list(self.specs["observationSpec"]["fields"])

    def _action_fields(self) -> list[dict[str, Any]]:
        if self.specs is None:
            raise BridgeError("bridge specs are not available")
        return list(self.specs["actionSpec"]["fields"])

    def _validate_vector_spec(self, spec: Any, label: str) -> None:
        if not isinstance(spec, dict):
            raise BridgeError(f"{label} must be an object")
        self._validate_nonblank_string(spec.get("schemaId"), f"{label}.schemaId")
        self._validate_positive_int(spec.get("schemaVersion"), f"{label}.schemaVersion")
        fields = spec.get("fields")
        if not isinstance(fields, list) or not fields:
            raise BridgeError(f"{label}.fields must be a non-empty list")
        seen_names: set[str] = set()
        for index, field in enumerate(fields):
            field_label = f"{label}.fields[{index}]"
            if not isinstance(field, dict):
                raise BridgeError(f"{field_label} must be an object")
            name = self._validate_nonblank_string(field.get("name"), f"{field_label}.name")
            if name in seen_names:
                raise BridgeError(f"{field_label}.name is duplicated: {name}")
            seen_names.add(name)
            minimum = self._validate_finite_number(field.get("minimum"), f"{field_label}.minimum")
            maximum = self._validate_finite_number(field.get("maximum"), f"{field_label}.maximum")
            if minimum >= maximum:
                raise BridgeError(f"{field_label}.minimum must be lower than maximum")
            if "unit" in field and not isinstance(field["unit"], str):
                raise BridgeError(f"{field_label}.unit must be a string")
            if "description" in field and not isinstance(field["description"], str):
                raise BridgeError(f"{field_label}.description must be a string")

    def _validate_positive_int(self, value: Any, label: str) -> int:
        if isinstance(value, bool) or not isinstance(value, int) or value < 1:
            raise BridgeError(f"{label} must be a positive integer")
        return value

    def _validate_nonblank_string(self, value: Any, label: str) -> str:
        if not isinstance(value, str) or not value.strip():
            raise BridgeError(f"{label} must be a nonblank string")
        return value

    def _require_session(self) -> None:
        if self.session_id is None:
            raise BridgeError("create_session must be called first")

    def _mark_disconnected(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
        self._sock = None
        self.session_id = None

    def __enter__(self) -> "MinecraftMachinesBridgeClient":
        self.connect()
        return self

    def __exit__(self, exc_type, exc, tb) -> None:
        self.close()
