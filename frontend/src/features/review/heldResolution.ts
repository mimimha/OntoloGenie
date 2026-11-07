import { ApiError } from '../../api/errors'
import type { HeldItem } from './heldItemApi'

export type HeldResolutionDecision = 'APPROVE' | 'DISMISS'
export type HeldResolutionInput = { reason: string } & (
  { decision: 'APPROVE'; targetObjectId: number } | { decision: 'DISMISS' }
)

// 058 계약·재검사 정책 확정 후 서버 근거를 변환할 화면 전용 모델이다.
export type HeldResolutionAvailability = {
  heldItemId: number
  status: string
  description: string
  reasonRequired: boolean
  reasonMaxLength: number | null
  actions: {
    decision: HeldResolutionDecision
    allowed: boolean
    unavailableReason: string | null
  }[]
}
export type HeldResolutionResult = { item: HeldItem; actionExecutionId: number }
export type HeldResolutionAccess = {
  availability: (id: number) => Promise<HeldResolutionAvailability>
  resolve: (
    id: number,
    input: HeldResolutionInput,
  ) => Promise<HeldResolutionResult>
}

export function validateResolutionAvailability(
  id: number,
  policy: HeldResolutionAvailability,
) {
  if (
    policy.heldItemId !== id ||
    policy.status !== 'HELD' ||
    !policy.description?.trim() ||
    typeof policy.reasonRequired !== 'boolean' ||
    (policy.reasonMaxLength !== null &&
      (!Number.isSafeInteger(policy.reasonMaxLength) ||
        policy.reasonMaxLength < 1)) ||
    !Array.isArray(policy.actions) ||
    policy.actions.some(
      (action) =>
        !['APPROVE', 'DISMISS'].includes(action.decision) ||
        typeof action.allowed !== 'boolean',
    ) ||
    new Set(policy.actions.map((action) => action.decision)).size !==
      policy.actions.length
  )
    throw new ApiError('처리 조건을 확인할 수 없습니다.', {
      kind: 'invalid-response',
    })
  return policy
}

export function validateHeldResolution(
  id: number,
  input: HeldResolutionInput,
  result: HeldResolutionResult,
) {
  const item = result?.item
  if (
    item?.id !== id ||
    item.status !== (input.decision === 'APPROVE' ? 'APPROVED' : 'DISMISSED') ||
    !Number.isSafeInteger(result.actionExecutionId) ||
    result.actionExecutionId < 1 ||
    !Number.isSafeInteger(item.resolvedBy) ||
    Number(item.resolvedBy) < 1 ||
    !item.resolvedAt ||
    !Number.isFinite(Date.parse(item.resolvedAt)) ||
    (input.decision === 'APPROVE'
      ? !Number.isSafeInteger(item.resolvedLinkId) ||
        Number(item.resolvedLinkId) < 1
      : item.resolvedLinkId !== null)
  )
    throw new ApiError(
      '처리 결과를 확인할 수 없습니다. 최신 상태를 다시 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  return result
}
