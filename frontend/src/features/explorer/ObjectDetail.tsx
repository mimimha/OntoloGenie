import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useNavigate } from 'react-router'
import Button from '../../components/Button'
import StatusBadge from '../../components/StatusBadge'
import { createConversation } from '../agent/api'
import type { AgentConversation, AgentEntryState } from '../agent/types'
import { fetchObject } from './api'
import EvidenceDialog from './EvidenceDialog'
import { formatObjectKey, formatPropertyValue } from './format'
import { statusLabels } from './labels'
import type { ObjectInstanceDetail } from './types'

function formatDateTime(value: string | null | undefined) {
  if (!value) {
    return '-'
  }
  return new Date(value).toLocaleString('ko-KR')
}

type ObjectDetailProps = {
  objectId: number | null
}

function ObjectDetail({ objectId }: ObjectDetailProps) {
  if (objectId === null) {
    return <p className="text-slate-500">왼쪽 목록에서 객체를 선택해 주세요.</p>
  }

  return <ObjectDetailContent key={objectId} objectId={objectId} />
}

type ObjectDetailContentProps = {
  objectId: number
}

function ObjectDetailContent({ objectId }: ObjectDetailContentProps) {
  const [evidenceOpen, setEvidenceOpen] = useState(false)
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const {
    data: object,
    isPending,
    isError,
    error,
  } = useQuery({
    queryKey: ['objects', objectId],
    queryFn: () => fetchObject(objectId),
  })

  // [질문하기]: 새 대화를 만들고, 이 객체와 미리 채울 질문을 들고 대화 화면으로 간다(152).
  const askMutation = useMutation<
    AgentConversation,
    Error,
    ObjectInstanceDetail
  >({
    mutationFn: () => createConversation(),
    onSuccess: (created, target) => {
      queryClient.setQueryData<AgentConversation[]>(
        ['agent', 'conversations'],
        (previous) => [created, ...(previous ?? [])],
      )
      const entry: AgentEntryState = {
        conversationId: created.id,
        referenceObjectId: target.id,
        referenceObjectName: target.displayName,
        referenceObjectTypeName: target.objectTypeName,
        draft: `${target.displayName}에 연결된 업무 정보를 알려줘`,
      }
      void navigate(`/agent?conversation=${created.id}`, { state: entry })
    },
  })

  if (isPending) {
    return <p className="text-slate-500">상세 정보를 불러오는 중…</p>
  }

  if (isError) {
    return <p className="text-red-600">{error.message}</p>
  }

  const source = object.sourceSystemName
    ? [object.sourceSystemName, object.sourceResourceKey, '원천 레코드']
        .filter(Boolean)
        .join(' · ')
    : '플랫폼 객체'

  return (
    <div className="explorer-object-detail">
      <div className="explorer-detail-actions">
        <p className="explorer-object-meta">
          {[object.objectTypeName, formatObjectKey(object)]
            .filter(Boolean)
            .join(' / ')}
        </p>
        <Button
          variant="secondary"
          disabled={askMutation.isPending}
          onClick={() => askMutation.mutate(object)}
        >
          질문하기
        </Button>
      </div>
      {askMutation.isError && (
        <p className="mt-1 text-sm text-red-600">대화를 만들지 못했어요.</p>
      )}
      <h2 className="explorer-detail-title">{object.displayName}</h2>
      <p className="explorer-source-caption">{source}</p>
      <StatusBadge tone={object.status === 'ACTIVE' ? 'success' : 'warning'}>
        {statusLabels[object.status]}
      </StatusBadge>

      <dl className="explorer-detail-times">
        <dt className="text-slate-500">기준 시점</dt>
        <dd className="text-slate-900">{formatDateTime(object.dataAsOf)}</dd>
        <dt className="text-slate-500">최근 동기화</dt>
        <dd className="text-slate-900">
          {formatDateTime(object.lastSyncedAt)}
        </dd>
      </dl>

      <h3 className="explorer-detail-section-title">속성</h3>
      <dl className="explorer-properties">
        {Object.entries(object.properties).map(([code, value]) => (
          <div key={code}>
            <dt>{object.propertyNames[code] ?? code}</dt>
            {object.restrictedPropertyCodes.includes(code) ? (
              <dd className="explorer-restricted">권한 없음</dd>
            ) : (
              <dd>{formatPropertyValue(value)}</dd>
            )}
          </div>
        ))}
      </dl>

      <section className="explorer-detail-section">
        <h3 className="explorer-detail-section-title">원천 근거</h3>
        <Button variant="text" onClick={() => setEvidenceOpen(true)}>
          원본·연결 근거 보기 ↗
        </Button>
      </section>

      <div className="mt-6">
        <Link
          to={`/audit?objectId=${object.id}`}
          className="ui-button ui-button--secondary"
        >
          이 객체 실행 이력
        </Link>
      </div>

      {evidenceOpen && (
        <EvidenceDialog
          object={object}
          onClose={() => setEvidenceOpen(false)}
        />
      )}
    </div>
  )
}

export default ObjectDetail
