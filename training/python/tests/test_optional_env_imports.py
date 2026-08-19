from __future__ import annotations

import unittest


class OptionalEnvironmentImportsTest(unittest.TestCase):
    def test_single_env_import_is_explicit_when_gymnasium_missing(self):
        try:
            import gymnasium  # noqa: F401
        except ImportError:
            from minecraft_machines_training.configuration import BridgeConnectionConfig
            from minecraft_machines_training.env import MinecraftMachinesEnv

            with self.assertRaises(ImportError):
                MinecraftMachinesEnv(BridgeConnectionConfig(port=1, token="token"))
        else:
            self.skipTest("gymnasium is installed; full env behavior is covered by live/mock bridge tests")


if __name__ == "__main__":
    unittest.main()
