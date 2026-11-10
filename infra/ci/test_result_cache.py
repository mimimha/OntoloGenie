import importlib.util
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("result_cache", Path(__file__).with_name("result_cache.py"))
cache = importlib.util.module_from_spec(spec)
spec.loader.exec_module(cache)


class ResultCacheTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.work = self.root / "work"
        self.saved = self.root / "cache"
        self.key = cache.cache_key("backend", "a" * 40, "b" * 40, "jdk21")
        self.write("build/reports/jacoco/test/jacocoTestReport.xml", "<report/>")
        self.write("build/test-results/test/TEST-example.xml", '<testsuite tests="2" failures="0" errors="0"/>')
        self.write("build/classes/java/main/Example.class", "compiled")

    def write(self, path, text):
        target = self.work / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text)

    def publish(self):
        cache.publish(self.work, self.saved, "backend", self.key, "c" * 40)

    def test_exact_match_restores_coverage_and_classes_and_removes_stale_classes(self):
        self.publish()
        target = self.root / "analysis"
        (target / "build/classes/java/main").mkdir(parents=True)
        (target / "build/classes/java/main/Stale.class").write_text("old")
        self.assertTrue(cache.restore(target, self.saved, "backend", self.key))
        self.assertEqual((target / "build/classes/java/main/Example.class").read_text(), "compiled")
        self.assertFalse((target / "build/classes/java/main/Stale.class").exists())

    def test_source_pipeline_or_runtime_change_is_a_miss(self):
        self.publish()
        for tree, pipeline, runtime in [("d" * 40, "b" * 40, "jdk21"), ("a" * 40, "d" * 40, "jdk21"), ("a" * 40, "b" * 40, "jdk22")]:
            key = cache.cache_key("backend", tree, pipeline, runtime)
            self.assertFalse(cache.restore(self.root / "analysis", self.saved, "backend", key))

    def test_corrupt_payload_is_a_miss_without_overwriting_existing_outputs(self):
        self.publish()
        (self.saved / self.key / "outputs/build/classes/java/main/Example.class").write_text("broken")
        self.assertFalse(cache.restore(self.work, self.saved, "backend", self.key))
        self.assertEqual((self.work / "build/classes/java/main/Example.class").read_text(), "compiled")

    def test_failed_empty_or_missing_tests_cannot_be_published(self):
        for report in ['<testsuite tests="2" failures="1"/>', '<testsuite tests="0"/>']:
            self.write("build/test-results/test/TEST-example.xml", report)
            with self.assertRaises(ValueError):
                self.publish()
        (self.work / "build/test-results/test/TEST-example.xml").unlink()
        with self.assertRaises(ValueError):
            self.publish()

    def test_missing_coverage_or_classes_cannot_be_published(self):
        (self.work / "build/classes/java/main/Example.class").unlink()
        with self.assertRaises(ValueError):
            self.publish()

    def test_manifest_path_injection_is_a_miss(self):
        self.publish()
        manifest = self.saved / self.key / "manifest.json"
        data = json.loads(manifest.read_text())
        data["files"]["../../outside"] = "anything"
        manifest.write_text(json.dumps(data))
        self.assertFalse(cache.restore(self.work, self.saved, "backend", self.key))
        self.assertFalse((self.root / "outside").exists())

    def test_retention_keeps_only_ten_completed_entries(self):
        for index in range(12):
            cache.publish(self.work, self.saved, "backend", f"{index:064x}", "c" * 40)
        self.assertEqual(len(list(self.saved.iterdir())), 10)
        self.assertFalse((self.saved / ("0" * 64)).exists())

    def test_frontend_and_ai_outputs_use_independent_shapes(self):
        self.write("coverage/lcov.info", "TN:\nSF:src/a.ts\nend_of_record\n")
        self.write("coverage/coverage.xml", "<coverage/>")
        self.write("test-results/pytest.xml", '<testsuites><testsuite tests="1" errors="0"/></testsuites>')
        for component in ("frontend", "ai"):
            cache.publish(self.work, self.saved / component, component, self.key, "c" * 40)
            self.assertTrue(cache.restore(self.root / component, self.saved / component, component, self.key))

    def test_ai_requires_configured_path_and_ignores_stale_top_level_report(self):
        import configparser

        config = configparser.ConfigParser()
        for parent in Path(__file__).resolve().parents:
            config_file = parent / "ai/.coveragerc"
            if config_file.is_file():
                config.read(config_file)
                break
        # The CI-only container mount may omit the repository's ai directory.
        if config.has_section("xml"):
            self.assertEqual(cache.REQUIRED["ai"], config["xml"]["output"])
        self.write("test-results/pytest.xml", '<testsuites><testsuite tests="1" errors="0"/></testsuites>')
        self.write("coverage.xml", "stale report")
        with self.assertRaises(ValueError):
            cache.publish(self.work, self.saved, "ai", self.key, "c" * 40)
        self.write("coverage/coverage.xml", "<coverage/>")
        cache.publish(self.work, self.saved, "ai", self.key, "c" * 40)
        target = self.root / "analysis-ai"
        self.assertTrue(cache.restore(target, self.saved, "ai", self.key))
        self.assertTrue((target / "coverage/coverage.xml").is_file())
        self.assertFalse((target / "coverage.xml").exists())


if __name__ == "__main__":
    unittest.main()
