import hashlib
from importlib.util import module_from_spec, spec_from_file_location
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch


spec = spec_from_file_location("upgrade", Path(__file__).with_name("upgrade-from-42150375552.py"))
m = module_from_spec(spec)
spec.loader.exec_module(m)


class UpgradeTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.path = Path(temp.name)
        self.upgrade = m.Upgrade(self.path, "test")

    def test_compose_only_accepts_caddy_host_port_changes(self):
        standard = (Path(__file__).resolve().parents[1] / m.COMPOSE.name).read_bytes()
        site = standard.replace(b'"443:443"', b'"8443:443"').replace(b'"80:80"', b'"8080:80"')
        self.assertEqual((443, 80), m.check_compose_ports(standard, standard))
        self.assertEqual((8443, 8080), m.check_compose_ports(site, standard))
        for altered in (site.replace(b'"8443:443"', b'"8443:8443"'),
                        site.replace(b'"8080:80"', b'"65536:80"'),
                        site.replace(b'"8080:80"', b'"8443:80"'),
                        site.replace(b'postgres:16', b'postgres:17'),
                        site.replace(b'praxis_pg_data:', b'other_data:')):
            with self.assertRaises(RuntimeError):
                m.check_compose_ports(altered, standard)

    def test_schema_checks_existing_versions_and_checksums(self):
        candidate = self.path
        (candidate / m.MIGRATIONS).mkdir(parents=True)
        rows = []
        for version in sorted(m.OLD_VERSIONS | {10}):
            file = candidate / m.MIGRATIONS / f"V{version:03d}__fixture.sql"
            file.write_bytes(b"select 1;")
            rows.append(f"{version}|{file.name}|{hashlib.sha256(file.read_bytes()).hexdigest()}")
        for history, expected in ((rows[:-1], False), (rows, True)):
            with patch.object(self.upgrade, "sql", side_effect=["t", "\n".join(history)]):
                self.assertEqual(expected, self.upgrade.check_schema(candidate))
        for history in (rows[1:], rows[:-1] + ["99|fixture|unknown"], [r[:-1] for r in rows]):
            with patch.object(self.upgrade, "sql", side_effect=["t", "\n".join(history)]):
                with self.assertRaisesRegex(RuntimeError, "校验和"):
                    self.upgrade.check_schema(candidate)

    def test_migration_failure_does_not_switch_program(self):
        with patch.object(self.upgrade, "backup", return_value=self.path), \
                patch.object(self.upgrade, "run", side_effect=subprocess.CalledProcessError(1, "psql")), \
                patch.object(self.upgrade, "switch") as switch, patch.object(m.subprocess, "run"):
            with self.assertRaises(subprocess.CalledProcessError):
                self.upgrade.apply(self.path)
            switch.assert_not_called()

    def test_failed_probe_rolls_back_after_switch(self):
        with patch.object(self.upgrade, "backup", return_value=self.path), \
                patch.object(self.upgrade, "run"), patch.object(self.upgrade, "check_schema", return_value=True), \
                patch.object(self.upgrade, "switch"), \
                patch.object(self.upgrade, "ready", side_effect=RuntimeError("probe")), \
                patch.object(self.upgrade, "rollback") as rollback, patch.object(m.subprocess, "run"):
            with self.assertRaisesRegex(RuntimeError, "probe"):
                self.upgrade.apply(self.path)
            rollback.assert_called_once_with(self.path)

    def test_replace_web_tree_does_not_keep_old_assets(self):
        new, current = self.path / "new", self.path / "current"
        new.mkdir()
        current.mkdir()
        (new / "new.js").touch()
        (current / "old.js").touch()
        m.install_file(new, current)
        self.assertEqual(["new.js"], [p.name for p in current.iterdir()])


if __name__ == "__main__":
    unittest.main()
