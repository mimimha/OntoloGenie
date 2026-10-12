# Git 규칙

실행 권한은 [AI 협업 규칙](ai-collaboration.md)을 따른다.

## Commit Message 기본안

```text
<type>(<scope>): <summary>
```

이 형식은 팀이 검토할 기본안이다. scope 필수 여부와 언어·길이 제한은 아래 미결정 항목이며, 별도 합의 없이 강제하지 않는다. scope를 생략할 때는 `<type>: <summary>`로 작성할 수 있다.

| Type | 의미 |
| --- | --- |
| feat | 새로운 기능 |
| fix | 버그 수정 |
| refactor | 동작 변경 없는 구조 개선 |
| test | 테스트 추가 또는 수정 |
| docs | 문서 변경 |
| perf | 성능 개선 |
| chore | 기타 유지보수 |
| build | 빌드 설정 |
| ci | CI/CD 관련 변경 |

예시:

- `feat: 입력값 길이 검증 추가`
- `fix: 빈 값 입력 시 발생하는 오류 수정`
- `refactor: 중복된 검증 로직 분리`
- `test: 경계값 실패 케이스 추가`

scope 사용 여부가 미결정이므로 예시는 scope 없이 작성했다. 예시 문장의 언어도 설명용이며 summary 언어를 정하지 않는다.

## 기본 규칙

- 하나의 Commit에는 하나의 논리적 변경을 담는다. 관련 없는 변경은 함께 포함하지 않는다.
- 실제 변경 내용을 확인한 후 메시지를 작성한다. 의미 없는 제목이나 변경하지 않은 내용을 쓰지 않는다.
- 사용자가 요청하지 않은 Commit을 하지 않는다.
- 임의의 amend, rebase, force push를 하지 않는다. 이력 변경의 대상과 영향을 설명하고 해당 작업에 대한 명시적 승인을 받은 경우에만 진행한다.
- 다른 사람이 만든 기존 변경사항을 함부로 되돌리지 않는다.

## Team Decision Needed

다음 항목은 아직 미결정이다. 팀 합의 후 이 문서에 결정 내용과 적용 범위를 반영한다.

- scope 필수 여부
- Commit Message 언어
- 제목 길이 제한
- Branch Naming Convention
- Branch Strategy
- Squash Merge 사용 여부
- Merge 또는 Rebase 정책
