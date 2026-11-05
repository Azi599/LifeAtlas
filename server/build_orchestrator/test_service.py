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

    def test_http_idempotency_returns_one_real_job(self):
        import threading, json, queue
        from http.server import HTTPServer
        from urllib.request import Request, urlopen
        from urllib.error import HTTPError
        with tempfile.TemporaryDirectory() as directory, patch.object(service,"DATABASE",os.path.join(directory,"jobs.db")), patch.object(service,"ACCESS","test-secret"), patch.object(service,"AI_KEY","configured"), patch.object(service,"TOKEN","configured"), patch.object(service,"JOBS",queue.Queue()):
            service.initialize();server=HTTPServer(("127.0.0.1",0),service.Handler);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
            def request(requirements):
                return urlopen(Request("http://127.0.0.1:"+str(server.server_port)+"/builds",data=json.dumps({"requirements":requirements}).encode(),headers={"Authorization":"Bearer test-secret","Idempotency-Key":"stable-request","Content-Type":"application/json"}),timeout=3)
            try:
                with request("native capability") as response: first=json.load(response)
                with request("native capability") as response: second=json.load(response)
                self.assertEqual(first['id'],second['id']);self.assertEqual(1,service.JOBS.qsize())
                with self.assertRaises(HTTPError) as error:request("different capability")
                self.assertEqual(409,error.exception.code)
            finally:server.shutdown();server.server_close();thread.join()

if __name__ == '__main__': unittest.main()
