import type { ActionDefinition } from './types'

// 서버 AgentActionTools의 도구 이름 규칙(OpenAI 호환)과 같다. 맞지 않으면 Agent에게 주지 않는다.
const TOOL_NAME = /^[a-zA-Z0-9_-]{1,64}$/
// 서버 등록 규칙(API 088). 코드는 상세 경로에도 쓰인다.
const CODE_FORMAT = /^[A-Z][A-Z0-9_]*$/

export type AgentUsage =
  | { usable: true; label: string }
  | { usable: false; label: string; reason: string }

// 사용자별 권한은 질문한 사람마다 달라 여기서 판단하지 않는다.
export function agentUsage(
  definition: Pick<
    ActionDefinition,
    'actionMode' | 'isActive' | 'handlerReady' | 'code'
  >,
): AgentUsage {
  if (definition.actionMode === 'RECORD')
    return {
      usable: false,
      label: '관리 작업',
      reason: '화면에서 직접 하는 관리 작업이라 Agent가 쓰지 않습니다.',
    }
  if (!definition.isActive)
    return {
      usable: false,
      label: '사용 안 함',
      reason: '사용하지 않는 기능이라 Agent가 쓰지 않습니다.',
    }
  if (!definition.handlerReady)
    return {
      usable: false,
      label: '구현 대기',
      reason:
        '개발자가 아직 기능을 구현하지 않았습니다. 배포되면 Agent가 쓸 수 있습니다.',
    }
  if (!TOOL_NAME.test(definition.code))
    return {
      usable: false,
      label: '코드 확인 필요',
      reason:
        '코드가 도구 이름 규칙(영문·숫자·_·-, 64자 이하)에 맞지 않아 Agent에게 줄 수 없습니다.',
    }
  return { usable: true, label: 'Agent 사용 가능' }
}

export function codeError(code: string): string {
  if (!code) return '코드를 입력해 주세요.'
  if (!CODE_FORMAT.test(code))
    return '코드는 영문 대문자로 시작하고 대문자·숫자·밑줄(_)만 쓸 수 있습니다.'
  if (!TOOL_NAME.test(code)) return '코드는 64자 이하로 입력해 주세요.'
  return ''
}
