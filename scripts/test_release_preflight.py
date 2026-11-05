import unittest
from release_preflight import validate

GOOD = '''defaultConfig {
    applicationId = "com.flowseal.tgwsproxy"
    versionCode = 22
    versionName = "1.9.1"
}'''

class ReleasePreflightTest(unittest.TestCase):
    def test_valid_manual_build(self):
        self.assertEqual(validate('v1.9.1',GOOD,'workflow_dispatch','branch','main')['versionCode'],22)

    def test_valid_tag_push(self):
        self.assertEqual(validate('v1.9.1',GOOD,'push','tag','v1.9.1')['tag'],'v1.9.1')

    def test_rejects_mismatched_release_tag(self):
        with self.assertRaisesRegex(ValueError,'does not match'):
            validate('v1.9.2',GOOD,'workflow_dispatch','branch','main')

    def test_rejects_manual_release_from_feature_branch(self):
        with self.assertRaisesRegex(ValueError,'main'):
            validate('v1.9.1',GOOD,'workflow_dispatch','branch','fix/network')

    def test_rejects_released_tag_in_wrong_context(self):
        with self.assertRaisesRegex(ValueError,'exact tag'):
            validate('v1.9.1',GOOD,'push','tag','v1.9.0')

    def test_rejects_missing_or_duplicate_metadata(self):
        with self.assertRaisesRegex(ValueError,'one versionName'):
            validate('v1.9.1',GOOD.replace('versionName = "1.9.1"',''),'push','tag','v1.9.1')
        with self.assertRaisesRegex(ValueError,'one versionCode'):
            validate('v1.9.1',GOOD+'\nversionCode = 23','push','tag','v1.9.1')

    def test_rejects_nonpositive_build_code(self):
        with self.assertRaisesRegex(ValueError,'positive'):
            validate('v1.9.1',GOOD.replace('versionCode = 22','versionCode = 0'),'push','tag','v1.9.1')

    def test_rejects_bad_version_numbers(self):
        for tag in ('1.9.1','v01.9.1','v1.9','v1.9.1;rm -rf /','v1.9.1+foo','v1.9.1\nX'):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                validate(tag,GOOD,'push','tag',tag)

    def test_rejects_unknown_workflow_event(self):
        with self.assertRaisesRegex(ValueError,'Unsupported'):
            validate('v1.9.1',GOOD,'pull_request','branch','main')

    def test_accepts_valid_prerelease_tag(self):
        cfg=GOOD.replace('1.9.1','1.9.1-rc.1')
        self.assertEqual(validate('v1.9.1-rc.1',cfg,'push','tag','v1.9.1-rc.1')['tag'],'v1.9.1-rc.1')

if __name__ == '__main__':unittest.main()
