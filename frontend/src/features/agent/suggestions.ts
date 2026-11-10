import type { ReferenceObject } from './types'

// 추천 질문(150). 문장은 화면에서 만든다(11-10 결정, API 086은 쓰지 않음).
// 참고 객체가 있으면 그 객체 이름으로 채운 문장을, 없으면 기본 예시를 보여 준다.
export type SuggestedQuestion = {
  // 버튼에 보일 짧은 이름
  label: string
  // 누르면 입력란에 채워질 문장
  text: string
}

// 재배정 계획은 설비에서만 의미가 있다.
const EQUIPMENT_TYPE_NAME = '설비'

// 참고 객체가 없을 때 보여 줄 기본 예시(API 086 명세 예시와 같다).
const GENERAL_QUESTIONS = [
  '현재 정지 중인 설비를 알려줘',
  'PR-03의 최근 비가동 이력을 보여줘',
  '현재 진행 중인 작업지시를 보여줘',
  '설비별 최근 생산 실적을 알려줘',
  '최근 정합성 문제 현황을 보여줘',
]

export function suggestedQuestions(
  reference: ReferenceObject | null,
): SuggestedQuestion[] {
  if (!reference) {
    return GENERAL_QUESTIONS.map((text) => ({ label: text, text }))
  }
  const { name } = reference
  const questions: SuggestedQuestion[] = [
    { label: '상태 확인', text: `${name}의 현재 상태와 주요 속성을 알려줘` },
    { label: '연결 관계', text: `${name}에 연결된 객체와 관계를 알려줘` },
    { label: '원천 근거', text: `${name}의 원천 근거를 알려줘` },
    { label: '실행 이력', text: `${name}의 최근 실행 이력을 알려줘` },
  ]
  if (reference.typeName === EQUIPMENT_TYPE_NAME) {
    questions.push({
      label: '재배정 계획',
      text: `${name}에 배정된 남은 작업을 다른 설비로 옮기는 재배정 계획을 만들어줘`,
    })
  }
  return questions
}
