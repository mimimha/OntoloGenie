// Jenkins 파이프라인 정의. Job 1(axcore-mr-check)과 Job 2(axcore-ci-test)가 같은 파일을 쓴다.
// - Job 1: MR이 열리거나 MR 브랜치에 push되면 실행. gitlabSourceBranch = MR 원본 브랜치.
// - Job 2: develop에 push(머지)되면 실행. gitlabSourceBranch = develop.
// 이 파일(설명서)은 항상 develop에서 읽고, 검사할 코드는 Checkout 단계에서 받는다.
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
            }
        }
        failure {
            script {
                // GitLab MR 화면에 실패 표시. 클릭하면 이 빌드 로그로 온다.
                if (env.gitlabActionType) {
                    updateGitlabCommitStatus name: 'jenkins', state: 'failed'
                }
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
