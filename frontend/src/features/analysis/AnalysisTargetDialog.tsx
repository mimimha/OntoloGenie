import { useMutation, useQueries, useQuery } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import Button from '../../components/Button'
import DataTable from '../../components/DataTable'
import Dialog from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import Pagination from '../../components/Pagination'
import { fetchAllResources, fetchColumns, fetchSources } from '../sources/api'
import type { SourceColumn, SourceResource } from '../sources/types'
import { startAnalysis } from './api'
import type { AnalysisKind, AnalysisRun, AnalysisTarget } from './types'

const inputClass = 'w-full rounded-lg border border-slate-300 px-3 py-2'

// 목업처럼 표 목록과 컬럼 목록은 몇 개씩 나눠 보여 준다.
export const RESOURCE_PAGE_SIZE = 6
export const COLUMN_PAGE_SIZE = 8

// 고른 컬럼을 표별로 모은다. allColumns는 그 표의 전체 컬럼 수.
// whole은 컬럼이 없는 문서·자료를 통째로 고른 경우다.
type Selection = Record<
  number,
  {
    resource: SourceResource
    columns: string[]
    allColumns: number
    whole: boolean
  }
>

function isDocument(resource: SourceResource) {
  return (
    resource.resourceKind === 'DOCUMENT_SET' || resource.resourceKind === 'FILE'
  )
}

function resourceTitle(resource: SourceResource) {
  const description = resource.sourceMetadata.description
  return description
    ? `${resource.resourceKey} · ${description}`
    : resource.resourceKey
}

