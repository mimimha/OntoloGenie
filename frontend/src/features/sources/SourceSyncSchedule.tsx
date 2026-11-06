import { useId, useState, type FormEvent } from 'react'
import Button from '../../components/Button'
import { ErrorState } from '../../components/Feedback'
import type { Source } from './types'

type Schedule = {
  syncEnabled: boolean
  syncIntervalMinutes: number | null
  rowVersion: number
}

export default function SourceSyncSchedule({
  schedule,
  save,
}: {
  schedule: Pick<Source, keyof Schedule>
  save?: {
    submit: (schedule: Schedule) => void
    pending: boolean
    error: Error | null
    succeeded: boolean
  }
}) {
  const intervalErrorId = useId()
  const [enabled, setEnabled] = useState(schedule.syncEnabled)
  const [interval, setInterval] = useState(
    String(schedule.syncIntervalMinutes ?? ''),
  )
  const minutes = Number(interval)
  const validInterval =
    /^\d+$/.test(interval) && minutes > 0 && minutes <= 2147483647
  const invalid = enabled && !validInterval

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!save || save.pending || invalid) return
    save.submit({
      syncEnabled: enabled,
      syncIntervalMinutes: validInterval ? minutes : null,
      rowVersion: schedule.rowVersion,
    })
  }

  return (
    <section
      aria-label="자동 수집 주기"
      className="space-y-3 border-t border-slate-200 pt-4"
    >
      <h3 className="font-semibold">자동 수집 주기</h3>
      <p className="text-slate-600">
        전체 비교 방식으로 수집하는 대상에만 적용됩니다.
      </p>
      <form onSubmit={submit}>
        <fieldset disabled={save?.pending} className="space-y-3">
          <label className="flex items-center gap-2">
            <input
              type="checkbox"
              checked={enabled}
              onChange={(event) => setEnabled(event.target.checked)}
            />
            자동 수집 사용
          </label>
          {enabled && (
            <label className="flex flex-col gap-1">
              수집 주기(분)
              <input
                type="number"
                min={1}
                max={2147483647}
                step={1}
                required
                value={interval}
                onChange={(event) => setInterval(event.target.value)}
                aria-invalid={invalid}
                aria-describedby={invalid ? intervalErrorId : undefined}
                className="w-36 rounded-lg border border-slate-300 px-3 py-2"
              />
            </label>
          )}
          {invalid && (
            <p id={intervalErrorId} className="text-red-700">
              수집 주기를 1분 이상의 정수로 입력해 주세요.
            </p>
          )}
          <Button type="submit" disabled={!save || save.pending || invalid}>
            {save?.pending ? '저장 중…' : '수집 주기 저장'}
          </Button>
          {!save && (
            <p className="text-slate-500">
              수집 주기 설정은 아직 저장할 수 없습니다.
            </p>
          )}
          {save?.error && <ErrorState error={save.error} />}
          {save?.succeeded && <p role="status">수집 주기를 저장했습니다.</p>}
        </fieldset>
      </form>
    </section>
  )
}
