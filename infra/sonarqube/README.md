# 메인 서버 SonarQube 운영

## 기존 수동 설치 편입

1. 메인 서버에서 `~/sonarqube`의 DB가 healthy이고 SonarQube가 UP인지 확인한다.

   ```bash
   cd ~/sonarqube
   docker compose ps
   curl -fsS http://127.0.0.1:9000/api/system/status
   docker exec jenkins curl -fsS http://sonarqube:9000/api/system/status
   ```

2. SSH 터널로 관리자 화면에 접속해 초기 비밀번호를 변경한다. 아래 세 프로젝트를 기본 브랜치 `develop`으로 만들고, 각 프로젝트의 Project Analysis Token을 발급해 Jenkins Secret text에 등록한다.

   | 이름 | 프로젝트 키 | Jenkins Credentials ID |
   | --- | --- | --- |
   | AXCore Frontend | `axcore-frontend` | `axcore-sonarqube-token` |
   | AXCore Backend | `axcore-backend` | `axcore-sonarqube-backend-token` |
   | AXCore AI | `axcore-ai` | `axcore-sonarqube-ai-token` |

3. Jenkins Secret text `axcore-sonarqube-db-password`에 기존 SonarQube `.env`의 DB 비밀번호를 등록한다. 현재 값과 동일해야 한다. 비밀번호와 토큰은 채팅·Git·콘솔에 붙여 넣지 않는다.
4. 메인 서버에서 기존 Compose와 실행용 환경 파일을 Jenkins가 볼 수 있는 경로에 복사한다. 원래 파일과 컨테이너는 보존된다. 운영 명령은 이후 새 경로에서 프로젝트 이름 `sonarqube`를 지정해 실행한다.

   ```bash
   install -d -m 700 /home/ubuntu/axcore/sonarqube
   cp -n ~/sonarqube/compose.yaml /home/ubuntu/axcore/sonarqube/compose.yaml
   cp -n ~/sonarqube/.env /home/ubuntu/axcore/sonarqube/.env
   chmod 600 /home/ubuntu/axcore/sonarqube/.env
   docker exec jenkins test -r /home/ubuntu/axcore/sonarqube/.env
   docker exec jenkins test -w /home/ubuntu/axcore/sonarqube
   ```

5. 아래 별도 분석 Job을 먼저 등록하고 파이프라인 변경을 `develop`에 반영한다. Jenkins에서 `ENABLE_SONARQUBE=true`를 설정한다. `axcore-deploy`를 수동 실행하고 `RUN_SONARQUBE`를 선택해 초기 편입과 분석 예약을 확인한다. 성공한 분석은 별도 Job과 SonarQube 프로젝트 화면에서 해당 커밋과 결과까지 확인한다.

## 분석 Job 등록 및 전환

운영자 적용이 필요한 설정이며 저장소 변경만으로 Job이 생성되지는 않는다.

1. 이름이 정확히 `axcore-sonarqube`인 Pipeline Job을 만든다. 기존 두 Job과 같은 저장소·`gitlab-clone` 자격 증명·`develop` 브랜치·Script Path `Jenkinsfile`을 사용한다. Webhook·SCM polling 트리거는 설정하지 않는다.
2. 첫 실행 전 `ANALYSIS_COMMIT`·`ANALYSIS_RECIPIENT` 문자열 파라미터와 `ANALYZE_BACKEND`·`ANALYZE_FRONTEND`·`ANALYZE_AI`·`RUN_SONARQUBE` Boolean 파라미터를 Jenkinsfile과 같은 기본값으로 등록한다. `FORCE_DEPLOY=false`도 등록한다. Pipeline 실행 중 선언되는 파라미터에만 의존하면 첫 비동기 호출에 전달한 값이 누락될 수 있다.
3. 기존 분석 토큰 3개와 `mm-webhook`에 접근할 수 있어야 한다. Job은 별도 workspace를 사용하며 운영 배포 자격 증명은 바인딩하지 않는다. 세 Job은 Docker CLI·Java 및 호스트 workspace 경로가 같은 기존 노드에서 실행한다.
4. 병합 후 실제 배포에서 `Queue SonarQube analysis`가 즉시 반환하는지 확인한다. 별도 Job의 Checkout SHA가 배포의 전체 SHA와 같고, 지정한 서비스만 분석하는지 확인한다. 대기 중 develop이 바뀌어도 전달한 커밋을 검사해야 한다.
5. 별도 분석 Job 실패·Quality Gate 대기가 배포 완료와 다음 배포를 막지 않는지 확인한다. 예약 자체가 실패하면 배포의 예약 단계가 UNSTABLE로 표시되지만 배포 결과는 유지된다. 로그를 확인해 Job 등록·권한을 복구하고 누락된 커밋 분석을 수동 재실행한다.

