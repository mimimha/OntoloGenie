import { expect, test } from 'vitest'
import { suggestedQuestions } from './suggestions'

const PRESS = { id: 301, name: '프레스 3호기', typeName: '설비' }
const REASSIGNMENT_PLAN =
  'PR-03에 배정된 남은 작업을 다른 설비로 옮기는 재배정 계획을 만들어줘'

test('참고 객체가 없으면 조회만 하는 역할에는 086 예시 다섯 개를 문장 그대로 보여 준다', () => {
  for (const role of ['VIEWER', 'MAINTENANCE'] as const) {
    const questions = suggestedQuestions(null, role)

    expect(questions.map((question) => question.text)).toEqual([
      '현재 정지 중인 설비를 알려줘',
      'PR-03의 최근 비가동 이력을 보여줘',
      '현재 진행 중인 작업지시를 보여줘',
      '설비별 최근 생산 실적을 알려줘',
      '최근 정합성 문제 현황을 보여줘',
    ])
    expect(questions[0]).toEqual({
      label: '정지 설비',
      text: '현재 정지 중인 설비를 알려줘',
    })
  }
})

test('참고 객체가 없으면 계획 권한이 있는 역할에는 재배정 계획 문장을 넣는다', () => {
  expect(suggestedQuestions(null, 'PLANNER').map((q) => q.text)).toEqual([
    '현재 정지 중인 설비를 알려줘',
    'PR-03의 최근 비가동 이력을 보여줘',
    '현재 진행 중인 작업지시를 보여줘',
    '설비별 최근 생산 실적을 알려줘',
    REASSIGNMENT_PLAN,
  ])
  expect(suggestedQuestions(null, 'ADMIN').map((q) => q.text)).toEqual([
    '현재 정지 중인 설비를 알려줘',
    'PR-03의 최근 비가동 이력을 보여줘',
    '현재 진행 중인 작업지시를 보여줘',
    '최근 정합성 문제 현황을 보여줘',
    REASSIGNMENT_PLAN,
  ])
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
