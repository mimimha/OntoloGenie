# S15P31C106-69 모니터링 알림 작업 결과

## 최종 설정

Grafana UI에서 생성한 규칙을 `infra/monitoring/grafana/provisioning/alerting/infra-1m.yaml`로 내보내 보관했다. 최종 다섯 규칙은 1분마다 평가하며 활성 상태다.

| 규칙 | 조건 | 지속 시간 |
| --- | --- | --- |
| axcore-main-disk-usage-high | 루트 디스크 사용률 > 85% | 5분 |
| axcore-container-memory-usage-high | 컨테이너 working set / 설정 메모리 한도 > 90% | 5분 |
| axcore-app-container-not-running | backend·ai·frontend·batch·nginx의 running 상태 값 < 1 | 2분 |
| axcore-app-container-unhealthy | 앱 컨테이너의 unhealthy 상태 값 > 0 | 2분 |
| axcore-app-container-missing | 기대 앱 서비스의 컨테이너 존재 값 < 1 | 2분 |

수신처 `axcore-mattermost`는 Slack 호환 Incoming Webhook으로 요청자가 단독 참여하는 Mattermost 비공개 채널에 연결했다. URL·토큰은 저장소에 포함하지 않는다. Summary·Description과 기본 값/라벨을 통해 서버·서비스·현재 값을 확인한다.

## 검증 결과와 출처

- 사용자 실행 및 첨부 화면: Contact point 테스트 메시지가 비공개 채널에 도착했다. [연결 테스트](contact-point-test.png).
- 사용자 실행 및 첨부 화면: 디스크 임계값을 일시적으로 10으로 낮춰 실제 규칙의 Firing 메시지 수신을 확인했다. 수신 값 A는 약 15.16%였다. [디스크 수신 증적](disk-firing.png). 캡처의 85% 설명은 고정 문구이며 실제 테스트 임계값은 10이다.
- 사용자 확인: 디스크 임계값을 85로 복구하고 Normal을 확인했다.
- 사용자 확인: 컨테이너 규칙을 일시적으로 `IS BELOW 2`로 바꿔 수신 후 `IS BELOW 1`로 복구하고 Normal을 확인했다. 운영 컨테이너를 중지시키지는 않았다. 추가 캡처는 사용자 요청에 따라 생략했다.
- 사용자 실행: Jenkins 메모리가 Docker stats에서 3.999GiB / 4GiB(99.96%)로 확인돼 cAdvisor 약 99.998%와 대조했다. 실행 당시 OOMKilled=false, RestartCount=0이었다. 이 수치는 관측 당시 값이며 원인 확정은 아니다.
- 파일 확인: 최종 YAML의 조건 85/90/lt 1, 지속 시간 5m/5m/2m, 수신처 이름, 활성 상태 및 테스트 문구 제거를 확인했다.
- 로컬 Python/PyYAML로 YAML 파싱 및 위 세 규칙의 비교 연산·임계값·지속 시간·활성 상태·수신처 assertion을 실행해 통과했다. Grafana 엔진의 재적용 검증은 아니다.
- 메모리 규칙의 개별 수신 결과는 이 작업 기록에 명시적인 확인이 없어 미검증으로 남긴다.

## 반영 범위와 복원 조건

현재 서버는 사용자가 Grafana UI에서 저장한 규칙으로 동작한다. 내보낸 규칙·증적 보관과 수집기 확장을 포함한다. compose 마운트·systemd 정의는 변경하지 않는다. 파일 기반 규칙 재적용은 실행하지 않았다.

위 설명은 이번 수동 작업 범위다. 병합 후 자동 배포는 `monitoring/grafana/` 아래 변경 파일을 동기화하면서 Grafana를 `--force-recreate`로 재생성할 수 있다(`infra/deploy/configuration.py`, `infra/deploy/sync-config.sh`). 현재 compose에는 alerting 경로 마운트가 없어 YAML이 복사돼도 규칙이 자동 프로비저닝되지는 않는다. 기존 UI 규칙은 grafana-data 볼륨에 보존되며, 병합·배포 시 Grafana의 일시 중단과 복구 확인이 필요하다.

복원 대상에는 Prometheus UID `axcore-prometheus`와 수신처 `axcore-mattermost`가 별도로 있어야 한다. YAML은 수신처 비밀값·Grafana 전체 데이터의 백업이 아니다. 파일 프로비저닝으로 전환하려면 기존 UI 규칙과 동일 UID 처리 및 배포 경로를 별도로 검토해야 한다.

## 남은 범위

- 초기 세 규칙에 건강 상태·누락 알림 두 규칙을 추가했다. 아래 최종 사용자 검증 기록을 따른다.
- 상태 수집 실패·90초 이상 지연은 쿼리에서 제외되며 각 서비스 누락까지 이 규칙이 보장하지 않는다.
- 실제 중지·unhealthy·삭제 유발 검사, 파일 재적용 검사, 메모리 개별 수신 검증은 수행하지 않았다.
- 현재 비교는 Jira 문구의 '이상'과 달리 엄밀히 '초과'다(디스크 >85, 메모리 >90).
- GitLab 보관, Jira 산출물 링크, 검토자 승인과 병합 확인 후 완료 여부를 판단한다. 이 기록은 승인이나 전체 요구 충족을 대신하지 않는다.

