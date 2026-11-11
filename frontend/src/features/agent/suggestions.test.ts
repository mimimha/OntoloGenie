import { expect, test } from 'vitest'
import { suggestedQuestions } from './suggestions'

const PRESS = { id: 301, name: '프레스 3호기', typeName: '설비' }
const REASSIGNMENT_PLAN =
  'P103에 배정된 남은 작업을 다른 설비로 옮기는 재배정 계획을 만들어줘'
// 역할과 관계없이 앞에 오는 세 문장. 배포 시연 데이터의 코드로 쓴다.
const COMMON = [
  'P103에 연결된 비가동 기록 보여줘',
  'P103에 연결된 정보는 뭐가 있어?',
  'JB2610-0138 작업지시 진행 상황 알려줘',
]

test('참고 객체가 없으면 조회만 하는 역할에는 시연 데이터로 답할 수 있는 다섯 문장을 보여 준다', () => {
  for (const role of ['VIEWER', 'MAINTENANCE'] as const) {
    const questions = suggestedQuestions(null, role)

    expect(questions.map((question) => question.text)).toEqual([
      ...COMMON,
      'P101 상세 정보 알려줘',
      'FA-0006 장부가와 취득가 알려줘',
    ])
    expect(questions[0]).toEqual({
      label: 'P103 비가동',
      text: 'P103에 연결된 비가동 기록 보여줘',
    })
  }
})

test('참고 객체가 없으면 계획 권한이 있는 역할에는 재배정 계획 문장을 넣는다', () => {
  expect(suggestedQuestions(null, 'PLANNER').map((q) => q.text)).toEqual([
    ...COMMON,
    'P101 상세 정보 알려줘',
    REASSIGNMENT_PLAN,
  ])
  expect(suggestedQuestions(null, 'ADMIN').map((q) => q.text)).toEqual([
    ...COMMON,
    'FA-0006 장부가와 취득가 알려줘',
    REASSIGNMENT_PLAN,
  ])
})

test('기본 문장에는 답하지 못하는 질문(종류 전체 목록, 범위 없는 기간)을 넣지 않는다', () => {
  // 2026-11-11 배포 확인: 종류 전체 목록은 "아직 조회할 수 없습니다", "최근"은 기간을 되묻는다.
  for (const role of ['VIEWER', 'MAINTENANCE', 'PLANNER', 'ADMIN'] as const) {
    for (const { text } of suggestedQuestions(null, role)) {
      expect(text).not.toMatch(/최근|현재 .*중인|설비별/)
    }
  }
})

test('역할을 모르면 조회만 하는 문장을 보여 준다', () => {
  const texts = suggestedQuestions(null, null).map((q) => q.text)

  expect(texts).toHaveLength(5)
  expect(texts).not.toContain(REASSIGNMENT_PLAN)
})

test('계획 권한이 있는 역할이면 설비 이름으로 채운 질문과 재배정 계획을 보여 준다', () => {
  for (const role of ['PLANNER', 'ADMIN'] as const) {
    const questions = suggestedQuestions(PRESS, role)

    expect(questions.map((question) => question.label)).toEqual([
      '상태 확인',
      '연결 관계',
      '원천 근거',
      '실행 이력',
      '재배정 계획',
    ])
    expect(questions[0].text).toBe(
      '프레스 3호기의 현재 상태와 주요 속성을 알려줘',
    )
    expect(questions[4].text).toBe(
      '프레스 3호기에 배정된 남은 작업을 다른 설비로 옮기는 재배정 계획을 만들어줘',
    )
  }
})

test('계획 권한이 없으면 설비여도 재배정 계획은 보여 주지 않는다', () => {
  for (const role of ['VIEWER', 'MAINTENANCE', null] as const) {
    expect(
      suggestedQuestions(PRESS, role).map((question) => question.label),
    ).toEqual(['상태 확인', '연결 관계', '원천 근거', '실행 이력'])
  }
})

test('설비가 아니면 재배정 계획은 보여 주지 않는다', () => {
  const questions = suggestedQuestions(
    { id: 1101, name: '프레스 작업', typeName: '작업지시' },
    'PLANNER',
  )

  expect(questions.map((question) => question.label)).not.toContain(
    '재배정 계획',
  )
  expect(questions[3].text).toBe('프레스 작업의 최근 실행 이력을 알려줘')
})
