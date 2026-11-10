import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from h2_preflight import accepted_preflight, probe, validated_public_host


class HostValidationTest(unittest.TestCase):
    def test_normalizes_public_domains(self):
        self.assertEqual("kws2.proxy.domain.org", validated_public_host("  KWS2.Proxy.Domain.ORG  "))

    def test_rejects_credentials_urls_private_endpoints_and_ip_literals(self):
        invalid = [
            "https://proxy.example.org", "user:password@proxy.domain.org",
            "127.0.0.1", "[::1]", "localhost", "kws2.local",
            "kws2.internal", "-bad.proxy.org", "bad-.proxy.org",
            "proxy.domain.org:8443", "proxy.domain.org/api", "proxy.domain.org?x=1",
            "kws2..domain.org", "proxy.domain.org\nmalicious",
        ]
        for name in invalid:
            with self.subTest(name=name), self.assertRaises(ValueError):
                validated_public_host(name)

    def test_only_expected_http2_preflight_codes_are_valid(self):
        for code in (200, 204, 205, 405, 501):
            self.assertTrue(accepted_preflight(code, "HTTP/2"))
            self.assertFalse(accepted_preflight(code, "HTTP/1.1"))
        for code in (301, 302, 307, 400, 403, 404, 429, 500, 502, 503):
            self.assertFalse(accepted_preflight(code, "HTTP/2"))

    def test_probe_uses_head_only_and_never_sends_a_payload(self):
        class Response:
            http_version = "HTTP/2"
            status_code = 405

        class Client:
            def __enter__(self):
                return self
            def __exit__(self, *_):
                return False
            def head(self, url, headers):
                self.url = url
                self.headers = headers
                return Response()

        client = Client()
        result = probe("kws2.proxy.domain.org", client_factory=lambda: client)
        self.assertEqual("https://kws2.proxy.domain.org/api", client.url)
        self.assertEqual({"Accept-Encoding": "identity"}, client.headers)
        self.assertEqual("tls_alpn_head_only", result["scope"])
        self.assertTrue(result["accepted"])


if __name__ == "__main__":
    unittest.main()
