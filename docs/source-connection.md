# 원천 연결 API·서비스 연동 계약

- 이슈: S15P31C106-175 · 기능: FN-SRC-01·02·03 · API: 009~015, 100 (016은 아래 "남은 것")
- 기준: develop `8ad8991`의 코드. 코드와 이 문서가 다르면 코드가 맞고, 이 문서를 고칩니다.
- 관련 문서: AI 쪽 분석 시작·결과 계약은 [ai/docs/api-internal.md](../ai/docs/api-internal.md), 원천 서버 설정은 [infra/source-db/README.md](../infra/source-db/README.md)

## 1. 누가 무엇을 하는지

| 일 | 맡는 곳 |
| --- | --- |
| 원천 등록·수정·조회(009~012), 비밀번호 암호화 저장 | Spring api |
| 연결 검사 실행(013·100), 읽을 수 있는 표·컬럼 목록(카탈로그) 갱신 | Spring batch (`SourceConnectionCheckJob`, `batch` 프로필에서만 돎) |
| 표·컬럼 목록 조회(014·015) | Spring api (batch가 채운 카탈로그를 읽음) |
| 분석 시작 때 AI 계정 복호화, 접속 정보 전달(018) | Spring api (`AnalysisStartService`) |
| 원천 접속, 고른 컬럼·행만 읽기, 분석 | FastAPI (`ai/app/sources/postgres_reader.py`) |

- FastAPI는 원천 비밀번호를 저장하지 않습니다. 분석 한 번마다 018 요청 본문으로 받고, 분석이 끝나면 버립니다.
- 원천에는 읽기만 합니다. 원천 계정은 모두 `default_transaction_read_only = on`입니다(`infra/source-db/sql/setup_roles.sql`).

## 2. API

모두 관리자(ADMIN)만 쓸 수 있습니다(`DataSourceController`).

| 번호 | 경로 | 하는 일 |
| --- | --- | --- |
| 009 | `GET /api/v1/sources` | 원천 목록. `sourceKind`·`connectionStatus`로 거르고 쪽 나눔(기본 20개) |
| 010 | `POST /api/v1/sources` | 원천 등록. 201 |
| 011 | `GET /api/v1/sources/{id}` | 원천 상세 + 가장 최근 연결 검사 |
| 012 | `PATCH /api/v1/sources/{id}` | 원천 수정 |
| 013 | `POST /api/v1/sources/{id}/test-connection` | 연결 검사 요청. 202, 실제 검사는 batch |
| 100 | `GET /api/v1/sources/{id}/connection-checks/{checkId}` | 연결 검사 결과 |
| 014 | `GET /api/v1/sources/{id}/resources` | 읽을 수 있는 표·뷰 목록 |
| 015 | `GET /api/v1/sources/{id}/resources/{resourceId}/columns` | 표의 컬럼 목록 |

**010 요청 본문**

| 필드 | 규칙 |
| --- | --- |
| `code` | 영문 대문자로 시작, 대문자·숫자·밑줄만(예: `ERP_MAIN`). CDC 토픽 이름 앞부분과 같아야 합니다(`infra/cdc/README.md`) |
| `name`, `sourceKind` | 필수. `sourceKind`는 지금 `POSTGRES`만 연결 검사가 됩니다 |
| `connectionConfig` | `{host, port, database}`만 받습니다. 다른 키(예: `schema`)는 400, 비밀번호 같은 비밀값 키도 400("계정은 credentials로 보냅니다") |
| `credentials[]` | `{purpose, username, password}`. `purpose`는 `SYNC` 또는 `AI`. POSTGRES는 두 계정이 모두 있어야 하고, 파일·문서 원천에는 넣지 않습니다 |
| `syncEnabled`, `syncIntervalMinutes` | 선택 |

## 3. 계정 두 개를 나누는 이유

| 용도 | 원천 계정 | 쓰는 곳 |
| --- | --- | --- |
| `SYNC` | `platform_ro` | batch: 연결 검사, 카탈로그, 전체 다시 읽기 |
| `AI` | `platform_ai` | FastAPI: 분석. 접속 5개, 쿼리 30초로 묶음 |

