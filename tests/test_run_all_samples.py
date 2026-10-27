import contextlib
import importlib.util
import io
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location(
    "sample_runner", Path(__file__).resolve().parents[1] / "run_all_samples.py")
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


class SampleRunnerTest(unittest.TestCase):
    def run_sample(self, files, returncode=0):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            sample = root / "samples" / "23_example"
            sample.mkdir(parents=True)
            for name in files:
                (sample / name).write_text("")
            result = subprocess.CompletedProcess([], returncode, "test output", "")
            output = io.StringIO()
            with patch.object(RUNNER, "ROOT", root), \
                    patch.object(sys, "argv", ["run_all_samples.py", "23"]), \
                    patch.dict(os.environ, {"MAVEN_CMD": "sample-mvn"}), \
                    patch.object(RUNNER.subprocess, "run", return_value=result) as execute, \
                    contextlib.redirect_stdout(output):
                status = RUNNER.main()
            self.assertEqual(1, execute.call_count, "The sample must be discovered and executed")
            self.assertEqual(sample, execute.call_args.kwargs["cwd"])
            return status, execute.call_args.args[0], output.getvalue()

    def test_runs_maven_project_without_python_launcher(self):
        status, command, output = self.run_sample(["pom.xml"])
        self.assertEqual(0, status)
        self.assertEqual(["sample-mvn", "-q", "--batch-mode", "-Preal-model", "test"], command)
        self.assertIn("[PASS] 23_example", output)

    def test_propagates_maven_test_failure(self):
        status, _, output = self.run_sample(["pom.xml"], returncode=2)
        self.assertEqual(1, status)
        self.assertIn("[FAIL] 23_example", output)
        self.assertIn("test output", output)

    def test_keeps_existing_python_samples_runnable(self):
        status, command, _ = self.run_sample(["main.py"])
        self.assertEqual(0, status)
        self.assertEqual([sys.executable, "-X", "utf8", "main.py"], command)


if __name__ == "__main__":
    unittest.main()
