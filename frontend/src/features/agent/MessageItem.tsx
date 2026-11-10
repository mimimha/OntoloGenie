import { Link } from 'react-router'
import DataTable from '../../components/DataTable'
import { formatObjectCode } from '../explorer/format'
import ActionResultList from './ActionResultList'
import {
  ANSWER_SNAPSHOT_NOTICE,
  DATA_AS_OF_UNKNOWN,
  RESULT_FAILED_LABEL,
  resultCategoryLabels,
  stageLabels,
  syncStatusNotices,
} from './labels'
import type { AgentMessage, AgentResult } from './types'

// 실행 이력 도구의 결과면 그 객체의 실행 이력으로도 이동한다(080).
const EXECUTION_TOOL_CODE = 'OBJECT_EXECUTIONS'

function formatAsOf(value: string) {
  return new Date(value).toLocaleString('ko-KR', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

// 정보 하나의 결과 이름. 조회를 못 끝낸 항목은 "데이터 없음"과 구분한다.
function resultLabel(result: AgentResult) {
  if (result.status === 'FAILED' || !result.resultCategory) {
    return RESULT_FAILED_LABEL
  }
  return resultCategoryLabels[result.resultCategory]
}

// 결과의 출처 중 객체만 골라, 같은 객체는 한 번만 보여 준다.
function sourceObjectIds(result: AgentResult) {
  return [
    ...new Set(
      result.sources.flatMap((source) =>
        source.objectId === undefined ? [] : [source.objectId],
      ),
    ),
  ]
}

function MessageItem({ message }: { message: AgentMessage }) {
  if (message.sender === 'USER') {
    return (
      <div className="flex justify-end">
        <p className="max-w-[80%] rounded-lg bg-slate-100 px-3 py-2 text-sm whitespace-pre-wrap text-slate-900">
          <span className="sr-only">나: </span>
          {message.content}
        </p>
      </div>
    )
  }

  if (message.status === 'RUNNING') {
    const stage = message.answerMeta.stage
    return (
      <p role="status" className="text-sm text-slate-600">
        답변 준비 중{stage ? ` · ${stageLabels[stage]}` : ''}…
      </p>
    )
  }

  const results = message.answerMeta.results ?? []
  const actions = message.answerMeta.actions ?? []
  const syncStatus = message.answerMeta.syncStatus
  const syncNotice = syncStatus ? syncStatusNotices[syncStatus] : undefined
  return (
    // Figma 목업처럼 답변을 테두리 상자로 묶고 위에 'Agent' 이름표를 단다. 색은 디자인 정리 때 맞춘다.
    <article
      aria-label="Agent 답변"
      className="rounded-xl border border-slate-200 bg-white p-4 text-sm text-slate-900"
    >
      <span aria-hidden="true" className="mb-1 block text-xs text-slate-500">
        Agent
      </span>
      {message.status === 'FAILED' && (
        <p role="alert" className="mb-1 font-medium text-red-700">
          답변을 끝내지 못했어요. {message.errorMessage}
        </p>
      )}
      <p className="whitespace-pre-wrap">{message.content}</p>
      {syncNotice && (
        <p className="mt-1 text-xs text-amber-700">{syncNotice}</p>
      )}
      {actions.length > 0 && <ActionResultList actions={actions} />}
      {results.length > 0 && (
        <>
          {/* 정보별 결과를 표로 보여 준다. 정보마다 결과·기준 시점·이동 링크를 같은 칸에 맞춰 비교하기 쉽게 한다. */}
          <div className="mt-2">
            <DataTable label="정보별 결과">
              <thead className="bg-slate-50 text-slate-600">
                <tr>
                  <th className="px-3 py-2">정보</th>
                  <th className="px-3 py-2">결과</th>
                  <th className="px-3 py-2">기준 시점</th>
                  <th className="px-3 py-2">바로 가기</th>
                </tr>
              </thead>
              <tbody>
                {results.map((result, index) => (
                  <tr
                    key={`${result.label}-${index}`}
                    className="border-t border-slate-100"
                  >
                    <td className="px-3 py-2 font-medium">{result.label}</td>
                    <td className="px-3 py-2">
                      {resultLabel(result)}
                      {result.estimated ? ' · 추정' : ''}
                    </td>
                    <td className="px-3 py-2 text-slate-500">
                      {result.dataAsOf
                        ? formatAsOf(result.dataAsOf)
                        : DATA_AS_OF_UNKNOWN}
                    </td>
                    <td className="px-3 py-2">
                      {sourceObjectIds(result).map((objectId) => (
                        <span key={objectId} className="flex gap-2">
                          <Link
                            to={`/explorer?object=${objectId}`}
                            className="text-blue-700 hover:underline"
                          >
                            {formatObjectCode(objectId)} 열기 ↗
                          </Link>
                          {result.toolCode === EXECUTION_TOOL_CODE && (
                            <Link
                              to={`/audit?objectId=${objectId}`}
                              className="text-blue-700 hover:underline"
                            >
                              실행 이력 ↗
                            </Link>
                          )}
                        </span>
                      ))}
                    </td>
                  </tr>
                ))}
              </tbody>
            </DataTable>
          </div>
          <p className="mt-1 text-xs text-slate-500">
            {ANSWER_SNAPSHOT_NOTICE}
          </p>
        </>
      )}
    </article>
  )
}

export default MessageItem
