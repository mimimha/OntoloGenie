# CI 초기화·분석 분리 검증

## 변경 범위

- 구현 시작 기준: origin/develop `14a13ef`, 최종 작업 브랜치 `chore/344-ci-test-performance`.
- 테스트 JVM당 PostgreSQL 서버·migration 템플릿을 한 번 만들고 컨텍스트별 독립 DB 복제.
- 원천 role·publication 시험은 전용 클러스터 유지. 캐시 크기 8과 기존 컨텍스트 구분 유지.
- 배포 후 커버리지·SonarQube·Quality Gate를 별도 `axcore-sonarqube` Job으로 비동기 분리. 배포 SHA·서비스·검증된 수신자를 전달.
- 스캐너 캐시는 workspace에 보존. API·운영 DB 스키마 변경 없음.

## 로컬 검증

- `gradlew spotlessApply compileTestJava --offline`: 포맷 적용 후 기존 Kafka 의존성 캐시 누락으로 컴파일 중단. 온라인 의존성 확인 후 컴파일 성공.
- DB 격리·migration·원천 연결·수집·대화 API 대상 테스트: 성공.
- 최초 전체 검사에서 DataJpaTest의 임베디드 DB 자동 교체와 컨텍스트 통합의 보안 시험 간섭을 발견. 테스트 PostgreSQL 유지 설정을 추가하고 컨텍스트 통합은 철회했다.
- JPA·로그인·비밀번호 변경·정합성 검사 회귀 테스트: 성공(48초).
- 수정 후 전체 검사: 1,286건 중 1건 실패, 기존 외부 GMS 시험 1건 건너뜀(3분 19초). 실패는 SyncRunJobTests의 DB 현재 시각과 JVM 시작 시각 비교였다. 기존 실행의 실제 시작 시각으로 fixture 경계를 명시했다. 마지막 재검사 결과는 아래에 기록한다.
- `infra/tests/jenkins-analysis-tests.groovy`: 실제 Jenkinsfile 단계 조건과 비동기 전달 검사 4개 그룹 성공. 전체 SHA·변경 서비스 전달, 전체 서비스 선택, 이벤트 SHA 불일치 시 수신자 생략, 분석 Job의 배포 단계 차단, 수동 MR 분석 유지 확인.
- `infra/tests/jenkins-deploy-tests.groovy`: 기존 배포·롤백 분기 8개 성공.
- `git diff --check`: 성공.

Groovy 검사는 Gradle 9.8.2의 번들 라이브러리로 실행했으며 Jenkins 단계 API를 대체했다. Jenkins Declarative 플러그인의 실제 검증이나 원격 실행을 대신하지 않는다.

## 서버 미검증·반영 필요

- 별도 Pipeline Job 등록과 첫 호출 전 파라미터 등록이 필요하다. 절차: `infra/sonarqube/README.md`.
- 후속 사용자 요청으로 Jenkins `axcore-sonarqube` Pipeline Job을 등록했다. Git 저장소·gitlab-clone 자격 증명·develop·Jenkinsfile·파라미터 7개를 설정하고 Webhook/SCM polling을 두지 않았다. 새 Jenkinsfile 병합 전이므로 비활성 상태로 저장했으며 실행 이력 없음까지 확인했다. 운영 Credentials·앱 컨테이너는 수정하지 않았고 Commit·Push·MR·배포도 실행하지 않았다.
- 실제 비동기 호출, 분석 커밋 일치, 스캐너 캐시 권한, 분석 실패·Quality Gate 알림과 다음 배포 진행은 서버 검증이 남아 있다.
- 개선 전후 동등 조건의 성능 비교는 아직 없다. 위 로컬 wall time을 기존 다른 커밋의 302초와 직접 비교하거나 Jenkins 단축 효과로 표현하지 않는다.
- executor·CPU 제한은 유지한다. 별도 분석 Job도 같은 서버 자원을 사용하므로 경합과 큐 대기는 남을 수 있다.

## 최종 전체 검사