수동 분석은 이 Job에서 실행한다. `ANALYSIS_COMMIT`을 비우면 최신 develop, 지정하면 소문자 40자리 SHA를 검사한다. `ANALYZE_*`로 서비스를 고르고 `RUN_SONARQUBE=true`이면 모두 검사한다. 기존 MR Job의 develop 수동 전체 분석도 유지한다.

분석 Job 내부 실행은 직렬이며 다른 Job과 executor·호스트 CPU를 공유한다. Job 분리만으로 총 검사량이나 자원 경합이 없어지지는 않는다. 전체 테스트의 동시 실행은 아래의 구성 요소별 잠금으로 제어한다. executor는 늘리지 않는다. 스캐너 캐시는 분석 Job workspace의 `.sonar-cache/<서비스>`에 보존한다. workspace를 지우면 캐시도 사라진다.

### MR 검사 결과 재사용

MR 검사와 SonarQube의 캐시 미스는 같은 전체 검사 함수를 사용한다. 전체 검사가 성공하면 커버리지·JUnit·백엔드 컴파일 결과를 Jenkins 홈의 `ci-results/<서비스>/<키>`에 보관한다. 어느 Job이 먼저 검사했든 일치하는 성공 결과를 이후 MR·분석 Job이 재사용한다. 서비스당 최근 10개만 남기며 운영 DB/앱 이미지와는 별도다. 새 Jenkins 플러그인은 필요하지 않다. 기존 세 Job과 동일 노드·Jenkins 홈·호스트 경로 매핑을 사용한다.

키에는 해당 서비스 Git tree(소스·테스트·의존성·빌드 설정 전체), 실제 실행하는 공통 검사 셸의 Git blob 해시, 캐시 프로그램 버전, 실행 환경을 포함한다. backend는 실제 Java 버전·JAVA_TOOL_OPTIONS·GRADLE_OPTS, frontend/AI는 검사 컨테이너 이미지 ID를 포함한다. develop에서 읽은 검사 절차와 MR 체크아웃의 Jenkinsfile이 다를 수 있으므로 실제 실행 셸을 해시한다. 스쿼시 머지나 다른 서비스·배포 단계만 바뀌어도 서비스 파일과 검사 절차·환경이 같으면 재사용한다. 서비스 파일·검사 명령·환경이 달라지면 재검사한다. 기존 키와 혼용하지 않는 `full-checks-v2` 실행 환경 접두사를 사용한다.

SonarQube Job은 배포 커밋을 체크아웃한 뒤 결과의 파일 목록·체크섬·필수 보고서·JUnit 성공 여부를 검증하고 복원한다. 원본 MR 커밋과 빌드 링크를 로그로 남긴다. backend의 클래스 파일도 함께 복원하므로 분석을 위한 재컴파일이 필요 없다. 보고서가 없거나 손상/불일치/만료/권한 문제로 복원하지 못하면 해당 서비스 테스트를 정상 실행한다. 재사용 여부와 무관하게 소스 정적 분석은 실행한다. 배포 Job에서는 재사용 검색이나 테스트를 실행하지 않는다.

backend 키에는 `TransformCasesTests`가 읽는 `ai/tests/fixtures/transform_cases.json`의 Git blob도 포함한다. 공유 자료의 내용 변경·삭제는 backend tree가 같아도 이전 성공을 재사용하지 않는다. MR에서 이 자료만 바뀐 경우에도 backend 검사를 선택한다. 캐시 미스에서는 `test --rerun`으로 Gradle의 이전 UP-TO-DATE 판정도 우회하며, 컴파일 등 다른 작업은 증분 처리를 유지한다. 새로운 서비스 외부 테스트 입력을 추가하면 검사 대상 선택과 키를 함께 보완한다.

