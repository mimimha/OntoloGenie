"""Jenkinsfile의 실제 셸을 실행해 구형 MR과 잘못된 bind source를 검증한다."""

import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


PIPELINE = Path(__file__).resolve().parents[2] / "Jenkinsfile"
FAKE_CLI = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import sys

args = sys.argv[1:]
if Path(sys.argv[0]).name == 'git':
    if args[0] == 'cat-file':
        sys.exit(0 if os.environ['TRACKED_CI'] == '1' else 1)
    if args[0] in ('rev-parse', 'hash-object'):
        print('a' * 40)
        sys.exit(0)
    sys.exit(2)
with open(os.environ['DOCKER_CALLS'], 'a') as handle:
    handle.write(json.dumps(args) + '\n')
if args[:2] == ['image', 'inspect']:
    print('sha256:test-image')
    sys.exit(0)
for index, argument in enumerate(args[:-1]):
    if argument not in ('-v', '--mount'):
        continue
    value = args[index + 1]
    source = value.split(':')[0] if argument == '-v' else dict(
        item.split('=', 1) for item in value.split(',') if '=' in item
    )['src']
    path = Path(source)
    if os.environ.get('DAEMON_ROOT'):
        try:
            path = Path(os.environ['DAEMON_ROOT']) / path.relative_to(os.environ['WORKSPACE'])
        except ValueError:
            pass
    if not path.exists():
        if argument == '-v':
            path.mkdir(parents=True)
        else:
            sys.exit(125)
sys.exit(0)
'''


@unittest.skipUnless(os.name != "nt" and shutil.which("bash"), "Linux bash 환경에서 실행합니다.")
class JenkinsMountTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.workspace = self.base / "workspace"
        for folder in ("infra/deploy", "infra/scripts", "backend", "bin"):
            (self.workspace / folder).mkdir(parents=True)
        for name in ("infra/deploy/sync-config.sh", "infra/scripts/pg-backup.sh"):
            (self.workspace / name).write_text("#!/bin/bash\n")
        for name in ("git", "docker"):
            executable = self.workspace / "bin" / name
            executable.write_text(FAKE_CLI)
            executable.chmod(0o755)
        java = self.workspace / "bin/java"
        java.write_text("#!/bin/sh\necho test-java >&2\n")
        java.chmod(0o755)
        gradle = self.workspace / "backend/gradlew"
        gradle.write_text("#!/bin/sh\necho full-check >> ../checks-ran\n")
        gradle.chmod(0o755)
        (self.workspace / "Jenkinsfile").write_text(PIPELINE.read_text())
        self.calls = self.base / "docker-calls"
        self.environment = {
            **os.environ,
            "PATH": f"{self.workspace / 'bin'}:{os.environ['PATH']}",
            "WORKSPACE": str(self.workspace),
            "JENKINS_HOME": str(self.base / "jenkins"),
            "TRACKED_CI": "0",
            "DOCKER_CALLS": str(self.calls),
            "CHECK_COMPONENT": "backend",
        }
        self.source = PIPELINE.read_text()

    def shell(self, marker):
        section = self.source.split(marker, 1)[1]
        return section.split("'''", 2)[1]

    def run_shell(self, marker):
        self.environment["CHECK_POLICY_BODY"] = self.shell("def componentCheckScript(")
        return subprocess.run(
            ["bash", "-e", "-c", self.shell(marker)],
            cwd=self.workspace, env=self.environment,
            capture_output=True, text=True, timeout=15,
        )

    def add_ci(self):
        folder = self.workspace / "infra/ci"
        folder.mkdir()
        for name in ("result_cache.py", "test_result_cache.py"):
            (folder / name).write_text("# fixture\n")
        self.environment["TRACKED_CI"] = "1"

    def docker_calls(self):
        return [json.loads(line) for line in self.calls.read_text().splitlines()] if self.calls.exists() else []

    def test_old_mr_does_not_create_ci_directory(self):
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertFalse((self.workspace / "infra/ci").exists())
        self.assertEqual(len(self.docker_calls()), 1)
        self.assertIn("캐시 도입 전", result.stdout)

    def test_untracked_leftover_directory_is_not_treated_as_ci_support(self):
        self.add_ci()
        self.environment["TRACKED_CI"] = "0"
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.docker_calls()), 1)

    def test_supported_commit_runs_both_infra_suites(self):
        self.add_ci()
        result = self.run_shell("stage('Infra check')")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(self.docker_calls()), 2)

    def test_missing_tracked_test_is_an_error(self):
        self.add_ci()
        (self.workspace / "infra/ci/test_result_cache.py").unlink()
        result = self.run_shell("stage('Infra check')")
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(len(self.docker_calls()), 1)

    def test_old_mr_cache_publish_and_restore_never_invoke_docker(self):
        result = self.run_shell("def componentCheckScript(")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.workspace / "checks-ran").exists())
        self.assertFalse((self.workspace / "infra/ci").exists())
        self.assertEqual(self.docker_calls(), [])

    def test_missing_host_source_is_not_created_by_infra_or_cache(self):
        self.add_ci()
        daemon = self.base / "daemon-workspace"
        (daemon / "infra/deploy").mkdir(parents=True)
        self.environment["DAEMON_ROOT"] = str(daemon)
        result = self.run_shell("stage('Infra check')")
        self.assertNotEqual(result.returncode, 0)
        result = self.run_shell("def componentCheckScript(")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertTrue((self.workspace / "checks-ran").exists())
        self.assertFalse((daemon / "infra/ci").exists())

    def test_supported_cache_uses_existing_sources(self):
        self.add_ci()
        result = self.run_shell("def componentCheckScript(")
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = self.docker_calls()
        self.assertEqual(len(calls), 1)
        self.assertNotIn("-v", calls[0])
        self.assertEqual(calls[0].count("--mount"), 3)


if __name__ == "__main__":
    unittest.main()
