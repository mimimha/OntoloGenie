# backend 외부 라우팅 전환 (354)

이 단계는 `/api/`와 Swagger의 외부 요청 대상을 함께 선택하는 설정이다. 최초 적용의 기본 대상은 기존 `backend:8080`이다. nginx 설정을 병합하거나 Green 컨테이너를 실행하는 것만으로 Green 전환이 일어나지는 않는다.

## 설정과 선택 파일

| 경로 | 역할 |
| --- | --- |
| `conf.d/default.conf` | HTTPS server에서 `$backend_upstream`의 기본값을 지정하고 선택 파일을 읽는다. `/api/`·Swagger location은 이 값을 재정의하지 않는다. |
| `templates/backend-blue.inc` | 기존 backend를 선택하는 예시 |
| `templates/backend-green.inc` | Green을 선택하는 예시 |
| 서버 `nginx/conf.d/runtime/backend-active.inc` | 사람이 검토한 선택 예시를 복사하는 실행 상태. Git에 저장하지 않는다. |

현재 HTTPS server는 다음 순서로 설정한다.

```nginx
set $backend_upstream http://backend:8080;
include /etc/nginx/conf.d/runtime/backend-active*.inc;
```

선택 파일이 없으면 마스크에 일치하는 파일 없이 기본값을 사용한다. 있으면 그 파일의 `set`이 뒤에서 실행되어 값을 바꾼다. 선택 파일은 `backend-active.inc` **하나만** 둔다. 백업은 `backup/`에, 교체 중 파일은 `.tmp` 확장자로 두어 include 마스크에 들어가지 않게 한다.

`runtime/`은 기존 conf.d 디렉터리 마운트 안에 있어 nginx 컨테이너 재생성이 필요 없다. `.inc`는 현재 `infra/deploy/configuration.py`의 자동 배포 허용 확장자가 아니며, 예시는 conf.d 밖에 두었다. 따라서 기본 설정 배포·복구가 활성 선택 파일을 복사하거나 삭제하지 않는다. 앞으로 배포 허용 목록을 바꿀 때도 이 구분을 유지한다.

Docker DNS resolver와 변수 proxy_pass를 유지한다. nginx가 대상 이름을 해석할 수 있다는 것과 대상 앱이 정상이라는 것은 별개이므로 전환 전에 앱 상태를 검사한다. 설정의 기본값은 장애 자동 전환 기능이 아니다. Green 선택 후 Green이 죽어도 자동으로 Blue를 선택하지 않는다.

인증서·로그인 요청 제한·Swagger Basic Auth·외부 internal 경로 차단·frontend 등 다른 경로의 라우팅은 기존 설정을 유지한다. 보안 헤더는 아래 설명대로 중복 추가를 제거한다.

API·Swagger의 access log는 `backend_routing` 형식을 사용한다. 시각·메서드·상태 코드·실제 upstream 주소만 기록하고 URI·쿼리·쿠키·토큰·본문은 기록하지 않는다. 공식 nginx 이미지의 기존 access.log 경로로 출력하므로 기존 Docker 로그 수집 경로를 사용한다. 접근 제어에서 먼저 거절된 요청은 upstream이 `-`일 수 있다. 이 형식은 선택 파일의 의도뿐 아니라 실제 요청 전달 대상을 확인하기 위한 것이다.

### 보안 헤더 중복 제거

기존 운영 nginx와 최초 후보 nginx 모두 API 응답에 HSTS·X-Content-Type-Options·X-Frame-Options를 두 번 전달한 것을 사용자가 확인했다. Spring Security 기본 헤더와 nginx의 고정 `add_header`가 함께 적용된 상태다.

수정 설정은 응답 헤더 세 항목을 각각 확인한다. 앱이 보낸 항목은 그대로 전달하고 nginx는 그 항목을 추가하지 않는다. 항목이 없을 때만 nginx의 기존 기본값을 추가한다. 이를 위해 `map`과 빈 값이면 헤더를 추가하지 않는 `add_header` 동작을 사용한다. 앱의 헤더를 숨기거나 Spring Security를 끄지 않는다. 이 규칙은 HTTPS server의 프록시 응답에 공통으로 적용되며 Grafana·Jenkins 등도 자체 헤더가 있으면 유지한다. 앱 자체가 같은 헤더를 여러 번 보내는 경우까지 정리하는 기능은 아니다.

