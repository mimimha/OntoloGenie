# Git 규칙

실행 권한은 [AI 협업 규칙](ai-collaboration.md)을 따른다. 팀 가이드 원본은 [C106 개발 가이드](../../docs/development-guide.md) 2~4절이다.

## Branch

| Branch | 용도 | MR 대상 |
| --- | --- | --- |
| `main` | 안정 버전 | `develop`에서 MR |
| `develop` | 기능 통합, 병합 시 자동 배포 | 작업 Branch에서 MR |
| `feature/*` | 신규 기능 | `develop` |
| `fix/*` | 개발 중 버그 수정 | `develop` |
| `hotfix/*` | 긴급 오류 수정 | 대상 Branch를 확인한다 |
| `refactor/*` | 기능 변경 없는 구조 개선 | `develop` |
| `docs/*` | 문서 추가·수정 | `develop` |
| `test/*` | 테스트 추가·수정 | `develop` |
| `chore/*` | 설정·개발 환경 변경 | `develop` |

이름 형식은 `{type}/{이슈번호}-{description}`이다.

- 이슈 번호는 Jira 키 `S15P31C106-123`의 숫자 `123`만 쓴다. 예: `feature/123-action-execution`
- Jira 이슈가 없는 작업은 번호를 생략한다. 예: `docs/development-guide`
- 소문자 영문, 숫자, 하이픈만 쓴다.
- 한 Branch에서는 하나의 작업 목표만 처리한다.

`release` Branch는 만들지 않는다. `main`과 `develop`에는 직접 push하지 않는다.

## Commit Message

```text
<type>: <summary>
```

- scope는 쓰지 않는다.
- summary는 한국어로 쓴다. type은 영어로 쓴다.
- 제목은 50자 이내를 권장한다. 강제하지 않는다.

| Type | 의미 |
| --- | --- |
| feat | 신규 기능 |
| fix | 버그 수정 |
| refactor | 동작 변경 없는 구조 개선 |
| test | 테스트 추가 또는 수정 |
| docs | 문서 변경 |
| style | 포맷·코드 스타일 수정 |
| perf | 성능 개선 |
| build | 의존성·빌드 설정 변경 |
| ci | Jenkins·CI/CD 변경 |
| chore | 기타 관리 작업 |

예시:

- `feat: 재배정 계획 생성 API 구현`
- `fix: 중복 수집 요청 처리 오류 수정`
- `test: 승인 시 버전 불일치 테스트 추가`

## MR

- 제목 형식은 `[JiraKey] <type>: <summary>`이다. Jira 키는 전체를 쓴다. 예: `[S15P31C106-123] feat: 재배정 계획 생성 API 구현`
- Jira 이슈가 없는 작업은 키를 붙이지 않는다. 예: `docs: 개발 가이드 추가`
- 일반 기능 MR은 Squash Merge로 병합한다.
- 병합 조건은 리뷰 승인, 미해결 필수 의견 없음, MR 파이프라인과 관련 테스트 통과, 충돌 없음, API·DB 변경의 문서 반영이다.

## 기본 규칙

- 하나의 Commit에는 하나의 논리적 변경을 담는다. 관련 없는 변경은 함께 포함하지 않는다.
- 기능 구현과 무관한 코드 정리를 섞지 않는다. 테스트는 관련 기능과 같은 Commit에 포함할 수 있다.
- 디버깅 코드와 불필요한 주석은 Commit하지 않는다.
- 실제 변경 내용을 확인한 후 메시지를 작성한다. 의미 없는 제목이나 변경하지 않은 내용을 쓰지 않는다.
- 사용자가 요청하지 않은 Commit을 하지 않는다.
- 임의의 amend, rebase, force push를 하지 않는다. 공유 Branch에는 force push를 하지 않는다. 이력 변경은 대상과 영향을 설명하고 해당 작업에 대한 명시적 승인을 받은 경우에만 진행한다.
- 다른 사람이 만든 기존 변경사항을 함부로 되돌리지 않는다.

## Team Decision Needed

다음 항목은 아직 미결정이다. 팀 합의 후 이 문서와 개발 가이드에 반영한다.

- 작업 Branch에 `develop` 변경을 반영할 때 Merge와 Rebase 중 무엇을 쓸지