## 건강 상태·서비스 누락 수집 확장

- `axcore_docker_container_health`: none·starting·healthy·unhealthy 상태 중 해당 값만 1로 출력한다. batch·nginx처럼 헬스체크가 없으면 none=1이며 unhealthy=0이다. 건강 상태 누락·알 수 없는 값은 수집 실패로 처리한다.
- `axcore_docker_service_present`: backend·ai·frontend·batch·nginx의 존재 여부를 서비스별 0/1로 출력한다. 중지된 컨테이너도 존재로 세며 실행 여부와 구분한다. 일회성 작업·다른 프로젝트는 제외하고 같은 서비스의 복수 컨테이너는 하나의 존재 지표로 출력한다.
- Docker 목록 조회가 정상 성공했지만 비어 있으면 기대 서비스 모두 0이다. Docker CLI 오류·inspect 응답 누락은 수집 실패이며 존재 지표를 남기지 않는다.
- 사용자 보고: 로컬 unittest 15개 모두 통과. 이 추가 변경의 최종 테스트 실행은 사용자가 담당했고 작성 세션은 재실행하지 않았다. 실제 unhealthy·삭제 상황은 로컬 가짜 데이터로 검증한 범위이며 운영 장애를 유발하지 않았다.
- 사용자 서버 출력 확인: axcore-docker-state.timer가 enabled·active이고 약 30초 간격으로 실행된다. 수집기 파일은 배포 허용 목록에 있어 MR 병합 후 Jenkins가 동기화하면 다음 타이머 실행에서 읽는다. 이번 변경은 타이머 재설치·앱 컨테이너 재시작을 요구하지 않는다.
- 서버의 새 지표 출력, Prometheus 전달, Grafana 알림과 수신은 배포 후 사용자 확인이 필요하다.

## Health 키가 없는 컨테이너 조회 오류 수정

- 최초 배포 뒤 사용자 출력에서 collection_success=0을 확인했다. 실제 Docker stderr는 `.State.Health: map has no entry for key "Health"`였다. 헬스체크가 없는 컨테이너에서 직접 필드 접근이 실패해 프로젝트 전체 스냅샷 수집이 중단됐다.
- INSPECT_FORMAT의 직접 접근을 `with index .State "Health"`로 바꿨다. 키가 없으면 JSON 문자열 none을 반환하고, 있으면 Status만 읽는다.
- 사용자 서버 실행: 수정한 건강 상태 템플릿으로 backend·ai·frontend는 healthy, batch·nginx는 none으로 조회됐다. 조회 명령만 실행했으며 컨테이너 상태를 변경하지 않았다.
- 기존 Python 테스트는 Docker CLI를 가짜 데이터로 대체해 이 Go-template 실행 오류를 잡지 못했다. 이번 수정 이후 전체 inspect JSON·타이머 지표·Prometheus 전달 복구는 재배포 후 사용자 확인이 필요하다. 작성 세션은 이번 수정의 테스트를 실행하지 않았다.

## 최종 서버 복구·추가 알림 확인

- 사용자 배포 완료 보고와 서버 출력: collection_success=1, 마지막 수집 후 약 6.318초를 확인했다. backend·ai·frontend healthy, batch·nginx none, 기대 앱 서비스 다섯 개의 present=1이었다.
- 사용자 Grafana Explore 첨부 화면: 앱 다섯 개의 unhealthy=0을 확인해 Prometheus 전달을 확인했다.
- 사용자 확인: unhealthy 규칙의 임계값을 일시적으로 >-1로 바꿔 수신하고 >0으로 복구 후 Normal을 확인했다.
- 사용자 확인: 누락 규칙의 임계값을 일시적으로 <2로 바꿔 수신하고 <1로 복구 후 Normal을 확인했다.
- 최종 내보내기 파일에서 다섯 규칙의 조건 >85/>90/<1/>0/<1과 지속 시간 5m/5m/2m/2m/2m, 활성 상태·수신처·테스트 문구 제거를 확인했다. YAML 파싱으로 내용을 읽었으며 Grafana 파일 재적용은 하지 않았다.
- 추가 캡처는 사용자 요청에 따라 요구하지 않는다. 위 수신·복구 결과는 사용자 보고로 기록하며 실제 운영 컨테이너의 중지·unhealthy·삭제를 유발한 검증은 아니다.
- 새 YAML은 UI의 최종 설정 보관용이다. 수신처 비밀값과 파일 프로비저닝 마운트는 포함하지 않는다. 이 파일 갱신도 기존 배포 규칙에 따라 Grafana 재생성을 유발할 수 있어 병합·배포 시 일시 중단과 복구 확인이 필요하다.