| 응답 | HSTS | X-Content-Type-Options | X-Frame-Options |
| --- | --- | --- | --- |
| backend의 익명 `/api/v1/auth/me` 401 | 기존 앱 값 `max-age=31536000 ; includeSubDomains` | `nosniff` | 기존 앱 값 `DENY` |
| nginx가 직접 만드는 internal 403·Swagger Basic 인증 401 | 기존 nginx 값 `max-age=604800` | `nosniff` | 기존 nginx 값 `SAMEORIGIN` |
| 자체 보안 헤더가 없는 frontend 응답 | 기존 nginx 값 `max-age=604800` | `nosniff` | 기존 nginx 값 `SAMEORIGIN` |

각 헤더는 한 번씩만 나와야 한다. API와 nginx 자체 응답의 기존 값 차이를 통일하거나 HSTS 적용 기간·하위 도메인 범위를 새로 늘리는 변경은 아니다. 보안 정책 자체를 통일하려면 별도로 영향과 사용 요구를 검토한다.

근거: [Spring Security 기본 헤더](https://docs.spring.io/spring-security/reference/features/exploits/headers.html), [nginx map](https://nginx.org/en/docs/http/ngx_http_map_module.html), [빈 add_header 값 처리](https://mailman.nginx.org/pipermail/nginx/2023-March/QAXESORMENZRXPUWKLQRJV4ZKEERTTMU.html).

## 적용 경계

- 원격 MR 검토·병합과 Jenkins 검사·설정 반영 결과를 확인한 뒤 사람이 운영 적용을 진행한다. 개발용 검증 사본으로 운영 파일을 덮어쓰지 않는다.
- 수동 전환 중 다른 backend 배포·설정 배포·인증서 reload와 겹치지 않게 조율한다. 아직 공통 배포 잠금은 구현하지 않았다.
- AI 콜백은 `backend:8080`을, 기존 앱 모니터링도 `backend:8080`을 직접 사용한다. 이번 외부 전환 뒤에도 기존 backend를 종료하지 않는다. 두 슬롯의 지표 수집과 내부 콜백 전환은 후속 작업이다.
- Agent는 HTTP 응답 후에도 별도 실행기에서 작업한다. nginx reload만으로 그 작업이 다른 인스턴스로 이전되지는 않는다. HTTP 연결 수나 일정 대기 시간만으로 기존 backend를 종료하지 않는다.
- 새 버전의 DB·세션·API 호환성, 마이그레이션, 앱 드레인, 자동 롤백·장애 감지는 별도 구현·검증 범위다. 최초 수동 전환 검증은 두 backend가 같은 이미지 ID인 상태에서 진행한다.
- `/tmp`의 Compose 확장 파일과 `restart: "no"` Green은 최초 검증용이다. 서버 재부팅 후 자동 복구가 완성된 운영 대기본으로 보지 않는다.

## 1. 후보 설정만 검사

다음 예시는 사용자가 직접 실행한다. 로컬에서 새 파일 세 개를 서버의 임시 경로로 전송한다. 키 경로는 본인 PC의 실제 경로를 쓴다. 기존 운영 파일과 인증서는 복사하거나 출력하지 않는다.

```powershell
scp -i "$env:USERPROFILE\Downloads\K15C106T.pem" .\infra\nginx\conf.d\default.conf ubuntu@k15c106.p.ssafy.io:/tmp/axcore-nginx-354-default.conf
scp -i "$env:USERPROFILE\Downloads\K15C106T.pem" .\infra\nginx\templates\backend-blue.inc ubuntu@k15c106.p.ssafy.io:/tmp/axcore-nginx-354-blue.inc
scp -i "$env:USERPROFILE\Downloads\K15C106T.pem" .\infra\nginx\templates\backend-green.inc ubuntu@k15c106.p.ssafy.io:/tmp/axcore-nginx-354-green.inc
```

서버의 같은 Bash 터미널에서, 현재 다른 nginx 설정도 포함하는 임시 디렉터리를 만든다. 운영 `runtime/` 선택 파일은 가져오지 않고 기본값부터 검사한다.

```bash
routingStage=$(mktemp -d /tmp/axcore-nginx-354.XXXXXX)
mkdir -p "$routingStage/conf.d/runtime"
cp /home/ubuntu/axcore/nginx/conf.d/*.conf "$routingStage/conf.d/"
cp /tmp/axcore-nginx-354-default.conf "$routingStage/conf.d/default.conf"

check_routing_candidate() {
    docker run --rm --pull=never --network axcore-net --entrypoint nginx \
      --mount "type=bind,src=$routingStage/conf.d,dst=/etc/nginx/conf.d,readonly" \
      --mount "type=bind,src=/home/ubuntu/axcore/nginx/htpasswd,dst=/etc/nginx/htpasswd,readonly" \
      --mount "type=bind,src=/home/ubuntu/axcore/certbot/conf,dst=/etc/letsencrypt,readonly" \
      --mount "type=bind,src=/home/ubuntu/axcore/certbot/www,dst=/var/www/certbot,readonly" \
      nginx:1.30 -t
}

check_routing_candidate
```

종료 코드 0과 `syntax is ok`·`test is successful`을 확인한다. 이는 임시 컨테이너의 검사이며 기존 nginx의 설정·프로세스를 변경하지 않는다. 호스트 포트도 공개하지 않는다.

기본값 검사가 성공한 경우에만 선택 파일을 순서대로 검사한다. 각 검사에서 실패하면 다음 명령을 진행하지 않는다.

```bash
cp /tmp/axcore-nginx-354-blue.inc "$routingStage/conf.d/runtime/backend-active.inc"
check_routing_candidate
```

```bash
cp /tmp/axcore-nginx-354-green.inc "$routingStage/conf.d/runtime/backend-active.inc"
check_routing_candidate
```

세 번 모두 성공해야 기본·명시적 Blue·Green 설정의 문법 검사가 통과한 것이다. 이 검사는 proxy_pass 대상의 가용성이나 인증·업무 동작을 검증하지 않는다. 위 임시 디렉터리에는 공개 가능한 설정 사본만 있고 인증서·htpasswd 내용은 복사하지 않았다. 정리는 해당 `routingStage`의 정확한 경로를 확인한 후 별도로 수행한다.

### 후보 nginx를 통한 실제 요청

세 문법 검사가 통과하고 임시 선택 파일이 Green을 가리키는 상태에서만 진행한다. 호스트 포트 없이 후보 nginx를 띄운다. 동일 이름의 컨테이너가 이미 있으면 먼저 그 상태를 확인하고 임의로 삭제하지 않는다.

```bash
docker run -d --rm --pull=never --name axcore-nginx-354-check \
  --network axcore-net --memory 128m --cpus 1 --entrypoint nginx \
  --mount "type=bind,src=${routingStage:?}/conf.d,dst=/etc/nginx/conf.d,readonly" \
  --mount "type=bind,src=/home/ubuntu/axcore/nginx/htpasswd,dst=/etc/nginx/htpasswd,readonly" \
  --mount "type=bind,src=/home/ubuntu/axcore/certbot/conf,dst=/etc/letsencrypt,readonly" \
  --mount "type=bind,src=/home/ubuntu/axcore/certbot/www,dst=/var/www/certbot,readonly" \
  nginx:1.30 -g 'daemon off;'
```

기동 성공 후 기존 backend 컨테이너의 curl에서 후보 nginx로만 연결한다. URL·TLS 이름은 실제 도메인을 사용하며 인증서 검증을 생략하지 않는다. 익명 조회이므로 기대 응답은 401이다.

```bash
docker exec backend curl --connect-to ontologenie.com:443:axcore-nginx-354-check:443 \
  -sS --max-time 10 -o /dev/null -w 'candidate_auth_me_http=%{http_code}\n' \
  https://ontologenie.com/api/v1/auth/me
docker inspect --format '{{.Name}}{{range .NetworkSettings.Networks}} ip={{.IPAddress}}{{end}}' backend backend-green
docker logs --since 2m axcore-nginx-354-check 2>&1 | grep '^{"event":"backend_routing",' | tail -n 10
```

해당 요청의 upstream 주소가 Green의 IP와 8080 포트인지 확인한다. 후보 nginx의 Green 선택 검증이 끝나면 Blue 예시를 임시 선택 파일에 복사하고 **후보 컨테이너만** 검사·reload해 같은 요청의 upstream이 Blue로 돌아오는지도 확인한다.

```bash
cp /tmp/axcore-nginx-354-blue.inc "$routingStage/conf.d/runtime/backend-active.inc" &&
docker exec axcore-nginx-354-check nginx -t &&
docker exec axcore-nginx-354-check nginx -s reload
```

reload 후 새 curl 요청과 로그를 다시 확인한다. 내부 경로 차단·Swagger 인증·TLS 등의 검증은 후보 컨테이너에 대해서도 다음 절의 요청을 같은 `--connect-to` 방식으로 수행할 수 있다. 이 검증으로 로그인 세션 유지나 실제 운영 전환까지 통과했다고 표시하지 않는다.

보안 헤더 중복 제거를 반영한 설정에서는 후보 컨테이너를 유지한 채 다음 요청으로 상태 코드·각 헤더의 값과 개수를 확인한다. 본문·쿠키·인증값은 출력하지 않는다.

```bash
check_candidate_headers() {
    docker exec backend curl \
      --connect-to ontologenie.com:443:axcore-nginx-354-check:443 \
      -sS --max-time 10 -D - -o /dev/null "https://ontologenie.com$1" |
    grep -Ei '^(HTTP/|Strict-Transport-Security:|X-Content-Type-Options:|X-Frame-Options:|WWW-Authenticate:)'
}

check_candidate_headers /api/v1/auth/me
check_candidate_headers /api/v1/internal
check_candidate_headers /api/v1/internal/verification
check_candidate_headers /v3/api-docs
check_candidate_headers /
```

예상 상태 코드는 순서대로 401·403·403·401·200이다. Swagger는 `WWW-Authenticate: Basic`도 확인한다. 헤더는 위 표와 비교하고 각 이름이 한 번씩만 나오는지 확인한다. API는 Blue와 Green을 각각 선택해 검사하고 실제 upstream도 로그로 확인한다. 수정한 설정의 기본값·Blue·Green 문법 검사도 다시 수행한다.

후보 요청 검증이 끝나면 아래 이름의 임시 컨테이너만 종료한다. `--rm`으로 생성했으므로 종료 후 컨테이너가 자동 제거된다. 운영 `nginx`, backend 두 개와 batch는 종료하지 않는다.

```bash
docker stop axcore-nginx-354-check
```

## 2. 기본 라우팅 설정 반영 확인

후보 검증과 사람 리뷰 후 MR을 병합한다. `default.conf`는 기존 설정 자동 배포 대상이므로 Jenkins가 문법 검사·백업·반영·reload를 수행한다. 활성 파일이 없다면 backend 대상은 그대로다. 배포 Job 결과와 기존 서비스 응답을 확인한다. 준비 단계 MR 병합을 354 전체 완료로 표시하지 않는다.

설정 반영이 끝난 후 서버에서 선택 기능의 존재를 확인한다.

```bash
docker exec nginx nginx -t
docker exec nginx nginx -T 2>/dev/null | grep -F 'include /etc/nginx/conf.d/runtime/backend-active*.inc;'
```

include가 확인되지 않으면 수동 선택 파일을 넣어도 효과가 없으므로 전환을 진행하지 않는다. `nginx -T`는 디스크의 설정을 검사·출력할 뿐 현재 worker에 적용됐다는 증거는 아니다. Jenkins 성공과 실제 응답도 확인한다.

## 3. 전환 전 검사와 백업

이 절부터는 실제 서비스 설정 변경 준비다. 사람이 적용 범위와 시점을 결정하고 같은 터미널에서 진행한다.

```bash
docker inspect --format '{{.Name}} image_id={{.Image}} health={{.State.Health.Status}} restarts={{.RestartCount}} oom={{.State.OOMKilled}}' backend backend-green
docker exec backend curl -fsS --max-time 10 http://backend:8080/actuator/health
docker exec backend curl -fsS --max-time 10 http://backend-green:8080/actuator/health
```

양쪽 healthy·UP, 예상하지 않은 재시작·OOM 없음, 최초 검증에서 이미지 ID가 같은지 확인한다. 실패하면 전환하지 않는다. 현재 세션으로 로그인한 브라우저에서 사용자와 읽기 전용 업무 화면을 확인해 전환 전 기준을 남긴다. 세션 쿠키·비밀번호·인증 헤더를 출력하거나 Jira에 붙이지 않는다.

기존 컨테이너와 runtime 설정을 기록한다.

```bash
routingBefore=$(docker inspect --format '{{.Name}} {{.Id}} {{.State.StartedAt}}' nginx backend backend-green batch)
routingBackup="/home/ubuntu/axcore/backup/backend-routing-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$routingBackup"
mkdir -p /home/ubuntu/axcore/nginx/conf.d/runtime
```

runtime에 `backend-active*.inc`가 여러 개 있거나 의도하지 않은 파일이 있으면 먼저 원인을 확인한다.

```bash
find /home/ubuntu/axcore/nginx/conf.d/runtime -maxdepth 1 -name 'backend-active*.inc' -print
```

최초 전환은 선택 파일이 없거나 기존 backend를 명시적으로 선택한 상태에서 진행한다. 선택 파일이 없으면 복구용 Blue 예시를 보관하고, 있으면 현재 내용을 보관한다.

```bash
if [ -f /home/ubuntu/axcore/nginx/conf.d/runtime/backend-active.inc ]; then
    cp /home/ubuntu/axcore/nginx/conf.d/runtime/backend-active.inc "$routingBackup/backend-active.inc"
else
    cp /tmp/axcore-nginx-354-blue.inc "$routingBackup/backend-active.inc"
fi
```

## 4. Green 수동 전환

앞의 모든 조건이 충족되고 사람의 적용 결정이 끝난 경우에만 실행한다. `.tmp`는 include 마스크에 걸리지 않으며, 같은 디렉터리의 rename으로 완성된 파일을 교체한다.

```bash
cp /tmp/axcore-nginx-354-green.inc /home/ubuntu/axcore/nginx/conf.d/runtime/backend-active.inc.tmp &&
mv /home/ubuntu/axcore/nginx/conf.d/runtime/backend-active.inc.tmp /home/ubuntu/axcore/nginx/conf.d/runtime/backend-active.inc &&
docker exec nginx nginx -t
```

문법 검사 성공 후에만 reload한다.

```bash
docker exec nginx nginx -s reload
```

검사·reload 실패 시 다음 절의 복구를 수행한다. 특히 검사 실패 후 디스크의 잘못된 선택 파일을 방치하면 이후 다른 reload도 실패할 수 있다. reload 명령 성공은 신호 전달 결과이므로 실제 응답 확인을 생략하지 않는다.

외부 응답은 인증서 검증을 유지한 채 확인한다. localhost 접속에서도 실제 도메인의 TLS 이름을 쓴다.

```bash
curl --resolve ontologenie.com:443:127.0.0.1 -sS --max-time 10 -o /dev/null -w 'auth_me_http=%{http_code}\n' https://ontologenie.com/api/v1/auth/me
curl --resolve ontologenie.com:443:127.0.0.1 -sS --max-time 10 -o /dev/null -w 'internal_http=%{http_code}\n' https://ontologenie.com/api/v1/internal
curl --resolve ontologenie.com:443:127.0.0.1 -sS --max-time 10 -o /dev/null -w 'swagger_http=%{http_code}\n' https://ontologenie.com/v3/api-docs
```

익명 요청의 기대값은 순서대로 401·403·401이다. 실제 전달 대상은 API 요청 직후의 라우팅 로그와 두 컨테이너의 IP를 비교한다.

```bash
docker inspect --format '{{.Name}}{{range .NetworkSettings.Networks}} ip={{.IPAddress}}{{end}}' backend backend-green
docker logs --since 2m nginx 2>&1 | grep '^{"event":"backend_routing",' | tail -n 10
```

`/api/v1/auth/me` 요청 시각의 로그에서 upstream 주소가 Green의 IP와 8080 포트인지 확인한다. 접근 제어에서 끝난 Swagger 요청의 upstream `-`는 실제 앱 도착 증거가 아니다. 기존 로그인 브라우저에서도 세션 유지·읽기 전용 업무 화면·CSRF를 사용하는 정상 동작을 별도로 확인한다. 기존 도메인도 동일 정책을 확인한다. 응답 코드나 전달 대상 확인만으로 모든 업무가 정상이라고 보증하지 않는다.

```bash
routingAfter=$(docker inspect --format '{{.Name}} {{.Id}} {{.State.StartedAt}}' nginx backend backend-green batch)
if [ "$routingBefore" = "$routingAfter" ]; then
    echo 'existing_containers_unchanged=PASS'
else
    echo 'existing_containers_unchanged=FAIL'
fi
```

모두 정상이더라도 기존 backend를 종료하지 않는다. nginx의 기존 worker와 진행 중 HTTP 요청뿐 아니라 Agent 백그라운드 작업·AI 콜백도 남아 있을 수 있다.

## 5. 이전 선택으로 복구

같은 터미널의 `routingBackup`이 올바른 백업 디렉터리를 가리키는지 먼저 확인한다. 새 터미널이면 실제 생성한 백업 경로를 확인해 지정한다.

```bash
test -f "$routingBackup/backend-active.inc"
```

백업이 존재하는 경우에만 복원한다.

```bash
cp "$routingBackup/backend-active.inc" /home/ubuntu/axcore/nginx/conf.d/runtime/backend-active.inc.tmp &&
mv /home/ubuntu/axcore/nginx/conf.d/runtime/backend-active.inc.tmp /home/ubuntu/axcore/nginx/conf.d/runtime/backend-active.inc &&
docker exec nginx nginx -t
```

복원 설정 검사가 성공한 뒤 reload하고 위 외부 응답·로그인·컨테이너 유지 여부를 다시 확인한다.

```bash
docker exec nginx nginx -s reload
```

이는 라우팅 선택 복구다. DB를 되돌리거나 애플리케이션 버전을 교체하지 않는다. 복구 실패를 성공으로 표시하지 않고 현 상태·백업을 보존해 사람이 원인을 확인한다.

## 검증 기록

현재 저장소 변경은 nginx 설정·선택 예시·안내 문서다. 사용자 직접 실행 결과, 서버의 nginx:1.30 임시 컨테이너에서 기본값·명시적 Blue·Green 세 상태 모두 `nginx -t`가 성공했고 각 종료 코드는 0이었다.

호스트 포트를 공개하지 않은 후보 nginx에서 `/api/v1/auth/me`의 익명 요청이 401로 응답했고, 라우팅 로그의 upstream이 Green 컨테이너 IP와 일치했다. Blue 선택 파일로 변경한 뒤 설정 검사·reload가 종료 코드 0으로 성공했으며, 같은 요청의 응답은 401이고 upstream은 Blue 컨테이너 IP와 일치했다. 이는 후보 nginx에서 Green 전달과 Blue 복귀를 확인한 결과다. 내부 API의 정확한 경로 403 및 Swagger 401·Basic 인증 요구도 사용자가 확인했다.

이후 API 보안 헤더 조회에서 기존 운영 nginx와 후보 nginx 모두 같은 중복 헤더를 반환한 것을 사용자가 확인했다. 이 결과를 바탕으로 중복 추가를 제거하는 설정을 작성했다.

중복 제거 수정 후에도 사용자가 후보 nginx에서 다음을 직접 확인했다.

- Blue 선택 상태의 `nginx -t`·reload 종료 코드 0, `/api/v1/auth/me` 401. 보안 헤더 세 항목이 앱의 기존 값으로 한 번씩만 출력됐다.
- 정확한 `/api/v1/internal` 경로 403, Swagger `/v3/api-docs` 401 및 Basic 인증 요구, frontend `/` 200. 각 응답의 보안 헤더 세 항목은 nginx의 기존 기본값으로 한 번씩만 출력됐다.
- Green 선택의 `nginx -t`·reload 종료 코드 0. API 401, 중복 없는 앱 보안 헤더, 실제 upstream과 Green 컨테이너 IP 일치를 확인했다.
- Blue 복귀의 `nginx -t`·reload 종료 코드 0. API 401, 중복 없는 앱 보안 헤더, 실제 upstream과 Blue 컨테이너 IP 일치를 확인했다.

검증 후 사용자가 `docker stop axcore-nginx-354-check`로 후보 컨테이너를 종료했다. 후보는 `--rm` 옵션으로 실행했다.

이는 후보 nginx를 통한 검증이다. 수정 후 선택 파일이 없는 기본값 상태의 문법 재검사, internal 하위 경로의 HTTP 재검사, Grafana·Jenkins 자체 보안 헤더 비교, 인증 후 Swagger 응답, 운영 nginx 반영·전환·복구 및 로그인 유지는 아직 검증하지 않았다. 운영 nginx의 중복 헤더도 이 후보 검증만으로 제거된 것은 아니다.

Green의 기동·내부 health 200/UP·익명 요청 401·호스트 포트 없음·기존 컨테이너 유지·사용 메모리는 사용자가 앞 단계에서 확인한 결과이며, 라우팅 전환 검증과 구분한다.
