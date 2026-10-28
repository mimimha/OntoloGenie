// Jenkins 파이프라인 정의. Job 1(axcore-mr-check)과 Job 2(axcore-deploy)가 같은 파일을 쓴다.
// - Job 1: MR이 열리거나 MR 브랜치에 push되면 실행. gitlabSourceBranch = MR 원본 브랜치.
// - Job 2: develop에 push(머지)되면 실행. gitlabSourceBranch = develop.
// 이 파일(설명서)은 항상 develop에서 읽고, 검사할 코드는 Checkout 단계에서 받는다.
// 결과는 GitLab(MR 화면 ✅/❌)과 Mattermost(빌드를 일으킨 사람에게 DM)로 알린다.
// 5-7에서 실제 앱 빌드·배포 단계로 확장한다.
pipeline {
    agent any

    options {
        // 빌드 기록은 최근 10개만 보관해 디스크를 아낀다.
        buildDiscarder(logRotator(numToKeepStr: '10'))
        // GitLab에 빌드 상태(성공/실패)를 써 줄 연결. Jenkins 관리 → System → GitLab의 Connection name.
        gitLabConnection('ssafy-gitlab')
    }

    environment {
        // Webhook으로 시작된 빌드는 GitLab 플러그인이 gitlabSourceBranch를 채워 준다.
        // 사람이 직접 돌린 빌드는 값이 없으므로 develop을 검사한다.
        CHECK_BRANCH = "${env.gitlabSourceBranch ?: 'develop'}"
        // Mattermost Incoming Webhook 주소. Jenkins 관리 → Credentials의 Secret text (ID: mm-webhook).
        // 로그에는 ****로 가려진다. 없으면 빌드가 시작하자마자 실패하니 먼저 등록할 것.
        MM_WEBHOOK = credentials('mm-webhook')
    }

    stages {
        stage('Checkout') {
            steps {
                // 검사할 브랜치를 받는다. MR 검증이면 MR 원본 브랜치, develop 머지면 develop.
                checkout([
                    $class: 'GitSCM',
                    branches: [[name: "origin/${env.CHECK_BRANCH}"]],
                    userRemoteConfigs: [[
                        url: 'https://lab.ssafy.com/s15-final/S15P31C106.git',
                        credentialsId: 'gitlab-clone'
                    ]]
                ])
                sh 'git log -1 --oneline'
                script {
                    // Webhook 빌드일 때만 GitLab에 "검사 중" 상태를 올린다. (수동 빌드는 대상 커밋이 없음)
                    if (env.gitlabActionType) {
                        updateGitlabCommitStatus name: 'jenkins', state: 'running'
                    }
                }
            }
        }

        stage('Hello') {
            steps {
                echo "Hello World from axcore CI (build #${env.BUILD_NUMBER}, branch ${env.CHECK_BRANCH})"
            }
        }

        stage('Docker build test') {
            steps {
                // Jenkins 컨테이너가 docker.sock으로 서버의 Docker를 쓸 수 있는지 확인한다.
                sh 'docker build -t axcore-ci-test:${BUILD_NUMBER} infra/ci-test'
                sh 'docker run --rm axcore-ci-test:${BUILD_NUMBER}'
            }
        }

        stage('Disk usage') {
            steps {
                // 빌드 이미지가 디스크를 얼마나 쓰는지 매번 기록한다.
                sh 'docker system df'
            }
        }
    }

    post {
        success {
            script {
                // GitLab MR 화면에 성공 표시
                if (env.gitlabActionType) {
                    updateGitlabCommitStatus name: 'jenkins', state: 'success'
                }
                notifyMattermost(':white_check_mark: 성공')
            }
        }
        failure {
            script {
                // GitLab MR 화면에 실패 표시. 클릭하면 이 빌드 로그로 온다.
                if (env.gitlabActionType) {
                    updateGitlabCommitStatus name: 'jenkins', state: 'failed'
                }
                notifyMattermost(':x: 실패')
            }
        }
        aborted {
            script {
                if (env.gitlabActionType) {
                    updateGitlabCommitStatus name: 'jenkins', state: 'canceled'
                }
            }
        }
        always {
            // 테스트 이미지는 매번 지워서 디스크에 쌓이지 않게 한다.
            sh 'docker rmi axcore-ci-test:${BUILD_NUMBER} || true'
        }
    }
}

// Mattermost DM 전송. 받는 사람 = 이 빌드를 일으킨 GitLab 사용자(MR 작성자, 또는 develop에 머지한 사람).
// SSAFY GitLab 아이디와 Mattermost 아이디가 같아서 "@아이디"로 바로 보낸다. 채널에는 올리지 않는다.
def notifyMattermost(String result) {
    def who = env.gitlabUserUsername
    if (!who) {
        echo 'Mattermost 알림 생략: 수동 빌드라 받는 사람이 없음'
        return
    }
    def what = env.gitlabMergeRequestIid
        ? "MR !${env.gitlabMergeRequestIid} (${env.gitlabSourceBranch} → ${env.gitlabTargetBranch})"
        : 'develop 머지 반영'
    def text = "${result} ${env.JOB_NAME} #${env.BUILD_NUMBER} · ${what}\n로그: ${env.BUILD_URL}console"
    def payload = groovy.json.JsonOutput.toJson([channel: "@${who}", text: text])
    echo "Mattermost DM → @${who}"
    // 비밀값(MM_WEBHOOK)은 Groovy 문자열에 섞지 않고 셸 환경변수로만 읽는다. 알림 실패가 빌드 결과를 바꾸면 안 되므로 || echo.
    withEnv(["MM_PAYLOAD=${payload}"]) {
        sh 'curl -sS -f -X POST -H "Content-Type: application/json" -d "$MM_PAYLOAD" "$MM_WEBHOOK" > /dev/null || echo "Mattermost 알림 실패"'
    }
}
