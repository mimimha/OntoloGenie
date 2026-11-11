import { useState } from 'react'
import { formatObjectCode, withAndParticle } from './format'
import { useQuery } from '@tanstack/react-query'
import Button from '../../components/Button'
import { fetchObject } from './api'
import {
  arrowLine,
  groupLinks,
  labelStyle,
  placeGroups,
  type LinkGroup,
} from './graphLayout'
import { useObjectLinks } from './useObjectLinks'

type ObjectGraphProps = {
  objectId: number | null
  onSelect: (id: number) => void
}

function ObjectGraph({ objectId, onSelect }: ObjectGraphProps) {
  if (objectId === null) {
    return (
      <p className="text-slate-500">객체를 선택하면 연결 관계를 표시합니다.</p>
    )
  }

  return (
    <ObjectGraphContent
      key={objectId}
      objectId={objectId}
      onSelect={onSelect}
    />
  )
}

type ObjectGraphContentProps = {
  objectId: number
  onSelect: (id: number) => void
}

function ObjectGraphContent({ objectId, onSelect }: ObjectGraphContentProps) {
  const [selectedKey, setSelectedKey] = useState<string | null>(null)
  const objectQuery = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
  })
  const linksQuery = useObjectLinks(objectId)

  if (objectQuery.isPending || linksQuery.isPending) {
    return <p className="text-slate-500">관계도를 불러오는 중…</p>
  }

  if (objectQuery.isError) {
    return <p className="text-red-600">{objectQuery.error.message}</p>
  }

  if (linksQuery.isError && !linksQuery.data) {
    return <p className="text-red-600">{linksQuery.error.message}</p>
  }

  const object = objectQuery.data
  const { links, totalCount } = linksQuery
  const groups = groupLinks(links, objectId)
  const nodes = placeGroups(groups)
  const selectedGroup =
    groups.find((group) => group.key === selectedKey) ?? groups[0]

  return (
    <div>
      <h2 className="mb-3 text-lg font-semibold text-slate-900">
        {withAndParticle(object.displayName)} 연결된 정보
      </h2>

      <div className="relative h-[440px] overflow-hidden bg-[radial-gradient(#d8dce1_1px,transparent_1px)] bg-[length:16px_16px]">
        <svg className="absolute inset-0 h-full w-full" aria-hidden="true">
          <defs>
            <marker
              id="graph-arrow"
              viewBox="0 0 10 10"
              refX="9"
              refY="5"
              markerWidth="6"
              markerHeight="6"
              orient="auto"
            >
              <path d="M 0 0 L 10 5 L 0 10 z" fill="#94a3b8" />
            </marker>
          </defs>
          {nodes.map((node) => {
            const arrow = arrowLine(node)
            return (
              <g key={node.group.key} stroke="#94a3b8" strokeWidth={1.5}>
                <line x1="50%" y1="50%" x2={`${node.x}%`} y2={`${node.y}%`} />
                <line
                  x1={`${arrow.x1}%`}
                  y1={`${arrow.y1}%`}
                  x2={`${arrow.x2}%`}
                  y2={`${arrow.y2}%`}
                  markerEnd="url(#graph-arrow)"
                />
              </g>
            )
          })}
        </svg>

        {nodes.map((node) => (
          <span
            key={`label-${node.group.key}`}
            className="absolute max-w-32 truncate rounded bg-white/80 px-1 text-xs text-slate-600"
            style={labelStyle(node)}
            title={node.group.linkTypeName}
          >
            {node.group.linkTypeName}
          </span>
        ))}

        <div className="absolute left-1/2 top-1/2 w-24 -translate-x-1/2 -translate-y-1/2 rounded-lg border border-slate-900 bg-slate-900 px-2 py-2 text-center text-xs ring-4 ring-slate-200">
          <span className="block text-slate-300">
            {formatObjectCode(object.id)}
          </span>
          <strong className="block text-white">{object.displayName}</strong>
          <span className="text-slate-300">{object.objectTypeName}</span>
        </div>

        {nodes.map((node) => {
          const selected = node.group.key === selectedGroup?.key
          return (
            <button
              key={node.group.key}
              type="button"
              onClick={() => setSelectedKey(node.group.key)}
              aria-pressed={selected}
              className={`absolute w-28 -translate-x-1/2 -translate-y-1/2 rounded-lg border px-2 py-2 text-center text-xs ${
                selected
                  ? 'border-slate-900 bg-slate-100'
                  : 'border-slate-300 bg-white hover:border-slate-900'
              }`}
              style={{ left: `${node.x}%`, top: `${node.y}%` }}
            >
              <strong className="block truncate text-slate-900">
                {node.group.relatedTypeName}
              </strong>
              <span className="text-slate-500">
                {`${node.group.links.length}건`}
              </span>
            </button>
          )
        })}
      </div>

      <p className="mt-2 text-xs text-slate-700">
        {`연결된 정보 · ${totalCount}건`}
      </p>
      {linksQuery.hasNextPage && (
        <div className="mt-1 flex flex-wrap items-center gap-2 text-xs text-slate-500">
          <span>
            {`지금은 불러온 ${links.length}건만 유형별로 묶어 보여 줍니다.`}
          </span>
          <Button
            variant="secondary"
            className="px-2 py-1 text-xs"
            pending={linksQuery.isFetchingNextPage}
            onClick={() => {
              void linksQuery.fetchNextPage()
            }}
          >
            더 불러오기
          </Button>
        </div>
      )}
      {linksQuery.isFetchNextPageError && (
        <p className="mt-1 text-xs text-red-600">
          {linksQuery.error?.message ?? '연결을 더 불러오지 못했습니다.'}
        </p>
      )}
      <p className="text-xs text-slate-500">
        {groups.length > 0
          ? '화살표는 저장된 관계 방향입니다. 유형 카드를 고르면 아래에 그 유형의 객체를 보여 줍니다.'
          : '이 객체에는 연결된 관계가 없습니다.'}
      </p>

      {selectedGroup && (
        <GroupObjectList group={selectedGroup} onSelect={onSelect} />
      )}
    </div>
  )
}

type GroupObjectListProps = {
  group: LinkGroup
  onSelect: (id: number) => void
}

function GroupObjectList({ group, onSelect }: GroupObjectListProps) {
  return (
    <section aria-label={`${group.relatedTypeName} 연결 목록`} className="mt-4">
      <h3 className="text-sm font-semibold text-slate-900">
        {`${group.linkTypeName} · ${group.relatedTypeName} ${group.links.length}건`}
      </h3>
      <ul className="mt-2 max-h-64 divide-y divide-slate-100 overflow-y-auto rounded-lg border border-slate-200">
        {group.links.map((link) => (
          <li key={link.id}>
            <button
              type="button"
              onClick={() => onSelect(link.relatedObject.id)}
              className="flex w-full items-center justify-between gap-2 px-3 py-2 text-left text-sm hover:bg-slate-50"
            >
              <span className="truncate text-slate-900">
                {link.relatedObject.displayName}
              </span>
              <span className="shrink-0 text-xs text-slate-500">
                {formatObjectCode(link.relatedObject.id)}
              </span>
            </button>
          </li>
        ))}
      </ul>
    </section>
  )
}

export default ObjectGraph
