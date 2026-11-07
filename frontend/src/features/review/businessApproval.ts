import { ApiError, isRecord } from '../../api/errors'

// FN-REV-08 상태 표시용 모델. 실제 API·상태값·권한 정책은 아직 미정이다.
export type BusinessApprovalSnapshot = {
  proposalId: number
  revision: number
  approvalStatus: string
  request: {
    reference: string | null
    requestedBy: string | null
    requestedAt: string | null
    reason: string | null
  } | null
  decisions: {
    decision: string
    decidedBy: string | null
    decidedAt: string | null
    reason: string | null
  }[]
  application: {
    status: string
    appliedBy: string | null
    appliedAt: string | null
    error: string | null
  } | null
}

export type BusinessApprovalSource = {
  load: (
    proposalId: number,
    revision: number,
    signal: AbortSignal,
  ) => Promise<BusinessApprovalSnapshot>
}

const textOrNull = (value: unknown) =>
  value === null || typeof value === 'string'
const fields = (value: unknown, keys: string[]) =>
  isRecord(value) && keys.every((key) => textOrNull(value[key]))

export function validateBusinessApproval(
  proposalId: number,
  revision: number,
  value: unknown,
): BusinessApprovalSnapshot {
  if (
    !isRecord(value) ||
    value.proposalId !== proposalId ||
    value.revision !== revision ||
    typeof value.approvalStatus !== 'string' ||
    !value.approvalStatus.trim() ||
    (value.request !== null &&
      !fields(value.request, [
        'reference',
        'requestedBy',
        'requestedAt',
        'reason',
      ])) ||
    !Array.isArray(value.decisions) ||
    value.decisions.some(
      (decision) =>
        !isRecord(decision) ||
        typeof decision.decision !== 'string' ||
        !decision.decision.trim() ||
        !fields(decision, ['decidedBy', 'decidedAt', 'reason']),
    ) ||
    (value.application !== null &&
      (!isRecord(value.application) ||
        typeof value.application.status !== 'string' ||
        !value.application.status.trim() ||
        !fields(value.application, ['appliedBy', 'appliedAt', 'error'])))
  )
    throw new ApiError(
      '현업 승인 상태 응답의 대상·개정 또는 형식을 확인할 수 없습니다.',
      { kind: 'invalid-response' },
    )
  return value as BusinessApprovalSnapshot
}
