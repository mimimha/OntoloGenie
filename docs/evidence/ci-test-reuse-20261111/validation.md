# CI 중복 검사와 자원 경합 확인

## 서버 읽기 전용 조사

- 2026-11-11 KST 17:05~17:25, Prometheus 1분 간격 이력을 조회했다.
- MR #372 전체 8분 34초 중 Backend check 8분 23초. 테스트 리포트 1,425건, 실패 0, 기존 조건부 건너뜀 4건.
- 조사 시 MR #373과 SonarQube #29가 각각 전체 backend 테스트를 실행했고 같은 MR Job 4건이 대기했다. CSS MR !308도 시작 전 대기 상태였다.
- SonarQube #29는 `No matching complete successful MR outputs` 뒤 `jacocoTestReport`에 연결된 test를 실행했다. MR #372의 backend tree와 분석 대상 backend tree가 달랐다. 다른 코드의 결과를 재사용하는 것은 허용하지 않는다.
- Jenkins 실제 cgroup 제한은 `cpu.max=200000 100000`(2 CPU), `memory.max=4294967296`(4 GiB).
- 동시 검사 구간 Jenkins CPU 사용량은 약 1.86~2.00코어에 도달했다. 제한 주기 비율(`rate(container_cpu_cfs_throttled_periods_total[1m]) / rate(container_cpu_cfs_periods_total[1m])`) 최고 99.34%. 이는 제한된 주기 비율이며 잃은 CPU 시간 비율을 뜻하지 않는다.
- 메모리 working set 최고 4,294,672,384 bytes(4 GiB 한도에 근접). 같은 조회 구간 호스트 MemAvailable 최저 약 5.10 GiB.
- 17:26 읽은 Jenkins `memory.events`의 oom/oom_kill은 모두 0. 호스트 swap 약 1.85 GiB는 기존 사용량일 수 있으므로 이번 검사로 발생했다고 단정하지 않는다.
- 결론: 호스트 전체 메모리 고갈보다 Jenkins 컨테이너 내부의 CPU 한도·메모리 한도 경합이 확인된다. 각 원인이 wall time에 기여한 초 단위 비중은 분리 측정하지 않았다.
- SSH로 사용량·cgroup 지표·Prometheus 이력·flock 존재 여부만 읽었다. 비밀값 내용 조회, 설정 변경, 서비스 재시작, Job 취소·재실행은 하지 않았다.

## 변경 범위

- 같은 코드·실제 실행 검사 절차·환경의 전체 성공 결과만 MR·SonarQube가 재사용한다.
- 구성 요소별 OS 파일 잠금과 잠금 후 캐시 재확인으로 동일 코드의 동시 중복 검사를 제거한다.
- 다른 코드의 backend 검사는 모두 실행하되 겹치지 않는다. 캐시 오류·검사 실패·취소는 성공으로 재사용하지 않는다.
- 기존 서비스 테스트·포맷·린트·빌드 범위, Sonar 분석·Quality Gate·배포 동작은 유지한다. backend 캐시 미스에서는 `test --rerun`으로 테스트 작업만 강제 재실행하고 컴파일 등은 증분 처리를 유지한다.
- 별도 작업 폴더의 `chore/ci-test-reuse`에서 작업한다. 기존 `chore/mr-latest-commit-guard`의 미커밋 변경은 보존했다.
- 이후 `origin/develop` ca7ea25를 fast-forward로 반영했다. MR 최신 커밋 보호와 공통 검사 호출을 함께 유지하고 보호 회귀 하네스도 맞췄다.
- 최종 확인에서 추가된 develop 67de243도 fast-forward로 반영했다. CI 실행 코드 변경은 없었고 `infra/README.md`의 별도 라우팅 설명을 자동 통합했다. 회귀 검사를 실행한 CI 코드와 동일하다.
- 독립 리뷰의 Major 1건(backend 외부 공유 fixture 누락)은 사용자 지시로 보완했다. 해당 자료의 blob/부재를 backend 캐시 키에 포함하고 MR 검사 대상 선택에도 반영했다.
- 같은 Major의 재리뷰에서 Gradle UP-TO-DATE 경로가 남은 것을 확인하여 `test --rerun`도 적용했다. 최종 독립 재리뷰는 Critical 0 / Major 0 / Minor 0이다.

## 검증

