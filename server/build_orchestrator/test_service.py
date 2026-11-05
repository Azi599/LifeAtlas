import unittest
import tempfile
import os
from unittest.mock import patch
import service

class BuildServiceTest(unittest.TestCase):
    def test_source_paths_are_scoped(self):
        self.assertEqual(1, len(service.validate_changes([{"path":"app/src/main/java/com/lifeatlas/app/NewFeature.kt","content":"package com.lifeatlas.app"}])))
        for path in ["../../secret", ".github/workflows/build.yml", "app/build.gradle.kts", "/app/src/main/java/Evil.kt", "app/src/main/java/../../bad"]:
            with self.assertRaises(ValueError):
                service.validate_changes([{"path":path,"content":"x"}])
    def test_restarted_jobs_remain_visible(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(service, "DATABASE", os.path.join(directory,"jobs.db")):
            service.initialize()
            with service.database() as db:
                db.execute("INSERT INTO jobs(id,status) VALUES ('x','GENERATING')")
            service.initialize()
            with service.database() as db:
                self.assertEqual("INTERRUPTED",db.execute("SELECT status FROM jobs WHERE id='x'").fetchone()[0])
    def test_remote_failure_is_persisted(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(service, "DATABASE", os.path.join(directory,"jobs.db")), patch.object(service,"github",side_effect=ValueError("not configured")):
            service.initialize()
            with service.database() as db:
                db.execute("INSERT INTO jobs(id,spec,status) VALUES ('x','{}','QUEUED')")
            service.process('x')
            self.assertEqual("FAILED",service.refresh('x')["status"])

if __name__ == '__main__': unittest.main()