- 연결 상태(`CONNECTED`·`FAILED`)는 `SYNC` 계정 기준입니다. `AI` 계정이 실패하면 계정별 결과에만 남습니다.
- 표·컬럼 목록(014·015)은 `SYNC` 계정이 읽을 수 있는 것입니다. 원천 권한으로 막은 컬럼은 목록에 나오지 않습니다(`has_table_privilege`·`has_column_privilege`로 판단, 업무 행은 읽지 않음).
- AI 계정이 볼 수 없는 표를 고르면 FastAPI가 "읽을 권한이 없다"로 실패를 보냅니다. 예: 회계 표 4개는 AI 계정 권한에서 빠져 있습니다(2026-11-04).

## 4. 비밀번호 처리

- 화면에서 받은 비밀번호는 Spring이 AES-256-GCM으로 암호화해 `data_source_credential`에 저장합니다(`SourceCredentialCipher`).
- 키는 환경변수 `AXCORE_SOURCE_CREDENTIAL_KEY_1`(32바이트 base64), 버전은 `AXCORE_SOURCE_CREDENTIAL_KEY_VERSION`입니다. 운영 값은 Jenkins Credentials `source-credential-key-1`입니다.
- 암호문에 `{data_source_id}:{purpose}`를 함께 묶어서, 다른 원천·용도의 행에 옮겨 붙이면 풀리지 않습니다.
- 비밀번호는 응답과 로그에 넣지 않습니다(`CredentialRequest.toString`이 비밀번호를 뺌).
- 복호화는 두 곳에서만 합니다. batch의 연결 검사, api의 분석 시작(018 본문, AI 계정만).

## 5. 접속 방식

- 원천은 PostgreSQL에 SSL로 접속합니다. 연결 검사는 `AXCORE_SOURCE_SSL_MODE`(기본 `require`), 분석 시작은 늘 `sslmode=require`로 FastAPI에 넘깁니다(2026-11-03 결정).
- FastAPI는 고른 컬럼과 기본키만 SELECT 하고, 표 하나는 최대 10만 행까지 읽습니다(S15P31C106-280). 자세한 것은 `ai/docs/api-internal.md` 3·4절입니다.

## 6. 흐름

1. 관리자가 원천을 등록합니다(010). 비밀번호는 암호화해 저장합니다.
2. 연결 검사를 요청합니다(013). batch가 `SYNC`·`AI` 계정으로 각각 접속해 읽을 수 있는 표·컬럼을 세고, `SYNC` 목록으로 카탈로그를 갱신합니다.
3. 화면이 결과(100)와 표·컬럼 목록(014·015)을 보여 줍니다.
4. 관리자가 분석할 표·컬럼을 고르면(018) Spring이 `AI` 계정을 복호화해 FastAPI에 보냅니다.
5. FastAPI가 원천을 읽고 분석해 결과를 105로 보냅니다.

## 7. 남은 것·확인할 것

- **016 원천 행 표본 조회:** 배치 작업 종류(`SOURCE_PREVIEW`)와 표(`source_preview`)는 있지만, API 경로는 develop에 없습니다(미구현). 화면(원천 표본 탭, S15P31C106-225)은 develop `119d85d`에 들어왔지만 실제 요청 함수를 주입하지 않아 서버와 연결되지 않습니다(`docs/source-samples-225.md`). 016·102 형식과 가림 정책이 정해져야 연결합니다.
- **CSV·DOCUMENT 원천:** 연결 검사는 "지원하지 않음"으로 실패합니다. 원천 목록도 PostgreSQL 카탈로그에서 TABLE·VIEW만 만듭니다(`SourceCatalogReader`).
- **로컬 시험:** 연결 검사가 batch에서만 돌아서, api와 batch를 같은 플랫폼 DB로 함께 띄워야 표 목록이 채워집니다.
