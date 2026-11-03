# Jenkins Credentials 전환

Credentials 주입·설정 자동 반영 구현의 운영 안내다. 운영 서버 적용·실제 Jenkins 배포 검증은 아직 완료하지 않았다.

## 비밀값 원본과 실행 파일

| 종류 | 원본 | 사용 |
|---|---|---|
| 비밀값 | Jenkins Secret text | 배포 Job의 전용 단계에서만 바인딩 |
| 일반 설정 | 서버 `.env.config`, `cdc/.env.config` | DB 이름·사용자, 초기 관리자 ID, 모델·주소 등. 저장소에는 예시만 보관 |
| 이미지 태그 | 서버 실행용 `.env`의 `*_TAG` | 기존 Jenkins 배포·롤백 함수가 갱신 |
| 실행용 환경 | 서버 `.env`, `cdc/.env` | Jenkins가 생성하는 Compose 입력. 비밀값 원본으로 편집하지 않음 |

Jenkins가 운영 비밀값을 MR 검사·이미지 빌드에 바인딩하지 않는다. 값은 Groovy 문자열이나 Docker 명령 인자에 넣지 않고 환경변수 이름으로만 전달한다. Docker 환경변수와 실행용 파일은 Docker·서버 관리자에게 접근 가능하며, Credentials 마스킹을 접근 통제나 암호화의 대체로 보지 않는다.

| Credentials ID | Jenkins 바인딩 | Compose 환경변수 |
|---|---|---|
| axcore-postgres-password | DEPLOY_POSTGRES_PASSWORD | POSTGRES_PASSWORD |
| axcore-initial-admin-password | DEPLOY_INITIAL_ADMIN_PASSWORD | AXCORE_INITIAL_ADMIN_PASSWORD |
| axcore-grafana-admin-password | DEPLOY_GRAFANA_PASSWORD | GRAFANA_ADMIN_PASSWORD |
| axcore-internal-api-token | DEPLOY_INTERNAL_TOKEN | AXCORE_INTERNAL_API_TOKEN, AI_ONTOLOGY_INTERNAL_TOKEN |
| source-credential-key-1 | DEPLOY_SOURCE_KEY | AXCORE_SOURCE_CREDENTIAL_KEY_1 |
| axcore-erp-cdc-password | DEPLOY_ERP_CDC_PASSWORD | ERP_CDC_PASSWORD |
| axcore-mes-cdc-password | DEPLOY_MES_CDC_PASSWORD | MES_CDC_PASSWORD |

기존 암호화 키·DB 비밀번호·토큰·CDC 비밀번호를 바꾸는 작업이 아니다. 현재 서버 값이 있으면 등록된 Credentials와 일치해야 진행한다. 변경이 필요하면 별도 회전 절차를 수행해야 한다. 기존 초기화용 비밀번호도 현재 로그인 비밀번호로 임의 변경하지 않는다.

## 최초 전환과 유지

1. 등록 ID와 기존 값의 일치를 사용자 측에서 확인한다. 실제 값은 문서·채팅·MR·콘솔에 붙이지 않는다.
2. MR 병합 전 서버 설정 백업 및 복구 경로를 확보한다. 배포 Job 전용 단계는 기존 DB 백업 스크립트를 백업하고, dotenv를 셸로 실행하지 않는 버전으로 먼저 교체한다.
3. Python 보조 컨테이너가 main·CDC `.env`를 값 출력 없이 검증한다. 필수 비밀값 누락·기존 값 불일치·중복 키·지원하지 않는 dotenv 형식이면 쓰기 전에 중단한다.
4. `.env.config`가 없으면 기존 파일의 일반 설정을 추출한다. 이후에는 `.env.config`가 일반 설정의 원본이다. 이미지 태그는 기존 `.env`에서 유지한다.
5. 새 실행용 `.env`를 원자적으로 교체한다. 생성 파일은 600이며 Jenkins와 같은 UID로 쓴다. 두 프로젝트의 파일 저장 중 오류가 나면 이미 저장한 파일을 복원한다. 프로세스 강제 종료까지 포괄하는 다중 파일 트랜잭션은 아니다.
6. 설정 배포 스크립트의 복구 처리 안에서 main·CDC Compose를 `config --quiet`로 검사한다. 최초 검사 실패도 이전 일반 설정을 복원하고 앱 배포 전에 중단한다. 이어서 설정 반영·상태 검사를 먼저 통과한 뒤 앱 변경 감지·빌드·배포를 수행한다. 기존 앱 배포·backend와 batch 묶음 롤백 로직은 유지한다.
7. 첫 실제 전환은 수동 배포에서 `FORCE_DEPLOY`를 켜 backend·batch·AI·frontend 재생성을 검증한다. 기존 값과 동일한 값을 주입하는 전환이므로 PostgreSQL·Grafana·Kafka Connect를 Credentials 등록만을 이유로 강제 재시작하지 않는다.

