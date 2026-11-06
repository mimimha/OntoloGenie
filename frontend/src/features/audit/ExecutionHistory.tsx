import Button from '../../components/Button'
import StatusBadge from '../../components/StatusBadge'
import DataTable from '../../components/DataTable'
import { LoadingState, EmptyState, ErrorState } from '../../components/Feedback'
import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import {
  fetchAuditExecutions,
  fetchObjectExecutions,
  type ExecutionFilters,
} from './api'
import { startOfDay, startOfNextDay } from './dateRange'
import EntityChangeTable from './EntityChangeTable'
import {
  actorTypeLabels,
  categoryLabels,
  eventKindLabels,
  executionStatusLabels,
} from './labels'
import type {
  Execution,
  ExecutionCategory,
  ExecutionScope,
  ExecutionStatus,
} from './types'

// 권한으로 가린 키(restricted)의 null은 "(권한으로 가림)"으로 바꿔 보여 준다.
function formatJson(value: Record<string, unknown>, restricted: string[]) {
  if (Object.keys(value).length === 0) {
    return null
  }
  return JSON.stringify(
    value,
    (key, item) =>
      item === null && restricted.includes(key) ? '(권한으로 가림)' : item,
    2,
  )
}

// "분류 · 동작 이름". 수집·검사 같은 데이터 작업은 분류 대신 작업 종류(데이터 수집 등)를 쓴다.
function actionLabel(execution: Execution) {
  const kind =
    execution.category && execution.category !== 'DATA_JOB'
      ? categoryLabels[execution.category]
      : eventKindLabels[execution.eventKind]
  const name =
    execution.actionName ??
    (execution.eventKind === 'ACTION'
      ? `Action #${execution.actionDefinitionId ?? '-'}`
      : null)
  return name ? `${kind} · ${name}` : kind
}

// 표의 굵은 대상 글자. 객체 이력이면 객체 이름을, 내 이력이면 대상 종류와 번호를 쓴다.
function targetText(execution: Execution) {
  if (!execution.targetType) {
    return '-'
  }
  const kind =
    execution.targetType === 'object_instance' ? '객체' : execution.targetType
  return `${kind} #${execution.targetId}`
}

// 실행자 이름이 없으면 번호로 보여 준다. SYSTEM은 이름 없이 "시스템".
function actorLabel(execution: Execution) {
  const type = actorTypeLabels[execution.actorType]
  if (execution.actorType === 'SYSTEM') {
    return type
  }
  const name =
    execution.actorName ??
    (execution.actorId ? `사용자 #${execution.actorId}` : null)
  return name ? `${type} · ${name}` : type
}

function scopeText(
  objectId: number | undefined,
  hasTarget: boolean,
  scope?: ExecutionScope,
) {
  if (hasTarget) {
    return '조회 범위: 선택한 대상에 대한 실행 기록'
  }
  if (objectId !== undefined) {
    return '조회 범위: 관리자는 이 객체의 전체 실행, 그 밖의 역할은 본인 실행'
  }
  if (scope === 'ALL') {
    return '조회 범위: 관리자에게 허용된 전체 실행 이력'
  }
  if (scope === 'MINE') {
    return '조회 범위: 현재 계정의 실행 이력'
  }
  return '조회 범위: 권한에 따라 볼 수 있는 실행 이력'
}

// 실패 이유 중 코드로 오는 값을 읽을 수 있는 문장으로 바꾼다.
const failureReasonTexts: Record<string, string> = {
  TIMEOUT: '분석 시간이 초과되었습니다.',
}

// 실패 이유는 업무 실행·데이터 작업은 result.errorMessage, 분석은 result.reason에 온다.
function failureReason(result: Record<string, unknown>) {
  const raw =
    typeof result.errorMessage === 'string'
      ? result.errorMessage
      : typeof result.reason === 'string'
        ? result.reason
        : null
  return raw === null ? null : (failureReasonTexts[raw] ?? raw)
}

type ExecutionDetailProps = {
  execution: Execution
}

