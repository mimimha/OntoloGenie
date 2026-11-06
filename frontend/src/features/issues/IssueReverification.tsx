import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { fetchRunResults } from '../review/checkApi'
import { Result } from '../review/CheckResults'
import type { Issue } from './api'

const categories = [
  ['resolved', '해결 문제'],
  ['remaining', '잔존 문제'],
  ['new', '신규 문제'],
] as const

type Comparison = Record<
  (typeof categories)[number][0],
  { issueId: number; reason: string | null }[]
>

function RunResults({
  runId,
  filter,
  title,
}: {
  runId: number
  filter: Parameters<typeof fetchRunResults>[1]
  title: string
}) {
  const [page, setPage] = useState(0)
  const query = useQuery({
    queryKey: ['issues', 'check-results', runId, filter, page],
    queryFn: ({ signal }) => fetchRunResults(runId, filter, page, signal),
    retry: false,
  })
  return (
    <section aria-label={title} className="space-y-2">
      <h4 className="font-medium">
        {title} · 실행 #{runId}
      </h4>
      {query.isPending && <LoadingState message="검사 결과를 불러오는 중…" />}
      {query.isError && (
        <ErrorState error={query.error} onRetry={() => void query.refetch()} />
      )}
      {query.isSuccess && (
        <>
          {query.data.data.length ? (
            query.data.data.map((result) => (
              <Result key={result.id} result={result} />
            ))
          ) : (
            <EmptyState message="이 대상의 검사 결과가 없습니다." />
          )}
          {(query.data.meta.totalElements > query.data.meta.size ||
            page > 0) && <Pagination {...query.data.meta} onChange={setPage} />}
        </>
      )}
    </section>
  )
}

export default function IssueReverification({
  issue,
  execution,
  comparison,
}: {
  issue: Issue
  execution?: { start: () => void; pending: boolean; error: Error | null }
  comparison?: Comparison
}) {
  const [open, setOpen] = useState(false)
  const {
    linkMappingId,
    proposalItemId,
    detectedCheckRunId,
    resolvedCheckRunId,
  } = issue
  const filter =
    linkMappingId != null
      ? { linkMappingId }
      : proposalItemId != null
        ? { proposalItemId }
        : null
  return (
    <section aria-label="재검증" className="space-y-3">
      <h3 className="font-semibold">재검증</h3>
      {issue.status === 'FIXED_PENDING_REVERIFY' && (
        <>
          <Button
            variant="secondary"
            disabled={!execution || execution.pending}
            onClick={execution?.start}
          >
            {execution?.pending ? '재검증 요청 중…' : '재검증 실행'}
          </Button>
          {!execution && (
            <p className="text-sm text-slate-500">
              재검증 실행 기능은 아직 사용할 수 없습니다.
            </p>
          )}
          {execution?.error && <ErrorState error={execution.error} />}
        </>
      )}
      <details onToggle={(event) => setOpen(event.currentTarget.open)}>
        <summary className="cursor-pointer text-sm font-medium">
          검사 결과 비교
        </summary>
        {open && (
          <div className="mt-3 space-y-4">
            {filter && detectedCheckRunId > 0 ? (
              <>
                <RunResults
                  key={detectedCheckRunId}
                  title="발견 당시 검사 결과"
                  runId={detectedCheckRunId}
                  filter={filter}
                />
                {resolvedCheckRunId != null &&
                resolvedCheckRunId !== detectedCheckRunId ? (
                  <RunResults
                    key={resolvedCheckRunId}
                    title="해결 처리에 사용한 재검증 결과"
                    runId={resolvedCheckRunId}
                    filter={filter}
                  />
                ) : (
                  <p className="text-sm text-slate-500">
                    이 문제에 연결된 해결 재검증 기록이 없습니다.
                  </p>
                )}
              </>
            ) : (
              <p className="text-sm text-slate-500">
                이 문제에서 조회할 수 있는 검사 기록이 없습니다.
              </p>
            )}
            {comparison ? (
              categories.map(([key, label]) => (
                <section key={key} aria-label={label}>
                  <h4 className="font-medium">
                    {label} · {comparison[key].length}건
                  </h4>
                  <ul className="space-y-1 text-sm">
                    {comparison[key].map(({ issueId, reason }) => (
                      <li key={issueId}>
                        문제 #{issueId} ·{' '}
                        {reason ?? '제공된 판정 근거가 없습니다.'}
                      </li>
                    ))}
                  </ul>
                </section>
              ))
            ) : (
              <p className="text-sm text-slate-500">
                해결·잔존·신규 문제의 비교 결과는 아직 조회할 수 없습니다.
              </p>
            )}
          </div>
        )}
      </details>
    </section>
  )
}
