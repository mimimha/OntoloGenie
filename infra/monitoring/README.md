# 지표 수집

Prometheus는 지표를 저장하고 Alloy는 30초마다 수집·전송한다. 외부 포트는 열지 않는다. 메모리 상한(Prometheus 512MiB, Alloy 256MiB)과 보관 설정(7일, TSDB 2GB)은 초기값이며 전체 수집 구성 후 실측해 조정한다. TSDB 보관 크기는 WAL 등을 포함한 전체 디스크 사용량의 엄격한 상한이 아니다.

## 파일과 실행 범위

- `prometheus.yml`: Prometheus 자체 지표 수집.
- `config.alloy`: Alloy 자체 지표와 호스트 CPU·메모리·디스크·시스템 부하 수집.
- `container-metrics.alloy`: Linux 서버의 Docker 컨테이너 CPU·메모리 수집. `job="cadvisor"`, `instance="axcore-main"`로 전송한다. Compose 서비스·프로젝트 라벨만 허용하고 환경변수는 지표 라벨로 수집하지 않는다.
- `compose.local.yaml`: 노트북용 네트워크·경로·권한 재정의. `config.alloy`만 실행하며 cAdvisor 실제 수집은 실행하지 않는다. Compose 2.24.4 이상이 필요하다.

서버에서는 Alloy가 `/etc/alloy` 디렉터리의 `.alloy` 파일을 함께 읽는다. 서버 반영 시 `compose.yaml`뿐 아니라 위 두 `.alloy` 파일과 `prometheus.yml`을 `~/axcore/monitoring/`에 복사해야 한다. 저장소 머지만으로 서버 설정이 복사되지는 않는다.

## Linux 서버 접근 권한

cAdvisor의 Docker 수집을 위해 Alloy에 root 사용자·`privileged: true`·호스트 cgroup namespace와 시스템 경로를 설정한다. 운영 반영 전에 이 권한 변경을 검토한다. Docker 소켓을 읽기 전용으로 마운트해도 Docker API의 변경 요청을 차단하지는 않는다.

현재 확인한 서버는 Docker 29.9.2, cgroup v2, `overlayfs`, Docker 저장 경로 `/var/lib/docker`다. containerd 소켓은 `/run/containerd/containerd.sock`을 사용하며 Docker namespace는 `moby`로 설정한다. 소켓 존재는 확인했지만 컨테이너 지표 수집 성공 여부는 서버 적용 후 검증해야 한다. 소켓이 없으면 자동으로 디렉터리를 만들지 않고 기동에 실패하도록 설정한다.

## 검증 범위

노트북에서는 `validate /etc/alloy`로 두 Alloy 파일의 문법·참조 관계를 검사하고, Compose 기본 설정과 로컬 설정을 각각 검사한다. Docker Desktop의 로컬 기동은 호스트 수집 경로 확인용이며 서버 컨테이너 수집 성공을 의미하지 않는다.

Linux 서버 반영 후에는 다음을 확인한다.

- 기존 호스트 수집 7개 항목의 성공 값이 모두 1이고 실제 메모리·루트 디스크 용량과 일치하는지.
- `up{job="cadvisor"}`가 1이고 실제 실행 중인 서비스마다 `container_cpu_usage_seconds_total`, `container_memory_working_set_bytes`가 있는지. `up=1`만으로 개별 컨테이너 수집을 완료 처리하지 않는다.
- Alloy 로그의 런타임 연결·권한 오류, Prometheus 상태와 기존 서비스 상태.
- 수집 추가 후 Alloy 메모리 사용량과 재시작·OOM 여부. 실제 배포·분석 부하 실측과 상한 확정은 별도 잔여 작업이다.
