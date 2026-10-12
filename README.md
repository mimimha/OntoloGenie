# AI 기반 온톨로지 레이어 구축 및 운영 플랫폼

> 서로 다른 업무 시스템의 데이터를 하나의 의미 체계로 연결하고, AI가 근거를 바탕으로 정보를 조회하고 승인된 업무 Action을 실행할 수 있도록 하는 플랫폼

**SSAFY 기업연계 프로젝트 | 협력기업: 에이엑스코어 | 개발 진행 중**

## 1. 프로젝트 소개

ERP, MES, 정비 기록, 업무 메모처럼 서로 다른 시스템에 저장된 데이터는 식별자와 표현 방식이 달라 하나의 업무 맥락으로 파악하기 어렵습니다. 기존의 단순 조회나 대시보드에서 나아가, 데이터 간 의미적 관계를 정의하고 실제 업무 실행까지 연결하는 **온톨로지 기반 운영 플랫폼**을 개발합니다.

본 프로젝트는 가상 원천 데이터를 활용합니다. 제조 현장의 ERP/MES를 주요 예시로 삼되, 구체적인 적용 도메인과 데이터셋은 팀 설계에 따라 조정할 수 있습니다.

### 핵심 목표

- **데이터 통합:** 원천 시스템의 스키마와 식별자 차이를 분석하고 업무 객체 간 관계를 연결합니다.
- **온톨로지 관리:** Object Type, Property, Link Type, Action Definition을 조회 가능한 메타데이터로 관리합니다.
- **AI 제안과 사람의 승인:** AI가 객체·속성·관계 후보를 근거 및 신뢰도와 함께 제안하고, 관리자가 검토한 후 반영합니다.
- **안전한 업무 실행:** AI Agent는 허용된 조회 Tool과 Action을 사용하며, 데이터 변경은 Action Handler의 유효성·권한 검증 및 감사 로그를 거칩니다.

## 2. 주요 기능

| 영역 | 기능 |
| --- | --- |
| 원천 데이터 연동 | 가상 업무 시스템 연결, 스키마 인식, 데이터 프로파일링 |
| 데이터 통합 | 서로 다른 시스템의 식별자 매핑, 테이블 관계 분석, 정합성 검증 |
| AI Ontology 제안 | 정형 데이터와 비정형 문서 분석을 통한 Object / Property / Link 후보 생성 |
| HITL | 제안의 근거·신뢰도 확인, 승인·거절, 승인 결과의 온톨로지 반영 |
| Ontology Core | Object Type / Property / Link Type / Action Definition 및 객체·관계 인스턴스 관리 |
| Action Engine | Action Dispatcher / Handler, 입력·권한·상태 검증, 실행 결과 및 Audit Log 기록 |
| Ontology Explorer | 객체 검색, 속성 조회, 관계 탐색, Action 실행, 감사 이력 확인 |
| AI Agent | 자연어 질의, 근거 제시, Tool Calling을 통한 Action 제안 및 실행 요청 |

## 3. 시스템 구성 (설계안)

```text
가상 원천 데이터 (ERP / MES / 문서 / 정비 기록 등)
                      |
                      v
        원천 데이터 분석 · 스키마 매핑
                      |
                      v
         AI Ontology 제안기 (FastAPI)
          Object / Property / Link 후보
                 + 근거 / 신뢰도
                      |
                      v
           Proposal 저장 · HITL 승인
                      |
                      v
       Ontology Core (Spring Boot)
    Metadata / Object Instance / Link Instance
                      |
          +-----------+------------+
          |                        |
          v                        v
   Ontology Explorer         AI Agent (Tool Calling)
                                   |
                                   v
                           Action Dispatcher
                                   |
                       권한 · 입력 · 상태 검증
                                   |
                                   v
                            Action Handler
                                   |
                          업무 처리 + Audit Log

       PostgreSQL: 메타데이터 · 인스턴스 · 제안 · 감사 이력
       React UI: 탐색 · 승인 · Agent 대화 · 실행 내역
```

### 설계 원칙

1. **메타모델과 인스턴스를 구분합니다.** 예를 들어 `Equipment`는 Object Type이고 `EQ-001`은 해당 타입의 실제 객체 인스턴스입니다.
2. **AI의 제안은 자동 반영하지 않습니다.** 후보는 Proposal로 저장하고 관리자의 승인 이후 온톨로지에 등록합니다.
3. **Action 정의와 실행 코드를 분리합니다.** `ActionDefinition`은 실행 가능한 업무의 메타데이터이고, `ActionHandler`는 실제 비즈니스 로직을 수행합니다.
4. **Agent가 데이터 변경 로직을 우회하지 않도록 합니다.** 변경성 요청은 Action Dispatcher와 Handler를 통해 실행하고 실행 결과를 감사 기록으로 남깁니다.

