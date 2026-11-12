"""Reuse successful full check outputs on the same Jenkins node; never reuse verdicts alone."""

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import xml.etree.ElementTree as ET


ROOTS = {
    "backend": ["build/classes", "build/resources", "build/reports/jacoco/test", "build/test-results/test"],
    "frontend": ["coverage"],
    "ai": ["coverage/coverage.xml", "test-results"],
}
REQUIRED = {
    "backend": "build/reports/jacoco/test/jacocoTestReport.xml",
    "frontend": "coverage/lcov.info",
    "ai": "coverage/coverage.xml",
}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def cache_key(component, tree, pipeline, runtime):
    if not re.fullmatch(r"[0-9a-f]{40}", tree) or not re.fullmatch(r"[0-9a-f]{40}", pipeline):
        raise ValueError("Invalid Git object ID")
    return hashlib.sha256(
        json.dumps([1, component, tree, pipeline, runtime, digest(Path(__file__))]).encode()
    ).hexdigest()


def files_under(root, component):
    files = {}
    for relative in ROOTS[component]:
        path = root / relative
        if path.is_symlink() or root.is_symlink():
            raise ValueError("Symlink in output root")
        candidates = sorted(path.rglob("*")) if path.is_dir() else [path]
        for candidate in candidates:
            if candidate.is_symlink():
                raise ValueError("Symlink in test outputs")
            if candidate.is_file():
                files[candidate.relative_to(root).as_posix()] = digest(candidate)
    required = REQUIRED[component]
    if required not in files or not (root / required).stat().st_size:
        raise ValueError("Coverage output missing")
    if component == "backend":
        if not any(name.startswith("build/classes/java/main/") for name in files):
            raise ValueError("Compiled main classes missing")
    if component in ("backend", "ai"):
        reports = list((root / ("build/test-results/test" if component == "backend" else "test-results")).glob("*.xml"))
        if not reports:
            raise ValueError("JUnit results missing")
        tests = 0
        for report in reports:
            xml = ET.parse(report).getroot()
            suites = [xml] if xml.tag == "testsuite" else list(xml.iter("testsuite"))
            for suite in suites:
                tests += int(suite.get("tests", 0))
                if int(suite.get("failures", 0)) or int(suite.get("errors", 0)):
                    raise ValueError("Unsuccessful tests")
        if tests == 0:
            raise ValueError("No tests executed")
    return files


def publish(work, cache, component, key, commit):
    files = files_under(work, component)
    cache.mkdir(parents=True, exist_ok=True)
    entry = cache / key
    if entry.exists():
        # A previous publication is immutable. A damaged entry is a miss until retention removes it.
        return
    with tempfile.TemporaryDirectory(prefix=".publish-", dir=cache) as staging:
        stage = Path(staging)
        for relative in files:
            target = stage / "outputs" / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(work / relative, target)
        (stage / "manifest.json").write_text(json.dumps({
            "key": key, "component": component, "commit": commit,
            "build": os.environ.get("BUILD_URL", ""), "files": files,
        }), encoding="utf-8")
        os.rename(stage, entry)
        # TemporaryDirectory still owns the empty temporary path, not the published entry.
        stage.mkdir()
    entries = sorted(
        (p for p in cache.iterdir() if re.fullmatch(r"[0-9a-f]{64}", p.name) and p.is_dir() and not p.is_symlink()),
        key=lambda p: p.stat().st_mtime, reverse=True,
    )
    for old in entries[10:]:
        shutil.rmtree(old)


def restore(work, cache, component, key):
    entry = cache / key
    try:
        manifest = json.loads((entry / "manifest.json").read_text(encoding="utf-8"))
        if manifest["key"] != key or manifest["component"] != component:
            return False
        # Independently enumerate the allowlisted payload. Do not trust paths from the manifest.
        files = files_under(entry / "outputs", component)
        if files != manifest["files"]:
            return False
    except (OSError, ValueError, KeyError, ET.ParseError):
        return False
    # Clear only generated output roots to avoid stale classes/coverage from another checkout.
    for relative in ROOTS[component]:
        destination = work / relative
        if destination.is_symlink() or any(p.is_symlink() for p in destination.parents if p != work.parent):
            raise ValueError("Symlink at output destination")
        if destination.is_dir():
            shutil.rmtree(destination)
        elif destination.exists():
            destination.unlink()
    for relative in files:
        target = work / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(entry / "outputs" / relative, target)
    print(f"Reused successful full check outputs: {component}, source={manifest['commit']}, build={manifest['build']}")
    return True


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["publish", "restore"])
    parser.add_argument("component", choices=list(ROOTS))
    parser.add_argument("--tree", required=True)
    parser.add_argument("--pipeline", required=True)
    parser.add_argument("--runtime", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--work", type=Path, default=Path("/work"))
    parser.add_argument("--cache", type=Path, default=Path("/cache"))
    args = parser.parse_args()
    key = cache_key(args.component, args.tree, args.pipeline, args.runtime)
    if args.action == "publish":
        publish(args.work, args.cache / args.component, args.component, key, args.commit)
    elif not restore(args.work, args.cache / args.component, args.component, key):
        print("No matching complete successful check outputs; run tests normally")
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
