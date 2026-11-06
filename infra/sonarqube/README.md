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

5. 파이프라인 변경을 `develop`에 반영한 후 Jenkins에서 `ENABLE_SONARQUBE=true`를 설정한다. `axcore-deploy`를 수동 실행하고 `RUN_SONARQUBE`를 선택해 초기 편입과 분석을 확인한다. 성공한 분석은 SonarQube 프로젝트 화면에서 해당 커밋과 결과까지 확인한다.

## 자동 실행

- `axcore-deploy`는 활성화 후 Git의 Compose를 검사한다. SonarQube의 CPU·메모리·프로세스 제한 및 재시작 정책 변경을 자동 반영한다. 적용 전에 현재 Compose와 PostgreSQL 덤프를 `/home/ubuntu/axcore/sonarqube/backup.*`에 남긴다. 이미지 버전, DB, 서비스 구성, 볼륨, 네트워크 변경은 별도 운영 작업으로 처리한다.
- SonarQube가 UP이면 배포 대상인 frontend·backend·AI를 고정 태그의 스캐너 컨테이너로 순서대로 분석한다. `RUN_SONARQUBE`를 선택하면 세 서비스 모두 분석한다. 백엔드는 Gradle `testClasses`로 분석에 필요한 Java 본문·테스트 바이트코드를 준비한다. 분석 단계는 결과를 병합 조건으로 사용하지 않는다. 커버리지 리포트는 별도 연동이다.
- 새 Compose는 SonarQube가 UP에 도달한 뒤 활성 파일로 저장한다. 갱신이 실패하면 이전 Compose를 다시 적용하고 UP을 확인한다. Jenkins는 해당 실행을 실패로 표시하고 분석을 생략한다. 복구도 실패하면 로그와 백업을 확인한다.
- DB 덤프에는 SonarQube 운영 데이터가 포함된다. 백업 폴더에 운영자만 접근하도록 하고 보존 기간은 팀에서 정한다.

## 배포 후 Quality Gate 알림

팀 브라우저 접속 주소는 `https://sonar.ontologenie.com`이다. 메인 서버를 가리키는 DNS A 레코드와 이 호스트 전용 Certbot 인증서를 먼저 준비한다. Nginx는 `axcore-net`에서 `sonarqube:9000`으로 요청을 전달한다. 외부 접속 전에 로그인 필수와 프로젝트 Private 설정을 확인하고 팀원에게 Browse 권한을 부여한다.

Nginx 설정이 배포되면 팀원 브라우저에서 로그인과 프로젝트 조회를 확인한다. 이후 SonarQube의 Server base URL과 Jenkins의 `SONARQUBE_DASHBOARD_URL`을 공용 HTTPS 주소로 설정한다.

`axcore-deploy`가 GitLab의 `develop` push 이벤트로 실행되고 이벤트 커밋이 실제 배포 체크아웃과 일치하면, Jenkins가 제출한 분석의 Quality Gate를 최대 2분 동안 기다린다. 미통과한 프로젝트가 있으면 이벤트를 일으킨 GitLab 사용자에게 대시보드 링크를 한 건의 Mattermost DM으로 보낸다. Quality Gate 미통과와 상태 조회 실패는 배포 결과를 변경하지 않는다.

기존 SonarQube 프로젝트 분석 토큰과 `mm-webhook`을 사용한다. 링크는 기본적으로 SSH 터널에서 접근하는 `http://localhost:9000`을 사용한다. 팀원이 접속할 수 있는 별도 SonarQube 주소가 있으면 Jenkins 전역 환경 변수 `SONARQUBE_DASHBOARD_URL`을 그 주소로 설정한다.