function ColumnPicker({
  sourceId,
  resource,
  sourceName,
  picked,
  onChange,
  included,
  onInclude,
}: {
  sourceId: number
  resource: SourceResource
  sourceName: string
  picked: string[]
  onChange: (columns: string[], allColumns: number) => void
  included: boolean
  onInclude: (include: boolean) => void
}) {
  const [query, setQuery] = useState('')
  const [columnPage, setColumnPage] = useState(0)
  const {
    data: columns,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['sources', sourceId, 'resources', resource.id, 'columns'],
    queryFn: () => fetchColumns(sourceId, resource.id),
  })

  let content
  if (isPending) {
    content = <LoadingState message="컬럼을 불러오는 중…" />
  } else if (isError) {
    content = (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  } else if (
    isDocument(resource) &&
    !columns.some((column) => column.isPresent)
  ) {
    // 컬럼이 없는 문서·자료는 목업처럼 자료 단위로 고른다.
    content = (
      <>
        <p className="text-sm text-slate-600">
          컬럼이 없는 문서·자료입니다. 고르면 자료 전체를 분석합니다.
        </p>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={included}
            onChange={(event) => onInclude(event.target.checked)}
          />
          이 자료를 분석에 포함
        </label>
      </>
    )
  } else {
    const present = columns.filter((column) => column.isPresent)
    const keyword = query.trim().toLowerCase()
    const shown = present.filter((column) =>
      `${column.columnName} ${column.sourceComment ?? ''}`
        .toLowerCase()
        .includes(keyword),
    )
    const page = Math.min(
      columnPage,
      Math.max(0, Math.ceil(shown.length / COLUMN_PAGE_SIZE) - 1),
    )
    const pageColumns = shown.slice(
      page * COLUMN_PAGE_SIZE,
      (page + 1) * COLUMN_PAGE_SIZE,
    )
    // 전체 체크는 검색 결과의 모든 페이지에 적용한다.
    const allPicked =
      shown.length > 0 &&
      shown.every((column) => picked.includes(column.columnName))
    const toggle = (column: SourceColumn) =>
      onChange(
        picked.includes(column.columnName)
          ? picked.filter((name) => name !== column.columnName)
          : [...picked, column.columnName],
        present.length,
      )
    const toggleAll = () => {
      const names = shown.map((column) => column.columnName)
      onChange(
        allPicked
          ? picked.filter((name) => !names.includes(name))
          : [...new Set([...picked, ...names])],
        present.length,
      )
    }
    content = (
      <>
        <p className="text-sm text-slate-600">
          전체 {present.length}개 중{' '}
          <strong className="text-slate-900">{picked.length}개 선택</strong>
        </p>
        <input
          value={query}
          onChange={(event) => {
            setQuery(event.target.value)
            setColumnPage(0)
          }}
          placeholder="컬럼 이름·설명 검색"
          aria-label="컬럼 이름·설명 검색"
          className={inputClass}
        />
        {shown.length === 0 ? (
          <EmptyState message="조건에 맞는 컬럼이 없습니다." />
        ) : (
          <DataTable label={`${resource.resourceKey} 컬럼 선택`}>
            <thead className="bg-slate-50 text-xs text-slate-500">
              <tr>
                <th className="w-16 px-3 py-2 font-normal">
                  <label className="flex flex-col items-start gap-1">
                    <input
                      type="checkbox"
                      checked={allPicked}
                      onChange={toggleAll}
                    />
                    전체
                  </label>
                </th>
                <th className="px-3 py-2 font-normal">컬럼 · 설명</th>
                <th className="px-3 py-2 font-normal">형식</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {pageColumns.map((column) => (
                <tr key={column.id}>
                  <td className="px-3 py-2">
                    <input
                      type="checkbox"
                      aria-label={`${column.columnName} 선택`}
                      checked={picked.includes(column.columnName)}
                      onChange={() => toggle(column)}
                    />
                  </td>
                  <td className="px-3 py-2 font-mono text-xs">
                    {column.columnName}
                    {column.isKeyPart && (
                      <span className="ml-2 font-sans text-slate-500">
                        기본키
                      </span>
                    )}
                    {column.sourceComment && (
                      <span className="block font-sans text-slate-500">
                        {column.sourceComment}
                      </span>
                    )}
                  </td>
                  <td className="px-3 py-2 font-mono text-xs">
                    {column.sourceDataType}
                  </td>
                </tr>
              ))}
            </tbody>
          </DataTable>
        )}
        {shown.length > COLUMN_PAGE_SIZE && (
          <Pagination
            page={page}
            size={COLUMN_PAGE_SIZE}
            totalElements={shown.length}
            onChange={setColumnPage}
          />
        )}
      </>
    )
  }

  return (
    <div className="space-y-3">
      <div>
        <h3 className="font-semibold text-slate-900">
          {resourceTitle(resource)}
        </h3>
        <p className="text-xs text-slate-500">{sourceName}</p>
      </div>
      {resource.isReadable ? (
        content
      ) : (
        <p role="alert" className="text-sm text-red-700">
          수집 계정이 읽을 수 없는 표입니다. 연결을 다시 확인해 주세요.
        </p>
      )}
    </div>
  )
}

type Props = {
  runKind?: AnalysisKind
  initialSourceId?: number
  onClose: () => void
  onStarted: (run: AnalysisRun) => void
  // 목업처럼 창을 닫고 데이터 연결 화면의 수집 대상 탭·원천 등록으로 넘어간다.
  onEditScope: (sourceId: number) => void
  onRegister: () => void
}

