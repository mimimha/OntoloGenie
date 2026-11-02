# 지표 수집

Prometheus는 지표를 저장하고 Alloy는 30초마다 수집·전송한다. Grafana는 저장된 지표를 조회한다. Prometheus·Alloy는 호스트 포트를 열지 않고, Grafana는 `127.0.0.1:13000`에만 연결한다. 메모리 상한(Prometheus 512MiB, Alloy 256MiB, Grafana 512MiB)과 보관 설정(7일, TSDB 2GB)은 초기값이며 전체 수집 구성 후 실측해 조정한다. TSDB 보관 크기는 WAL 등을 포함한 전체 디스크 사용량의 엄격한 상한이 아니다.

## 파일과 실행 범위

- `prometheus.yml`: Prometheus 자체 지표 수집.
- `config.alloy`: Alloy 자체 지표와 호스트 CPU·메모리·디스크·시스템 부하 수집.
- `container-metrics.alloy`: Linux 서버의 Docker 컨테이너 CPU·메모리 수집. `job="cadvisor"`, `instance="axcore-main"`로 전송한다. Compose 서비스·프로젝트 라벨만 허용하고 환경변수는 지표 라벨로 수집하지 않는다.
- `compose.local.yaml`: 노트북용 네트워크·경로·권한 재정의. `config.alloy`만 실행하며 cAdvisor 실제 수집은 실행하지 않는다. Compose 2.24.4 이상이 필요하다.

서버에서는 Alloy가 `/etc/alloy` 디렉터리의 `.alloy` 파일을 함께 읽는다. 서버 반영 시 `compose.yaml`뿐 아니라 위 두 `.alloy` 파일과 `prometheus.yml`을 `~/axcore/monitoring/`에 복사해야 한다. 저장소 머지만으로 서버 설정이 복사되지는 않는다.

## Grafana

Grafana 이미지는 `grafana/grafana:13.3.0`으로 고정한다. `grafana-data` 볼륨에 계정·대시보드 등을 저장하고 `grafana/provisioning/datasources/prometheus.yaml`로 내부 Prometheus를 자동 등록한다. 기본 provisioning 하위 폴더가 가려지지 않도록 이 파일만 읽기 전용으로 연결한다. 데이터 소스 UID는 `axcore-prometheus`다. Grafana 자체가 지표를 보관하는 것은 아니며 지표 보존 정책은 Prometheus에서 관리한다.

서버에서는 새 Compose를 적용하기 전에 `.env`에 `GRAFANA_ADMIN_USER`·`GRAFANA_ADMIN_PASSWORD`를 지정한다. 두 값이 비어 있으면 Compose 변수 해석이 실패하므로 다른 서비스의 Compose 명령에도 영향을 준다. 실제 값을 저장소나 채팅에 남기지 않는다. 이 값은 최초 관리자 생성용이며, 기존 Grafana 데이터 볼륨이 있으면 환경변수 변경만으로 기존 비밀번호가 바뀌지는 않는다. 익명 접근과 사용자 직접 가입은 비활성화한다.

서버 화면은 SSH 터널을 통해 로컬 `http://127.0.0.1:13000`으로 확인한다. 현재 단계에서는 nginx 외부 경로를 추가하지 않는다. 노트북 검증에서는 프로세스 환경변수로 별도 계정을 지정하고 기존 로컬 네트워크 재정의 파일을 함께 사용한다. 로그인·Prometheus 연결·지표 조회·재기동 후 데이터 유지·자원 사용량은 실행 후 검증해야 한다.

### 자원 대시보드 자동 등록

`grafana/provisioning/dashboards/axcore.yaml`은 `/etc/grafana/dashboards`의 JSON 파일을 시작 시와 30초 주기로 읽는다. `grafana/dashboards/axcore-resources.json`은 UI에서 만든 대시보드를 Classic JSON으로 내보낸 파일이며, UID `axjnjgr`와 데이터 소스 UID `axcore-prometheus`를 유지한다. 동일 UID의 로컬 대시보드가 있으면 파일 내용이 해당 대시보드에 반영된다. 다른 UID의 대시보드는 관리 대상이 아니다.