`backend/`에서 `gradlew spotlessApply build --offline` 성공(3분 22초). 전체 테스트가 실제 실행됐으며 `test`는 UP-TO-DATE로 생략되지 않았다. 최종 JUnit 집계는 아래와 같다.

- 1,286건, 실패 0, 오류 0, 건너뜀 1.
- 건너뛴 1건은 기존 `QuestionInterpreterGmsTests`의 환경변수 조건이다. 이번 작업에서 Skip을 추가하지 않았다.
- `spotlessCheck`·컴파일·전체 테스트·build 성공.

이 결과는 로컬 회귀 검증이며 Jenkins 자원 제한 아래의 성능이나 실제 비동기 Job 실행을 보증하지 않는다.

## Jira·테스트 컨테이너 정리 확인

- Jira S15P31C106-344 생성: 담당자·보고자 손주연, 인프라 에픽 S15P31C106-34, 작업, 5포인트, 해야 할 일. 설명의 task-list 체크박스 저장 확인.
- 로컬 Docker의 `org.testcontainers=true` 컨테이너와 볼륨 목록 모두 비어 있다. 이번 테스트에서 만든 임시 컨테이너·DB는 자동 정리되었고 추가 삭제 대상이 없다.
- 남은 컨테이너 4개는 기존 Grafana·Alloy·Loki·Prometheus이며 삭제하지 않았다. 다른 작업의 이미지·볼륨·빌드 캐시도 일괄 삭제하지 않았다.

## 후속: 안전한 컨텍스트 통합·MR 결과 재사용

- AgentConversationApiTests·ProposalDecisionApiTests·ProposalObjectTypeApprovalApiTests만 `test.context=csrf-api`로 통합(해당 그룹 3 → 1). 실제 로그인·쿠키 CSRF 및 서로 다른 Mock/Spy/Clock은 분리 유지.
- 위 세 클래스와 AuthApiTests·PasswordChangeApiTests를 함께 실행해 성공(34초). 최종 `gradlew spotlessApply build jacocoTestReport --offline` 성공(3분 41초): 1,286건, 실패·오류 0, 기존 조건부 GMS 건너뜀 1. `test` 실제 실행 및 JaCoCo XML 생성 확인.
- MR의 각 서비스 검사 성공 후 커버리지·JUnit·backend 클래스 파일을 보관한다. 서비스 tree·Jenkinsfile blob·캐시 프로그램 버전·런타임이 같고 파일 체크섬·필수 출력이 유효한 경우만 분석 Job에서 복원한다. 실패/누락/불일치/손상/보관 만료이면 재검사한다. MR 검사는 생략하지 않는다.
- `infra/ci/test_result_cache.py`: Windows Python 및 Jenkins와 같은 Linux `python:3.11-slim` 컨테이너에서 8개 검증 성공. 환경/소스 불일치, 손상, 실패/빈 JUnit, 경로 삽입, 오래된 클래스 제거, 서비스별 결과, 최근 10개 보관 검증.
- 실제 최종 backend 출력물로 Linux 컨테이너 안에서 publish → restore 성공. JaCoCo XML·JUnit·클래스 891개 복원 확인. 이 검증에서는 Gradle/테스트를 다시 실행하지 않았다. 임시 캐시는 컨테이너 내부에 만들고 종료 시 정리했다.
- Jenkins Groovy 분석 검사: 기존 4개 그룹 + 재사용 시 세 서비스 테스트 생략 / 복원 실패 시 해당 서비스 재검사 2개 그룹 성공. 배포·롤백 8개 분기도 성공.
- 초기 구현의 로컬 3분 22초는 `build`, 후속 3분 41초는 `build jacocoTestReport`이며 동등 조건의 반복 성능 측정이 아니다. 시간 단축률을 계산하지 않는다. 컨텍스트 통합 효과를 과장하지 않는다.
- frontend/AI의 MR 검사에는 커버리지 측정 비용이 추가된다. 재사용 가능할 때 SonarQube Job의 중복 전체 테스트 제거로 총 작업량을 줄이는 구조다. 실제 Jenkins 캐시 적중률·부하·단계별 시간은 병합 후 검증이 남아 있다.

