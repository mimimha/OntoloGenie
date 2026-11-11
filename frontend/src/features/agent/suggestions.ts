import type { Account } from '../auth/types'
import type { ReferenceObject } from './types'

// 추천 질문(150). 문장은 화면에서 만든다(11-10 결정, API 086은 MVP에서 쓰지 않음).
// 참고 객체가 있으면 그 객체 이름으로 채운 문장을, 없으면 로그인한 사용자 역할에 맞는 고정 문장 5개를 보여 준다.
// 권한 없는 기능은 추천하지 않는다.
export type SuggestedQuestion = {
  // 버튼에 보일 짧은 이름
  label: string
  // 누르면 입력란에 채워질 문장
  text: string
}

type RoleCode = Account['roleCode']

// 재배정 계획은 설비에서만 의미가 있다.
const EQUIPMENT_TYPE_NAME = '설비'

// 재배정 계획 생성 권한(PLAN_CREATE)이 있는 역할. 화면은 역할만 알고 세부 권한은 받지 않으므로, 서버의 역할별 권한
// (V10__add_action_definitions.sql)과 같게 맞춘다. 서버에서 바꾸면 여기도 함께 바꾼다.
const PLAN_CREATE_ROLES: readonly RoleCode[] = ['ADMIN', 'PLANNER']

// 버튼에는 짧은 이름을 보여 한 줄에 들어가게 하고, 누르면 문장 전체를 입력란에 채운다.
const STOPPED_EQUIPMENT: SuggestedQuestion = {
  label: '정지 설비',
  text: '현재 정지 중인 설비를 알려줘',
}
const DOWNTIME_HISTORY: SuggestedQuestion = {
  label: 'PR-03 비가동',
  text: 'PR-03의 최근 비가동 이력을 보여줘',
}
const ACTIVE_WORK_ORDERS: SuggestedQuestion = {
  label: '진행 작업지시',
  text: '현재 진행 중인 작업지시를 보여줘',
}
const PRODUCTION_RESULTS: SuggestedQuestion = {
  label: '생산 실적',
  text: '설비별 최근 생산 실적을 알려줘',
}
const CONSISTENCY_ISSUES: SuggestedQuestion = {
  label: '정합성 문제',
  text: '최근 정합성 문제 현황을 보여줘',
}
const REASSIGNMENT_PLAN: SuggestedQuestion = {
  label: '재배정 계획',
  text: 'PR-03에 배정된 남은 작업을 다른 설비로 옮기는 재배정 계획을 만들어줘',
}

// 조회만 하는 역할의 문장. API 086 명세 예시와 같다.
const VIEW_ONLY_QUESTIONS = [
  STOPPED_EQUIPMENT,
  DOWNTIME_HISTORY,
  ACTIVE_WORK_ORDERS,
  PRODUCTION_RESULTS,
  CONSISTENCY_ISSUES,
]

// 참고 객체가 없을 때 역할별 고정 문장 5개. 정비 담당은 MVP에서 조회 사용자와 권한이 같다(V10 주석).
const GENERAL_QUESTIONS: Record<RoleCode, SuggestedQuestion[]> = {
  VIEWER: VIEW_ONLY_QUESTIONS,
  MAINTENANCE: VIEW_ONLY_QUESTIONS,
  PLANNER: [
    STOPPED_EQUIPMENT,
    DOWNTIME_HISTORY,
    ACTIVE_WORK_ORDERS,
    PRODUCTION_RESULTS,
    REASSIGNMENT_PLAN,
  ],
  ADMIN: [
    STOPPED_EQUIPMENT,
    DOWNTIME_HISTORY,
    ACTIVE_WORK_ORDERS,
    CONSISTENCY_ISSUES,
    REASSIGNMENT_PLAN,
  ],
}

export function suggestedQuestions(
  reference: ReferenceObject | null,
  roleCode: RoleCode | null,
): SuggestedQuestion[] {
  if (!reference) {
    // 역할을 모르면 조회만 하는 문장을 보여 준다.
    return GENERAL_QUESTIONS[roleCode ?? 'VIEWER']
  }
  const { name } = reference
  const questions: SuggestedQuestion[] = [
    { label: '상태 확인', text: `${name}의 현재 상태와 주요 속성을 알려줘` },
    { label: '연결 관계', text: `${name}에 연결된 객체와 관계를 알려줘` },
    { label: '원천 근거', text: `${name}의 원천 근거를 알려줘` },
    { label: '실행 이력', text: `${name}의 최근 실행 이력을 알려줘` },
  ]
  if (
    reference.typeName === EQUIPMENT_TYPE_NAME &&
    roleCode !== null &&
    PLAN_CREATE_ROLES.includes(roleCode)
  ) {
    questions.push({
      label: '재배정 계획',
      text: `${name}에 배정된 남은 작업을 다른 설비로 옮기는 재배정 계획을 만들어줘`,
    })
  }
  return questions
}
