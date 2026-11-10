import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useLocation, useNavigate } from 'react-router'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import ChatComposer from './ChatComposer'
import MessageItem from './MessageItem'
import { fetchMessages, sendQuestion } from './api'
import { NEW_CONVERSATION_TITLE } from './labels'
import type {
  AgentConversation,
  AgentEntryState,
  ReferenceObject,
} from './types'

// 처리 중인 답변이 있으면 이 간격으로 084를 다시 읽는다(정책 문서 3.3).
const POLL_INTERVAL_MS = 1500

function ConversationPanel({
  conversation,
}: {
  conversation: AgentConversation
}) {
  const queryClient = useQueryClient()
  const location = useLocation()
  const navigate = useNavigate()
  const messagesQuery = useQuery({
    queryKey: ['agent', 'messages', conversation.id],
    queryFn: () => fetchMessages(conversation.id),
    // 들어올 때마다 대화를 새로 읽는다. 아래 참고 객체 판단과 입력란 표시가 "이 화면에서 새로 읽은 결과"를 기다리기 때문이다.
    refetchOnMount: 'always',
    refetchInterval: (query) =>
      query.state.data?.some((message) => message.status === 'RUNNING')
        ? POLL_INTERVAL_MS
        : false,
  })
  const messages = messagesQuery.data ?? []
  const running = messages.some((message) => message.status === 'RUNNING')

  // Explorer의 [질문하기]로 이 대화에 들어왔으면 그 객체를 첫 질문에만 함께 보낸다(152).
  // 다음 질문부터 같은 대상을 이어 쓰거나 다른 대상으로 바꾸는 일은 서버가 맡는다(147). 질문마다 보내면 다른 객체를 물어도
  // 처음 객체로 답할 수 있기 때문이다. 첫 질문 전까지는 주소 기록(history state)에 남아 새로고침해도 유지되고, 화면에는 따로
  // 표시하지 않고 추천 질문에 반영한다(150).
  // 주소 기록만 믿지 않고, 이 화면에서 새로 읽은 대화가 비어 있을 때만 참고 객체를 쓴다. 첫 질문을 보내는 사이 다른 대화로
  // 옮기거나 새로고침하면 기록이 지워지지 않고 남는데, 나중에 돌아왔을 때 다시 붙지 않게 하기 위해서다.
  const savedEntry = entryFor(location.state, conversation.id)
  const freshlyLoaded =
    messagesQuery.isSuccess && messagesQuery.isFetchedAfterMount
  // 미리 채운 질문은 입력란을 처음 그릴 때만 들어가므로, 기록이 있으면 대화를 다 읽은 뒤 입력란을 그린다. 읽다 실패하면
  // 입력란 없이 오류와 [다시 시도]만 보여 주어, 다시 읽은 뒤 미리 채운 질문과 참고 객체가 함께 나오게 한다.
  // 입력란을 처음 그릴 때 참고 객체를 쓸지도 정하고 그 뒤로는 바꾸지 않는다. 나중에 다시 읽다 실패해도 입력란과 쓰던 질문,
  // 추천 질문이 그대로 남는다.
  const [decision, setDecision] = useState<{
    entry: AgentEntryState | null
  } | null>(null)
  if (decision === null && (!savedEntry || freshlyLoaded)) {
    setDecision({
      entry: savedEntry && messages.length === 0 ? savedEntry : null,
    })
  }
  const composerReady = decision !== null
  // 첫 질문을 보내 주소 기록을 지우면 더는 쓰지 않는다.
  const entry = savedEntry ? (decision?.entry ?? null) : null
  const reference: ReferenceObject | null = entry
    ? {
        id: entry.referenceObjectId,
        name: entry.referenceObjectName,
        typeName: entry.referenceObjectTypeName,
      }
    : null

  // 주소 기록의 상태만 바꾼다. 화면 이동은 하지 않는다.
  function replaceEntry(next: AgentEntryState | null) {
    void navigate(`${location.pathname}${location.search}`, {
      replace: true,
      state: next,
    })
  }

  const sendMutation = useMutation({
    mutationFn: (content: string) =>
      sendQuestion(
        conversation.id,
        reference
          ? { content, referenceObjectIds: [reference.id] }
          : { content },
      ),
    onSuccess: () => {
      // 접수된 질문과 처리 중 답변을 다시 읽고, 첫 질문이면 바뀐 제목도 목록에 반영한다.
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'messages', conversation.id],
      })
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'conversations'],
      })
    },
  })

  async function handleSend(content: string) {
    await sendMutation.mutateAsync(content, {
      // 첫 질문을 보냈으면 참고 객체와 미리 채운 질문을 주소 기록에서 지운다. 이 콜백은 화면이 그대로일 때만 실행되므로,
      // 보내는 사이 다른 대화로 옮겼다면 그 주소를 원래 대화로 덮어쓰지 않는다.
      onSuccess: () => {
        if (entry) {
          replaceEntry(null)
        }
      },
    })
  }

  function renderMessages() {
    if (messagesQuery.isPending) {
      return <LoadingState message="대화를 불러오는 중…" />
    }
    if (messagesQuery.isError) {
      return (
        <ErrorState
          error={messagesQuery.error}
          onRetry={() => void messagesQuery.refetch()}
        />
      )
    }
    if (messages.length === 0) {
      return (
        <EmptyState message="업무에 대해 질문해 주세요. 예: PR-03의 상태를 알려줘" />
      )
    }
    return messages.map((message) => (
      <MessageItem key={message.id} message={message} />
    ))
  }

  return (
    <section
      aria-label="현재 대화"
      className="flex min-h-[32rem] flex-col rounded-xl border border-slate-200 bg-white"
    >
      <h2 className="border-b border-slate-200 p-3 text-sm font-semibold text-slate-900">
        {conversation.title ?? NEW_CONVERSATION_TITLE}
      </h2>
      <div aria-live="polite" className="flex-1 space-y-4 overflow-y-auto p-4">
        {renderMessages()}
      </div>
      {sendMutation.isError && (
        <div className="px-3">
          <ErrorState
            error={sendMutation.error}
            title="질문을 보내지 못했습니다."
          />
        </div>
      )}
      {composerReady && (
        <ChatComposer
          initialDraft={entry?.draft}
          reference={reference}
          disabled={running || sendMutation.isPending}
          onSend={handleSend}
        />
      )}
    </section>
  )
}

export default ConversationPanel

// 주소 기록의 상태가 이 대화의 참고 객체이면 돌려준다. 이름·유형이 없는 예전 기록은 참고 객체 없음으로 본다(150).
function entryFor(state: unknown, conversationId: number) {
  const entry = state as Partial<AgentEntryState> | null
  if (
    entry?.conversationId !== conversationId ||
    typeof entry.referenceObjectId !== 'number' ||
    typeof entry.referenceObjectName !== 'string' ||
    typeof entry.referenceObjectTypeName !== 'string'
  ) {
    return null
  }
  return entry as AgentEntryState
}
