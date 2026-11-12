import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ApiError } from '../../api/client'
import { fetchObjectProperties, type ObjectFilters } from './api'
import type { ObjectTypeOption } from './types'

type ObjectSearchBarProps = {
  filters: ObjectFilters
  objectTypes: ObjectTypeOption[]
  onSearch: (filters: ObjectFilters) => void
}

const labelClass = 'mb-1.5 block text-xs font-semibold text-slate-700'
const fieldClass =
  'w-full min-w-0 rounded-lg border border-slate-300 px-3 py-1.5 text-sm disabled:bg-slate-100 disabled:text-slate-400'

function ObjectSearchBar({
  filters,
  objectTypes,
  onSearch,
}: ObjectSearchBarProps) {
  const [typeId, setTypeId] = useState(
    filters.objectTypeId ? String(filters.objectTypeId) : '',
  )
  const [keyword, setKeyword] = useState(filters.q ?? '')
  const initialProperty = Object.entries(filters.propertyValues ?? {})[0]
  const [propertyCode, setPropertyCode] = useState(initialProperty?.[0] ?? '')
  const [propertyValue, setPropertyValue] = useState(initialProperty?.[1] ?? '')
  const {
    data: properties,
    isPending,
    error,
    refetch,
  } = useQuery({
    queryKey: ['ontology', 'object-properties', typeId],
    queryFn: () => fetchObjectProperties(Number(typeId)),
    enabled: Boolean(typeId),
    retry: false,
  })
  const searchableProperties =
    properties?.filter((property) => property.dataType !== 'JSON') ?? []
  const selectedProperty = searchableProperties.find(
    (property) => property.code === propertyCode,
  )
  const isForbidden = error instanceof ApiError && error.status === 403
  const showPropertyHelp =
    !typeId || isPending || Boolean(error) || searchableProperties.length === 0
  const propertyUnavailable =
    Boolean(propertyCode) && (!selectedProperty || Boolean(error))

  function handleReset() {
    setTypeId('')
    setKeyword('')
    setPropertyCode('')
    setPropertyValue('')
    onSearch({})
  }

  return (
    <form
      className="grid gap-3 rounded-lg bg-white p-4 lg:grid-cols-[140px_minmax(190px,1.4fr)_150px_minmax(150px,1fr)_auto] lg:items-end"
      onSubmit={(event) => {
        event.preventDefault()
        if (propertyUnavailable) return
        onSearch({
          q: keyword.trim() || undefined,
          objectTypeId: typeId ? Number(typeId) : undefined,
          ...(selectedProperty
            ? { propertyValues: { [propertyCode]: propertyValue } }
            : {}),
        })
      }}
    >
      <div>
        <label htmlFor="object-type-filter" className={labelClass}>
          객체 유형
        </label>
        <select
          id="object-type-filter"
          value={typeId}
          onChange={(event) => {
            setTypeId(event.target.value)
            setPropertyCode('')
            setPropertyValue('')
          }}
          className={fieldClass}
        >
          <option value="">전체</option>
          {objectTypes.map((type) => (
            <option key={type.id} value={type.id}>
              {type.name}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor="object-search" className={labelClass}>
          ID · 이름 검색
        </label>
        <input
          id="object-search"
          type="search"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          placeholder="예: PR-03, 프레스"
          className={fieldClass}
        />
      </div>

      <div>
        <label htmlFor="property-filter" className={labelClass}>
          속성 조건
        </label>
        <select
          id="property-filter"
          value={selectedProperty ? propertyCode : ''}
          disabled={
            !typeId ||
            isPending ||
            Boolean(error) ||
            searchableProperties.length === 0
          }
          onChange={(event) => {
            setPropertyCode(event.target.value)
            setPropertyValue('')
          }}
          aria-describedby={
            showPropertyHelp ? 'property-filter-help' : undefined
          }
          className={fieldClass}
        >
          <option value="">전체 속성</option>
          {searchableProperties.map((property) => (
            <option key={property.id} value={property.code}>
              {property.name}
            </option>
          ))}
        </select>
      </div>

      <div>
        <label htmlFor="property-value" className={labelClass}>
          속성 값 · 정확 일치
        </label>
        <input
          id="property-value"
          disabled={!selectedProperty || Boolean(error)}
          value={propertyValue}
          onChange={(event) => setPropertyValue(event.target.value)}
          placeholder={
            selectedProperty?.dataType === 'BOOLEAN'
              ? 'true 또는 false'
              : '일치하는 값을 입력하세요'
          }
          className={fieldClass}
        />
      </div>

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={propertyUnavailable}
          className="rounded-lg bg-slate-900 px-3 py-1.5 text-sm font-medium text-white disabled:opacity-50"
        >
          검색
        </button>
        <button
          type="button"
          onClick={handleReset}
          className="rounded-lg border border-slate-300 px-3 py-1.5 text-sm text-slate-700"
        >
          초기화
        </button>
      </div>

      {showPropertyHelp && (
        <div
          id="property-filter-help"
          className="col-span-full text-xs text-slate-500"
        >
          {!typeId ? (
            '객체 유형을 먼저 선택하면 속성 조건을 사용할 수 있습니다.'
          ) : isPending ? (
            '속성 목록을 불러오는 중…'
          ) : isForbidden ? (
            '이 계정은 속성 목록 조회 권한이 없습니다.'
          ) : error ? (
            <span role="alert">
              속성 목록을 불러오지 못했습니다.{' '}
              <button
                type="button"
                className="underline"
                onClick={() => void refetch()}
              >
                속성 다시 불러오기
              </button>
            </span>
          ) : (
            '정확 일치로 검색할 수 있는 속성이 없습니다.'
          )}
        </div>
      )}
    </form>
  )
}

export default ObjectSearchBar