- 로컬 Linux 컨테이너에서 `python -m unittest discover -s infra/ci -p 'test_*.py' -v`: 최종 21건 통과(54.473초). 실제 flock과 실제 캐시 프로그램을 사용하고 서비스 빌드 도구는 대체했다.
- 같은 환경의 `python -m unittest discover -s infra/tests -p 'test_*.py' -v`: 구형 MR·경로 오류·bind mount 보호 7건 통과(2.510초), 건너뜀 없음.
- 동시 동일 코드 2개 Job에서 전체 검사 한 번, 다른 코드는 모두 검사하되 겹치지 않음, 실패·취소 후 재실행, 환경·실행 절차 변경 시 재검사, 캐시 손상·장애 시 전체 검사 수행을 확인했다.
- Gradle 9.8.2 번들 Groovy로 `jenkins-analysis-tests.groovy`: 7개 검증 그룹 통과. 실제 MR 단계와 Sonar 준비 단계의 공통 전체 검사 호출을 확인했다.
- 같은 Groovy로 `jenkins-deploy-tests.groovy`: 배포·롤백 8개 분기 통과.
- 같은 Groovy로 `jenkins-mr-head-tests.groovy`: 기존 MR head 보호 8개 그룹 및 공유 자료 변경 시 backend 선택 회귀 그룹 통과.
- backend tree가 같아도 실제 임시 공유 fixture의 내용 변경·삭제 시 다시 검사하며, 삭제 후 검사 실패가 이전 성공으로 바뀌지 않음을 확인했다. Git CLI는 fixture 바이트에서 blob 해시를 계산하는 모의 도구를 사용했다.
- 실제 backend Gradle 9.8.2·JDK 21의 같은 workspace에서 `test --tests '*TransformCasesTests' --offline`로 정상 1건 통과 후 공유 자료의 기대값만 변경했다. 기존 명령은 `:test UP-TO-DATE`로 잘못 성공하는 것을 재현했고, `test --rerun --tests '*TransformCasesTests' --offline`은 1건 실패를 검출했다. 자료는 원본 바이트로 복원했다. 이 실패는 의도한 회귀 재현이며 정상 코드의 테스트 실패가 아니다.
- 원본 복원 후 같은 `test --rerun --tests '*TransformCasesTests' --offline`로 실제 1건 재통과했다. 서비스 코드·테스트·fixture의 Git diff는 없다. 전체 서비스 테스트 스위트는 이번 CI 변경 검증에서 재실행하지 않았다.
- `git diff --check`: 통과. 서비스별 애플리케이션 코드·실제 테스트 파일은 변경하지 않았다.

## 검증 한계

- Jenkins 단계 호출은 Groovy 대체 환경, 셸 동시성은 로컬 Linux의 실제 flock과 모의 빌드 도구로 검사했다. 서비스 테스트 자체를 이 모의 도구로 대체 배포하지 않는다.
- 운영 Jenkins 적용·서버에서의 수정 전후 성능 비교는 실행하지 않았다. develop 병합과 운영 반영은 사람의 최종 통제를 따른다.

## MR !340 실행 환경 호환 수정

- 사용자가 전달한 실패 로그에서 기존 develop Jenkinsfile이 `infra/ci`만 `/work`에 마운트한 것을 확인했다. 새 테스트가 부모 폴더의 Jenkinsfile을 찾다가 import 시 `StopIteration`으로 실패했다. 앞선 전체 저장소 마운트 검증은 이 환경 차이를 포함하지 못했다.
- 동일한 단독 마운트로 오류를 재현했다. 9개 캐시 검사는 통과했으나 셸 검사 모듈 import가 실패했다.
- 동일 검사 셸의 fixture를 `infra/ci/fixtures/component_checks.sh`에 포함했다. Jenkinsfile 없는 기존 마운트에서도 모든 셸 검사를 실행하고, Jenkinsfile이 보이는 새 환경에서는 fixture와 실제 검사 절차의 일치도 강제한다. 테스트 누락·Skip으로 우회하지 않는다.
- 애플리케이션 코드·서비스 테스트·실제 Jenkins 실행 명령은 이번 후속 수정에서 바꾸지 않았다.
- 기존 단독 마운트에서 CI 24건 통과(48.622초), 전체 저장소 마운트에서도 같은 CI 24건 통과(48.641초). 마운트 보호 7건도 통과(3.066초), 새로 건너뛴 테스트 없음.
- 독립 재리뷰 Critical 0 / Major 0 / Minor 0. 리뷰어가 실제 Jenkinsfile·fixture·단독 마운트 loader의 일치와 import 성공을 직접 확인했다.