캐시가 없거나 검증에 실패하면 MR·분석 Job 모두 기존 MR 전체 검사 명령을 실행한다. backend는 `spotlessCheck test --rerun build jacocoTestReport`, frontend는 `npm ci` 후 포맷·린트·커버리지 테스트·빌드, AI는 의존성 설치 후 Ruff·JUnit·커버리지 pytest를 실행한다. 실패한 전체 검사는 그대로 실패하며 결과를 보관하지 않는다. 캐시 프로그램이 없거나 저장·복원에 실패해도 테스트를 생략하지 않는다. 성공한 검사 뒤 캐시 저장만 실패한 경우에는 다음 Job이 전체 검사를 다시 수행한다.

구성 요소별로 Jenkins 홈의 `ci-locks/<서비스>.lock`을 `flock`으로 잠근다. 캐시를 먼저 조회하고, 미스면 잠금을 얻은 뒤 다시 조회하여 대기 중 완료된 검사 결과를 재사용한다. 코드가 달라 재검사가 필요한 경우에도 두 backend Gradle JVM은 동시에 실행하지 않는다. frontend·AI는 각각 별도 잠금이어서 다른 구성 요소와 불필요하게 직렬화하지 않는다. 배포 단계와 Sonar 스캐너·Quality Gate 대기는 이 잠금을 잡지 않는다. Job 취소 시 프로세스 종료로 OS 잠금이 해제된다. 같은 Job의 workspace 보호용 `disableConcurrentBuilds()`는 유지한다.

테스트 범위나 실패 판정은 줄이지 않는다. 재사용은 동일 코드·검사 절차·환경에서 이미 통과한 전체 결과에만 적용한다. 최초 실행, 캐시 만료·손상, 다른 서비스 코드의 통합으로 tree가 달라지는 경우에는 재검사가 필요하므로 모든 중복 시간을 없애는 것은 아니다. 운영 단축 시간은 develop 반영 후 같은 부하 조건에서 따로 측정한다.

캐시는 같은 Jenkins 노드와 기존 신뢰된 팀 MR 실행 환경을 전제로 한다. 서로 다른 노드/도구체인/테스트 실행 옵션을 도입하면 키와 저장소 구성을 함께 수정해야 한다.

### 캐시 도입 전 MR 및 workspace 경로

MR Job이 읽은 develop의 Jenkinsfile과 실제 체크아웃한 MR 커밋은 다를 수 있다. 따라서 캐시 사용 여부는 디스크의 폴더 존재만이 아니라 `HEAD:infra/ci`와 `HEAD:infra/ci/result_cache.py`의 Git 추적 여부로 판단한다. 캐시 도입 전 커밋에서는 캐시 회귀 검사·결과 보관만 대상에서 제외하고 서비스 검사는 유지한다. 분석 Job에서 복원할 수 없으면 기존처럼 전체 커버리지 검사를 실행한다. 캐시를 포함한 커밋인데 회귀 테스트 파일이 누락되면 인프라 검사를 실패시킨다.

병합 전 기존 Jenkinsfile은 `infra/ci`만 검사 컨테이너에 마운트한다. 이 환경의 셸 회귀 테스트는 같은 폴더에 포함한 `fixtures/component_checks.sh`로 전체 검사를 실행한다. 저장소 전체나 새 Jenkinsfile 마운트가 있으면 실제 Jenkinsfile의 검사 셸과 fixture가 정확히 같은지도 확인하고, 다르면 실패한다. 검사 셸을 변경할 때 fixture도 함께 갱신한다. 어느 환경에서도 테스트를 건너뛰지 않는다.

인프라 검사와 결과 캐시의 bind mount는 `--mount type=bind`를 사용한다. `-v`와 달리 Docker 호스트에 source가 없으면 폴더를 자동 생성하지 않고 실패한다. Jenkins 쪽 존재 확인과 별도로 호스트 경로 매핑 오류가 root 소유 폴더를 만들지 않게 하는 기존 보호를 유지한다.

체크아웃에서 `infra/ci/*: Permission denied`가 발생하면 해당 workspace를 쓰는 실행이 없는 상태에서 인프라 담당자가 호스트의 소유권과 내용을 확인한다. `/home/ubuntu/axcore/jenkins-data/workspace/axcore-mr-check/infra/ci`가 잘못 생성된 빈 폴더라면 그 경로만 `sudo rmdir -- <확인한 경로>`로 제거한 뒤 실패한 MR 검사를 다시 실행한다. 비어 있지 않으면 먼저 내용을 확인하며 workspace 전체·Jenkins 홈·결과 캐시·운영 DB를 일괄 삭제하지 않는다. 이 코드 변경은 이미 발생한 권한 문제를 자동 복구하지 않는다.

