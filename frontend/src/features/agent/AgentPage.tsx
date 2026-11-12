import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useSearchParams } from 'react-router'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import ConversationList from './ConversationList'
import ConversationPanel from './ConversationPanel'
import {
  createConversation,
  deleteConversationTemporarily,
  fetchConversations,
} from './api'
import type { AgentConversation } from './types'

function AgentPage() {
  const queryClient = useQueryClient()
  const [searchParams, setSearchParams] = useSearchParams()

  const conversationsQuery = useQuery({
    queryKey: ['agent', 'conversations'],
    queryFn: fetchConversations,
  })

  const createMutation = useMutation({
    mutationFn: createConversation,
    onSuccess: (created) => {
      // 목록을 다시 받기 전에도 새 대화가 바로 보이도록 맨 앞에 넣는다.
      queryClient.setQueryData<AgentConversation[]>(
        ['agent', 'conversations'],
        (previous) => [created, ...(previous ?? [])],
      )
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'conversations'],
      })
      setSearchParams({ conversation: String(created.id) })
    },
  })

  // 임시(개발 데이터 정리용, 나중에 제거): 지운 뒤 목록을 다시 읽고, 열려 있던 대화면 주소에서 뺀다.
  const deleteMutation = useMutation({
    mutationFn: deleteConversationTemporarily,
    onSuccess: (_, deletedId) => {
      queryClient.removeQueries({ queryKey: ['agent', 'messages', deletedId] })
      void queryClient.invalidateQueries({
        queryKey: ['agent', 'conversations'],
      })
      if (Number(searchParams.get('conversation')) === deletedId) {
        setSearchParams({})
      }
    },
  })

  const conversations = conversationsQuery.data ?? []
  const requestedId = Number(searchParams.get('conversation'))
  // 주소의 대화가 목록에 없으면 가장 최근 대화를 연다.
  const selected =
    conversations.find((conversation) => conversation.id === requestedId) ??
    conversations[0]

  function renderBody() {
    if (conversationsQuery.isPending) {
      return <LoadingState message="대화 목록을 불러오는 중…" />
    }
    if (conversationsQuery.isError) {
      return (
        <ErrorState
          error={conversationsQuery.error}
          onRetry={() => void conversationsQuery.refetch()}
        />
      )
    }
    return (
      <div className="grid auto-rows-[45rem] gap-4 md:grid-cols-[16rem_minmax(0,1fr)]">
        <ConversationList
          conversations={conversations}
          selectedId={selected?.id ?? null}
          creating={createMutation.isPending}
          onSelect={(id) => setSearchParams({ conversation: String(id) })}
          onCreate={() => createMutation.mutate()}
          deleting={deleteMutation.isPending}
          onDelete={(id) => deleteMutation.mutate(id)}
        />
        {selected ? (
          <ConversationPanel key={selected.id} conversation={selected} />
        ) : (
          <EmptyState message="[+ 새 대화]를 눌러 대화를 시작하세요." />
        )}
      </div>
    )
  }

  return (
    <div>
      <header className="mb-6">
        <p className="text-xs font-semibold tracking-widest text-slate-500">
          INTELLIGENCE
        </p>
        <h1 className="mt-1 text-xl font-semibold text-slate-900">
          AI 업무 도우미
        </h1>
        <p className="mt-1 text-sm text-slate-500">
          연결된 데이터에서 답을 찾고, 다음 업무를 계획하세요.
        </p>
      </header>
      {createMutation.isError && (
        <div className="mb-4">
          <ErrorState
            error={createMutation.error}
            title="대화를 만들지 못했습니다."
          />
        </div>
      )}
      {deleteMutation.isError && (
        <div className="mb-4">
          <ErrorState
            error={deleteMutation.error}
            title="대화를 지우지 못했습니다."
          />
        </div>
      )}
      {renderBody()}
    </div>
  )
}

export default AgentPage
