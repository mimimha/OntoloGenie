import {
  useIsMutating,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { ApiError, getHttpErrorMessage } from '../../api/errors'
import Button from '../../components/Button'
import { ErrorState, LoadingState } from '../../components/Feedback'
import type { HeldItem } from './heldItemApi'
import {
  validateHeldResolution,
  validateResolutionAvailability,
  type HeldResolutionAccess,
  type HeldResolutionDecision,
  type HeldResolutionInput,
} from './heldResolution'

const decisions = [
  ['APPROVE', '수동 승인'],
  ['DISMISS', '종결'],
] as const
function safeError(error: unknown) {
  return error instanceof ApiError
    ? new ApiError(
        error.code === 'HELD_ITEM_TARGET_INVALID'
          ? '관계 유형에 맞는 대상 객체인지, 출발 객체와 다른지 확인해 주세요.'
          : getHttpErrorMessage(error.status ?? 500),
        {
          kind: error.kind,
          status: error.status ?? undefined,
          code: error.code ?? undefined,
        },
      )
    : new Error(
        '처리 결과를 확인하지 못했습니다. 최신 상태를 다시 확인해 주세요.',
      )
}

export default function HeldItemResolution({
  item,
  access,
  onRefresh,
}: {
  item: HeldItem
  access?: HeldResolutionAccess
  onRefresh: () => Promise<HeldItem | undefined>
}) {
  const client = useQueryClient()
  const mutationKey = ['held-item-resolution', item.id]
  const saving = useIsMutating({ mutationKey }) > 0
  const lock = useRef(false)
  const [reason, setReason] = useState('')
  const [target, setTarget] = useState('')
  const [validation, setValidation] = useState('')
  const [confirmation, setConfirmation] = useState<HeldResolutionInput>()
  const [refreshing, setRefreshing] = useState(false)
  const availability = useQuery({
    queryKey: ['held-items', 'resolution-policy', item.id],
    queryFn: async () =>
      validateResolutionAvailability(
        item.id,
        await access!.availability(item.id),
      ),
    enabled: !!access && item.status === 'HELD',
    retry: false,
    gcTime: 0,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
  })
  const mutation = useMutation({
    mutationKey,
    networkMode: 'always',
    retry: false,
    mutationFn: async (input: HeldResolutionInput) => {
      await client.cancelQueries({
        queryKey: ['held-items', 'detail', item.id],
        exact: true,
      })
      return validateHeldResolution(
        item.id,
        input,
        await access!.resolve(item.id, input),
      )
    },
    onSuccess: async (result) => {
      client.setQueryData(['held-items', 'detail', item.id], result.item)
      await client.invalidateQueries({ queryKey: ['held-items', 'list'] })
    },
  })
  const policy = availability.data
  const ready =
    !!access &&
    availability.isSuccess &&
    !availability.isFetching &&
    !availability.isPaused
  const error = mutation.error
  const blocked =
    !!error &&
    (!(error instanceof ApiError) || !['validation'].includes(error.kind))
  const unavailable =
    item.status !== 'HELD' ||
    !ready ||
    saving ||
    refreshing ||
    blocked ||
    !!mutation.data
  const allowed = (decision: HeldResolutionDecision) =>
    policy?.actions.find((action) => action.decision === decision)?.allowed ===
    true

  function prepare(decision: HeldResolutionDecision) {
    if (unavailable || !allowed(decision) || client.isMutating({ mutationKey }))
      return
    if (policy?.reasonRequired && !reason.trim()) {
      setValidation('처리 사유를 입력해 주세요.')
      return
    }
    if (policy?.reasonMaxLength && reason.length > policy.reasonMaxLength) {
      setValidation(
        `처리 사유는 ${policy.reasonMaxLength}자 이내로 입력해 주세요.`,
      )
      return
    }
    if (decision === 'APPROVE') {
      const id = Number(target)
      if (!/^[1-9]\d*$/.test(target) || !Number.isSafeInteger(id)) {
        setValidation('대상 객체 ID는 1 이상의 정수로 입력해 주세요.')
        return
      }
      if (id === item.sourceObjectId) {
        setValidation('출발 객체와 다른 대상 객체를 입력해 주세요.')
        return
      }
      setConfirmation({ decision, targetObjectId: id, reason: reason.trim() })
    } else setConfirmation({ decision, reason: reason.trim() })
    setValidation('')
  }
  async function confirm() {
    if (
      !confirmation ||
      unavailable ||
      !allowed(confirmation.decision) ||
      lock.current ||
      client.isMutating({ mutationKey })
    )
      return
    lock.current = true
    try {
      await mutation.mutateAsync(confirmation)
    } catch {
      /* 오류는 아래에 표시한다. */
    } finally {
      lock.current = false
      setConfirmation(undefined)
    }
  }
  async function refresh() {
    if (saving || refreshing) return
    setRefreshing(true)
    setConfirmation(undefined)
    try {
      const fresh = await onRefresh()
      if (
        fresh &&
        (fresh.status !== 'HELD' || (await availability.refetch()).isSuccess)
      ) {
        mutation.reset()
        setValidation('')
      }
    } finally {
      setRefreshing(false)
    }
  }
  return (
    <section
      aria-label="개별 보류 건 수동 처리"
      className="mt-5 space-y-3 rounded-lg border border-slate-200 p-4"
    >
      <h3 className="font-semibold">개별 보류 건 수동 처리</h3>
      <p className="text-sm text-slate-600">
        수동 승인은 대상 객체로 연결을 만듭니다. 종결은 연결을 만들지 않고 이
        보류 건을 닫습니다.
      </p>
      {item.status !== 'HELD' ? (
        <p role="status">이미 처리됐거나 수동 처리할 수 없는 상태입니다.</p>
      ) : (
        <>
          {!access ? (
            <p role="status" className="rounded bg-amber-50 p-3 text-sm">
              수동 승인·종결은 준비 중입니다. 처리 기능과 정책 확정 후 사용할 수
              있습니다.
            </p>
          ) : availability.isPending ||
            availability.isFetching ||
            availability.isPaused ? (
            <LoadingState message="가능한 처리 동작을 확인하는 중…" />
          ) : availability.isError ? (
            <ErrorState
              error={safeError(availability.error)}
              onRetry={() => {
                void availability.refetch()
              }}
            />
          ) : (
            <p className="text-sm">{policy?.description}</p>
          )}
          <label className="block space-y-1 text-sm">
            <span>승인할 대상 객체 ID</span>
            <input
              type="text"
              inputMode="numeric"
              value={target}
              disabled={unavailable || !!confirmation}
              onChange={(e) => setTarget(e.target.value)}
              className="w-full rounded border border-slate-300 p-2"
            />
          </label>
          <p className="text-sm text-slate-500">
            수동 승인할 때만 필요합니다. 관계 유형의 도착 유형에 맞는 객체를
            입력하세요.
          </p>
          <label className="block space-y-1 text-sm">
            <span>처리 사유{policy?.reasonRequired ? ' (필수)' : ''}</span>
            <textarea
              value={reason}
              disabled={unavailable || !!confirmation}
              onChange={(e) => setReason(e.target.value)}
              className="min-h-24 w-full rounded border border-slate-300 p-2"
            />
          </label>
          {policy?.reasonMaxLength && (
            <p className="text-sm text-slate-500">
              {reason.length} / {policy.reasonMaxLength}자
            </p>
          )}
          {validation && (
            <p role="alert" className="text-sm text-red-800">
              {validation}
            </p>
          )}
          <div className="flex flex-wrap gap-2">
            {decisions.map(([decision, label]) => (
              <div key={decision}>
                <Button
                  variant={decision === 'APPROVE' ? 'primary' : 'secondary'}
                  disabled={unavailable || !allowed(decision) || !!confirmation}
                  onClick={() => prepare(decision)}
                >
                  {label} 내용 확인
                </Button>
                {ready && !allowed(decision) && (
                  <p className="mt-1 text-sm text-slate-600">
                    {policy?.actions.find(
                      (action) => action.decision === decision,
                    )?.unavailableReason ??
                      '현재 이 동작을 허용한다는 근거가 없습니다.'}
                  </p>
                )}
              </div>
            ))}
          </div>
          {confirmation && (
            <div
              role="group"
              aria-label="처리 내용 최종 확인"
              className="space-y-2 rounded bg-amber-50 p-3 text-sm"
            >
              <p>
                보류 건 #{item.id} · 출발 객체 #{item.sourceObjectId} · 관계
                유형 #{item.linkTypeId}
              </p>
              <p>
                {confirmation.decision === 'APPROVE'
                  ? `대상 객체 #${confirmation.targetObjectId}로 수동 연결을 만들고 승인합니다.`
                  : '연결을 만들지 않고 이 보류 건을 종결합니다.'}
              </p>
              <p className="whitespace-pre-wrap break-words">
                처리 사유: {confirmation.reason || '입력 없음'}
              </p>
              <div className="flex gap-2">
                <Button
                  pending={saving}
                  disabled={unavailable}
                  onClick={() => {
                    void confirm()
                  }}
                >
                  {confirmation.decision === 'APPROVE'
                    ? '최종 승인'
                    : '최종 종결'}
                </Button>
                <Button
                  variant="secondary"
                  disabled={saving}
                  onClick={() => setConfirmation(undefined)}
                >
                  입력으로 돌아가기
                </Button>
              </div>
            </div>
          )}
        </>
      )}
      {saving && <LoadingState message="보류 건을 처리하는 중…" />}
      {error && (
        <div className="space-y-2">
          <ErrorState error={safeError(error)} />
          {blocked && (
            <>
              <p className="text-sm">
                다시 처리하기 전에 최신 상태와 처리 기록을 확인해 주세요.
              </p>
              <Button
                variant="secondary"
                pending={refreshing}
                disabled={saving}
                onClick={() => {
                  void refresh()
                }}
              >
                최신 보류 건 다시 확인
              </Button>
            </>
          )}
        </div>
      )}
      {mutation.data && (
        <div
          role="status"
          className="space-y-1 rounded bg-emerald-50 p-3 text-sm"
        >
          <p>
            {mutation.data.item.status === 'APPROVED'
              ? '수동 승인 처리가 완료되었습니다.'
              : '종결 처리가 완료되었습니다.'}
          </p>
          <p>
            처리 시각{' '}
            {new Date(mutation.data.item.resolvedAt!).toLocaleString('ko-KR')} ·
            처리자 #{mutation.data.item.resolvedBy}
          </p>
          {mutation.data.item.resolvedLinkId !== null && (
            <p>생성된 연결 #{mutation.data.item.resolvedLinkId}</p>
          )}
          <p>감사 실행 #{mutation.data.actionExecutionId}</p>
          <p className="whitespace-pre-wrap break-words">
            처리 기록: {mutation.data.item.resolutionNote ?? '제공되지 않음'}
          </p>
        </div>
      )}
    </section>
  )
}