재발 방지 회귀 검사는 저장소 루트의 Linux 환경에서 `python -m unittest discover -s infra/tests -p 'test_jenkins_mounts.py' -v`로 실행한다. 실제 Jenkinsfile 셸을 실행하고 Git·Docker 명령은 가짜 CLI로 대체한다. 실제 서버 소유권 확인이나 Jenkins 플러그인 실행 검증을 대신하지 않는다.

## 자동 실행

- `axcore-deploy`는 활성화 후 Git의 Compose를 검사한다. SonarQube의 CPU·메모리·프로세스 제한 및 재시작 정책 변경을 자동 반영한다. 적용 전에 현재 Compose와 PostgreSQL 덤프를 `/home/ubuntu/axcore/sonarqube/backup.*`에 남긴다. 이미지 버전, DB, 서비스 구성, 볼륨, 네트워크 변경은 별도 운영 작업으로 처리한다.
- SonarQube가 UP이고 배포가 성공하면 `axcore-deploy`가 전체 커밋 SHA·변경 서비스·알림 수신자를 `axcore-sonarqube`에 `wait: false`로 전달한다. `RUN_SONARQUBE`를 선택하면 세 서비스 모두 예약한다. 배포 Job은 커버리지 테스트·스캐너·Quality Gate를 기다리지 않는다. 분석 결과는 병합 조건으로 사용하지 않는다.
- 프론트 분석은 검증된 `frontend/coverage/lcov.info`를 재사용하며 일치하는 결과가 없으면 별도 Job에서 전체 프론트 검사를 실행한다.
- 백엔드 분석은 검증된 `backend/build/reports/jacoco/test/jacocoTestReport.xml`과 클래스 파일을 재사용하며 일치하는 결과가 없으면 별도 Job에서 전체 백엔드 검사를 실행한다.
- AI 분석은 검증된 `ai/coverage/coverage.xml`을 재사용하며 일치하는 결과가 없으면 별도 Job에서 전체 AI 검사를 실행한다.
- 새 Compose는 SonarQube가 UP에 도달한 뒤 활성 파일로 저장한다. 갱신이 실패하면 이전 Compose를 다시 적용하고 UP을 확인한다. Jenkins는 해당 실행을 실패로 표시하고 분석을 생략한다. 복구도 실패하면 로그와 백업을 확인한다.
- DB 덤프에는 SonarQube 운영 데이터가 포함된다. 백업 폴더에 운영자만 접근하도록 하고 보존 기간은 팀에서 정한다.

## 배포 후 Quality Gate 알림

팀 브라우저 접속 주소는 `https://sonar.ontologenie.com`이다. 메인 서버를 가리키는 DNS A 레코드와 이 호스트 전용 Certbot 인증서를 먼저 준비한다. Nginx는 `axcore-net`에서 `sonarqube:9000`으로 요청을 전달한다. 외부 접속 전에 로그인 필수와 프로젝트 Private 설정을 확인하고 팀원에게 Browse 권한을 부여한다.

Nginx 설정이 배포되면 팀원 브라우저에서 로그인과 프로젝트 조회를 확인한다. 이후 SonarQube의 Server base URL과 Jenkins의 `SONARQUBE_DASHBOARD_URL`을 공용 HTTPS 주소로 설정한다.

`axcore-deploy`가 GitLab의 `develop` push 이벤트로 실행되고 이벤트 커밋이 실제 배포 체크아웃과 일치하면 수신자를 별도 분석 Job에 전달한다. 분석 Job이 제출한 분석의 Quality Gate를 서비스마다 최대 2분 기다린다. 미통과한 프로젝트가 있으면 해당 사용자에게 대시보드 링크를 한 건의 Mattermost DM으로 보낸다. 분석 실패는 별도 Job에서 표시하고 수신자가 있을 때 실패 DM을 보낸다. Quality Gate 미통과와 상태 조회 실패는 배포 결과를 변경하지 않는다.

기존 SonarQube 프로젝트 분석 토큰과 `mm-webhook`을 사용한다. 링크는 기본적으로 SSH 터널에서 접근하는 `http://localhost:9000`을 사용한다. 팀원이 접속할 수 있는 별도 SonarQube 주소가 있으면 Jenkins 전역 환경 변수 `SONARQUBE_DASHBOARD_URL`을 그 주소로 설정한다.