function ExecutionDetail({ execution }: ExecutionDetailProps) {
  const restricted = execution.restrictedPropertyCodes ?? []
  const failed = execution.status === 'FAILED'
  // 실패 이유(errorMessage·reason)는 따로 보여 주므로 처리 결과에서는 뺀다.
  const { errorMessage, reason, ...restResult } = execution.result
  const input = formatJson(execution.input, restricted)
  const result = formatJson(failed ? restResult : execution.result, restricted)

  return (
    <details className="text-xs">
      <summary className="cursor-pointer text-slate-500">내용 보기</summary>
      <p className="mt-2 font-semibold text-slate-700">입력값</p>
      <pre className="whitespace-pre-wrap break-all text-slate-900">
        {input ?? '기록된 입력값 없음'}
      </pre>
      {execution.status === 'BLOCKED' && (
        <>
          <p className="mt-2 font-semibold text-slate-700">차단 이유</p>
          <p className="text-slate-900">
            {execution.blockedReason ?? '기록된 이유가 없습니다.'}
          </p>
        </>
      )}
      {failed && (
        <>
          <p className="mt-2 font-semibold text-slate-700">실패 이유</p>
          <p className="text-slate-900">
            {failureReason({ errorMessage, reason }) ??
              '기록된 이유가 없습니다.'}
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
      {execution.entityChanges.length > 0 ? (
        <details className="mt-2">
          <summary className="cursor-pointer text-slate-500">
            {`실행 전후 비교 ${execution.entityChanges.length}건`}
          </summary>
          {execution.entityChanges.map((change, index) => (
            <EntityChangeTable
              key={index}
              change={change}
              restrictedCodes={restricted}
            />
          ))}
        </details>
      ) : (
        <p className="mt-2 text-slate-500">변경 전후: 기록된 변경 없음</p>
      )}
    </details>
  )
}

// 종류 탭. 데이터 작업(DATA_JOB)은 [전체]에서만 보인다.
const categoryTabs: { value: ExecutionCategory | ''; label: string }[] = [
  { value: '', label: '전체' },
  { value: 'ANALYSIS', label: categoryLabels.ANALYSIS },
  { value: 'OPERATION', label: categoryLabels.OPERATION },
  { value: 'CHANGE', label: categoryLabels.CHANGE },
]

// objectId가 있으면 그 객체의 이력(080), 없으면 권한에 따른 실행 이력(096)을 보여 준다.
// target이 있으면 096을 그 대상(targetType·targetId)으로 거른다. (예: 규칙 이력의 활동 기록)
type ExecutionHistoryProps = {
  objectId?: number
  targetLabel?: string
  target?: { type: string; id: number }
}

function ExecutionHistory({
  objectId,
  targetLabel,
  target,
}: ExecutionHistoryProps) {
  const [category, setCategory] = useState<ExecutionCategory | ''>('')
  const [fromDate, setFromDate] = useState('')
  const [toDate, setToDate] = useState('')
  const [status, setStatus] = useState<ExecutionStatus | ''>('')
  const [formError, setFormError] = useState('')
  const [filters, setFilters] = useState<ExecutionFilters>({})
  const {
    data: executionPage,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey:
      objectId === undefined
        ? ['audit', 'actions', target, category, filters]
        : ['objects', objectId, 'executions', filters],
    queryFn: () =>
      objectId === undefined
        ? fetchAuditExecutions({
            ...filters,
            category: category || undefined,
            targetType: target?.type,
            targetId: target?.id,
          })
        : fetchObjectExecutions(objectId, filters),
    placeholderData: keepPreviousData,
  })

  function search() {
    if (fromDate && toDate && fromDate > toDate) {
      setFormError('종료일은 시작일 이후로 선택해 주세요.')
      return
    }
    setFormError('')
    setFilters({
      from: fromDate ? startOfDay(fromDate) : undefined,
      to: toDate ? startOfNextDay(toDate) : undefined,
      status: status || undefined,
    })
  }

  function reset() {
    setFromDate('')
    setToDate('')
    setStatus('')
    setFormError('')
    setFilters({})
  }

  let content
  if (isPending) {
    content = <LoadingState message="실행 이력을 불러오는 중…" />
  } else if (isError) {
    content = (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  } else if (executionPage.data.length === 0) {
    content = <EmptyState message="조건에 맞는 이력이 없습니다." />
  } else {
    content = (
      <DataTable label="실행 이력" className="table-fixed min-w-[640px]">
        <thead className="bg-slate-50 text-xs text-slate-500">
          <tr>
            <th className="w-[22%] px-3 py-2 font-normal">시각·실행자</th>
            <th className="w-[24%] px-3 py-2 font-normal">동작·대상</th>
            <th className="w-[8%] px-3 py-2 font-normal">결과</th>
            <th className="px-3 py-2 font-normal">상세</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-slate-100 align-top">
          {executionPage.data.map((execution) => {
            const requestedAt = new Date(execution.requestedAt)
            return (
              <tr key={execution.id}>
                <td className="px-3 py-3">
                  <p>{requestedAt.toLocaleString('ko-KR')}</p>
                  <p className="text-xs text-slate-500">
                    {actorLabel(execution)}
                  </p>
                </td>
                <td className="px-3 py-3">
                  <p>{actionLabel(execution)}</p>
                  <p className="font-semibold">
                    {targetLabel ??
                      execution.targetLabel ??
                      targetText(execution)}
                  </p>
                  {!targetLabel && execution.targetLabel && (
                    <p className="text-xs text-slate-500">
                      {targetText(execution)}
                    </p>
                  )}
                </td>
                <td className="px-3 py-3 font-semibold">
                  <StatusBadge
                    tone={
                      execution.status === 'SUCCEEDED'
                        ? 'success'
                        : execution.status === 'FAILED'
                          ? 'danger'
                          : execution.status === 'BLOCKED'
                            ? 'warning'
                            : 'info'
                    }
                  >
                    {executionStatusLabels[execution.status]}
                  </StatusBadge>
                </td>
                <td className="px-3 py-3">
                  <ExecutionDetail execution={execution} />
                </td>
              </tr>
            )
          })}
        </tbody>
      </DataTable>
    )
  }

  return (
    <div>
      {/* 종류 탭은 096 전체 목록에서만 쓴다. 객체(080)나 대상(규칙 활동 기록 등)을 고른 화면에서는 숨긴다. */}
      {objectId === undefined && target === undefined && (
        <div
          role="group"
          aria-label="실행 이력 종류"
          className="mb-4 flex flex-wrap gap-2"
        >
          {categoryTabs.map((tab) => (
            <button
              key={tab.value}
              type="button"
              aria-pressed={category === tab.value}
              onClick={() => setCategory(tab.value)}
              className={`rounded-lg border px-3 py-1.5 text-sm ${
                category === tab.value
                  ? 'border-slate-900 bg-slate-900 text-white'
                  : 'border-slate-300 text-slate-700 hover:border-slate-900'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      )}
      <form
        className={`grid items-end gap-3 text-sm ${
          targetLabel
            ? 'md:grid-cols-[1fr_1fr_1.4fr_1fr_auto_auto]'
            : 'md:grid-cols-[1fr_1fr_1fr_auto_auto]'
        }`}
        onSubmit={(event) => {
          event.preventDefault()
          search()
        }}
      >
        <label className="flex flex-col gap-1">
          시작일
          <input
            type="date"
            value={fromDate}
            onChange={(event) => setFromDate(event.target.value)}
            className="rounded-lg border border-slate-300 px-2 py-1"
          />
        </label>
        <label className="flex flex-col gap-1">
          종료일
          <input
            type="date"
            value={toDate}
            onChange={(event) => setToDate(event.target.value)}
            className="rounded-lg border border-slate-300 px-2 py-1"
          />
        </label>
        {targetLabel && (
          <label className="flex flex-col gap-1">
            대상
            <input
              type="text"
              value={targetLabel}
              readOnly
              className="rounded-lg border border-slate-300 bg-slate-50 px-2 py-1"
            />
          </label>
        )}
        <label className="flex flex-col gap-1">
          결과
          <select
            value={status}
            onChange={(event) =>
              setStatus(event.target.value as ExecutionStatus | '')
            }
            className="rounded-lg border border-slate-300 px-2 py-1"
          >
            <option value="">전체</option>
            {Object.entries(executionStatusLabels).map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </label>
        <Button
          type="submit"
          className="rounded-lg bg-slate-900 px-3 py-1.5 font-medium text-white"
        >
          검색
        </Button>
        <Button
          variant="secondary"
          onClick={reset}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-slate-700"
        >
          초기화
        </Button>
      </form>
      {formError && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {formError}
        </p>
      )}
      <p className="mt-2 text-xs text-slate-500">
        {`${scopeText(objectId, target !== undefined, executionPage?.meta?.scope)} · Agent 조회 기록은 제외됩니다.`}
      </p>
      {executionPage && (
        <p className="mt-3 text-xs text-slate-500">
          {`${executionPage.meta?.totalElements ?? executionPage.data.length}건`}
        </p>
      )}
      <div className="mt-2 text-sm">{content}</div>
    </div>
  )
}

export default ExecutionHistory
