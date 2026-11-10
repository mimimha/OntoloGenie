# 백엔드 테스트 초기화

`TestcontainersConfiguration`은 테스트 JVM당 PostgreSQL 18.6 서버를 한 번 시작한다. 서버의 `test_template` DB에 실제 `db/migration`을 Flyway로 적용한 뒤, 새 Spring 컨텍스트마다 독립적인 임시 DB를 복제한다. 컨텍스트마다 애플리케이션 초기화와 Flyway 검증은 유지하며 fixture와 테이블 변경은 다른 컨텍스트로 전파되지 않는다. migration 자체의 변경·추가 여부는 매 실행의 새 템플릿과 기존 DatabaseMigrationTests에서 검증한다.

서버는 Spring Bean으로 등록하지 않는다. 캐시 퇴출이나 `@DirtiesContext`로 컨텍스트가 닫혀도 다른 컨텍스트의 DB가 중단되지 않으며 JVM 종료 후 Testcontainers Ryuk이 임시 컨테이너와 DB를 정리한다. 실행 간 컨테이너 재사용(`withReuse`)은 사용하지 않는다. `bootTestRun`도 같은 설정을 사용한다.

PostgreSQL role은 클러스터 전체에서 공유된다. role·publication을 변경하고 컨테이너 주소를 원천 접속 정보로 사용하는 SourceConnectionCheckJobTests·SyncRunJobTests는 `SourceTestcontainersConfiguration`으로 기존 전용 컨테이너 격리를 유지한다. 직접 만든 원천 PostgreSQL·Kafka 컨테이너도 이번 변경 대상이 아니다.

`spring.test.context.cache.maxSize=8`은 기존 Jenkins 힙 부족 재발을 막기 위해 유지한다. Mock·프로필·업무 속성은 강제로 통합하지 않는다. CSRF 후처리기처럼 같은 컨텍스트의 테스트 지원 객체를 바꾸는 경우가 있어, 트랜잭션 rollback만으로 모든 시험 격리를 보장할 수 없다. `DataJpaTest`는 시험용 `config/application.yml`에서 임베디드 DB 자동 교체를 끄고 컨텍스트별 PostgreSQL 연결을 그대로 사용한다.

AgentConversationApiTests·ProposalDecisionApiTests·ProposalObjectTypeApprovalApiTests는 `test.context=csrf-api`를 공유한다(3개 컨텍스트 → 1개). 모두 같은 api 프로필·실제 서비스·MockMvc를 사용하고 CSRF 후처리기를 사용한다. fixture가 UUID 또는 새로 생성한 계정/후보 ID로 격리되어 있으며 조회·건수 검증도 해당 ID를 기준으로 한다. 실제 로그인·쿠키 CSRF 시험은 이 그룹에 합치지 않고, 서로 다른 Mock/Spy·Clock·내부 테스트 설정을 가진 클래스도 유지한다.

## 검증

- `TestDatabaseIsolationTests`: 같은 서버 사용, 독립 DB, 컨텍스트 종료 후 서버 유지, 빈 템플릿의 migration 이력 확인.
- `./gradlew build`: 기존 전체 통합 테스트와 포맷 검사를 유지한다.
- 실제 성능 비교는 같은 커밋·동일 JVM/호스트 조건에서 수행한다. 테스트 실행이 UP-TO-DATE로 생략된 결과와 비교하지 않는다. JUnit XML의 테스트 시간 합은 전체 wall time과 다르며 둘의 차이 전체를 DB 기동 시간이라고 단정하지 않는다.

공유 서버 기동과 템플릿 마이그레이션은 한 번만 발생하지만 Spring 컨텍스트 초기화 자체는 남아 있다. 서버 환경에서의 단축 시간은 Jenkins 실측 후 기록한다.