UI 편집·저장은 허용하지만 UI 변경이 저장소 JSON에 자동으로 기록되지는 않는다. JSON 파일이 갱신되면 UI 변경을 덮어쓸 수 있으므로, 수정한 화면은 다시 Classic JSON으로 내보내 저장소 파일에도 반영한다. `disableDeletion: true`로 설정해 원본 JSON 파일이 없어졌다는 이유만으로 DB의 대시보드를 자동 삭제하지 않는다.

패널은 호스트 메모리·CPU·루트 디스크 사용률과 서비스별 메모리·CPU 사용률 5개다. 로컬에서는 루트 디스크·컨테이너 지표가 없어 해당 3개 패널은 No data가 예상된다. 서버 반영 후 실제 그래프와 변환된 패널 배치·단위, 새로고침 30초·기본 시간 범위를 확인한다. Classic 내보내기에서 V2 전용 레이아웃 기능은 유지되지 않을 수 있다.

잔여: Grafana `search-manifest-watcher`의 `zero manifests` 경고는 내부 검색용 앱 설명 정보 조회 결과가 비어 발생한다. 로컬 Prometheus 연결·지표 조회는 확인했으며, 검색 동작과 경고 반복 여부는 추가 확인한다. 로그를 숨기는 설정은 추가하지 않았다.

## Linux 서버 접근 권한

cAdvisor의 Docker 수집을 위해 Alloy에 root 사용자·`privileged: true`·호스트 cgroup namespace와 시스템 경로를 설정한다. 운영 반영 전에 이 권한 변경을 검토한다. Docker 소켓을 읽기 전용으로 마운트해도 Docker API의 변경 요청을 차단하지는 않는다.

현재 확인한 서버는 Docker 29.9.2, cgroup v2, `overlayfs`, Docker 저장 경로 `/var/lib/docker`다. containerd 소켓은 `/run/containerd/containerd.sock`을 사용한다. 소켓이 없으면 자동으로 디렉터리를 만들지 않고 기동에 실패하도록 설정한다.

서버에서 8개 컨테이너의 CPU·메모리 수집은 확인했지만, `containerd_namespace="moby"` 설정에서는 이름이 ID로 나오고 Compose 라벨이 누락됐다. containerd 수집기의 namespace를 기본값 `k8s.io`로 분리해 Docker 컨테이너는 Docker 처리기가 조회하도록 조정한다. 이는 Kubernetes를 추가하는 설정이 아니며 Docker가 실제 사용하는 `moby` namespace도 변경하지 않는다. Compose 서비스 라벨은 추가로 `service` 라벨에 복사한다. 이 수정으로 이름·라벨이 복구되는지는 서버 반영 후 확인해야 한다.

## 검증 범위

노트북에서는 `validate /etc/alloy`로 두 Alloy 파일의 문법·참조 관계를 검사하고, Compose 기본 설정과 로컬 설정을 각각 검사한다. Docker Desktop의 로컬 기동은 호스트 수집 경로 확인용이며 서버 컨테이너 수집 성공을 의미하지 않는다.

Linux 서버 반영 후에는 다음을 확인한다.

- 기존 호스트 수집 7개 항목의 성공 값이 모두 1이고 실제 메모리·루트 디스크 용량과 일치하는지.
- `up{job="cadvisor"}`가 1이고 실제 실행 중인 서비스마다 `container_cpu_usage_seconds_total`, `container_memory_working_set_bytes`가 있는지. `up=1`만으로 개별 컨테이너 수집을 완료 처리하지 않는다.
- 위 두 지표에 `service="backend"`, `service="ai"` 등 실제 Compose 서비스 라벨이 붙는지. Compose로 생성되지 않은 임시 컨테이너에는 서비스 라벨이 없을 수 있으며 임의로 이름을 붙이지 않는다.
- Alloy 로그의 런타임 연결·권한 오류, Prometheus 상태와 기존 서비스 상태.
- 수집 추가 후 Alloy 메모리 사용량과 재시작·OOM 여부. 실제 배포·분석 부하 실측과 상한 확정은 별도 잔여 작업이다.
