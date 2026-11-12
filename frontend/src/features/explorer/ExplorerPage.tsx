import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { lazy, Suspense, useState } from 'react'
import { useSearchParams } from 'react-router'
import Pagination from '../../components/Pagination'
import { ApiError } from '../../api/client'
import {
  fetchModelGraph,
  fetchObjects,
  fetchObjectTypes,
  OBJECT_PAGE_SIZE,
  type ObjectFilters,
} from './api'
import ObjectCard from './ObjectCard'
import ObjectDetail from './ObjectDetail'
import ObjectGraph from './ObjectGraph'
import ObjectSearchBar from './ObjectSearchBar'
import TypeRelations from './TypeRelations'
import { formatObjectCode } from './format'

// 유형 관계도는 그래프 라이브러리(React Flow)가 커서, 처음 화면에 그릴 때 따로 받아 온다.
const ModelGraph = lazy(() => import('./ModelGraph'))

// 주소의 ?object= 값이 1 이상의 정수일 때만 객체 번호로 본다.
function parseObjectId(value: string | null) {
  const id = Number(value)
  return Number.isInteger(id) && id > 0 ? id : null
}

function ExplorerPage() {
  // 고른 객체는 주소의 ?object=에 둔다. Agent 답변의 [객체 열기], 새로고침, 뒤로 가기가 같은 객체를 연다(151).
  const [searchParams, setSearchParams] = useSearchParams()
  const selectedId = parseObjectId(searchParams.get('object'))
  const [filters, setFilters] = useState<ObjectFilters>({})
  const [page, setPage] = useState(0)

  // 객체를 고르면 주소에 번호를 쓰고, 선택을 풀면 지운다. 기록을 쌓지 않아 뒤로 가기는 이전 화면으로 간다.
  function selectObject(id: number | null) {
    setSearchParams(
      (previous) => {
        const next = new URLSearchParams(previous)
        if (id === null) {
          next.delete('object')
        } else {
          next.set('object', String(id))
        }
        return next
      },
      { replace: true },
    )
  }

  const {
    data: objectPage,
    isPending,
    isFetching,
    isError,
    error,
  } = useQuery({
    queryKey: ['objects', filters, page],
    queryFn: () => fetchObjects(filters, page),
    placeholderData: keepPreviousData,
  })

  // 객체를 고르기 전 가운데에 보여 줄 객체 유형 관계도(042).
  // 관리자 전용 API의 403은 객체 선택 안내로 처리한다.
  const {
    data: modelGraph,
    isPending: isModelGraphPending,
    isFetching: isModelGraphFetching,
    error: modelGraphError,
    refetch: refetchModelGraph,
  } = useQuery({
    queryKey: ['ontology', 'model-graph'],
    queryFn: fetchModelGraph,
    // 권한이 없으면 다시 시도해도 같은 결과라, 기다리지 않고 바로 안내로 넘어간다.
    retry: false,
  })
  const isModelGraphForbidden =
    modelGraphError instanceof ApiError && modelGraphError.status === 403

  const {
    data: objectTypes = [],
    error: objectTypesError,
    refetch: refetchObjectTypes,
  } = useQuery({
    queryKey: ['objects', 'types'],
    queryFn: fetchObjectTypes,
  })

  function handleSearch(nextFilters: ObjectFilters) {
    setFilters(nextFilters)
    setPage(0)
  }

  // 유형 카드를 누르면 검색어는 그대로 두고 객체 유형만 바꾼다.
  function handleSelectType(typeId: number) {
    handleSearch({ q: filters.q, objectTypeId: typeId })
  }

  const totalCount = objectPage?.meta?.totalElements ?? objectPage?.data.length

  let listContent
  if (isPending) {
    listContent = <p className="text-slate-500">불러오는 중…</p>
  } else if (isError) {
    listContent = <p className="text-red-600">{error.message}</p>
  } else if (objectPage.data.length === 0) {
    listContent = (
      <p className="text-sm text-slate-500">조건에 맞는 객체가 없습니다.</p>
    )
  } else {
    listContent = objectPage.data.map((item) => (
      <ObjectCard
        key={item.id}
        code={formatObjectCode(item.id)}
        name={item.displayName}
        typeLabel={item.objectTypeName}
        selected={item.id === selectedId}
        onSelect={() => selectObject(item.id)}
      />
    ))
  }

  return (
    <div>
      <h1 className="sr-only">업무 데이터</h1>

      {/* 유형 카드로 조건이 바뀌면 검색 줄 칸도 그 조건으로 다시 채운다. */}
      <ObjectSearchBar
        key={JSON.stringify(filters)}
        filters={filters}
        objectTypes={objectTypes}
        onSearch={handleSearch}
      />
      {objectTypesError && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          객체 유형을 불러오지 못했습니다.{' '}
          <button
            type="button"
            onClick={() => void refetchObjectTypes()}
            className="underline"
          >
            유형 다시 불러오기
          </button>
        </p>
      )}

      <div className="mt-4 grid gap-6 xl:grid-cols-[minmax(200px,0.7fr)_minmax(480px,1.5fr)_minmax(280px,1fr)]">
        <section aria-label="객체 목록" className="flex flex-col gap-2">
          <div className="flex items-baseline gap-2">
            <h2 className="text-lg font-semibold text-slate-900">객체 목록</h2>
            {totalCount !== undefined && (
              <span className="text-sm text-slate-500">{totalCount}건</span>
            )}
            {isFetching && !isPending && (
              <span className="text-sm text-slate-400">검색 중…</span>
            )}
          </div>
          {listContent}

          {objectPage && objectPage.data.length > 0 && (
            <Pagination
              page={page}
              size={OBJECT_PAGE_SIZE}
              totalElements={totalCount ?? 0}
              onChange={setPage}
            />
          )}
        </section>

        <section
          aria-label="관계도"
          className="min-w-0 rounded-xl border border-slate-200 bg-white p-4"
        >
          {selectedId === null && modelGraphError && !isModelGraphForbidden && (
            <div role="alert" className="mb-3 text-sm text-red-600">
              <p>
                유형 관계도를 불러오지 못했습니다. {modelGraphError.message}
              </p>
              <button
                type="button"
                disabled={isModelGraphFetching}
                onClick={() => void refetchModelGraph()}
                className="mt-2 rounded-lg border border-slate-300 px-3 py-1.5 text-slate-700 disabled:opacity-50"
              >
                {isModelGraphFetching ? '다시 불러오는 중…' : '다시 시도'}
              </button>
            </div>
          )}
          {selectedId !== null ? (
            <>
              <button
                type="button"
                onClick={() => selectObject(null)}
                className="mb-3 rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
              >
                ← 전체 구조로
              </button>
              <ObjectGraph objectId={selectedId} onSelect={selectObject} />
            </>
          ) : isModelGraphPending ? (
            <p className="text-slate-500">유형 관계도를 불러오는 중…</p>
          ) : modelGraph && !isModelGraphForbidden ? (
            <Suspense
              fallback={
                <p className="text-slate-500">유형 관계도를 불러오는 중…</p>
              }
            >
              <ModelGraph
                graph={modelGraph}
                selectedTypeId={filters.objectTypeId ?? null}
                onSelect={handleSelectType}
              />
            </Suspense>
          ) : !modelGraphError || isModelGraphForbidden ? (
            <ObjectGraph objectId={null} onSelect={selectObject} />
          ) : null}
        </section>

        <section className="rounded-xl border border-slate-200 bg-white p-6">
          {selectedId !== null || !modelGraph || isModelGraphForbidden ? (
            <ObjectDetail objectId={selectedId} />
          ) : filters.objectTypeId ? (
            <TypeRelations graph={modelGraph} typeId={filters.objectTypeId} />
          ) : (
            <p className="text-slate-500">
              가운데에서 객체 유형을 고르거나 왼쪽 목록에서 객체를 선택해
              주세요.
            </p>
          )}
        </section>
      </div>
    </div>
  )
}

export default ExplorerPage
