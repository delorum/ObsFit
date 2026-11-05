"""Release orchestration and retention tests without Gradle or real APK builds."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release


class ReleaseTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.dist = self.root / "dist"
        self.dist.mkdir()
        for i in range(6):
            p = self.dist / f"ObsFit-{i}-debug.apk"
            p.write_bytes(str(i).encode())
            os.utime(p, ns=(i + 1, i + 1))
        (self.dist / "CHECKS.md").write_text("keep")
        self.apk = self.root / "app/build/outputs/apk/debug/app-debug.apk"
        self.apk.parent.mkdir(parents=True)
        self.apk.write_bytes(b"new APK")
        (self.apk.parent / "output-metadata.json").write_text(json.dumps({
            "elements": [{"outputFile": self.apk.name, "versionName": "0.5.0"}]}))

    def test_keeps_newest_three_and_refreshes_checksums_without_touching_reports(self):
        artifact = release.publish(self.apk, self.dist, "0.5.0")
        self.assertEqual({artifact.name, "ObsFit-5-debug.apk", "ObsFit-4-debug.apk"},
                         {p.name for p in release.apk_files(self.dist)})
        self.assertEqual("keep", (self.dist / "CHECKS.md").read_text())
        self.assertEqual(3, len((self.dist / "SHA256SUMS").read_text().splitlines()))
        self.assertEqual(b"new APK", artifact.read_bytes())

    def test_same_version_replaces_atomically_without_extra_slot(self):
        artifact = release.publish(self.apk, self.dist, "5")
        self.assertEqual(3, len(release.apk_files(self.dist)))
        self.assertEqual(b"new APK", artifact.read_bytes())
        self.assertFalse(list(self.dist.glob(".release-*")))

    def test_new_apk_is_kept_even_if_old_timestamp_is_in_future(self):
        for p in release.apk_files(self.dist):
            os.utime(p, (4102444800, 4102444800))
        self.assertTrue(release.publish(self.apk, self.dist, "new").exists())
        self.assertEqual(3, len(release.apk_files(self.dist)))

    def test_copy_failure_leaves_previous_apks_untouched(self):
        before = {p.name: p.read_bytes() for p in release.apk_files(self.dist)}
        with patch.object(release.shutil, "copyfileobj", side_effect=OSError("disk full")):
            with self.assertRaises(OSError):
                release.publish(self.apk, self.dist, "5")
        self.assertEqual(before, {p.name: p.read_bytes() for p in release.apk_files(self.dist)})
        self.assertFalse(list(self.dist.glob(".release-*")))

    def test_invalid_version_and_empty_apk_do_not_prune(self):
        for version in ("../outside", "", "a/b"):
            with self.assertRaises(ValueError):
                release.publish(self.apk, self.dist, version)
        self.apk.write_bytes(b"")
        with self.assertRaises(ValueError):
            release.publish(self.apk, self.dist, "new")
        self.assertEqual(6, len(release.apk_files(self.dist)))

    def test_checks_run_in_order_before_publication(self):
        commands = []
        def run(command, **options):
            self.assertTrue(options["check"])
            self.assertEqual(self.root, options["cwd"])
            self.assertEqual(6, len(release.apk_files(self.dist)))
            commands.append(command)
        release.release(self.root, run)
        self.assertIn("unittest", commands[0])
        self.assertIn(":core:test", commands[1])
        self.assertIn(":app:assembleDebug", commands[2])
        self.assertIn(":app:lintDebug", commands[2])
        self.assertEqual("tools/check-apk-alignment.py", commands[3][1])
        self.assertEqual(3, len(release.apk_files(self.dist)))

    def test_each_verification_failure_stops_before_publication(self):
        for failed_step in range(4):
            count = 0
            def run(command, **options):
                nonlocal count
                count += 1
                if count == failed_step + 1:
                    raise subprocess.CalledProcessError(1, command)
            with self.assertRaises(subprocess.CalledProcessError):
                release.release(self.root, run)
            self.assertEqual(failed_step + 1, count)
            self.assertEqual(6, len(release.apk_files(self.dist)))

    def test_symlinks_and_directories_are_not_pruned(self):
        outside = self.root / "outside.apk"
        outside.write_bytes(b"keep")
        (self.dist / "link.apk").symlink_to(outside)
        (self.dist / "directory.apk").mkdir()
        release.publish(self.apk, self.dist, "new")
        self.assertEqual(b"keep", outside.read_bytes())
        self.assertTrue((self.dist / "link.apk").is_symlink())
        self.assertTrue((self.dist / "directory.apk").is_dir())


if __name__ == "__main__":
    unittest.main()
