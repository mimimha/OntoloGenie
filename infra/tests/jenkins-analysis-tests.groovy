// 실제 Jenkinsfile의 단계 조건·비동기 전달 함수를 실행한다. Jenkins와 Docker에는 접근하지 않는다.
def source = new File('Jenkinsfile').getText('UTF-8')
def stages = [:]
def environment = [JOB_NAME: 'axcore-deploy', IS_DEPLOY_JOB: 'true', IS_SONAR_JOB: 'false',
    ENABLE_SONARQUBE: 'true', SONARQUBE_READY: 'true', DEPLOY_BACKEND: 'true',
    DEPLOY_FRONTEND: 'false', DEPLOY_AI: 'false', CHECK_REF: 'origin/develop']
def parameters = [RUN_SONARQUBE: false, ANALYSIS_RECIPIENT: 'onteam']
def invoked = []
def script = new GroovyShell().parse(source)
script.binding = new Binding([
    env: environment, params: parameters, currentBuild: [currentResult: 'SUCCESS'],
    pipeline: { Closure body ->
        body.delegate = new Object() {
            def agent(Object ignored) {}
            def options(Closure ignored) {}
            def parameters(Closure ignored) {}
            def environment(Closure ignored) {}
            def post(Closure ignored) {}
            def stages(Closure list) { list() }
            def stage(String name, Closure stageBody) { stages[name] = stageBody }
            def getAny() { 'any' }
        }
        body.resolveStrategy = Closure.DELEGATE_FIRST
        body()
    },
    sh: { Map args -> 'a' * 40 },
    string: { Map args -> args }, booleanParam: { Map args -> args },
    build: { Map args -> invoked << args },
    echo: { Object ignored -> }
])
script.run()

def allowed = { String name ->
    def result = false
    def body = stages[name].rehydrate(new Object() {
        def when(Closure condition) {
            condition.delegate = new Object() {
                def expression(Closure predicate) { result = predicate() }
            }
            condition.resolveStrategy = Closure.DELEGATE_FIRST
            condition()
        }
        def steps(Closure ignored) {}
        def post(Closure ignored) {}
    }, script, script)
    body.resolveStrategy = Closure.DELEGATE_FIRST
    body()
    result
}

assert !allowed('SonarQube analysis')
assert allowed('Queue SonarQube analysis')
script.binding.currentBuild.currentResult = 'FAILURE'
assert !allowed('Queue SonarQube analysis')
script.binding.currentBuild.currentResult = 'SUCCESS'
println 'PASS: 배포 Job에서는 분석을 실행하지 않고 성공한 배포만 분석을 예약'

environment.gitlabActionType = 'PUSH'
environment.gitlabSourceBranch = 'develop'
environment.gitlabUserUsername = 'onteam'
environment.gitlabAfter = 'a' * 40
script.queueSonarAnalysis()
assert invoked.size() == 1
assert invoked[0].job == 'axcore-sonarqube'
assert !invoked[0].wait && !invoked[0].propagate
def forwarded = invoked[0].parameters.collectEntries { [(it.name): it.value] }
assert forwarded.ANALYSIS_COMMIT == 'a' * 40
assert forwarded.ANALYSIS_RECIPIENT == 'onteam'
assert forwarded.ANALYZE_BACKEND && !forwarded.ANALYZE_FRONTEND && !forwarded.ANALYZE_AI
parameters.RUN_SONARQUBE = true
environment.gitlabAfter = 'b' * 40
script.queueSonarAnalysis()
def allServices = invoked[1].parameters.collectEntries { [(it.name): it.value] }
assert allServices.ANALYZE_BACKEND && allServices.ANALYZE_FRONTEND && allServices.ANALYZE_AI
assert allServices.ANALYSIS_COMMIT == 'a' * 40
assert allServices.ANALYSIS_RECIPIENT == ''
parameters.RUN_SONARQUBE = false
println 'PASS: 전체 커밋·대상 서비스·알림 수신자를 비동기 Job에 고정 전달'

environment.JOB_NAME = 'axcore-sonarqube'
environment.IS_DEPLOY_JOB = 'false'
environment.IS_SONAR_JOB = 'true'
environment.CHECK_REF = 'a' * 40
environment.CHECK_BACKEND = 'true'
assert allowed('SonarQube analysis')
assert !allowed('Backend check')
assert !allowed('Prepare deployment credentials')
assert !allowed('Deploy configuration')
assert !allowed('Queue SonarQube analysis')
assert script.sonarGateRecipient() == 'onteam'
parameters.ANALYSIS_RECIPIENT = 'invalid/recipient'
assert script.sonarGateRecipient() == null
println 'PASS: 분석 Job은 MR 검사·운영 설정 반영·재귀 트리거 없이 실행하고 수신자 검증'

environment.JOB_NAME = 'axcore-mr-check'
environment.IS_SONAR_JOB = 'false'
environment.CHECK_REF = 'origin/develop'
environment.gitlabActionType = null
parameters.RUN_SONARQUBE = true
assert allowed('SonarQube analysis')
environment.gitlabActionType = 'MERGE'
assert !allowed('SonarQube analysis')
println 'PASS: MR Job의 develop 수동 전체 분석만 유지하고 일반 MR 분석은 실행하지 않음'

def commands = []
script.metaClass.restoreTestResults = { String component -> true }
script.metaClass.runFrontendCommands = { String command -> commands << command }
script.metaClass.runAiCommands = { String command -> commands << command }
script.binding.setVariable('dir', { String path, Closure body -> body() })
script.binding.setVariable('sh', { Object command -> commands << command })
environment.IS_SONAR_JOB = 'true'
['backend', 'frontend', 'ai'].each { script.prepareSonarResults(it) }
assert commands.isEmpty()
println 'PASS: 일치하는 MR 결과가 있으면 세 서비스 모두 테스트 재실행 없음'
script.metaClass.restoreTestResults = { String component -> false }
['backend', 'frontend', 'ai'].each { script.prepareSonarResults(it) }
assert commands == ['./gradlew --no-daemon jacocoTestReport', 'npm run test:coverage', 'pytest -q --cov --cov-report=xml']
println 'PASS: MR 결과가 없거나 복원 실패하면 해당 서비스 커버리지 테스트 재실행'
