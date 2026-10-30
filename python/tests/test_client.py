import json
import threading
import unittest
from http.server import BaseHTTPRequestHandler, HTTPServer

from onekey_sdk import OneKeyClient, OneKeyError, SecretNotFound

TOKEN = "dbs_test_token"


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):  # keep test output quiet
        pass

    def do_GET(self):
        if self.headers.get("Authorization") != f"Bearer {TOKEN}":
            return self._send(401, {"success": False, "error": {"AUTHENTICATION_INVALID": "Sign in."}})
        routes = {
            "/cli/api/v1/health": {"product": "onekey", "version": "0.7.14", "apiVersion": "v1", "status": "ok"},
            "/cli/api/v1/projects": [
                {"id": "prj_1", "name": "payments", "createdAt": "t", "updatedAt": "t", "myRole": "developer"}
            ],
            "/cli/api/v1/environments?project=payments": [
                {"id": "env_1", "projectId": "prj_1", "projectName": "payments", "name": "production", "createdAt": "t", "updatedAt": "t"}
            ],
            "/cli/api/v1/environments/env_1/secrets": [
                {"key": "API_KEY", "version": 2, "createdAt": "t", "updatedAt": "t", "description": "Stripe"}
            ],
            "/cli/api/v1/projects/payments/secrets/runtime": {
                "project": "payments",
                "environment": "production",
                "environmentId": "env_1",
                "entries": [{"key": "API_KEY", "value": "sk_test"}, {"key": "DB-URL", "value": "postgres://x"}],
            },
        }
        if self.path == "/cli/api/v1/projects/missing/secrets/runtime":
            return self._send(404, {"success": False, "error": {"PROJECT_NOT_FOUND": "No such project."}})
        if self.path in routes:
            return self._send(200, {"success": True, "message": "OK", "data": routes[self.path]})
        self._send(404, {"success": False, "error": {"NOT_FOUND": self.path}})

    def _send(self, status, payload):
        body = json.dumps(payload).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class ClientTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.server = HTTPServer(("127.0.0.1", 0), Handler)
        threading.Thread(target=cls.server.serve_forever, daemon=True).start()
        port = cls.server.server_address[1]
        cls.client = OneKeyClient(f"http://127.0.0.1:{port}/cli/", TOKEN)

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()

    def test_runtime_is_a_mapping_and_never_prints_values(self):
        secrets = self.client.runtime("payments")
        self.assertEqual(secrets["API_KEY"], "sk_test")
        self.assertEqual(secrets.get("DB-URL"), "postgres://x")
        self.assertEqual(sorted(secrets), ["API_KEY", "DB-URL"])
        self.assertNotIn("sk_test", repr(secrets))
        self.assertEqual(secrets.as_env()["API_KEY"], "sk_test")

    def test_secret_and_not_found(self):
        self.assertEqual(self.client.secret("payments", "API_KEY"), "sk_test")
        with self.assertRaises(SecretNotFound) as raised:
            self.client.secret("payments", "NOPE")
        self.assertEqual(raised.exception.code, "SECRET_NOT_FOUND")

    def test_metadata_endpoints(self):
        self.assertEqual(self.client.health()["version"], "0.7.14")
        self.assertEqual(self.client.projects()[0].name, "payments")
        self.assertEqual(self.client.groups("payments")[0].name, "production")
        names = self.client.secret_names("payments")
        self.assertEqual(names[0].key, "API_KEY")
        self.assertEqual(names[0].description, "Stripe")
        self.assertEqual(self.client.secret_names("payments", group="other"), [])

    def test_api_error_codes(self):
        with self.assertRaises(OneKeyError) as raised:
            self.client.runtime("missing")
        self.assertEqual((raised.exception.status, raised.exception.code), (404, "PROJECT_NOT_FOUND"))
        bad = OneKeyClient(self.client._base, "wrong")
        with self.assertRaises(OneKeyError) as raised:
            bad.health()
        self.assertEqual(raised.exception.code, "AUTHENTICATION_INVALID")

    def test_requires_url_and_token(self):
        with self.assertRaises(ValueError):
            OneKeyClient("", "x")
        with self.assertRaises(ValueError):
            OneKeyClient("http://x", "")


if __name__ == "__main__":
    unittest.main()
