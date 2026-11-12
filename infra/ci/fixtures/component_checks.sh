
        set +x
        set -eu
        umask 077
        COMPONENT="$CHECK_COMPONENT"
        case "$COMPONENT" in backend|frontend|ai) ;; *) exit 2 ;; esac
        HOST_WS=$(printf '%s' "$WORKSPACE" | sed 's#^/var/jenkins_home/#/home/ubuntu/axcore/jenkins-data/#')
        CACHE_DIR="${JENKINS_HOME:-/var/jenkins_home}/ci-results"
        LOCK_DIR="${JENKINS_HOME:-/var/jenkins_home}/ci-locks"
        mkdir -p "$CACHE_DIR" "$LOCK_DIR"
        CACHE_HOST=$(printf '%s' "$CACHE_DIR" | sed 's#^/var/jenkins_home/#/home/ubuntu/axcore/jenkins-data/#')
        TREE=$(git rev-parse "HEAD:$COMPONENT")
        if [ "$COMPONENT" = backend ]; then
            # TransformCasesTests는 backend 밖의 AI 공유 fixture도 읽는다. 삭제·구형 커밋도 구분한다.
            FIXTURE=$(git rev-parse HEAD:ai/tests/fixtures/transform_cases.json 2>/dev/null) || FIXTURE=missing
            TREE=$(printf '%s %s' "$TREE" "$FIXTURE" | git hash-object --stdin)
        fi
        # 실제 검사 절차를 고정한다. 관련 없는 배포 처리 변경으로 성공 결과를 무효화하지 않는다.
        PIPELINE=$(printf '%s' "${CHECK_POLICY_BODY:?}" | git hash-object --stdin)
        COMMIT=$(git rev-parse HEAD)

        cache_results() {
            if ! git cat-file -e HEAD:infra/ci/result_cache.py 2>/dev/null || [ ! -f infra/ci/result_cache.py ]; then
                return 1
            fi
            if [ "$COMPONENT" = backend ]; then
                RUNTIME="$(java -version 2>&1) ${JAVA_TOOL_OPTIONS:-} ${GRADLE_OPTS:-}"
                set -- "$1" --user "$(id -u):$(id -g)"
            else
                if [ "$COMPONENT" = frontend ]; then IMAGE=node:24.18.0-alpine; else IMAGE=python:3.11-slim; fi
                RUNTIME=$(docker image inspect "$IMAGE" --format '{{.Id}}' 2>/dev/null) || return 1
                set -- "$1"
            fi
            ACTION=$1
            shift
            docker run --rm --network none --memory 256m "$@" \
                -e BUILD_URL \
                --mount "type=bind,src=$HOST_WS/infra/ci,dst=/script,readonly" \
                --mount "type=bind,src=$HOST_WS/$COMPONENT,dst=/work" \
                --mount "type=bind,src=$CACHE_HOST,dst=/cache" \
                python:3.11-slim python /script/result_cache.py "$ACTION" "$COMPONENT" \
                --tree "$TREE" --pipeline "$PIPELINE" --runtime "full-checks-v2 $RUNTIME" --commit "$COMMIT"
        }

        if cache_results restore; then
            exit 0
        fi
        WAIT_STARTED=$(date +%s)
        echo "$COMPONENT: 전체 검사 실행 슬롯 대기"
        # 같은 구성 요소의 테스트만 직렬화한다. 특히 두 Gradle JVM이 Jenkins의 2 CPU/4 GiB를 나눠 쓰지 않는다.
        # 프로세스 종료/취소 시 OS가 잠금을 해제한다. 배포와 Sonar 스캐너는 이 잠금을 잡지 않는다.
        exec 9>"$LOCK_DIR/$COMPONENT.lock"
        flock 9
        echo "$COMPONENT: 실행 슬롯 획득 (잠금 대기 $(($(date +%s) - WAIT_STARTED))초)"
        # 기다리는 동안 다른 Job이 같은 코드의 전체 검사를 끝냈을 수 있다.
        if cache_results restore; then
            exit 0
        fi
        echo "$COMPONENT: 전체 검사 시작"
        CHECK_STARTED=$(date +%s)
        case "$COMPONENT" in
            backend)
                # 캐시 미스면 공유 fixture 변경도 실제로 검사한다. 컴파일 등 다른 작업의 증분 처리는 유지한다.
                (cd backend && ./gradlew --no-daemon spotlessCheck test --rerun build jacocoTestReport)
                ;;
            frontend)
                docker run --rm --memory 2g -e CI=true \
                    -v "$HOST_WS/frontend:/app" -w /app \
                    -v axcore-npm-cache:/root/.npm node:24.18.0-alpine \
                    sh -c 'npm ci --no-audit --no-fund && npm run format:check && npm run lint && npm run test:coverage && npm run build'
                ;;
            ai)
                docker run --rm --memory 1g -e CI=true \
                    -v "$HOST_WS/ai:/app" -w /app \
                    -v axcore-pip-cache:/root/.cache/pip python:3.11-slim \
                    sh -c 'pip install -q -r requirements-dev.txt && ruff check . && pytest -q --junitxml=test-results/pytest.xml --cov --cov-report=xml'
                ;;
        esac
        echo "$COMPONENT: 전체 검사 성공 ($(($(date +%s) - CHECK_STARTED))초)"
        # 위 전체 명령이 성공한 뒤에만 보관한다. 캐시 오류로 성공한 검사를 실패시키지는 않는다.
        if ! cache_results publish; then
            echo "$COMPONENT: 결과 보관 실패. 다음 Job은 전체 검사를 실행합니다."
        fi