// 분석 대상 선택: 원천의 표·컬럼을 골라 018로 분석을 맡긴다.
function AnalysisTargetDialog({
  runKind = 'INITIAL',
  initialSourceId,
  onClose,
  onStarted,
  onEditScope,
  onRegister,
}: Props) {
  const [chosenSourceId, setChosenSourceId] = useState(initialSourceId)
  const [tableQuery, setTableQuery] = useState('')
  const [onlyPicked, setOnlyPicked] = useState(false)
  const [listPage, setListPage] = useState(0)
  const [activeId, setActiveId] = useState<number>()
  const [selection, setSelection] = useState<Selection>({})
  const requesting = useRef(false)
  const backdropClicked = useRef(false)

  const sources = useQuery({
    queryKey: ['sources', 'list'],
    queryFn: fetchSources,
  })
  // 목록에 없는 원천 번호(삭제된 원천 등)로 열면 첫 원천으로 맞춰 선택칸과 표 목록이 어긋나지 않게 한다.
  const knownSource = sources.data?.some((item) => item.id === chosenSourceId)
  const sourceId = knownSource ? chosenSourceId : sources.data?.[0]?.id
  const source = sources.data?.find((item) => item.id === sourceId)
  const missingInitialSource =
    initialSourceId !== undefined &&
    chosenSourceId === initialSourceId &&
    sources.data !== undefined &&
    sources.data.length > 0 &&
    !sources.data.some((item) => item.id === initialSourceId)

  const resources = useQuery({
    // 표가 200개를 넘는 원천도 다 고를 수 있게 모든 쪽을 받는다(다른 원천 화면과 같은 조회)
    queryKey: ['sources', sourceId, 'resources', 'all'],
    queryFn: () => fetchAllResources(sourceId!),
    enabled: sourceId !== undefined,
  })

  // 목업처럼 선택칸에 원천별 자료 수를 보여 준다. 표 목록과 같은 캐시를 쓴다.
  const sourceResources = useQueries({
    queries: (sources.data ?? []).map((item) => ({
      queryKey: ['sources', item.id, 'resources', 'all'],
      queryFn: () => fetchAllResources(item.id),
    })),
  })

  const start = useMutation({
    mutationFn: () => {
      const targets: AnalysisTarget[] = Object.values(selection).map(
        ({ resource, columns, allColumns, whole }) =>
          // 자료를 통째로 골랐거나 표의 컬럼을 모두 골랐으면 컬럼 목록을 빼서 전체를 분석한다.
          whole || columns.length === allColumns
            ? { sourceResourceId: resource.id }
            : { sourceResourceId: resource.id, columns },
      )
      return startAnalysis(targets, runKind)
    },
    retry: false,
    onSuccess: onStarted,
    onSettled: () => {
      requesting.current = false
    },
  })

  const picked = Object.values(selection)
  const summary = {
    sources: new Set(picked.map(({ resource }) => resource.dataSourceId)).size,
    tables: picked.filter(({ resource }) => !isDocument(resource)).length,
    columns: picked.reduce((sum, item) => sum + item.columns.length, 0),
    documents: picked.filter(({ resource }) => isDocument(resource)).length,
  }

  const shownResources = (resources.data ?? []).filter(
    (resource) =>
      resource.resourceKey
        .toLowerCase()
        .includes(tableQuery.trim().toLowerCase()) &&
      (!onlyPicked || selection[resource.id]),
  )
  const page = Math.min(
    listPage,
    Math.max(0, Math.ceil(shownResources.length / RESOURCE_PAGE_SIZE) - 1),
  )
  const pageResources = shownResources.slice(
    page * RESOURCE_PAGE_SIZE,
    (page + 1) * RESOURCE_PAGE_SIZE,
  )
  const active =
    resources.data?.find((resource) => resource.id === activeId) ??
    pageResources[0]
  // 목록에 보이는 표의 컬럼 수. 컬럼 선택과 같은 캐시를 써서 표를 열 때 다시 부르지 않는다.
  const pageColumns = useQueries({
    queries: pageResources.map((resource) => ({
      queryKey: ['sources', sourceId, 'resources', resource.id, 'columns'],
      queryFn: () => fetchColumns(sourceId!, resource.id),
      enabled: sourceId !== undefined && !isDocument(resource),
    })),
  })

  function pickColumns(
    resource: SourceResource,
    columns: string[],
    allColumns: number,
  ) {
    setSelection((previous) => {
      const next = { ...previous }
      if (columns.length === 0) {
        delete next[resource.id]
      } else {
        next[resource.id] = { resource, columns, allColumns, whole: false }
      }
      return next
    })
  }

  function includeWhole(resource: SourceResource, include: boolean) {
    setSelection((previous) => {
      const next = { ...previous }
      if (include) {
        next[resource.id] = {
          resource,
          columns: [],
          allColumns: 0,
          whole: true,
        }
      } else {
        delete next[resource.id]
      }
      return next
    })
  }

  let resourceList
  if (sources.isError) {
    resourceList = <ErrorState error={sources.error} />
  } else if (sources.isPending) {
    resourceList = <LoadingState message="원천을 불러오는 중…" />
  } else if (sourceId === undefined) {
    // 원천이 없으면 표 조회를 하지 않아 isPending이 계속 참으로 남는다.
    resourceList = (
      <EmptyState message="등록된 원천이 없습니다. 먼저 원천을 등록해 주세요." />
    )
  } else if (resources.isPending) {
    resourceList = <LoadingState message="표를 불러오는 중…" />
  } else if (resources.isError) {
    resourceList = (
      <ErrorState
        error={resources.error}
        onRetry={() => {
          void resources.refetch()
        }}
      />
    )
  } else if (shownResources.length === 0) {
    resourceList = <EmptyState message="조건에 맞는 표가 없습니다." />
  } else {
    resourceList = (
      <ul aria-label="표·자료 목록" className="space-y-2">
        {pageResources.map((resource, index) => {
          const chosen = selection[resource.id]
          const columnCount = pageColumns[index]?.data?.filter(
            (column) => column.isPresent,
          ).length
          return (
            <li key={resource.id}>
              <button
                type="button"
                aria-pressed={resource.id === active?.id}
                onClick={() => setActiveId(resource.id)}
                className={`w-full rounded-lg border px-3 py-2 text-left ${
                  resource.id === active?.id
                    ? 'border-slate-900 bg-white'
                    : 'border-slate-200 bg-white hover:border-slate-400'
                }`}
              >
                <span className="block font-medium text-slate-900">
                  {resourceTitle(resource)}
                </span>
                <span className="text-xs text-slate-500">
                  {isDocument(resource) ? '문서·자료' : '테이블'}
                  {columnCount !== undefined && ` · ${columnCount}개 컬럼`}
                  {chosen
                    ? chosen.whole
                      ? ' · 자료 전체 선택'
                      : ` · ${chosen.columns.length}개 컬럼 선택`
                    : ''}
                </span>
              </button>
            </li>
          )
        })}
      </ul>
    )
  }

  // 접수 중에는 닫지 않는다(닫아도 접수가 끝나면 화면이 넘어갔다). 고른 것이 있으면 배경 클릭 한 번으로 선택을 잃지 않게 한다.
  function close() {
    const fromBackdrop = backdropClicked.current
    backdropClicked.current = false
    if (requesting.current || start.isPending) return
    if (fromBackdrop && picked.length > 0) return
    onClose()
  }

  return (
    <div
      className="contents"
      onClickCapture={(event) => {
        backdropClicked.current =
          event.target === event.currentTarget.firstElementChild
      }}
    >
      <Dialog
        title={
          runKind === 'REANALYSIS' ? '변경 데이터 재분석' : '분석 대상 선택'
        }
        size="wide"
        onClose={close}
      >
        <div className="mt-4 space-y-4 text-sm">
          {runKind === 'REANALYSIS' && (
            <p className="rounded-lg bg-amber-50 p-3 text-amber-900">
              변경된 테이블·컬럼을 직접 선택하세요. 선택한 범위의 현재 데이터를
              다시 분석합니다. 변경 행만 분석하는 방식은 아니며, 기존 확정
              내용은 자동으로 변경하지 않습니다.
            </p>
          )}
          <div className="flex flex-wrap items-end gap-3">
            <label className="flex w-full max-w-sm flex-col gap-2">
              등록된 원천
              <select
                value={sourceId ?? ''}
                onChange={(event) => {
                  setChosenSourceId(Number(event.target.value))
                  setActiveId(undefined)
                  setListPage(0)
                }}
                className={inputClass}
              >
                {(sources.data ?? []).map((item, index) => {
                  const count = sourceResources[index]?.data?.length
                  return (
                    <option key={item.id} value={item.id}>
                      {count === undefined
                        ? item.name
                        : `${item.name} · ${count}개 자료`}
                    </option>
                  )
                })}
              </select>
            </label>
            <div className="ml-auto flex gap-2">
              {source && (
                <Button
                  variant="secondary"
                  onClick={() => onEditScope(source.id)}
                >
                  이 원천의 수집 대상 변경
                </Button>
              )}
              <Button variant="secondary" onClick={onRegister}>
                + 원천 등록
              </Button>
            </div>
          </div>
          {missingInitialSource && (
            <p role="status" className="text-amber-800">
              열려던 원천을 찾을 수 없어 첫 원천을 보여 줍니다.
            </p>
          )}

          <div className="grid gap-4 md:grid-cols-[16rem_1fr]">
            <section
              aria-label="테이블·자료 찾기"
              className="space-y-3 rounded-lg bg-slate-50 p-3"
            >
              <h3 className="font-semibold text-slate-900">테이블·자료 찾기</h3>
              <input
                value={tableQuery}
                onChange={(event) => {
                  setTableQuery(event.target.value)
                  setListPage(0)
                }}
                placeholder="테이블·자료 이름 검색"
                aria-label="테이블·자료 이름 검색"
                className={`${inputClass} bg-white`}
              />
              <label className="flex items-center gap-2">
                <input
                  type="checkbox"
                  checked={onlyPicked}
                  onChange={(event) => {
                    setOnlyPicked(event.target.checked)
                    setListPage(0)
                  }}
                />
                이 원천에서 선택한 자료만
              </label>
              {resourceList}
              {resources.data && shownResources.length > RESOURCE_PAGE_SIZE && (
                <Pagination
                  page={page}
                  size={RESOURCE_PAGE_SIZE}
                  totalElements={shownResources.length}
                  onChange={setListPage}
                />
              )}
            </section>
            <section aria-label="컬럼 선택">
              {active && source ? (
                <ColumnPicker
                  key={active.id}
                  sourceId={source.id}
                  resource={active}
                  sourceName={source.name}
                  picked={selection[active.id]?.columns ?? []}
                  onChange={(columns, allColumns) =>
                    pickColumns(active, columns, allColumns)
                  }
                  included={selection[active.id]?.whole ?? false}
                  onInclude={(include) => includeWhole(active, include)}
                />
              ) : (
                <EmptyState message="왼쪽에서 표를 골라 주세요." />
              )}
            </section>
          </div>

          {start.isError && <ErrorState error={start.error} />}

          {runKind === 'REANALYSIS' && (
            <section
              aria-label="재분석 범위 확인"
              className="space-y-2 rounded-lg bg-slate-50 p-3"
            >
              <h3 className="font-semibold">재분석 범위 확인</h3>
              {picked.length ? (
                <ul className="space-y-2">
                  {picked.map((item) => (
                    <li key={item.resource.id}>
                      <p>
                        {sources.data?.find(
                          (source) => source.id === item.resource.dataSourceId,
                        )?.name ?? `원천 #${item.resource.dataSourceId}`}{' '}
                        · {item.resource.resourceKey}
                      </p>
                      <p className="break-words">
                        {item.whole
                          ? '자료 전체'
                          : item.columns.length === item.allColumns
                            ? '전체 컬럼'
                            : item.columns.join(', ')}
                      </p>
                    </li>
                  ))}
                </ul>
              ) : (
                <p>변경된 자료와 컬럼을 선택해 주세요.</p>
              )}
              <p className="text-slate-500">
                같은 범위의 분석이 진행 중이면 기존 실행에 합류합니다.
              </p>
            </section>
          )}

          <div className="flex flex-wrap items-center justify-between gap-3 border-t border-slate-200 pt-4">
            <p aria-live="polite" className="text-slate-700">
              {`${summary.sources}개 원천 · ${summary.tables}개 테이블 · ${summary.columns}개 컬럼 · ${summary.documents}개 문서·자료`}
            </p>
            <div className="flex gap-2">
              <Button
                variant="secondary"
                disabled={start.isPending}
                onClick={close}
              >
                취소
              </Button>
              <Button
                disabled={picked.length === 0}
                pending={start.isPending}
                onClick={() => {
                  if (requesting.current || picked.length === 0) return
                  requesting.current = true
                  start.mutate()
                }}
              >
                {runKind === 'REANALYSIS'
                  ? '재분석하고 후보 받기'
                  : '분석하고 후보 받기'}
              </Button>
            </div>
          </div>
        </div>
      </Dialog>
    </div>
  )
}

export default AnalysisTargetDialog
