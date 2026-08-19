from __future__ import annotations

import copy
import socket
import struct
import threading
import time
import unittest
from contextlib import closing

from minecraft_machines_training.client import MinecraftMachinesBridgeClient
from minecraft_machines_training.configuration import BridgeConnectionConfig, PPOTrainingConfig
from minecraft_machines_training.protocol import (
    BridgeError,
    BridgeMessageType,
    PROTOCOL_VERSION,
    ProtocolError,
    ProtocolEnvelope,
    read_envelope,
    write_envelope,
)


def specs_payload(
    slot_count: int = 2,
    protocol_version: int = PROTOCOL_VERSION,
    morphology_id: str = "minecraft_machines:duopod",
):
    observation_fields = [
        {"name": "obs_0", "minimum": -1.0, "maximum": 1.0, "unit": "normalized", "description": "test"},
        {"name": "obs_1", "minimum": -1.0, "maximum": 1.0, "unit": "normalized", "description": "test"},
    ]
    action_fields = [
        {"name": "act_0", "minimum": -1.0, "maximum": 1.0, "unit": "normalized", "description": "test"},
    ]
    return {
        "protocolVersion": protocol_version,
        "modVersion": "test",
        "morphologyId": morphology_id,
        "observationSpec": {"schemaId": "test:obs", "schemaVersion": 1, "fields": observation_fields},
        "actionSpec": {"schemaId": "test:act", "schemaVersion": 1, "fields": action_fields},
        "slotCount": slot_count,
        "controlTicks": 1,
        "curriculumStage": "flat_commands",
        "seed": 42,
        "observationSchemaHash": "obs-hash",
        "actionSchemaHash": "act-hash",
    }


class MockBridgeServer:
    def __init__(
        self,
        *,
        protocol_version: int = PROTOCOL_VERSION,
        token: str = "token",
        slot_count: int = 2,
        morphology_id: str = "minecraft_machines:duopod",
        specs_payload_override: dict | None = None,
        reset_payload: dict | None = None,
        step_payload: dict | None = None,
    ):
        self.protocol_version = protocol_version
        self.token = token
        self.slot_count = slot_count
        self.morphology_id = morphology_id
        self.specs_payload_override = specs_payload_override
        self.reset_payload = reset_payload
        self.step_payload = step_payload
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(1)
        self.port = self._sock.getsockname()[1]
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.session_id = "session"
        self.closed = threading.Event()
        self.targets = None
        self.commands = None
        self.last_step_payload = None

    def start(self):
        self.thread.start()

    def stop(self):
        with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as sock:
            try:
                sock.connect(("127.0.0.1", self.port))
            except OSError:
                pass
        self._sock.close()
        self.closed.set()

    def _run(self):
        try:
            conn, _ = self._sock.accept()
        except OSError:
            return
        with conn:
            while not self.closed.is_set():
                try:
                    request = read_envelope(conn)
                except (EOFError, OSError):
                    return
                try:
                    payload = self._handle(request)
                    response = ProtocolEnvelope(request.type, request.requestId, payload)
                except Exception as exc:  # noqa: BLE001 - test server intentionally serializes errors
                    response = ProtocolEnvelope(BridgeMessageType.ERROR, request.requestId, {"message": str(exc)})
                write_envelope(conn, response)

    def _handle(self, request: ProtocolEnvelope):
        if request.type in {BridgeMessageType.HELLO, BridgeMessageType.GET_SPECS}:
            return self._specs()
        if request.type == BridgeMessageType.CREATE_SESSION:
            if request.payload.get("token") != self.token:
                raise RuntimeError("invalid bridge token")
            payload = self._specs()
            payload["sessionId"] = self.session_id
            payload["reset"] = self._reset()
            return payload
        if request.payload.get("sessionId") != self.session_id:
            raise RuntimeError("session id mismatch")
        if request.type == BridgeMessageType.RESET_ALL:
            return self._reset()
        if request.type == BridgeMessageType.RESET_MASK:
            return self._reset()
        if request.type == BridgeMessageType.SET_CURRICULUM:
            return {"curriculumStage": request.payload["curriculumStage"], "reset": self._reset()}
        if request.type == BridgeMessageType.SET_TARGETS:
            return self._set_targets(request.payload)
        if request.type == BridgeMessageType.UPDATE_TARGETS:
            return self._update_targets(request.payload)
        if request.type == BridgeMessageType.STEP:
            self.last_step_payload = request.payload
            if self.step_payload is not None:
                return self.step_payload
            actions = request.payload["actions"]
            return {
                "observations": [[float(row[0]), 0.0] for row in actions],
                "rewards": [1.0 for _ in actions],
                "terminated": [False for _ in actions],
                "truncated": [False for _ in actions],
                "infos": [{"terminal_observation": [0.0, 0.0]} for _ in actions],
                "resetObservations": [[] for _ in actions],
            }
        if request.type == BridgeMessageType.CLOSE_SESSION:
            self.closed.set()
            return {"closed": True}
        if request.type == BridgeMessageType.PING:
            return {"ok": True}
        raise RuntimeError(f"unsupported {request.type}")

    def _specs(self):
        if self.specs_payload_override is not None:
            return copy.deepcopy(self.specs_payload_override)
        return specs_payload(self.slot_count, self.protocol_version, self.morphology_id)

    def _reset(self):
        if self.reset_payload is not None:
            return self.reset_payload
        return {
            "observations": [[0.0, 0.0] for _ in range(self.slot_count)],
            "infos": [{"event": "reset", "slot": i} for i in range(self.slot_count)],
        }

    def _set_targets(self, payload):
        if "targets" in payload:
            self.targets = payload["targets"]
            return {"curriculumStage": "manual_point_goals", "reset": self._reset()}
        self.commands = payload["commands"]
        return {"curriculumStage": "manual_commands", "reset": self._reset()}

    def _update_targets(self, payload):
        if "targets" in payload:
            self.targets = payload["targets"]
            stage = "manual_point_goals"
        else:
            self.commands = payload["commands"]
            stage = "manual_commands"
        return {
            "curriculumStage": stage,
            "update": {
                "observations": [[0.5, 0.0] for _ in range(self.slot_count)],
                "infos": [{"event": "episode_update", "slot": i} for i in range(self.slot_count)],
            },
        }


