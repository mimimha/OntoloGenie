"""Docker 실행 없이 수집 실패·중지 컨테이너·지표 교체를 검사한다."""

from contextlib import redirect_stderr, redirect_stdout
import io
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import collect


def container(**overrides):
    row = dict(id="abc123", name="/backend", project="axcore", service="backend",
               status="exited", health="none", restarts=3, oneoff="False", nano_cpus=0,
               cpu_quota=0, cpu_period=0, cpuset="")
    row.update(overrides)
    return row


class CollectorTests(unittest.TestCase):
    def test_service_presence_distinguishes_stopped_missing_and_recreated(self):
        output = collect.render([
            container(status="exited"),
            container(id="ai-new", service="ai", name="/ai", status="running"),
            container(id="ai-old", service="ai", name="/ai-old", status="exited"),
        ], "axcore", 100)
        metrics = [line for line in output.splitlines()
                   if line.startswith("axcore_docker_service_present{")]
        self.assertEqual(len(metrics), 5)
        self.assertIn('axcore_docker_service_present{project="axcore",service="backend"} 1', metrics)
        self.assertIn('axcore_docker_service_present{project="axcore",service="ai"} 1', metrics)
        self.assertIn('axcore_docker_service_present{project="axcore",service="frontend"} 0', metrics)
        self.assertTrue(all("container_id" not in line for line in metrics))

    def test_successful_empty_snapshot_reports_all_services_missing(self):
        output = collect.render([], "axcore", 100)
        metrics = [line for line in output.splitlines()
                   if line.startswith("axcore_docker_service_present{")]
        self.assertEqual(len(metrics), 5)
        self.assertTrue(all(line.endswith(" 0") for line in metrics))
        self.assertIn("axcore_docker_state_collection_success 1", output)

    def test_oneoff_or_foreign_container_does_not_satisfy_presence(self):
        output = collect.render([
            container(oneoff="True"), container(project="foreign"),
        ], "axcore", 100)
        self.assertIn('axcore_docker_service_present{project="axcore",service="backend"} 0', output)

    def test_empty_docker_list_is_not_an_inspection_failure(self):
        with patch.object(collect, "docker", return_value="") as docker:
            self.assertEqual(collect.collect("axcore"), [])
        self.assertEqual(docker.call_count, 1)

    def test_incomplete_inspect_result_is_not_reported_as_missing(self):
        with patch.object(collect, "docker", side_effect=["abc123\n", ""]):
            with self.assertRaises(ValueError):
                collect.collect("axcore")

    def test_each_health_status_emits_one_active_series(self):
        for health in ("none", "starting", "healthy", "unhealthy"):
            with self.subTest(health=health):
                output = collect.render([container(status="running", health=health)], "axcore", 100)
                metrics = [line for line in output.splitlines()
                           if line.startswith("axcore_docker_container_health{")]
                self.assertEqual(len(metrics), 4)
                self.assertEqual(sum(int(line.rsplit(" ", 1)[1]) for line in metrics), 1)
                self.assertTrue(any(f'health="{health}"}} 1' in line for line in metrics))
                self.assertTrue(all('service="backend"' in line for line in metrics))
                if health == "none":
                    self.assertTrue(any('health="unhealthy"} 0' in line for line in metrics))

    def test_missing_or_unknown_health_fails_collection_without_stale_metrics(self):
        for health in ("unknown", None):
            with self.subTest(health=health), tempfile.TemporaryDirectory() as directory:
                row = container(health=health)
                if health is None:
                    del row["health"]
                output = Path(directory) / "docker-state.prom"
                output.write_text(collect.render([container()], "axcore", 100), encoding="utf-8")
                with patch("sys.argv", ["collect.py", "--project", "axcore", "--output", str(output)]), \
                        patch.object(collect, "collect", return_value=[row]), \
                        redirect_stderr(io.StringIO()):
                    self.assertEqual(collect.main(), 1)
                content = output.read_text(encoding="utf-8")
                self.assertIn("collection_success 0", content)
                self.assertNotIn("axcore_docker_container_health", content)
                self.assertNotIn("axcore_docker_container_state", content)

    def test_cpu_caps_and_unlimited(self):
        self.assertEqual(collect.cpu_limit(container()), 0)
        self.assertEqual(collect.cpu_limit(container(nano_cpus=1_500_000_000)), 1.5)
        self.assertEqual(collect.cpu_limit(container(cpu_quota=50000, cpu_period=100000)), 0.5)
        self.assertEqual(collect.cpu_limit(container(cpu_quota=50000)), 0.5)
        self.assertEqual(collect.cpu_limit(container(cpuset="0-2,2,4")), 4)
        self.assertEqual(collect.cpu_limit(container(nano_cpus=2_000_000_000, cpuset="0")), 1)
        with self.assertRaises(ValueError):
            collect.cpu_limit(container(cpuset="3-1"))

    def test_host_network_bytes(self):
        output = collect.render_network(
            "Inter-| Receive | Transmit\n eth0: 100 1 0 0 0 0 0 0 200 2 0 0 0 0 0 0\n", 100)
        self.assertIn('receive_bytes_total{device="eth0"} 100', output)
        self.assertIn('transmit_bytes_total{device="eth0"} 200', output)
        self.assertIn("collection_timestamp_seconds 100.000", output)
        for content in ("", "eth0: 100 200", "eth0: -1 0 0 0 0 0 0 0 1 0 0 0 0 0 0 0"):
            with self.subTest(content=content), self.assertRaises(ValueError):
                collect.render_network(content, 100)

    def test_stopped_container_retains_state_and_restart_count(self):
        output = collect.render([container()], "axcore", 100)
        self.assertIn('state="exited"} 1', output)
        self.assertIn('state="running"} 0', output)
        self.assertIn('container_id="abc123"} 3', output)
        self.assertIn("# TYPE axcore_docker_container_restart_count gauge", output)

    def test_recreated_container_reports_new_identity_and_zero(self):
        output = collect.render([container(id="new123", restarts=0)], "axcore", 100)
        self.assertIn('container_id="new123"} 0', output)
        self.assertNotIn("abc123", output)

    def test_foreign_project_and_oneoff_are_excluded(self):
        output = collect.render([
            container(), container(project="other", name="/foreign"),
            container(oneoff="True", name="/oneoff"),
        ], "axcore", 100)
        self.assertNotIn("foreign", output)
        self.assertNotIn("oneoff", output)

    def test_label_escaping_and_invalid_snapshot(self):
        self.assertEqual(collect.label('a"b\\c\nd'), 'a\\"b\\\\c\\nd')
        for rows in ([container(status="unknown")], [container(restarts=-1)]):
            with self.subTest(rows=rows), self.assertRaises(ValueError):
                collect.render(rows, "axcore", 100)

    def test_inspection_failure_removes_previous_successful_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "docker-state.prom"
            output.write_text(collect.render([container()], "axcore", 100), encoding="utf-8")
            with patch("sys.argv", ["collect.py", "--project", "axcore", "--output", str(output)]), \
                    patch.object(collect, "collect", side_effect=subprocess.TimeoutExpired("docker", 10)), \
                    redirect_stderr(io.StringIO()):
                self.assertEqual(collect.main(), 1)
            self.assertIn("collection_success 0", output.read_text(encoding="utf-8"))
            self.assertNotIn("container_state", output.read_text(encoding="utf-8"))
            self.assertNotIn("service_present", output.read_text(encoding="utf-8"))
            self.assertEqual(list(Path(directory).glob("*.tmp")), [])

    def test_success_replaces_existing_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "docker-state.prom"
            output.write_text("old", encoding="utf-8")
            with patch("sys.argv", ["collect.py", "--project", "axcore", "--output", str(output)]), \
                    patch.object(collect, "collect", return_value=[container()]), \
                    redirect_stdout(io.StringIO()):
                self.assertEqual(collect.main(), 0)
            self.assertIn("collection_success 1", output.read_text(encoding="utf-8"))
            self.assertNotIn("old", output.read_text(encoding="utf-8"))


if __name__ == "__main__":
    unittest.main()
