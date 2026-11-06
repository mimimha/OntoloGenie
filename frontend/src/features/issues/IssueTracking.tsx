import { useId } from 'react'
import Button from '../../components/Button'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import type { Issue } from './api'

type StatusHistoryEntry = {
  id: number
  previousStatus: string | null
  status: string
  changedAt: string
  actor: string
  reason: string | null
}

type StatusHistory =
  | { state: 'loading' }
  | { state: 'error'; error: Error; retry: () => void }
  | { state: 'ready'; entries: StatusHistoryEntry[] }

export default function IssueTracking({
  issue,
  statusLabels,
  forwarding,
  history,
}: {
  issue: Pick<Issue, 'forwardedAt' | 'forwardedBy'>
  statusLabels: Record<string, string>
  forwarding?: { record: () => void; pending: boolean; error: Error | null }
  history?: StatusHistory
}) {
  const forwardingHintId = useId()

  return (
    <>
      <section aria-label="원천 전달 기록" className="space-y-2">
        <h3 className="font-semibold">원천 전달 기록</h3>
        <dl className="grid gap-3 rounded-lg bg-slate-50 p-3 text-sm sm:grid-cols-3">
          <div>
            <dt>전달 여부</dt>
            <dd>{issue.forwardedAt ? '전달됨' : '미전달'}</dd>
          </div>
          <div>
            <dt>전달 시각</dt>
            <dd>
              {issue.forwardedAt
                ? new Date(issue.forwardedAt).toLocaleString('ko-KR')
                : '—'}
            </dd>
          </div>
          <div>
            <dt>전달 처리자</dt>
            <dd>
              {issue.forwardedBy == null ? '—' : `계정 #${issue.forwardedBy}`}
            </dd>
          </div>
        </dl>
        {!issue.forwardedAt && (
          <>
            <Button
              variant="secondary"
              disabled={!forwarding || forwarding.pending}
              aria-describedby={forwardingHintId}
              onClick={forwarding?.record}
            >
              {forwarding?.pending ? '기록 중…' : '전달함으로 기록'}
            </Button>
            <p id={forwardingHintId} className="text-sm text-slate-500">
              {forwarding
                ? '원천 담당자에게 이미 전달한 사실을 기록합니다.'
                : '전달 기록 저장 기능은 아직 사용할 수 없습니다.'}
            </p>
          </>
        )}
        {forwarding?.error && <ErrorState error={forwarding.error} />}
      </section>
      <section aria-label="상태 변경 이력" className="space-y-2">
        <h3 className="font-semibold">상태 변경 이력</h3>
        {!history && (
          <p className="text-sm text-slate-500">
            상태 변경 이력 조회는 아직 사용할 수 없습니다.
          </p>
        )}
        {history?.state === 'loading' && (
          <LoadingState message="상태 변경 이력을 불러오는 중…" />
        )}
        {history?.state === 'error' && (
          <ErrorState error={history.error} onRetry={history.retry} />
        )}
        {history?.state === 'ready' &&
          (history.entries.length ? (
            <ul className="space-y-2 text-sm">
              {history.entries.map((entry) => (
                <li key={entry.id} className="rounded-lg bg-slate-50 p-3">
                  <p className="font-medium">
                    {entry.previousStatus &&
                      `${statusLabels[entry.previousStatus] ?? entry.previousStatus} → `}
                    {statusLabels[entry.status] ?? entry.status}
                  </p>
                  <p>
                    {entry.actor} ·{' '}
                    <time dateTime={entry.changedAt}>
                      {new Date(entry.changedAt).toLocaleString('ko-KR')}
                    </time>
                  </p>
                  {entry.reason && (
                    <p className="whitespace-pre-wrap break-words">
                      {entry.reason}
                    </p>
                  )}
                </li>
              ))}
            </ul>
          ) : (
            <EmptyState message="상태 변경 이력이 없습니다." />
          ))}
      </section>
    </>
  )
}
