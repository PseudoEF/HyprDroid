import unittest
from version_codes import next_codes, MAX_ANDROID_CODE

class VersionCodeTest(unittest.TestCase):
    def test_legacy_installations_start_below_new_distribution_range(self):
        self.assertEqual((10000, 10001), next_codes("refs/tags/v0.5\nrefs/tags/other/999999"))

    def test_failed_runs_and_old_commit_reruns_never_reuse_codes(self):
        refs = "refs/tags/ci/debug/10009\nrefs/tags/ci/debug/10002\nrefs/tags/ci/debug/10008"
        self.assertEqual((10010, 10011), next_codes(refs))
        self.assertEqual((10012, 10013), next_codes(refs + "\nrefs/tags/ci/debug/10011"))

    def test_android_limit_is_checked_for_both_apks(self):
        self.assertEqual((MAX_ANDROID_CODE - 1, MAX_ANDROID_CODE), next_codes(f"refs/tags/ci/debug/{MAX_ANDROID_CODE - 2}"))
        for previous in (MAX_ANDROID_CODE - 1, MAX_ANDROID_CODE, 43_762_462_358):
            with self.assertRaises(ValueError):
                next_codes(f"refs/tags/ci/debug/{previous}")

if __name__ == "__main__":
    unittest.main()