## MR 준비 리뷰 후 수정

- 독립 리뷰에서 AI 캐시 경로가 실제 `ai/.coveragerc`의 `coverage/coverage.xml`과 달라 재사용이 실패하는 Major 1건을 발견했다. 사용자 승인 후 ROOTS·REQUIRED·fixture를 실제 경로로 수정했다.
- 오래된 최상위 `coverage.xml`만으로는 보관할 수 없고 실제 경로를 복원하는 회귀 검증을 추가했다. 저장소 전체를 읽기 전용 마운트한 Linux Python 3.11 컨테이너에서 실제 `.coveragerc`와의 일치 검증 포함 9개 성공(테스트 실행 0.099초, 컨테이너 기동 별도).
- Jenkins와 같은 스크립트 전용 읽기 전용 마운트에서도 9개 성공. 독립 재리뷰에서 기존 Major 해결 확인, 신규 Critical·Major·Minor 0건. AI 리뷰이며 사람의 MR 승인과 실제 Jenkins 검증을 대신하지 않는다.

## Jenkins MR !275 실패 및 후속 수정

- 사용자 제공 `axcore-mr-check #323` 콘솔: MR 커밋 `17c0d1b`의 backend `spotlessCheck build`는 7분 26초에 성공했다. 이후 Infra check의 기존 `test_console_http_failure_is_not_accepted`가 30초 제한을 초과했다. 배포 단계는 실행되지 않았으며 결과 캐시 검사까지 도달하지 못했다.
- 이 테스트는 HTTP가 계속 실패하는 상황에서 반영 검사 24회와 이전 설정 복구 검사 24회를 실행한다. 테스트용 sleep은 이미 생략하지만 가짜 Docker의 inspect/exec마다 Python 프로세스를 새로 시작하여 자원이 부족하면 전체 테스트의 30초 제한을 넘긴다. 운영 서비스가 정상일 때는 첫 성공 확인에서 반환한다.
- 로컬 Linux `python:3.11-slim`, `--network none --cpus 0.25 --memory 256m`, 스크립트 디렉터리 읽기 전용 마운트로 같은 테스트를 재현했다. 수정 전 30.089초에 동일 TimeoutExpired, 수정 후 9.322초에 성공했다. CPU 제한은 경합 상황을 재현하기 위한 로컬 조건이며 Jenkins의 실제 가용 CPU 측정값은 아니다.
- 가짜 Docker의 반복 inspect/exec만 Bash로 처리하고 JSON·설정 helper 호출은 기존 Python을 사용한다. 운영 `sync-config.sh`, 재시도 24회, 상태 확인 간격, 테스트 timeout=30은 변경하지 않았다. HTTP probe 48회, 기존 설정 복원, manifest 미확정, 임시 비밀 파일 제거를 명시적으로 검증한다.
- 수정 후 동일 Linux 이미지의 `--cpus 2 --memory 256m`에서 `python -m unittest discover -p 'test_*.py' -v`: 31건, 오류·실패 0, 기존 Docker CLI 조건부 건너뜀 2건, 11.590초. 실제 운영 Docker 명령 대신 가짜 CLI를 실행하는 검사이며 운영 배포·복구 검증을 대신하지 않는다.
- Jenkins 실패로 실행되지 못했던 `infra/ci` 결과 캐시 검사도 같은 Linux 이미지·CPU/메모리 조건에서 9건 모두 성공했다(0.097초, 컨테이너 기동 별도).
- 백엔드 코드는 이번 후속 수정에서 바꾸지 않았고 전체 Gradle 테스트를 다시 실행하지 않았다. 수정 커밋의 Jenkins 재검사는 별도로 필요하다. 위 소요시간은 이 실패 회귀 검사에 대한 로컬 결과이며 MR 전체 속도 개선률을 뜻하지 않는다.
