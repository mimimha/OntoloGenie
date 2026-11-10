import type { Proposal } from './types'

export const originLabels: Record<string, string> = {
  AI: 'AI 분석',
  MANUAL: '직접 추가',
}
export const kindLabels: Record<string, string> = {
  OBJECT_TYPE: '객체 유형',
  PROPERTY: '속성',
  LINK_TYPE: '관계 유형',
  OBJECT_MAPPING: '객체 매핑',
  PROPERTY_MAPPING: '속성 매핑',
  LINK_RULE: '연결 규칙',
  DOCUMENT_LINK: '문서 연결',
}
// AI 제안은 표 하나(객체 유형)·관계 하나(연결 규칙)로 묶여 오고, 직접 추가는 연결 규칙만 된다.
// 실제로 저장되는 종류만 필터에 둔다(속성·매핑만 고르면 늘 0건이 나왔다).
export const filterKindLabels: Record<string, string> = {
  OBJECT_TYPE: kindLabels.OBJECT_TYPE,
  LINK_RULE: kindLabels.LINK_RULE,
  LINK_TYPE: kindLabels.LINK_TYPE,
  DOCUMENT_LINK: kindLabels.DOCUMENT_LINK,
}
// 재분석 비교(evidence.changeType)
export const changeLabels: Record<string, string> = {
  ADDED: '추가',
  CHANGED: '변경',
  UNCHANGED: '유지',
}
export const reviewLabels: Record<string, string> = {
  PENDING: '검토 대기',
  APPROVED: '승인',
  REJECTED: '거절',
  HELD: '보류',
}
export const applyLabels: Record<string, string> = {
  NOT_APPLIED: '미반영',
  APPLIED: '반영 완료',
  FAILED: '반영 실패',
}

const checkLabels: Record<string, string> = {
  QUEUED: '대기',
  RUNNING: '실행 중',
  SUCCEEDED: '완료',
  FAILED: '실패',
}

export const CHECK_NOT_REQUIRED_MESSAGE =
  '검사 대상 아님: 이 종류는 원천 행으로 시험하지 않으며, 승인에 검사 결과가 필요하지 않습니다.'

// 서버가 checkRequired를 주지 않은 예전 응답은 검사 대상으로 본다.
export function requiresCandidateCheck(
  proposal: Pick<Proposal, 'checkRequired'>,
) {
  return proposal.checkRequired !== false
}

export function candidateCheckLabel(
  check: NonNullable<Proposal['latestCheck']>,
) {
  if (check.unsupported) return '이 후보는 아직 자동 시험을 지원하지 않음'
  return checkLabels[check.status] ?? check.status
}

export function proposalTitle(proposal: Proposal) {
  const name = proposal.payload.name
  const code = proposal.payload.code
  return typeof name === 'string' && name.trim()
    ? name
    : typeof code === 'string' && code.trim()
      ? code
      : `${kindLabels[proposal.itemKind] ?? proposal.itemKind} 제안 #${proposal.id}`
}

export function confidence(value: number | null) {
  return value !== null && Number.isFinite(value) && value >= 0 && value <= 1
    ? `${(value * 100).toFixed(1)}%`
    : '제공되지 않음'
}