class HangingBridgeServer:
    def __init__(self, *, delay_seconds: float = 0.25):
        self.delay_seconds = delay_seconds
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(1)
        self.port = self._sock.getsockname()[1]
        self.thread = threading.Thread(target=self._run, daemon=True)
        self.closed = threading.Event()

    def start(self):
        self.thread.start()

    def stop(self):
        self.closed.set()
        with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as sock:
            try:
                sock.connect(("127.0.0.1", self.port))
            except OSError:
                pass
        self._sock.close()

    def _run(self):
        try:
            conn, _ = self._sock.accept()
        except OSError:
            return
        with conn:
            try:
                read_envelope(conn)
                time.sleep(self.delay_seconds)
            except (EOFError, OSError):
                return


class DisconnectingBridgeServer:
    def __init__(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(1)
        self.port = self._sock.getsockname()[1]
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self.thread.start()

    def stop(self):
        with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as sock:
            try:
                sock.connect(("127.0.0.1", self.port))
            except OSError:
                pass
        self._sock.close()

    def _run(self):
        try:
            conn, _ = self._sock.accept()
        except OSError:
            return
        conn.close()


class RawResponseBridgeServer:
    def __init__(self, response_bytes: bytes):
        self.response_bytes = response_bytes
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(1)
        self.port = self._sock.getsockname()[1]
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self.thread.start()

    def stop(self):
        with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as sock:
            try:
                sock.connect(("127.0.0.1", self.port))
            except OSError:
                pass
        self._sock.close()

    def _run(self):
        try:
            conn, _ = self._sock.accept()
        except OSError:
            return
        with conn:
            try:
                read_envelope(conn)
                conn.sendall(struct.pack(">I", len(self.response_bytes)) + self.response_bytes)
            except (EOFError, OSError):
                return


class WrongRequestIdBridgeServer:
    def __init__(self):
        self._sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        self._sock.bind(("127.0.0.1", 0))
        self._sock.listen(1)
        self.port = self._sock.getsockname()[1]
        self.thread = threading.Thread(target=self._run, daemon=True)

    def start(self):
        self.thread.start()

    def stop(self):
        with closing(socket.socket(socket.AF_INET, socket.SOCK_STREAM)) as sock:
            try:
                sock.connect(("127.0.0.1", self.port))
            except OSError:
                pass
        self._sock.close()

    def _run(self):
        try:
            conn, _ = self._sock.accept()
        except OSError:
            return
        with conn:
            try:
                read_envelope(conn)
                write_envelope(conn, ProtocolEnvelope(BridgeMessageType.PING, "wrong-id", {"ok": True}))
            except (EOFError, OSError, ProtocolError):
                return


class ProtocolClientTest(unittest.TestCase):
    def test_client_handshake_and_step(self):
        server = MockBridgeServer()
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            hello = client.hello()
            self.assertEqual(hello["protocolVersion"], PROTOCOL_VERSION)
            created = client.create_session()
            self.assertEqual(created["sessionId"], "session")
            reset = client.reset_all()
            self.assertEqual(len(reset["observations"]), 2)
            step = client.step([[0.25], [-0.5]])
            self.assertEqual(step["rewards"], [1.0, 1.0])
            self.assertEqual(step["observations"][0], [0.25, 0.0])
            self.assertEqual(server.last_step_payload["observationSchemaHash"], "obs-hash")
            self.assertEqual(server.last_step_payload["actionSchemaHash"], "act-hash")
            curriculum = client.set_curriculum("low_bumps_commands")
            self.assertEqual(curriculum["curriculumStage"], "low_bumps_commands")
            self.assertEqual(len(curriculum["reset"]["observations"]), 2)
            targets = client.set_targets([[4.0, -2.0], [4.0, 2.0]])
            self.assertEqual(targets["curriculumStage"], "manual_point_goals")
            self.assertEqual(len(targets["reset"]["observations"]), 2)
            target_update = client.update_targets([[6.0, -3.0], [6.0, 3.0]])
            self.assertEqual(target_update["curriculumStage"], "manual_point_goals")
            self.assertEqual(target_update["update"]["infos"][0]["event"], "episode_update")
            command_update = client.update_commands([[1.0, 0.0, 0.5], [1.0, 0.0, -0.5]])
            self.assertEqual(command_update["curriculumStage"], "manual_commands")
        finally:
            client.close()
            server.stop()

    def test_client_rejects_malformed_reset_payload(self):
        server = MockBridgeServer(reset_payload={"observations": [[0.0]], "infos": [{"event": "bad"}, {"event": "bad"}]})
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            with self.assertRaisesRegex(BridgeError, "slot count mismatch"):
                client.create_session()
        finally:
            client.close()
            server.stop()

    def test_client_rejects_malformed_step_payload(self):
        server = MockBridgeServer(step_payload={
            "observations": [[1.25, 0.0], [0.0, 0.0]],
            "rewards": [1.0, 1.0],
            "terminated": [False, False],
            "truncated": [False, False],
            "infos": [{}, {}],
            "resetObservations": [[], []],
        })
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            client.create_session()
            with self.assertRaisesRegex(BridgeError, "out of bounds"):
                client.step([[0.0], [0.0]])
        finally:
            client.close()
            server.stop()

    def test_client_rejects_nonboolean_step_flags(self):
        server = MockBridgeServer(step_payload={
            "observations": [[0.0, 0.0], [0.0, 0.0]],
            "rewards": [1.0, 1.0],
            "terminated": [0, False],
            "truncated": [False, False],
            "infos": [{}, {}],
            "resetObservations": [[], []],
        })
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            client.create_session()
            with self.assertRaisesRegex(BridgeError, "boolean"):
                client.step([[0.0], [0.0]])
        finally:
            client.close()
            server.stop()

    def test_client_validates_outgoing_requests(self):
        server = MockBridgeServer()
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            client.create_session()
            with self.assertRaisesRegex(BridgeError, "mask length"):
                client.reset_mask([True])
            with self.assertRaisesRegex(BridgeError, "mask\\[1\\]"):
                client.reset_mask([True, 1])
            with self.assertRaisesRegex(BridgeError, "actions row count"):
                client.step([[0.0]])
            with self.assertRaisesRegex(BridgeError, "column count"):
                client.step([[0.0], [0.0, 0.0]])
            with self.assertRaisesRegex(BridgeError, "finite"):
                client.step([[float("nan")], [0.0]])
            with self.assertRaisesRegex(BridgeError, "out of bounds"):
                client.step([[1.25], [0.0]])
            with self.assertRaisesRegex(BridgeError, "targets row count"):
                client.set_targets([[4.0, 1.0]])
            with self.assertRaisesRegex(BridgeError, "finite"):
                client.set_targets([[4.0, 1.0], [4.0, float("nan")]])
            with self.assertRaisesRegex(BridgeError, "column count"):
                client.set_commands([[1.0, 0.0, 0.0], [1.0, 0.0]])
            with self.assertRaisesRegex(BridgeError, "finite"):
                client.update_commands([[1.0, 0.0, 0.0], [1.0, 0.0, "bad"]])
        finally:
            client.close()
            server.stop()

    def test_client_rejects_unexpected_morphology(self):
        server = MockBridgeServer(morphology_id="minecraft_machines:other")
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            with self.assertRaisesRegex(BridgeError, "unexpected morphology"):
                client.hello()
        finally:
            client.close()
            server.stop()

    def test_client_rejects_wrong_protocol_version(self):
        server = MockBridgeServer(protocol_version=999)
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            with self.assertRaises(BridgeError):
                client.hello()
        finally:
            client.close()
            server.stop()

    def test_client_rejects_malformed_specs(self):
        cases = []
        zero_slots = specs_payload()
        zero_slots["slotCount"] = 0
        cases.append((zero_slots, "slotCount"))

        blank_hash = specs_payload()
        blank_hash["observationSchemaHash"] = ""
        cases.append((blank_hash, "observationSchemaHash"))

        bad_bounds = specs_payload()
        bad_bounds["observationSpec"]["fields"][0]["minimum"] = 2.0
        cases.append((bad_bounds, "minimum"))

        duplicate_field = specs_payload()
        duplicate_field["observationSpec"]["fields"][1]["name"] = duplicate_field["observationSpec"]["fields"][0]["name"]
        cases.append((duplicate_field, "duplicated"))

        for payload, message in cases:
            server = MockBridgeServer(specs_payload_override=payload)
            server.start()
            client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
            try:
                with self.assertRaisesRegex(BridgeError, message):
                    client.hello()
            finally:
                client.close()
                server.stop()

    def test_client_wraps_bridge_timeout_as_bridge_error(self):
        server = HangingBridgeServer()
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token", timeout_seconds=0.05))
        try:
            with self.assertRaisesRegex(BridgeError, "timed out"):
                client.hello()
            self.assertIsNone(client.session_id)
        finally:
            client.close()
            server.stop()

    def test_client_wraps_bridge_disconnect_as_bridge_error(self):
        server = DisconnectingBridgeServer()
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            with self.assertRaisesRegex(BridgeError, "connection failed"):
                client.hello()
            self.assertIsNone(client.session_id)
        finally:
            client.close()
            server.stop()

    def test_client_wraps_protocol_decode_failure_as_bridge_error(self):
        server = RawResponseBridgeServer(b'{"type":"PING","payload":{}}')
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            with self.assertRaisesRegex(BridgeError, "protocol failed"):
                client.hello()
            self.assertIsNone(client.session_id)
            self.assertIsNone(client._sock)
        finally:
            client.close()
            server.stop()

    def test_client_clears_connection_on_request_id_mismatch(self):
        server = WrongRequestIdBridgeServer()
        server.start()
        client = MinecraftMachinesBridgeClient(BridgeConnectionConfig(port=server.port, token="token"))
        try:
            with self.assertRaisesRegex(BridgeError, "request id mismatch"):
                client.ping()
            self.assertIsNone(client.session_id)
            self.assertIsNone(client._sock)
        finally:
            client.close()
            server.stop()

    def test_config_validation_rejects_illegal_batch_size(self):
        with self.assertRaises(ValueError):
            PPOTrainingConfig(n_steps=8, batch_size=7).validate(num_envs=2)

    def test_frame_round_trip(self):
        left, right = socket.socketpair()
        try:
            envelope = ProtocolEnvelope(BridgeMessageType.PING, "r1", {"ok": True})
            write_envelope(left, envelope)
            decoded = read_envelope(right)
            self.assertEqual(decoded, envelope)
        finally:
            left.close()
            right.close()

    def test_frame_rejects_missing_request_id(self):
        with self.assertRaisesRegex(ProtocolError, "requestId"):
            ProtocolEnvelope(BridgeMessageType.PING, "", {"ok": True})
        with self.assertRaisesRegex(ProtocolError, "requestId"):
            ProtocolEnvelope.from_json('{"type":"PING","payload":{"ok":true}}')
        with self.assertRaisesRegex(ProtocolError, "payload"):
            ProtocolEnvelope.from_json('{"type":"PING","requestId":"r1","payload":[]}')
        with self.assertRaisesRegex(ProtocolError, "envelope"):
            ProtocolEnvelope.from_json("[]")
        with self.assertRaisesRegex(ProtocolError, "type"):
            ProtocolEnvelope.from_json('{"requestId":"r1","payload":{}}')
        with self.assertRaisesRegex(ProtocolError, "unsupported"):
            ProtocolEnvelope.from_json('{"type":"NOPE","requestId":"r1","payload":{}}')


if __name__ == "__main__":
    unittest.main()
