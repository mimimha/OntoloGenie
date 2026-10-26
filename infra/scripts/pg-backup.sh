#!/usr/bin/env bash
# 플랫폼 PostgreSQL 자동 백업
# - 매일 새벽 cron 으로 실행. pg_dump 로 DB 전체를 파일 하나로 뜬다.
# - 7일 지난 백업 파일은 지운다.
# - 비밀번호 불필요: 컨테이너 안에서 실행하므로 로컬 접속으로 인증된다.
set -euo pipefail

cd /home/ubuntu/axcore
BACKUP_DIR=/home/ubuntu/axcore/backup
KEEP_DAYS=7
STAMP=$(date +%Y%m%d_%H%M)

# .env 에서 DB 이름과 계정 이름을 읽는다.
set -a; source ./.env; set +a

mkdir -p "$BACKUP_DIR"
FILE="$BACKUP_DIR/${POSTGRES_DB}_${STAMP}.dump"

docker compose exec -T postgres pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Fc > "$FILE"

find "$BACKUP_DIR" -name "${POSTGRES_DB}_*.dump" -mtime +"$KEEP_DAYS" -delete

echo "[$(date '+%F %T')] backup OK: $(basename "$FILE") $(du -h "$FILE" | cut -f1), kept $(ls "$BACKUP_DIR" | wc -l) files"
