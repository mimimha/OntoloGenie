import { expect, test } from 'vitest'
import { suggestedQuestions } from './suggestions'

test('참고 객체가 없으면 기본 예시 다섯 개를 문장 그대로 보여 준다', () => {
  const questions = suggestedQuestions(null)

  expect(questions).toHaveLength(5)
  expect(questions[0]).toEqual({
    label: '현재 정지 중인 설비를 알려줘',
    text: '현재 정지 중인 설비를 알려줘',
  })
})

test('설비면 그 이름으로 채운 질문과 재배정 계획을 보여 준다', () => {
  const questions = suggestedQuestions({
    id: 301,
    name: '프레스 3호기',
    typeName: '설비',
  })

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
})

test('설비가 아니면 재배정 계획은 보여 주지 않는다', () => {
  const questions = suggestedQuestions({
    id: 1101,
    name: '프레스 작업',
    typeName: '작업지시',
  })

  expect(questions.map((question) => question.label)).not.toContain(
    '재배정 계획',
  )
  expect(questions[3].text).toBe('프레스 작업의 최근 실행 이력을 알려줘')
})
