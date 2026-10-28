import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { fetchObjects } from './api'
import { objectTypeLabel } from './labels'
import ObjectCard from './ObjectCard'
import ObjectDetail from './ObjectDetail'

function ExplorerPage() {
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const {
    data: objectPage,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ['objects'],
    queryFn: fetchObjects,
  })

  if (isPending) {
    return <p className="text-slate-500">불러오는 중…</p>
  }

  if (isError) {
    return <p className="text-red-600">{error.message}</p>
  }

  const objects = objectPage.data
  const totalCount = objectPage.meta?.totalElements ?? objects.length

  return (
    <div className="grid gap-6 md:grid-cols-[20rem_1fr]">
      <section className="flex flex-col gap-2">
        <div className="flex items-baseline gap-2">
          <h1 className="text-xl font-semibold text-slate-900">객체 목록</h1>
          <span className="text-sm text-slate-500">{totalCount}건</span>
        </div>
        {objects.map((item) => (
          <ObjectCard
            key={item.id}
            name={item.displayName}
            typeLabel={objectTypeLabel(item)}
            selected={item.id === selectedId}
            onSelect={() => setSelectedId(item.id)}
          />
        ))}
      </section>

      <section className="rounded-xl border border-slate-200 bg-white p-6">
        <ObjectDetail objectId={selectedId} />
      </section>
    </div>
  )
}

export default ExplorerPage
