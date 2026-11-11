# CI bind mount 재발 방지 검증

## 발생 경위와 확인 범위

- 사용자 전달: MR !283 검사 `axcore-mr-check #339`가 `git checkout -f 140d903` 중 `infra/ci/result_cache.py`, `test_result_cache.py` 생성 권한 부족으로 실패했다. 직전 workspace 커밋은 캐시 도입 전 `02859c7`이며 인프라 담당자가 문제 폴더를 제거했다고 전달했다.
- 코드 확인: 캐시 코드는 develop의 `e834fdb`(!275)로 도입됐다. Job이 최신 develop의 Jenkinsfile을 읽고 캐시 도입 전 MR 코드를 체크아웃하면, 기존 인프라 검사·캐시 함수가 없는 `infra/ci`를 `docker run -v`로 연결할 수 있었다.
- Docker `-v`는 없는 호스트 source를 자동 생성한다. 일반적인 root Docker daemon이 만든 폴더에 Jenkins 사용자가 쓰지 못해 다음 체크아웃이 실패하는 경로와 일치한다. 삭제 전 실제 소유자·ACL·생성 프로세스를 직접 확인하지 않았으므로 서버에서의 정확한 생성 경위는 추정이다. Git 충돌이나 로컬 샌드박스 오류와 구분한다.
- 공식 동작 근거: https://docs.docker.com/engine/storage/bind-mounts/

## 수정

- 브랜치 `fix/344-ci-bind-mount`, 시작 커밋 `84f3b60`. MR 대상은 develop이며 최신 확인 참조는 `840831e`다. 그 사이 Jenkinsfile·관련 캐시/검사 파일 변경 없음 확인.
- 인프라 검사에서 `HEAD:infra/ci`를 확인한다. 캐시 도입 전 MR은 기존 배포 설정 검사만 수행하며 캐시 폴더를 만들지 않는다. Git에 캐시 폴더가 있는데 `test_result_cache.py`가 디스크에 없으면 실패한다.
- 결과 보관·복원은 `HEAD:infra/ci/result_cache.py`와 실제 파일을 확인한 뒤 시작한다. 없으면 Docker·캐시 폴더 생성 전에 실패 상태를 반환하며, 기존 호출부가 MR 보관 실패 또는 분석 Job의 전체 커버리지 검사로 처리한다.
- 관련 인프라·캐시 bind mount를 `--mount type=bind`로 변경한다. 호스트 source가 없으면 자동 생성하지 않고 실패한다. 기존 컨테이너 수·서비스 검사·커버리지 측정·캐시 키·비동기 분석 구조는 유지한다.
- 서버 폴더 삭제·소유권 변경·Jenkins 재실행·운영 배포는 수행하지 않았다. 기존 workspace의 권한 복구는 별도 인프라 조치가 필요하다.

## 실행한 검증

- Linux `python:3.11-slim`, 네트워크 차단·메모리 256MB·저장소 읽기 전용 마운트: `python -m unittest discover -s infra/tests -p 'test_jenkins_mounts.py' -v` 7건 성공(1.563초, 컨테이너 기동 별도).
- 실제 Jenkinsfile 셸을 추출해 실행했다. Git·Docker CLI는 가짜 구현으로 대체하여 구형 MR, 추적되지 않는 잔여 폴더, 정상 커밋, 추적된 테스트 누락, publish/restore의 구형 코드, Docker 호스트 source 누락을 확인했다. 실제 Docker의 root 소유권 재현 시험은 아니다.
- 기존 `infra/tests/jenkins-analysis-tests.groovy` 6개 그룹과 `infra/tests/jenkins-deploy-tests.groovy` 8개 분기 성공. 로컬 Gradle 번들 Groovy로 실행했으며 Jenkins 단계 API를 대체했다.
- `git diff --check` 성공. 이 변경에서 backend/frontend/AI 코드는 수정하거나 전체 서비스 테스트를 재실행하지 않았다.
- 실제 Jenkins Declarative 실행, 병합 후 최신 Jenkinsfile을 사용한 구형 MR 검사, 서버의 폴더 소유권과 !283 재검사 결과는 미확인이다.
