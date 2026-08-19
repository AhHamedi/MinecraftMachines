from __future__ import annotations

import json
import socket
import struct
from dataclasses import dataclass
from enum import Enum
from typing import Any


PROTOCOL_VERSION = 1
DEFAULT_MAX_MESSAGE_BYTES = 4 * 1024 * 1024


class BridgeMessageType(str, Enum):
    HELLO = "HELLO"
    CREATE_SESSION = "CREATE_SESSION"
    GET_SPECS = "GET_SPECS"
    RESET_ALL = "RESET_ALL"
    RESET_MASK = "RESET_MASK"
    STEP = "STEP"
    SET_CURRICULUM = "SET_CURRICULUM"
    SET_TARGETS = "SET_TARGETS"
    UPDATE_TARGETS = "UPDATE_TARGETS"
    GET_METRICS = "GET_METRICS"
    CLOSE_SESSION = "CLOSE_SESSION"
    PING = "PING"
    ERROR = "ERROR"


class ProtocolError(RuntimeError):
    pass


class BridgeError(RuntimeError):
    pass


@dataclass(frozen=True)
class ProtocolEnvelope:
    type: BridgeMessageType
    requestId: str
    payload: dict[str, Any]

    def __post_init__(self) -> None:
        if not isinstance(self.type, BridgeMessageType):
            object.__setattr__(self, "type", BridgeMessageType(self.type))
        if not isinstance(self.requestId, str) or not self.requestId.strip():
            raise ProtocolError("requestId must not be blank")
        if self.payload is None:
            object.__setattr__(self, "payload", {})
        elif not isinstance(self.payload, dict):
            raise ProtocolError("payload must be an object")

    def to_json(self) -> str:
        return json.dumps(
            {"type": self.type.value, "requestId": self.requestId, "payload": self.payload},
            separators=(",", ":"),
        )

    @staticmethod
    def from_json(data: str) -> "ProtocolEnvelope":
        raw = json.loads(data)
        if not isinstance(raw, dict):
            raise ProtocolError("protocol envelope must be an object")
        if "type" not in raw or not isinstance(raw["type"], str) or not raw["type"].strip():
            raise ProtocolError("type must be a nonblank string")
        if "requestId" not in raw or not isinstance(raw["requestId"], str) or not raw["requestId"].strip():
            raise ProtocolError("requestId must not be blank")
        payload = raw["payload"] if "payload" in raw else {}
        if payload is None:
            payload = {}
        if not isinstance(payload, dict):
            raise ProtocolError("payload must be an object")
        try:
            message_type = BridgeMessageType(raw["type"])
        except ValueError as exc:
            raise ProtocolError(f"unsupported bridge message type {raw['type']}") from exc
        return ProtocolEnvelope(
            type=message_type,
            requestId=raw["requestId"],
            payload=payload,
        )


def write_envelope(sock: socket.socket, envelope: ProtocolEnvelope, max_bytes: int = DEFAULT_MAX_MESSAGE_BYTES) -> None:
    payload = envelope.to_json().encode("utf-8")
    if len(payload) > max_bytes:
        raise ProtocolError(f"message exceeds maximum size: {len(payload)} > {max_bytes}")
    sock.sendall(struct.pack(">I", len(payload)) + payload)


def read_envelope(sock: socket.socket, max_bytes: int = DEFAULT_MAX_MESSAGE_BYTES) -> ProtocolEnvelope:
    header = _read_exact(sock, 4)
    (length,) = struct.unpack(">I", header)
    if length > max_bytes:
        raise ProtocolError(f"message exceeds maximum size: {length} > {max_bytes}")
    payload = _read_exact(sock, length)
    return ProtocolEnvelope.from_json(payload.decode("utf-8"))


def _read_exact(sock: socket.socket, size: int) -> bytes:
    chunks: list[bytes] = []
    remaining = size
    while remaining:
        chunk = sock.recv(remaining)
        if not chunk:
            raise EOFError("bridge socket closed")
        chunks.append(chunk)
        remaining -= len(chunk)
    return b"".join(chunks)
