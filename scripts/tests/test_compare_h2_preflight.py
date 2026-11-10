import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from compare_h2_preflight import compare


class ComparisonTests(unittest.TestCase):
    def setUp(self):
        self.httpx = {
            "probe": "python-httpx-upstream-stack", "host": "kws2.proxy.org", "scope": "tls_alpn_head_only",
            "http_status": 200, "http_version": "HTTP/2", "elapsed_ms": 10, "accepted": True
        }
        self.okhttp = {
            "probe": "android-okhttp-stack", "host": "kws2.proxy.org", "scope": "tls_alpn_head_only",
            "http_status": 405, "http_version": "h2", "elapsed_ms": 12, "accepted": True
        }

    def test_success_does_not_claim_data_plane_or_mobile_compatibility(self):
        output = compare(self.httpx, self.okhttp)
        self.assertTrue(output["both_accepted"])
        self.assertFalse(output["mtproto_data_plane_tested"])
        self.assertFalse(output["android_device_tested"])

    def test_host_mismatch_cannot_be_misreported_as_upstream_parity(self):
        self.okhttp["host"] = "other.proxy.org"
        with self.assertRaises(ValueError):
            compare(self.httpx, self.okhttp)

    def test_preflight_failure_is_reported_as_failure(self):
        self.httpx["accepted"] = False
        self.assertFalse(compare(self.httpx, self.okhttp)["both_accepted"])

    def test_unexpected_probe_scope_is_rejected(self):
        self.okhttp["scope"] = "native_mtproto_data_plane"
        with self.assertRaises(ValueError):
            compare(self.httpx, self.okhttp)


if __name__ == "__main__":
    unittest.main()
