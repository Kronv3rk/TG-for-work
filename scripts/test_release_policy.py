import pathlib
import re
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[1]
WORKFLOW = ROOT / ".github/workflows/android-release.yml"

class ReleasePolicyTest(unittest.TestCase):
    def test_no_legacy_fallback(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertNotIn("release-keystore.b64", text)
        self.assertNotIn("secrets.ANDROID_KEYSTORE", text)
        self.assertFalse((ROOT / "app/signing/release-keystore.b64").exists())

    def test_release_only_from_main(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertIn("workflow_dispatch:", text)
        self.assertNotRegex(text, r"(?m)^  push:")
        self.assertIn("github.ref == 'refs/heads/main'", text)
        self.assertIn("git/ref/heads/main", text)

    def test_signing_isolated(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        build, tail = text.split("\n  sign:\n", 1)
        sign, publish = tail.split("\n  publish:\n", 1)
        self.assertNotIn("secrets.", build)
        self.assertNotIn("./gradlew", sign)
        self.assertNotIn("actions/checkout", sign)
        self.assertIn("environment: android-release", sign)
        self.assertIn("TG_RELEASE_CERT_SHA256", sign)
        self.assertIn("apksigner", sign)
        self.assertIn("PREVIOUS_CERT", sign)
        self.assertIn('"$EXPECTED_CERT" == "$PREVIOUS_CERT"', sign)
        self.assertIn("NEW_CODE", sign)
        self.assertIn("sha256sum --check", publish)
        self.assertIn("needs: [build, sign]", publish)
        self.assertNotIn("secrets.", publish)

    def test_publication_requires_explicit_confirmation(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertRegex(text, r"(?s)publish_release:\s+description:.*?default: false\s+type: boolean")
        self.assertIn("if: inputs.publish_release", text)
        self.assertIn("if: github.ref == 'refs/heads/main' && inputs.publish_release", text)

    def test_sdk_setup_does_not_depend_on_sdkmanager_in_path(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        self.assertNotRegex(text, r"(?m)^\s*(?:run:\s*)?sdkmanager\s")
        self.assertIn('cmdline-tools/latest/bin/sdkmanager', text)
        self.assertIn('build-tools/35.0.0/apksigner', text)
        self.assertIn('platforms/android-35/android.jar', text)

    def test_all_actions_are_pinned(self):
        text = WORKFLOW.read_text(encoding="utf-8")
        for ref in re.findall(r"uses:\s+\S+@([^\s]+)", text):
            self.assertRegex(ref, r"^[0-9a-f]{40}$")

if __name__ == "__main__":
    unittest.main()
