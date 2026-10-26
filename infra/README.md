# infra

메인 서버(Lightsail, `k15c106.p.ssafy.io`)의 배포 설정 파일 사본입니다. 서버의 `/home/ubuntu/axcore/` 폴더와 같은 구조이며, 서버가 사라져도 같은 구성을 다시 만들 수 있도록 보관합니다.

## 구성

| 파일 | 역할 |
| --- | --- |
| `compose.yaml` | nginx · certbot · Jenkins · PostgreSQL 컨테이너 정의 (이미지 버전 고정, 내부망 `axcore-net`) |
| `.env.example` | `compose.yaml`이 읽는 환경 변수 이름 목록 (실제 값은 서버의 `.env`에만) |
| `nginx/conf.d/default.conf` | HTTP → HTTPS 리다이렉트, TLS 설정, `/jenkins/` 리버스 프록시 |
| `nginx/html/index.html` | 기본 페이지 (nginx 동작 확인용) |
| `jenkins/Dockerfile` | Jenkins 공식 이미지 + docker CLI · compose 플러그인 |
| `scripts/renew-cert.sh` | 인증서 자동 갱신 + nginx reload (cron에서 매일 03:00 실행) |

## 저장소에 올리지 않는 것

`.gitignore`로 제외합니다. 서버에만 존재합니다.

- `certbot/` — Let's Encrypt 인증서와 개인키
- `jenkins-data/` — Jenkins 설정, 계정, 자격 증명
- `logs/` — 갱신 로그
- `.env`, `*.pem` — 비밀값과 접속 키

## 서버에 적용하는 방법

아직 Jenkins 자동 배포 전이라 사람이 직접 반영합니다. 노트북에서 저장소 루트 기준으로 실행합니다.

```bash
# 1. 바뀐 파일을 서버로 복사 (예: nginx 설정)
scp -i ~/.ssh/K15C106T.pem infra/nginx/conf.d/default.conf ubuntu@k15c106.p.ssafy.io:axcore/nginx/conf.d/

# 2. 서버에서 적용
ssh -i ~/.ssh/K15C106T.pem ubuntu@k15c106.p.ssafy.io
cd ~/axcore
docker compose exec nginx nginx -t        # nginx 설정 문법 검사
docker compose exec nginx nginx -s reload # 설정만 바꿨을 때
docker compose up -d                      # compose.yaml을 바꿨을 때
```

반대로 서버에서 먼저 고친 경우에는 같은 방법으로 노트북에 받아 커밋합니다. 서버와 저장소의 내용이 같은지 `diff`로 확인합니다.

## 새 서버에 처음 세팅할 때 필요한 것

이 폴더만으로는 바로 뜨지 않습니다. 서버마다 다른 값이 있습니다.

1. `compose.yaml`의 `group_add: "988"`은 docker 소켓 그룹 번호입니다. 새 서버에서는 `stat -c '%g' /var/run/docker.sock`으로 다시 확인합니다.
2. `jenkins-data/` 폴더를 만들고 소유자를 uid 1000(컨테이너의 jenkins 사용자)으로 맞춥니다.
3. 인증서는 certbot으로 새로 발급합니다. (`docker compose run --rm certbot certonly --webroot ...`)
4. `renew-cert.sh`를 crontab에 등록합니다. (`0 3 * * * /home/ubuntu/axcore/scripts/renew-cert.sh >> /home/ubuntu/axcore/logs/renew-cert.log 2>&1`)
5. `.env`를 만듭니다. `cp .env.example .env` 후 값을 채우고 `chmod 600 .env`. DB 비밀번호는 `openssl rand -base64 24`로 생성합니다.
6. PostgreSQL 데이터는 Docker 볼륨 `axcore-pg-data`에 있습니다. 서버를 옮길 때는 볼륨 복사가 아니라 `pg_dump` 백업 파일로 옮깁니다. 포트는 `127.0.0.1:5432`로만 열려 있어 바깥에서 접속할 수 없습니다.

## 관련 문서

- 노션 Infra > 할 일 리스트
- 노션 Infra > Jenkins 설치 가이드
