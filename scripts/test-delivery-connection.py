#!/usr/bin/env python3
import json
import os
import pathlib
import runpy
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


bootstrap = runpy.run_path(str(pathlib.Path(__file__).with_name("delivery-bootstrap.py")))
main = bootstrap["main"]


class DeliveryConnectionTest(unittest.TestCase):
    def setUp(self):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = pathlib.Path(directory.name)
        self.cert = self.root / "cert.pem"
        self.key = self.root / "key.pem"
        self.output = self.root / "enterprise-connection.praxis"
        self.addCleanup(patch.stopall)
        patch.dict(main.__globals__, {
            "CONFIG": self.root, "TLS_DIR": self.root, "TLS_CERT": self.cert,
            "TLS_KEY": self.key, "CONNECTION_FILE": self.output,
        }).start()
        patch.dict(os.environ, {"DELIVERY_HOST": ""}).start()
        main.__globals__["ensure_self_signed_tls"]("192.0.2.31")

    def run_main(self, *args):
        with patch.object(sys, "argv", ["delivery-bootstrap.py", *args]):
            return main()

    def test_export_uses_existing_public_certificate_without_starting_services(self):
        cert = self.cert.read_bytes()
        key = self.key.read_bytes()
        with patch.dict(main.__globals__, {"compose_up_stack": None, "emit_migrate_sql": None,
                                           "write_secrets_if_needed": None, "COMPOSE": self.root / "missing"}):
            self.assertEqual(self.run_main("--export-connection", "--host", "192.0.2.31"), 0)
        self.assertEqual(self.cert.read_bytes(), cert)
        self.assertEqual(self.key.read_bytes(), key)
        self.assertEqual(json.loads(self.output.read_text()), {
            "version": 1, "backendUrl": "https://192.0.2.31", "serverCertificatePem": cert.decode("ascii"),
        })

    def test_wrong_host_does_not_replace_previously_exported_file(self):
        self.run_main("--export-connection", "--host", "localhost")
        previous = self.output.read_bytes()
        with self.assertRaisesRegex(SystemExit, "证书未包含此地址"):
            self.run_main("--export-connection", "--host", "192.0.2.32")
        self.assertEqual(self.output.read_bytes(), previous)

    def test_expired_certificate_cannot_be_exported(self):
        csr = self.root / "expired.csr"
        config = self.root / "expired.cnf"
        config.write_text("[ext]\nsubjectAltName=DNS:localhost\n")
        subprocess.run(["openssl", "req", "-new", "-key", str(self.key), "-subj", "/CN=localhost",
                        "-out", str(csr)], check=True, capture_output=True)
        subprocess.run(["openssl", "x509", "-req", "-in", str(csr), "-signkey", str(self.key),
                        "-days", "0", "-extfile", str(config), "-extensions", "ext", "-out", str(self.cert)],
                       check=True, capture_output=True)
        with self.assertRaisesRegex(SystemExit, "已过期"):
            self.run_main("--export-connection", "--host", "localhost")
        self.assertFalse(self.output.exists())

    def test_normalizes_host_and_rejects_non_host_inputs(self):
        normalize = bootstrap["connection_host"]
        self.assertEqual(normalize("[2001:0db8::1]"), "2001:db8::1")
        self.assertEqual(normalize("Praxis.Corp"), "praxis.corp")
        for host in ("https://localhost", "localhost:443", "localhost/foo", "test,IP:127.0.0.1", "127.1"):
            with self.subTest(host=host), self.assertRaises(SystemExit):
                normalize(host)

    def test_bootstrap_exports_only_when_host_is_explicit_and_stack_started(self):
        inputs = {}
        for name in ("COMPOSE", "JAR", "WEB"):
            path = self.root / name
            path.touch()
            inputs[name] = path
        actions = []
        inputs.update({
            "ONCE": self.root / "once", "SECRETS": self.root / "secrets",
            "compose_up_stack": lambda: actions.append(("stack", self.output.exists())),
            "emit_migrate_sql": lambda: None, "write_secrets_if_needed": lambda: None,
            "ensure_self_signed_tls": lambda host: None,
        })
        run = subprocess.run
        with patch.dict(main.__globals__, inputs), patch.object(
            subprocess, "run", side_effect=lambda command, **kw: None if command[0] == "docker" else run(command, **kw)
        ), patch.dict(os.environ, {"DELIVERY_HOST": "192.0.2.31"}):
            self.run_main("--skip-connection-export")
            self.assertFalse(self.output.exists())
            self.run_main()
        self.assertEqual(actions, [("stack", False), ("stack", False)])
        self.assertTrue(self.output.is_file())


if __name__ == "__main__":
    unittest.main()