## 4. 주요 데이터 모델 (초안)

| 구분 | 주요 개념 | 설명 |
| --- | --- | --- |
| 메타데이터 | ObjectType | 객체 종류와 비즈니스 의미 정의 |
| 메타데이터 | PropertyDefinition | 객체에 허용되는 속성 정의 |
| 메타데이터 | LinkType | 객체 간 관계의 의미와 연결 규칙 정의 |
| 메타데이터 | ActionDefinition | 실행 가능한 업무, 입력 스키마, 권한 및 Handler 연결 정보 정의 |
| 인스턴스 | ObjectInstance | 실제 설비, 작업지시 등 개별 업무 객체 |
| 인스턴스 | LinkInstance | 개별 객체 사이의 연결 관계 |
| 검토 | OntologyProposal | AI 제안 후보, 근거, 신뢰도 및 승인 상태 |
| 실행 이력 | ActionAuditLog | 요청 주체, 대상, 파라미터, 실행 결과 등의 감사 정보 |

> 실제 테이블명과 컬럼 구조는 ERD 및 API 계약 확정 후 반영합니다.

## 5. 기술 스택

아래 항목은 **요구명세와 현재 설계 방향을 기준으로 한 예정 스택**이며, 구현 과정에서 일부 변경될 수 있습니다.

| 영역 | 기술 | 상태 |
| --- | --- | --- |
| Backend Core / Action / HITL | Java, Spring Boot, Spring Data JPA | 적용 예정 |
| AI Ontology | Python, FastAPI, Pydantic, Pandas | 적용 예정 |
| AI Agent | Spring AI, LLM Tool Calling | 검토 중 |
| LLM | 외부 LLM API (SSAFY GMS 제공 모델 등) | 모델 선정 예정 |
| Frontend | React, TypeScript | 적용 예정 |
| Database | PostgreSQL | 요구명세 기준 |
| Cache / 임시 상태 | Redis | 활용 목적 검토 중 |
| Infra | Docker, CI/CD | 세부 도구 및 배포 구성 협의 중 |

## 6. 사용자 시나리오 (예시)

**질문:** “EQ-001 설비가 관련된 작업지시를 확인하고, 문제가 있으면 정비 요청을 생성해 줘.”

1. AI Agent가 온톨로지 조회 Tool을 이용해 설비와 연결된 작업지시를 탐색합니다.
2. 조회된 데이터와 근거를 사용자에게 설명합니다.
3. 정비 요청 생성에 필요한 Action과 입력값을 제안합니다.
4. 실행 요청이 들어오면 Backend가 권한·입력값·업무 상태를 검증합니다.
5. Action Handler가 정비 요청을 처리하고 결과와 감사 로그를 기록합니다.

*위 사례는 기능을 설명하기 위한 예시이며, 실제 시연 도메인과 Action은 MVP 설계 과정에서 확정합니다.*

## 7. 팀 구성

| 담당 | 주요 책임 |
| --- | --- |
| Backend Core / HITL | 온톨로지 메타모델, 객체·관계 인스턴스, Proposal 저장 및 승인 반영 |
| Backend Action | ActionDefinition, Dispatcher / Handler, 권한·유효성 검증, Audit |
| AI Ontology | 원천 데이터 분석, 객체·속성·관계 제안, 근거·신뢰도 생성 |
| AI Agent | LLM 연동, Tool Calling, 조회 및 Action 실행 연결 |
| Frontend | Ontology Explorer, HITL 승인, Agent 대화 및 감사 조회 화면 |
| Infra / Integration | 컨테이너 환경, CI/CD, 서비스 배포 및 통합 환경 관리 |

*세부 담당 영역과 인프라 지원 범위는 팀 협의에 따라 조정될 수 있습니다.*

## 8. 개발 계획

- [ ] 가상 원천 데이터 및 핵심 업무 시나리오 확정
- [ ] 공통 메타모델·인스턴스·Proposal 스키마 설계
- [ ] Ontology Core 및 HITL API 구현
- [ ] AI Ontology 제안 PoC 및 Backend 연동
- [ ] Action Dispatcher / Handler / Audit 구현
- [ ] AI Agent Tool Calling 및 Action 연동
- [ ] Explorer·승인·Agent UI 통합
- [ ] 테스트, 성능 측정 및 최종 시연

## 9. 개발 환경 및 실행 방법

개발 환경과 실행 명령은 서비스별 디렉터리 구조, 환경변수 예제 및 Docker 구성이 확정된 후 추가할 예정입니다.

LLM API Key를 비롯한 민감 정보는 저장소에 커밋하지 않고 서버 환경변수 또는 별도 Secret으로 관리합니다.

---

> 본 저장소의 README는 개발 진행에 따라 실제 구현 범위, 실행 방법 및 시연 결과를 지속적으로 갱신합니다.
