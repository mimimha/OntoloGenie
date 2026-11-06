import type { Proposal } from './types'

// 검사 상태 자동 확인. 화면을 연 뒤 3초마다 20번(약 1분)까지만 확인하고, 그 뒤에는 새로고침으로 확인한다.
export const CHECK_POLL_MS = 3000
export const CHECK_POLL_LIMIT = 20

export function checkWaiting(proposal: Proposal | undefined) {
  return ['QUEUED', 'RUNNING'].includes(proposal?.latestCheck?.status ?? '')
}

// updates: 화면을 연 뒤 받은 조회 결과 수. refetchInterval과 화면 안내가 같은 기준을 쓴다.
export function shouldPoll(
  proposal: Proposal | undefined,
  updates: number,
  failed: boolean,
) {
  return !failed && checkWaiting(proposal) && updates < CHECK_POLL_LIMIT
}
