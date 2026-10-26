// Jenkins가 develop에 push가 올 때 저장소에서 읽어 실행하는 빌드 절차입니다.
// 지금은 3-9 테스트용입니다. 5-7에서 실제 앱 빌드·배포 단계로 확장합니다.
pipeline {
    agent any

    options {
        // 빌드 기록은 최근 10개만 보관해 디스크를 아낍니다.
        buildDiscarder(logRotator(numToKeepStr: '10'))
    }

    stages {
        stage('Checkout') {
            steps {
                // 코드는 Jenkins가 이 파일을 읽을 때 이미 받아 왔습니다.
                // 어떤 커밋을 받았는지 기록만 남깁니다.
                sh 'git log -1 --oneline'
            }
        }

        stage('Hello') {
            steps {
                echo "Hello World from axcore CI (build #${env.BUILD_NUMBER})"
            }
        }

        stage('Docker build test') {
            steps {
                // Jenkins 컨테이너가 docker.sock으로 서버의 Docker를 쓸 수 있는지 확인합니다.
                sh 'docker build -t axcore-ci-test:${BUILD_NUMBER} infra/ci-test'
                sh 'docker run --rm axcore-ci-test:${BUILD_NUMBER}'
            }
        }

        stage('Disk usage') {
            steps {
                // 빌드 이미지가 디스크를 얼마나 쓰는지 매번 기록합니다.
                sh 'docker system df'
            }
        }
    }

    post {
        always {
            // 테스트 이미지는 매번 지워서 디스크에 쌓이지 않게 합니다.
            sh 'docker rmi axcore-ci-test:${BUILD_NUMBER} || true'
        }
    }
}
