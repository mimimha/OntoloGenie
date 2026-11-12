import { expect, test } from 'vitest'
import { agentUsage, codeError } from './agentUsage'

const ready = {
  actionMode: 'EXECUTE',
  isActive: true,
  handlerReady: true,
  code: 'ESTIMATE_COMPLETION',
} as const

test('업무 Action이 사용 중이고 구현돼 있으면 Agent가 쓸 수 있다', () => {
  expect(agentUsage(ready)).toEqual({ usable: true, label: 'Agent 사용 가능' })
})

test.each([
  [{ ...ready, actionMode: 'RECORD' as const }, '관리 작업'],
  [{ ...ready, isActive: false }, '사용 안 함'],
  [{ ...ready, handlerReady: false }, '구현 대기'],
  [{ ...ready, code: 'A'.repeat(65) }, '코드 확인 필요'],
])('Agent가 쓰지 못하면 이유를 알려 준다: %o → %s', (definition, label) => {
  const usage = agentUsage(definition)
  expect(usage.usable).toBe(false)
  expect(usage.label).toBe(label)
})

test('관리 작업은 사용 여부·구현 상태보다 먼저 판단한다', () => {
  expect(
    agentUsage({
      ...ready,
      actionMode: 'RECORD',
      isActive: false,
      handlerReady: false,
    }).label,
  ).toBe('관리 작업')
})

test.each([
  ['', '코드를 입력해 주세요.'],
  ['estimate', '영문 대문자로 시작'],
  ['1ESTIMATE', '영문 대문자로 시작'],
  ['ESTIMATE-COMPLETION', '영문 대문자로 시작'],
  ['A'.repeat(65), '64자 이하'],
])('코드 %s는 등록 전에 거절한다', (code, message) => {
  expect(codeError(code)).toContain(message)
})

test('서버 등록 규칙과 도구 이름 규칙을 모두 지키면 통과한다', () => {
  expect(codeError('ESTIMATE_COMPLETION')).toBe('')
  expect(codeError('A'.repeat(64))).toBe('')
})
