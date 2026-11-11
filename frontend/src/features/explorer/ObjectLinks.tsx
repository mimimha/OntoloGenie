import StatusBadge from '../../components/StatusBadge'
import DataTable from '../../components/DataTable'
import { LoadingState, EmptyState, ErrorState } from '../../components/Feedback'
import { useQuery } from '@tanstack/react-query'
import { fetchObjectLinks } from './api'
import { formatSourceKey } from './format'
import type { ObjectLink } from './types'

type LinkEvidenceProps = {
  link: ObjectLink
}

function LinkSummary({ link }: LinkEvidenceProps) {
  const evidence = link.evidence

  if (link.origin === 'RULE') {
    if (!evidence.comparisons?.length) return null
    const matchedValues = evidence.comparisons.map(
      (condition) => condition.matchedValue,
    )
    return <p>{`비교 값 ${matchedValues.join(' / ')} 일치`}</p>
  }

  if (link.origin === 'DOCUMENT') {
    const expression = link.expressionKind === 'ESTIMATED' ? '추정' : '관찰'
    return (
      <p>
        {`원문: “${evidence.quote ?? '-'}”`}
        <StatusBadge tone="warning">{expression}</StatusBadge>
      </p>
    )
  }

  return <p>관리자가 직접 연결</p>
}

function ComparisonValue({
  property,
  value,
  column,
}: {
  property: string
  value: string
  column?: string
}) {
  return (
    <>
      <span>{`${property} = ${value}`}</span>
      {column && column !== property && <p>현재 원천 컬럼 {column}</p>}
    </>
  )
}

function LinkDetail({ link }: LinkEvidenceProps) {
  const evidence = link.evidence

  if (link.origin === 'RULE') {
    const comparisons = evidence.comparisons ?? []
    const hasSourceKey = Object.values(evidence.sourceKey ?? {}).some(
      (value) => value != null,
    )
    if (!hasSourceKey && !comparisons.length) return null
    return (
      <details className="mt-1 text-xs text-slate-500">
        <summary className="cursor-pointer">상세 근거</summary>
        {hasSourceKey && (
          <dl className="mt-1 grid grid-cols-[5rem_1fr] gap-y-1">
            <dt>원천 행</dt>
            <dd>{formatSourceKey(evidence.sourceKey)}</dd>
          </dl>
        )}
        {comparisons.length > 0 && (
          <table
            aria-label="비교 조건"
            className="mt-2 w-full table-fixed text-left"
          >
            <thead>
              <tr>
                <th scope="col" className="py-1 pr-3 font-medium">
                  출발 원본
                </th>
                <th scope="col" className="py-1 font-medium">
                  비교 대상
                </th>
              </tr>
            </thead>
            <tbody>
              {comparisons.map((condition) => (
                <tr key={condition.sequenceNo}>
                  <td className="break-all whitespace-pre-wrap py-1 pr-3">
                    <ComparisonValue
                      property={condition.sourceProperty}
                      value={condition.sourceValue}
                      column={condition.sourceColumn}
                    />
                  </td>
                  <td className="break-all whitespace-pre-wrap py-1">
                    <ComparisonValue
                      property={condition.targetProperty}
                      value={condition.targetValue}
                      column={condition.targetColumn}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </details>
    )
  }

  if (link.origin === 'DOCUMENT') {
    return (
      <details className="mt-1 text-xs text-slate-500">
        <summary className="cursor-pointer">상세 근거</summary>
        <dl className="mt-1 grid grid-cols-[5rem_1fr] gap-y-1">
          <dt>사건</dt>
          <dd>{evidence.event ?? '-'}</dd>
          <dt>시간 표현</dt>
          <dd>{evidence.timeExpression ?? '-'}</dd>
        </dl>
      </details>
    )
  }

  return null
}

type ObjectLinksProps = {
  objectId: number
}

function ObjectLinks({ objectId }: ObjectLinksProps) {
  const {
    data: linkPage,
    isPending,
    isError,
    error,
    refetch,
  } = useQuery({
    queryKey: ['objects', objectId, 'links'],
    queryFn: () => fetchObjectLinks(objectId),
  })

  if (isPending) {
    return <LoadingState message="연결을 불러오는 중…" />
  }

  if (isError) {
    return (
      <ErrorState
        error={error}
        onRetry={() => {
          void refetch()
        }}
      />
    )
  }

  if (linkPage.data.length === 0) {
    return <EmptyState message="현재 연결된 정보가 없습니다." />
  }

  return (
    <DataTable label="연결 근거" className="min-w-[480px] table-fixed">
      <thead className="text-xs text-slate-500">
        <tr>
          <th className="w-[26%] px-3 py-2 font-normal">연결된 정보</th>
          <th className="w-[24%] py-2 pr-3 font-normal">관계</th>
          <th className="py-2 pr-3 font-normal">핵심 근거</th>
        </tr>
      </thead>
      <tbody className="divide-y divide-slate-100 align-top">
        {linkPage.data.map((link) => (
          <tr key={link.id}>
            <td className="px-3 py-2 [overflow-wrap:anywhere]">
              {link.relatedObject.displayName}
              <p className="text-xs text-slate-500">
                {link.relatedObject.objectTypeName}
              </p>
            </td>
            <td className="py-2 pr-3 [overflow-wrap:anywhere]">
              {link.linkTypeName}
              <p className="text-xs text-slate-500">
                {link.sourceObjectId === objectId
                  ? '나가는 연결'
                  : '들어오는 연결'}
              </p>
            </td>
            <td className="py-2 pr-3 [overflow-wrap:anywhere]">
              <LinkSummary link={link} />
              <LinkDetail link={link} />
            </td>
          </tr>
        ))}
      </tbody>
    </DataTable>
  )
}

export default ObjectLinks