`prepare_credentials.py`는 `.env`를 셸로 실행하지 않으며 생성 시 `$`를 Compose의 리터럴 달러로 이스케이프한다. 기존 파일에 해석이 필요한 `$변수`, 여러 줄 값, 지원하지 않는 따옴표 표현이 있으면 자동 추측하지 않고 중단한다. 값이 출력되는 `docker compose config`, `env`, `printenv`, `docker inspect` 전체 출력으로 진단하지 않는다. `config --quiet`만 사용한다.

AI API 키는 사용자 확인 시 비어 있어 현재 바인딩에 포함하지 않았다. API 키를 사용하려면 Secret text를 추가하고 Jenkins 매핑·검증·문서를 함께 수정한다. 비어 있지 않은 기존 키를 발견하면 지우지 않고 전환을 중단한다. 원천 서버 자체의 관리자·조회 계정 비밀번호와 DB에 암호화 저장하는 원천 접속 정보는 이 메인 서버 전환과 구분한다.

## 검증 및 후속 범위

```powershell
python -m unittest discover -s infra/deploy -p "test_*.py" -v
```

사용자 실행으로 최종 Python 검사 28개를 환경별로 확인했다. Linux 컨테이너에서 27개 통과·Docker CLI 필요 검사 1개 건너뜀이며, 해당 특수문자 검사는 앞서 Windows의 실제 Compose CLI로 통과했다. Console HTTP 정상·실패와 최초 Compose 검사 실패 시 일반 설정 복구도 통과했다. 기존 backend·batch 배포·롤백 검사 8개와 보완 전 셸 문법 검사도 통과했다. 로컬 검사는 가짜 값·임시 폴더만 사용하며 운영 Credentials를 읽지 않는다. Docker Compose의 특수문자 검사는 출력용 이스케이프와 실제 치환 환경을 각각 확인한다. 셸 통합 검사는 Linux bash 환경에서 실행하며 Docker를 가짜 CLI로 대체하므로 실제 배포는 하지 않는다. 실제 Jenkins 단계·운영 서비스 검증은 아직 남아 있다.

실제 전환 후 확인: backend·batch 동일 이미지, 정상 기동·안정화, backend·AI 내부 인증, 원천 암·복호화, Kafka 커넥터 상태, Grafana 로그인 및 다음 cron DB 백업. 환경변수 값은 출력하지 말고 존재·일치 여부만 확인한다.

## 설정 자동 반영

관리 대상은 `configuration.py`의 허용 목록이다. nginx 설정, main·CDC Compose, Prometheus·Alloy·Loki 설정, Grafana 대시보드·프로비저닝, CDC Console, 상태 수집 Python 및 백업·갱신 스크립트를 포함한다. 로컬 전용 Compose, 비밀값·인증서·볼륨 데이터·생성 지표·커넥터 등록 파일은 복사하지 않는다. 커넥터 등록은 데이터 수집에 영향을 주므로 기존 수동 절차를 유지한다.

