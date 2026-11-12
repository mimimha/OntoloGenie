import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { ErrorState, LoadingState } from '../../components/Feedback'
import { fetchExecutionChanges, fetchExecutionDetail } from './api'
import EntityChangeTable from './EntityChangeTable'
import type { Execution, ExecutionDetail as Detail } from './types'

function formatJson(
  value: Record<string, unknown> | null,
  restricted: string[] = [],
) {
  if (!value || Object.keys(value).length === 0) return null
  return JSON.stringify(
    value,
    (key, item) =>
      item === null && restricted.includes(key) ? '(권한으로 가림)' : item,
    2,
  )
}

function failureReasonText(reason: string | null) {
  if (!reason) return '기록된 이유가 없습니다.'
  if (reason === 'TIMEOUT') return '실행 시간이 초과되었습니다.'
  return /^[A-Z][A-Z0-9_]*$/.test(reason)
    ? `실행에 실패했습니다. (오류 코드: ${reason})`
    : reason
}

function DetailContent({ detail }: { detail: Detail }) {
  const failed = detail.status === 'FAILED'
  const resultValues = { ...detail.result }
  if (failed) {
    delete resultValues.errorMessage
    delete resultValues.reason
  }
  const input = formatJson(detail.input, detail.restrictedPropertyCodes)
  const result = formatJson(resultValues)
  return (
    <>
      <p className="mt-2 font-semibold text-slate-700">입력값</p>
      {detail.restrictedPropertyCodes.length > 0 && (
        <p className="text-slate-500">
          입력값 중 권한으로 가린 속성이 있습니다.
        </p>
      )}
      <pre className="whitespace-pre-wrap break-all text-slate-900">
        {input ?? '기록된 입력값 없음'}
      </pre>
      {detail.status === 'BLOCKED' && (
        <>
          <p className="mt-2 font-semibold text-slate-700">차단 이유</p>
          <p className="text-slate-900">
            {detail.blockedReason ?? '기록된 이유가 없습니다.'}
          </p>
        </>
      )}
      {failed && (
        <>
          <p className="mt-2 font-semibold text-slate-700">실패 이유</p>
          <p className="text-slate-900">
            {failureReasonText(detail.failureReason)}
          </p>
        </>
      )}
      {result && (
        <>
          <p className="mt-2 font-semibold text-slate-700">처리 결과</p>
          <pre className="whitespace-pre-wrap break-all text-slate-900">
            {result}
          </pre>
        </>
      )}
    </>
  )
}

function ExecutionChanges({ id, count }: { id: number; count: number }) {
  const [open, setOpen] = useState(false)
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['audit', 'actionChanges', id],
    queryFn: ({ signal }) => fetchExecutionChanges(id, { signal }),
    enabled: open && count > 0,
  })

  if (count === 0) {
    return <p className="mt-2 text-slate-500">변경 전후: 기록된 변경 없음</p>
  }

  return (
    <details
      className="mt-2"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="cursor-pointer text-slate-500">
        {`실행 전후 비교 ${count}건`}
      </summary>
      {open &&
        (isPending ? (
          <LoadingState message="변경 내역을 불러오는 중…" />
        ) : isError ? (
          <ErrorState
            error={error}
            onRetry={() => {
              void refetch()
            }}
          />
        ) : (
          <>
            {data.data.restrictedPropertyCodes.length > 0 && (
              <p className="mt-2 text-slate-500">
                변경 내역 중 권한으로 가린 속성이 있습니다.
              </p>
            )}
            {data.data.changes.length === 0 ? (
              <p className="mt-2 text-slate-500">기록된 변경 없음</p>
            ) : (
              data.data.changes.map((change, index) => (
                <EntityChangeTable
                  key={index}
                  change={change}
                  restrictedCodes={data.data.restrictedPropertyCodes}
                />
              ))
            )}
          </>
        ))}
    </details>
  )
}

export default function ExecutionDetail({
  execution,
}: {
  execution: Execution
}) {
  const [open, setOpen] = useState(false)
  const { data, isPending, isError, error, refetch } = useQuery({
    queryKey: ['audit', 'actionDetail', execution.id],
    queryFn: ({ signal }) => fetchExecutionDetail(execution.id, { signal }),
    enabled: open,
  })
  return (
    <details
      className="management-detail"
      onToggle={(event) => setOpen(event.currentTarget.open)}
    >
      <summary className="cursor-pointer text-slate-500">내용 보기</summary>
      {open && (
        <>
          {isPending ? (
            <LoadingState message="실행 내용을 불러오는 중…" />
          ) : isError ? (
            <ErrorState
              error={error}
              onRetry={() => {
                void refetch()
              }}
            />
          ) : (
            <DetailContent detail={data.data} />
          )}
          <ExecutionChanges id={execution.id} count={execution.changeCount} />
        </>
      )}
    </details>
  )
}
