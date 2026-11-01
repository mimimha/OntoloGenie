import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import Pagination from '../../components/Pagination'
import { fetchObjects, OBJECT_PAGE_SIZE, type ObjectFilters } from './api'
import ObjectCard from './ObjectCard'
import ObjectDetail from './ObjectDetail'
import ObjectGraph from './ObjectGraph'
import ObjectSearchBar from './ObjectSearchBar'
import { formatObjectCode } from './format'

function ExplorerPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [filters, setFilters] = useState<ObjectFilters>({})
  const [page, setPage] = useState(0)

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

  // 일반 사용자용 객체 유형 목록 API가 없어서, 조건 없이 받은 목록에 나온 유형으로 선택지를 만든다.
  const { data: allObjects } = useQuery({
    queryKey: ['objects', {}, 0],
    queryFn: () => fetchObjects({}),
  })
  const objectTypes = [
    ...new Map(
      allObjects?.data.map(
        (item) => [item.objectTypeId, item.objectTypeName] as const,
      ),
    ),
  ].map(([id, name]) => ({ id, name }))

  function handleSearch(nextFilters: ObjectFilters) {
    setFilters(nextFilters)
    setPage(0)
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
        onSelect={() => setSelectedId(item.id)}
      />
    ))
  }

  return (
    <div>
      <header className="mb-6">
        <p className="text-xs font-semibold tracking-widest text-slate-500">
          WORKSPACE
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">
          업무 데이터
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          흩어진 업무 데이터를 연결하고, 객체 간 관계를 한눈에 탐색하세요.
        </p>
      </header>

      <ObjectSearchBar objectTypes={objectTypes} onSearch={handleSearch} />

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
          className="rounded-xl border border-slate-200 bg-white p-4"
        >
          <ObjectGraph objectId={selectedId} onSelect={setSelectedId} />
        </section>

        <section className="rounded-xl border border-slate-200 bg-white p-6">
          <ObjectDetail objectId={selectedId} />
        </section>
      </div>
    </div>
  )
}

export default ExplorerPage