1. 체크아웃한 develop의 허용 파일만 `backup/config-<SHA>-<빌드번호>/stage`에 준비하고, 바뀐 기존 파일을 같은 폴더의 `backup`에 보관한다.
2. 일반 설정 변경 전후와 새 Compose를 실제 `docker compose config --format json`으로 해석해 서비스별 차이를 비교한다. 작업 중 렌더링 파일은 비밀값을 포함할 수 있어 600으로 쓰고 성공·실패 시 제거하며 아카이브하지 않는다. 원본 `.env`·Credentials는 설정 백업에 넣지 않는다.
3. Jenkins·PostgreSQL·Certbot 정의, 프로젝트·볼륨·네트워크, 서비스 추가·삭제, systemd 단위 파일 변경은 **복사 전에 중단**한다. Jenkins 자신 재생성, DB 전환, 호스트 systemd·cron 등록, 원천 서버 구성은 사람이 별도 적용한다. 자동화가 임의의 호스트 관리 권한을 얻지 않는다.
4. 영향 서비스가 정지 중이면 자동으로 켜지 않고 중단한다. backend 또는 batch 설정이 바뀌면 두 서비스를 함께 재생성한다. 이 경우 기존 DB 백업을 먼저 실행한다.
5. 허용 파일만 반영하고 Compose·nginx 및 변경된 Prometheus/Alloy/Loki 문법을 검사한다. 영향 서비스만 `--no-deps --force-recreate`로 재생성해 단일 파일 bind mount도 새 파일을 읽게 한다. nginx 설정 파일만 바뀌면 reload하며 서비스 정의가 바뀐 경우에만 재생성한다.
6. 헬스체크가 정의된 HTTP·Kafka 서비스는 healthy를 기다린다. 헬스체크가 없는 Kafka Console은 Prometheus 컨테이너에서 내부 HTTP 응답을 확인하며 실패 시 복구한다. Alloy·Loki는 내부 HTTP 준비 상태를 확인한다. batch는 현재 프로세스의 기동 완료 로그·OOM·재시작 여부와 60초 안정화를 확인한다.
7. 실패하면 이전 파일·일반 설정을 복원하고 영향 서비스를 다시 생성해 상태를 재검사한다. 복구 실패를 성공으로 표시하지 않는다. 현재 이미지 태그·비밀값은 설정 롤백으로 변경하지 않는다.
8. 성공 시 `.managed-config.json`에 관리 목록을 남긴다. 다음부터 Git에서 삭제한 **기존 관리 파일만** 삭제하며, 서버에만 있던 파일은 자동 삭제하지 않는다. 설정 백업 보존 기간과 정리는 후속 운영 정책으로 정한다.

설정 반영은 현재 이미지로 먼저 검증한다. 새 앱 이미지 없이는 동작하지 않는 호환성 파괴 설정은 이 자동 흐름으로 한 번에 적용하지 말고 단계적으로 배포한다. 설정 성공 후 앱 배포가 실패하면 앱의 기존 이미지 롤백을 수행하며, 이미 성공한 설정까지 묶어서 되돌리는 전역 트랜잭션은 아니다. 프로세스 강제 종료·서버 장애 시에는 백업과 실행 상태를 사람이 확인한다.

일반 설정 변경은 `.env.config`를 수정하고 배포 Job을 실행한다. DB 이름·DB 사용자·암호화 키 버전 변경은 전환 단계에서 중단한다. 일반 설정 실패 시 마지막 실행 설정으로 `.env.config`도 되돌리므로 실패 원인을 수정한 뒤 다시 실행한다. 비밀값 교체는 이 전환 코드가 의도적으로 거부하므로 별도 회전 절차로 수행한다.

GitLab·Notion·Jira의 최신 운영 문서 반영과 실제 운영 배포 검증은 남아 있다. 현재 스크립트와 문서를 운영 적용 완료로 취급하지 않는다.
