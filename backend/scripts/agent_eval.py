"""Clone a deployment snapshot into a new local DB and run Agent model evaluations.

Uses Python's standard library, Docker, OpenSSH and the existing Gradle wrapper.
Secrets stay in process environment or the git-ignored backend/build directory.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import secrets
import statistics
import subprocess
import sys
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import HTTPRedirectHandler, Request, build_opener

BACKEND = Path(__file__).resolve().parents[1]
WORK = BACKEND / "build" / "agent-eval"
STATE = WORK / "snapshot.json"
BASE_URL = "https://gms.ssafy.io/gmsapi/api.openai.com/v1"
AGENT_KEYS = {
    "AXCORE_AGENT_LLM_API_KEY",
    "AXCORE_AGENT_LLM_BASE_URL",
    "AXCORE_AGENT_LLM_MODEL",
    "AXCORE_AGENT_LLM_REASONING_EFFORT",
}
EVAL_TEST = "com.axcore.platform.agent.service.ModelComparisonGmsTests"


def stamp() -> str:
    return datetime.now(timezone(timedelta(hours=9))).strftime("%Y%m%d-%H%M%S")


def load_env(path: Path) -> dict[str, str]:
    """Parse assignments as data; never execute shell expressions."""
    values = {}
    if path.is_file():
        for line in path.read_text(encoding="utf-8-sig").splitlines():
            match = re.fullmatch(
                r"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=\s*(.*?)\s*", line
            )
            if match:
                name, value = match.groups()
                if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                    value = value[1:-1]
                values[name] = value
    return values


def write_json(path: Path, data: object) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )


def command(args: list[str], **kwargs: object) -> subprocess.CompletedProcess:
    return subprocess.run(args, check=True, **kwargs)


def text_command(args: list[str]) -> str:
    return command(
        args, capture_output=True, text=True, encoding="utf-8"
    ).stdout.strip()


def state() -> dict:
    if not STATE.is_file():
        raise ValueError(
            "평가 DB가 없습니다. 먼저 clone --dump <백업 파일>을 실행하세요."
        )
    data = json.loads(STATE.read_text(encoding="utf-8"))
    if not re.fullmatch(r"axcore-agent-eval-[a-z0-9-]+", data["container"]):
        raise ValueError("평가 컨테이너 이름이 올바르지 않습니다.")
    if not 1 <= int(data["port"]) <= 65535:
        raise ValueError("평가 DB 포트가 올바르지 않습니다.")
    label = text_command(
        [
            "docker",
            "inspect",
            "--format",
            '{{index .Config.Labels "axcore.agent-eval"}}',
            data["container"],
        ]
    )
    if label != "true":
        raise ValueError("이 도구가 만든 평가 컨테이너가 아닙니다.")
    binding = text_command(["docker", "port", data["container"], "5432/tcp"])
    if not re.fullmatch(r"127\.0\.0\.1:\d+", binding):
        raise ValueError("평가 DB가 실행 중이며 localhost 전용으로 열려 있어야 합니다.")
    current_port = int(binding.rsplit(":", 1)[1])
    if current_port != int(data["port"]):
        reader_path = Path(data["readerEnv"]).resolve()
        if not reader_path.is_relative_to(WORK.resolve()):
            raise ValueError("평가 환경 파일이 작업 디렉터리 밖에 있습니다.")
        reader = load_env(reader_path)
        previous_url = f"jdbc:postgresql://127.0.0.1:{data['port']}/axcore_eval"
        if (
            reader.get("DB_USERNAME") != "axcore_eval_reader"
            or reader.get("DB_URL") != previous_url
        ):
            raise ValueError("저장된 평가 DB 설정을 확인할 수 없습니다.")
        reader["DB_URL"] = f"jdbc:postgresql://127.0.0.1:{current_port}/axcore_eval"
        reader_path.write_text(
            "".join(
                f"{name}={reader[name]}\n"
                for name in ("DB_URL", "DB_USERNAME", "DB_PASSWORD")
            ),
            encoding="utf-8",
        )
        os.chmod(reader_path, 0o600)
        data["port"] = current_port
        write_json(STATE, data)
    return data


def sql(container: str, statement: str, *, reader: bool = False) -> str:
    user = "axcore_eval_reader" if reader else "axcore_eval_owner"
    return command(
        [
            "docker",
            "exec",
            "-i",
            container,
            "psql",
            "-X",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            user,
            "-d",
            "axcore_eval",
            "-At",
        ],
        input=statement,
        capture_output=True,
        text=True,
        encoding="utf-8",
    ).stdout.strip()


def snapshot_counts(container: str) -> dict:
    rows = sql(
        container,
        """
        select json_build_object(
          'objects', (select count(*) from platform.object_instance),
          'links', (select count(*) from platform.object_link),
          'types', (select count(*) from platform.object_type),
          'flyway', (select coalesce(max(installed_rank),0) from platform.flyway_schema_history)
        );
        """,
    )
    return json.loads(rows)


def clone(args: argparse.Namespace) -> None:
    WORK.mkdir(parents=True, exist_ok=True)
    if STATE.exists():
        raise ValueError(
            "이미 평가 DB 상태 파일이 있습니다. 기존 DB를 덮어쓰지 않습니다. 상태 파일을 별도로 보관한 뒤 새 복제를 시작하세요."
        )
    run_id = stamp() + "-" + secrets.token_hex(3)
    directory = WORK / ("snapshot-" + run_id)
    directory.mkdir()
    synthetic = getattr(args, "synthetic", False)
    dump = None
    checksum = None
    if synthetic:
        pass
    elif args.dump:
        dump = Path(args.dump).expanduser().resolve(strict=True)
    else:
        if not args.ssh_key:
            raise ValueError(
                "--dump 또는 --ssh-key가 필요합니다. 비밀번호를 인자로 받지 않습니다."
            )
        key = Path(args.ssh_key).expanduser().resolve(strict=True)
        if not re.fullmatch(r"[A-Za-z0-9_.-]+@[A-Za-z0-9.-]+", args.ssh_host):
            raise ValueError("SSH 대상은 user@host 형식이어야 합니다.")
        dump = directory / "deployment.dump"
        partial = directory / "deployment.dump.part"
        # Binary stdout goes directly to a file; PowerShell text pipelines are not used.
        remote = (
            "docker exec postgres pg_dump -U axcore -d axcore -Fc --no-owner --no-acl "
            "--exclude-table-data=platform.data_source_credential "
            "--exclude-table-data=platform.spring_session "
            "--exclude-table-data=platform.spring_session_attributes"
        )
        with partial.open("wb") as output:
            command(
                [
                    "ssh",
                    "-i",
                    str(key),
                    "-o",
                    "BatchMode=yes",
                    "-o",
                    "StrictHostKeyChecking=yes",
                    "-o",
                    "ConnectTimeout=15",
                    args.ssh_host,
                    remote,
                ],
                stdout=output,
            )
        partial.replace(dump)
    if dump is not None:
        with dump.open("rb") as source:
            if source.read(5) != b"PGDMP":
                raise ValueError(
                    "pg_dump -Fc로 만든 custom 형식 백업 파일이 필요합니다."
                )
            source.seek(0)
            checksum = hashlib.file_digest(source, "sha256").hexdigest()
    container = "axcore-agent-eval-" + run_id
    owner_password, reader_password = (
        secrets.token_urlsafe(32),
        secrets.token_urlsafe(32),
    )
    owner_env = directory / ".owner.env"
    owner_env.write_text(
        f"POSTGRES_USER=axcore_eval_owner\nPOSTGRES_PASSWORD={owner_password}\nPOSTGRES_DB=axcore_eval\n",
        encoding="utf-8",
    )
    os.chmod(owner_env, 0o600)
    command(
        [
            "docker",
            "run",
            "-d",
            "--name",
            container,
            "--label",
            "axcore.agent-eval=true",
            "-p",
            "127.0.0.1::5432",
            "--env-file",
            str(owner_env),
            "postgres:18.6",
        ],
        capture_output=True,
    )
    for attempt in range(60):
        ready = subprocess.run(
            [
                "docker",
                "exec",
                container,
                "pg_isready",
                "-h",
                "127.0.0.1",
                "-U",
                "axcore_eval_owner",
                "-d",
                "axcore_eval",
            ],
            check=False,
            capture_output=True,
        )
        if ready.returncode == 0:
            break
        time.sleep(1)
    else:
        raise ValueError(
            f"평가 DB 준비 시간 초과: {container}. 컨테이너는 진단을 위해 남겼습니다."
        )
    if dump is not None:
        with dump.open("rb") as source:
            command(
                [
                    "docker",
                    "exec",
                    "-i",
                    container,
                    "pg_restore",
                    "--exit-on-error",
                    "--no-owner",
                    "--no-acl",
                    "-U",
                    "axcore_eval_owner",
                    "-d",
                    "axcore_eval",
                ],
                stdin=source,
                capture_output=True,
            )
    binding = text_command(["docker", "port", container, "5432/tcp"])
    if not re.fullmatch(r"127\.0\.0\.1:\d+", binding):
        raise ValueError("평가 DB의 포트가 localhost 전용이 아닙니다.")
    port = int(binding.rsplit(":", 1)[1])
    migration_env = os.environ.copy()
    migration_env.update(
        {
            "DB_URL": f"jdbc:postgresql://127.0.0.1:{port}/axcore_eval",
            "DB_USERNAME": "axcore_eval_owner",
            "DB_PASSWORD": owner_password,
            "AXCORE_AGENT_EVAL": "prepare",
        }
    )
    command(
        [
            "java",
            "-classpath",
            "gradle/wrapper/gradle-wrapper.jar",
            "org.gradle.wrapper.GradleWrapperMain",
            "test",
            "--rerun",
            "--tests",
            "com.axcore.platform.agent.service.LocalSnapshotMigrationTests",
        ],
        cwd=BACKEND,
        env=migration_env,
    )
    if synthetic:
        fixtures = BACKEND / "src/test/resources/agent-eval"
        sql(container, (fixtures / "smoke-fixture.sql").read_text(encoding="utf-8"))
        if args.variant == "diverse":
            sql(
                container,
                (fixtures / "diverse-fixture.sql").read_text(encoding="utf-8"),
            )
    # Only the newly created evaluation copy is sanitized. Deployment is never modified.
    sql(
        container,
        """
        do $sanitize$
        declare t text;
        begin
          foreach t in array array['spring_session_attributes','spring_session','data_source_credential'] loop
            if to_regclass('platform.' || t) is not null then
              execute format('delete from platform.%I', t);
            end if;
          end loop;
        end $sanitize$;
        update platform.account set password_hash='!EVALUATION_DISABLED!',
          login_id='eval-' || id, display_name='평가계정-' || id, status='DISABLED';
        """,
    )
    # Passwords are sent through stdin, never command arguments or reports.
    sql(
        container,
        f"""
        create role axcore_eval_reader login password '{reader_password}';
        grant connect on database axcore_eval to axcore_eval_reader;
        grant usage on schema platform to axcore_eval_reader;
        grant select on all tables in schema platform to axcore_eval_reader;
        revoke all on platform.account from axcore_eval_reader;
        grant select (id, role_id, status) on platform.account to axcore_eval_reader;
        alter role axcore_eval_reader set default_transaction_read_only=on;
        alter role axcore_eval_reader set search_path=platform;
        """,
    )
    reader_env = directory / ".reader.env"
    reader_env.write_text(
        f"DB_URL=jdbc:postgresql://127.0.0.1:{port}/axcore_eval\n"
        f"DB_USERNAME=axcore_eval_reader\nDB_PASSWORD={reader_password}\n",
        encoding="utf-8",
    )
    os.chmod(reader_env, 0o600)
    if synthetic:
        dump = directory / "synthetic.dump"
        with dump.open("wb") as target:
            command(
                [
                    "docker",
                    "exec",
                    container,
                    "pg_dump",
                    "-U",
                    "axcore_eval_owner",
                    "-d",
                    "axcore_eval",
                    "-Fc",
                    "--no-owner",
                    "--no-acl",
                ],
                stdout=target,
            )
        with dump.open("rb") as source:
            checksum = hashlib.file_digest(source, "sha256").hexdigest()
    record = {
        "container": container,
        "port": port,
        "createdAtKst": stamp(),
        "snapshotSha256": checksum,
        "origin": ("synthetic-" + args.variant)
        if synthetic
        else ("provided-dump" if args.dump else "deployment-ssh"),
        "readerEnv": str(reader_env),
        "counts": snapshot_counts(container),
    }
    write_json(STATE, record)
    print(
        json.dumps(
            {k: v for k, v in record.items() if k != "readerEnv"},
            ensure_ascii=False,
            indent=2,
        )
    )


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        raise ValueError(
            "인증정보 보호를 위해 GMS 요청의 리다이렉트를 허용하지 않습니다."
        )


def gms_settings(env_file: Path) -> dict[str, str]:
    settings = {
        key: value for key, value in load_env(env_file).items() if key in AGENT_KEYS
    }
    settings.update({key: os.environ[key] for key in AGENT_KEYS if os.environ.get(key)})
    if not settings.get("AXCORE_AGENT_LLM_API_KEY"):
        raise ValueError(
            "AXCORE_AGENT_LLM_API_KEY 환경 변수 또는 로컬 .env 설정이 필요합니다."
        )
    url = settings.get("AXCORE_AGENT_LLM_BASE_URL", BASE_URL).rstrip("/")
    parsed = urlsplit(url)
    if (
        parsed.scheme != "https"
        or parsed.hostname != "gms.ssafy.io"
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("GMS 키는 https://gms.ssafy.io 주소에만 사용할 수 있습니다.")
    settings["AXCORE_AGENT_LLM_BASE_URL"] = url
    return settings


def candidate_chat(model_id: str) -> bool:
    return not any(
        term in model_id.lower()
        for term in (
            "embedding",
            "whisper",
            "tts",
            "dall-e",
            "gpt-image",
            "sora",
            "moderation",
            "transcription",
            "realtime",
        )
    )


def discover(args: argparse.Namespace) -> None:
    settings = gms_settings(Path(args.env_file))
    request = Request(
        settings["AXCORE_AGENT_LLM_BASE_URL"] + "/models",
        headers={
            "Authorization": "Bearer " + settings["AXCORE_AGENT_LLM_API_KEY"],
            "Accept": "application/json",
        },
    )
    with build_opener(NoRedirect()).open(request, timeout=30) as response:
        payload = json.load(response)
    rows = payload.get("data")
    if not isinstance(rows, list) or not all(
        isinstance(row, dict) and isinstance(row.get("id"), str) for row in rows
    ):
        raise ValueError(
            "GMS 모델 목록 응답 형식을 확인할 수 없습니다. run --models로 모델 ID를 직접 지정하세요."
        )
    ids = sorted({row["id"] for row in rows})
    catalog = {
        "fetchedAtKst": stamp(),
        "source": settings["AXCORE_AGENT_LLM_BASE_URL"] + "/models",
        "coverage": "catalog",
        "models": [
            {"id": model_id, "candidateChat": candidate_chat(model_id)}
            for model_id in ids
        ],
    }
    write_json(WORK / "models.json", catalog)
    print(json.dumps(catalog, ensure_ascii=False, indent=2))


def probe(args: argparse.Namespace) -> None:
    """Verify an explicit candidate list without sending any business records."""
    settings = gms_settings(Path(args.env_file))
    ids = json.loads(Path(args.candidates).read_text(encoding="utf-8"))
    if not isinstance(ids, list) or not all(isinstance(value, str) for value in ids):
        raise ValueError("후보 파일은 모델 ID의 JSON 배열이어야 합니다.")
    ids = choose_models(",".join(ids), Path())
    rows = []
    for model_id in ids:
        payload = {
            "model": model_id,
            "messages": [{"role": "user", "content": "Reply with exactly OK."}],
        }
        started = time.monotonic()
        row = {"id": model_id, "candidateChat": False}
        try:
            request = Request(
                settings["AXCORE_AGENT_LLM_BASE_URL"] + "/chat/completions",
                data=json.dumps(payload).encode("utf-8"),
                headers={
                    "Authorization": "Bearer " + settings["AXCORE_AGENT_LLM_API_KEY"],
                    "Content-Type": "application/json",
                    "Accept": "application/json",
                },
            )
            with build_opener(NoRedirect()).open(request, timeout=45) as response:
                answer = json.load(response)
            row["candidateChat"] = bool(answer.get("choices"))
            row["httpStatus"] = 200
            row["usage"] = answer.get("usage")
        except HTTPError as error:
            row["httpStatus"] = error.code
        except (URLError, TimeoutError, ValueError):
            row["error"] = "응답 형식 또는 연결 실패"
        row["elapsedMs"] = round((time.monotonic() - started) * 1000)
        rows.append(row)
        print(
            f"{model_id}: chat={row['candidateChat']} http={row.get('httpStatus', 'unavailable')}",
            flush=True,
        )
        write_json(
            WORK / "models.json",
            {
                "fetchedAtKst": stamp(),
                "source": str(Path(args.candidates).resolve()),
                "coverage": "explicit-candidates-not-exhaustive",
                "models": rows,
            },
        )


def choose_models(explicit: str | None, catalog: Path) -> list[str]:
    if explicit:
        ids = [part.strip() for part in explicit.split(",") if part.strip()]
    else:
        payload = json.loads(catalog.read_text(encoding="utf-8"))
        ids = [row["id"] for row in payload["models"] if row["candidateChat"]]
    if not ids or any(
        not re.fullmatch(r"[A-Za-z0-9_./:-]{1,200}", value) for value in ids
    ):
        raise ValueError(
            "유효한 모델 ID가 필요합니다. models 명령 또는 --models를 사용하세요."
        )
    return list(dict.fromkeys(ids))


def summarize(results: list[dict]) -> str:
    groups: dict[str, list[dict]] = {}
    for row in results:
        groups.setdefault(row["model"], []).append(row)
    lines = [
        "# Agent 모델 비교",
        "",
        "정확도는 대상·조회 도구·명시한 답변 사실의 자동 검사 결과이며 전체 답변의 의미 정확도를 대신하지 않습니다.",
        "",
        "| 모델 | 턴 | 통과 | 오류 | 중앙 응답(ms) | 입력 토큰 | 출력 토큰 |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for model, rows in sorted(groups.items()):
        lines.append(
            f"| {model} | {len(rows)} | {sum(row['passed'] for row in rows)} | "
            f"{sum(row['status'] == 'ERROR' for row in rows)} | "
            f"{statistics.median(row['elapsedMs'] for row in rows):.0f} | "
            f"{sum(row.get('promptTokens', 0) for row in rows)} | "
            f"{sum(row.get('completionTokens', 0) for row in rows)} |"
        )
    lines.extend(
        [
            "",
            "토큰은 Spring AI가 응답 메타데이터로 보고한 값입니다. 도구 내부 재호출의 사용량을 모두 포함한다고 보장하지 않으며, 요금 정보가 없어 비용으로 환산하지 않습니다.",
            "",
        ]
    )
    return "\n".join(lines)


def evaluate(args: argparse.Namespace) -> None:
    snapshot = state()
    settings = gms_settings(Path(args.env_file))
    model_ids = choose_models(args.models, WORK / "models.json")
    reasoning_effort = getattr(args, "reasoning_effort", "none")
    parallel = getattr(args, "parallel", 1)
    if not 1 <= parallel <= 4:
        raise ValueError("동시 실행 모델 수는 1~4여야 합니다.")
    directory = WORK / ("run-" + stamp() + "-" + secrets.token_hex(2))
    directory.mkdir()
    questions = Path(args.questions).resolve(strict=True)
    spec = json.loads(questions.read_text(encoding="utf-8"))
    frozen_questions = directory / "questions-original.json"
    frozen_questions.write_bytes(questions.read_bytes())
    env = os.environ.copy()
    reader = load_env(Path(snapshot["readerEnv"]))
    if (
        reader.get("DB_URL")
        != f"jdbc:postgresql://127.0.0.1:{snapshot['port']}/axcore_eval"
        or reader.get("DB_USERNAME") != "axcore_eval_reader"
    ):
        raise ValueError(
            "평가 DB 설정이 이 도구가 만든 localhost 읽기 전용 DB와 다릅니다."
        )
    env.update(reader)
    env.update(settings)
    env.update(
        {
            "AXCORE_AGENT_LLM": "openai",
            "AXCORE_AGENT_EVAL": "models",
            "AXCORE_AGENT_EVAL_MODELS": ",".join(model_ids),
            "AXCORE_AGENT_EVAL_OUTPUT": str(directory),
            "AXCORE_AGENT_EVAL_QUESTIONS": str(frozen_questions),
            "AXCORE_AGENT_EVAL_REPEAT": str(args.repeat),
            "AXCORE_AGENT_EVAL_ROLE": args.role,
            "AXCORE_AGENT_EVAL_TURN_LIMIT": str(args.turn_limit),
            "AXCORE_AGENT_EVAL_REASONING_EFFORT": reasoning_effort,
            "AXCORE_AGENT_EVAL_PARALLEL": str(parallel),
        }
    )
    write_json(
        directory / "manifest.json",
        {
            "models": model_ids,
            "repeat": args.repeat,
            "role": args.role,
            "answerReasoningEffort": reasoning_effort,
            "parallel": parallel,
            "snapshot": snapshot,
            "questionsSha256": hashlib.sha256(questions.read_bytes()).hexdigest(),
            "plannedTurns": len(model_ids)
            * args.repeat
            * min(args.turn_limit, len(spec["turns"])),
            "gitCommit": text_command(["git", "-C", str(BACKEND), "rev-parse", "HEAD"]),
            "gitDirty": bool(
                text_command(["git", "-C", str(BACKEND), "status", "--porcelain"])
            ),
        },
    )
    print(
        f"평가: {len(model_ids)}개 모델 × {min(args.turn_limit, len(spec['turns']))}개 질문 × {args.repeat}회. 결과: {directory}"
    )
    before = snapshot_counts(snapshot["container"])
    result = subprocess.run(
        [
            "java",
            "-classpath",
            "gradle/wrapper/gradle-wrapper.jar",
            "org.gradle.wrapper.GradleWrapperMain",
            "test",
            "--rerun",
            "--tests",
            EVAL_TEST,
        ],
        check=False,
        cwd=BACKEND,
        env=env,
    )
    after = snapshot_counts(snapshot["container"])
    write_json(
        directory / "db-verification.json",
        {"before": before, "after": after, "unchanged": before == after},
    )
    output = directory / "outcomes.jsonl"
    if output.is_file():
        rows = [
            json.loads(line)
            for line in output.read_text(encoding="utf-8").splitlines()
            if line
        ]
        (directory / "summary.md").write_text(summarize(rows), encoding="utf-8")
    if result.returncode or before != after:
        raise ValueError(
            f"평가 실행 또는 DB 불변 검사 실패. 결과를 확인하세요: {directory}"
        )
    print(f"완료: {directory / 'summary.md'}")


def parser() -> argparse.ArgumentParser:
    root = argparse.ArgumentParser(description=__doc__)
    root.add_argument(
        "--workspace", default=str(WORK), help="backend/build 아래 평가 작업 디렉터리"
    )
    commands = root.add_subparsers(dest="action", required=True)
    copy = commands.add_parser(
        "clone", help="새 localhost 평가 DB로 복원 (기존 DB 덮어쓰기 없음)"
    )
    source = copy.add_mutually_exclusive_group(required=True)
    source.add_argument("--dump")
    source.add_argument("--ssh-key")
    copy.add_argument("--ssh-host", default="ubuntu@k15c106.p.ssafy.io")
    copy.set_defaults(handler=clone)
    synthetic = commands.add_parser(
        "synthetic", help="운영 데이터 없이 새 합성 평가 DB 생성"
    )
    synthetic.add_argument("--variant", choices=["smoke", "diverse"], default="diverse")
    synthetic.set_defaults(handler=clone, synthetic=True)
    models = commands.add_parser("models", help="GMS /models에서 현재 모델 목록 확인")
    models.add_argument("--env-file", default=str(BACKEND / ".env"))
    models.set_defaults(handler=discover)
    candidates = commands.add_parser(
        "probe",
        help="명시한 모델 후보의 실제 접근 가능 여부 확인 (업무 데이터 전송 없음)",
    )
    candidates.add_argument("--env-file", default=str(BACKEND / ".env"))
    candidates.add_argument(
        "--candidates", default=str(BACKEND / "scripts/gms-model-candidates.json")
    )
    candidates.set_defaults(handler=probe)
    run = commands.add_parser("run", help="복제된 평가 DB에서 같은 대화로 모델 비교")
    run.add_argument("--env-file", default=str(BACKEND / ".env"))
    run.add_argument(
        "--models", help="쉼표로 구분한 모델 ID. 생략하면 발견한 채팅 후보 전체"
    )
    run.add_argument(
        "--questions",
        default=str(BACKEND / "src/test/resources/agent-eval/model-comparison.json"),
    )
    run.add_argument("--repeat", type=int, default=1)
    run.add_argument("--turn-limit", type=int, default=8)
    run.add_argument("--parallel", type=int, choices=[1, 2, 3, 4], default=1)
    run.add_argument(
        "--reasoning-effort",
        choices=["none", "minimal", "low", "medium", "high", "xhigh"],
        default="none",
        help="답변 단계의 추론 강도. 기본 none이며 모델별 지원 값이 다릅니다",
    )
    run.add_argument(
        "--role",
        choices=["VIEWER", "PLANNER", "MAINTENANCE", "ADMIN"],
        default="VIEWER",
    )
    run.set_defaults(handler=evaluate)
    status = commands.add_parser("status", help="평가 DB의 식별 정보와 행 수 확인")
    status.set_defaults(
        handler=lambda _: print(
            json.dumps(
                {**state(), "currentCounts": snapshot_counts(state()["container"])},
                ensure_ascii=False,
                indent=2,
            )
        )
    )
    return root


def main() -> int:
    global WORK, STATE
    args = parser().parse_args()
    workspace = Path(args.workspace).resolve()
    if not workspace.is_relative_to((BACKEND / "build").resolve()):
        raise ValueError(
            "평가 작업 디렉터리는 Git에서 제외되는 backend/build 아래여야 합니다."
        )
    WORK, STATE = workspace, workspace / "snapshot.json"
    if getattr(args, "repeat", 1) < 1 or getattr(args, "turn_limit", 1) < 1:
        raise ValueError("반복 수와 질문 수는 1 이상이어야 합니다.")
    args.handler(args)
    return 0


if __name__ == "__main__":
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8")
    try:
        sys.exit(main())
    except HTTPError as exc:
        print(
            f"GMS 모델 목록 요청 실패: HTTP {exc.code}. 모델 목록 API가 제공되지 않으면 run --models를 사용하세요.",
            file=sys.stderr,
        )
        sys.exit(1)
    except (ValueError, OSError, URLError, subprocess.CalledProcessError) as exc:
        # Do not print subprocess stderr: it can contain SQL/passwords or remote data.
        message = (
            "외부 명령 실행 실패 (비밀값 보호를 위해 출력 생략). Docker/SSH 및 백업 형식을 확인하세요."
            if isinstance(exc, subprocess.CalledProcessError)
            else str(exc)
        )
        print(message, file=sys.stderr)
        sys.exit(1)
