"""Read the Quality Gate for the analysis submitted by this deploy."""

import json
import os
from pathlib import Path
import re
import sys
import time
from urllib.error import URLError
from urllib.parse import urlencode
from urllib.request import HTTPRedirectHandler, Request, build_opener


class NoRedirects(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def sonar_get(endpoint, **parameters):
    url = f"{os.environ['SONAR_HOST_URL']}/api/{endpoint}?{urlencode(parameters)}"
    headers = {"Authorization": f"Bearer {os.environ['SONAR_TOKEN']}"}
    with build_opener(NoRedirects()).open(Request(url, headers=headers), timeout=10) as response:
        return json.load(response)


def quality_gate(report, project):
    task_id = re.search(r"^ceTaskId=([A-Za-z0-9_-]+)$", report, re.MULTILINE)
    if not task_id:
        raise ValueError("Missing analysis task ID")

    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        task = sonar_get("ce/task", id=task_id[1])["task"]
        if task.get("componentKey") != project:
            raise ValueError("Unexpected analysis project")
        if task["status"] == "SUCCESS":
            result = sonar_get("qualitygates/project_status", analysisId=task["analysisId"])
            return result["projectStatus"]["status"]
        if task["status"] not in ("PENDING", "IN_PROGRESS"):
            raise ValueError("Analysis processing failed")
        time.sleep(3)
    raise ValueError("Analysis processing timed out")


if __name__ == "__main__":
    try:
        print(quality_gate(Path(sys.argv[1]).read_text(encoding="utf-8"), sys.argv[2]))
    except URLError as error:
        sys.exit(f"SonarQube API request failed (HTTP {getattr(error, 'code', 'unavailable')}).")
    except (OSError, KeyError, ValueError):
        sys.exit("Could not read the submitted analysis Quality Gate.")
