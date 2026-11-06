#!/usr/bin/env bash
set +x
set -euo pipefail
umask 077

directory="${DEPLOY_DIR:?}/sonarqube"
source="${WORKSPACE:?}/infra/sonarqube/compose.yaml"
active="$directory/compose.yaml"

# 최초 편입에서는 기존 프로젝트와 DB 비밀번호를 그대로 사용한다.
[[ ${SONAR_DB_PASSWORD:-} =~ ^[a-f0-9]{64}$ ]] &&
    cmp -s "$directory/.env" <(printf 'SONAR_DB_PASSWORD=%s\n' "$SONAR_DB_PASSWORD") || {
    echo 'SonarQube DB credential does not match the adopted .env.' >&2
    exit 1
}
unset SONAR_DB_PASSWORD

compose() {
    local file=$1
    shift
    docker compose --project-name sonarqube --project-directory "$directory" \
        --env-file "$directory/.env" -f "$file" "$@"
}

wait_for_up() {
    for _ in $(seq 1 36); do
        if curl -fsS --max-time 5 http://sonarqube:9000/api/system/status 2>/dev/null |
            grep -Eq '"status"[[:space:]]*:[[:space:]]*"UP"'; then
            return 0
        fi
        sleep 5
    done
    echo 'SonarQube did not reach UP.' >&2
    return 1
}

compose "$active" config --quiet
if cmp -s "$active" "$source"; then
    compose "$active" up -d --no-deps --pull never sonarqube
    wait_for_up
    exit 0
fi

compose "$source" config --quiet
# 이미지나 데이터 연결 변경은 단순 설정 롤백으로 복구할 수 없다.
current_model=$(compose "$active" config --no-interpolate --format json)
next_model=$(compose "$source" config --no-interpolate --format json)
printf '[%s,%s]\n' "$current_model" "$next_model" |
    docker run --rm -i --network none --memory 128m python:3.11-slim python -c '
import json
import sys

current, candidate = json.load(sys.stdin)
for model in (current, candidate):
    service = model["services"]["sonarqube"]
    for setting in ("cpus", "mem_limit", "restart", "ulimits"):
        service.pop(setting, None)
if current != candidate:
    sys.exit("Only SonarQube runtime limits and restart policy can be changed automatically.")
'

backup=$(mktemp -d "$directory/backup.XXXXXXXX")
cp "$active" "$backup/compose.yaml"
compose "$active" exec -T db pg_dump -U sonar -d sonar -Fc > "$backup/sonar.dump"
test -s "$backup/sonar.dump"
echo "SonarQube configuration and database backup: $backup"
candidate="$backup/next-compose.yaml"
install -m 600 "$source" "$candidate"

if compose "$candidate" up -d --no-deps --pull never sonarqube && wait_for_up && mv "$candidate" "$active"; then
    exit 0
fi

echo 'SonarQube update failed. Reapplying the previous configuration.' >&2
if compose "$active" up -d --no-deps --pull never sonarqube && wait_for_up; then
    echo 'Previous SonarQube configuration restored.' >&2
else
    echo "SonarQube recovery failed. Check logs and backup at $backup." >&2
fi
exit 1
