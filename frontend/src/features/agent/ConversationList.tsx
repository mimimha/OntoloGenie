import { useLayoutEffect, useRef, useState } from 'react'
import Button from '../../components/Button'
import Pagination from '../../components/Pagination'
import { NEW_CONVERSATION_TITLE } from './labels'
import type { AgentConversation } from './types'

type ConversationListProps = {
  conversations: AgentConversation[]
  selectedId: number | null
  creating: boolean
  onSelect: (id: number) => void
  onCreate: () => void
  // 임시(개발 데이터 정리용, 나중에 제거)
  deleting: boolean
  onDelete: (id: number) => void
}

function ConversationList({
  conversations,
  selectedId,
  creating,
  onSelect,
  onCreate,
  deleting,
  onDelete,
}: ConversationListProps) {
  const viewportRef = useRef<HTMLDivElement>(null)
  const rowRef = useRef<HTMLLIElement>(null)
  const [pageSize, setPageSize] = useState(16)
  const selectedIndex = Math.max(
    0,
    conversations.findIndex((conversation) => conversation.id === selectedId),
  )
  const [pagination, setPagination] = useState({
    selectedId,
    pageSize,
    page: Math.floor(selectedIndex / pageSize),
    followingSelection: true,
  })
  const lastPage = Math.max(0, Math.ceil(conversations.length / pageSize) - 1)
  const page = Math.min(pagination.page, lastPage)

  // 선택한 대화는 목록에서도 보이게 하고, 크기가 바뀌면 보던 목록의 첫 항목을 유지한다.
  if (
    pagination.selectedId !== selectedId ||
    pagination.pageSize !== pageSize
  ) {
    setPagination({
      selectedId,
      pageSize,
      followingSelection:
        pagination.selectedId !== selectedId || pagination.followingSelection,
      page:
        pagination.selectedId !== selectedId || pagination.followingSelection
          ? Math.floor(selectedIndex / pageSize)
          : Math.min(
              Math.floor((pagination.page * pagination.pageSize) / pageSize),
              lastPage,
            ),
    })
  } else if (pagination.page !== page) {
    setPagination({ ...pagination, page })
  }

  const hasConversations = conversations.length > 0
  const firstVisibleId = conversations[page * pageSize]?.id
  useLayoutEffect(() => {
    const viewport = viewportRef.current
    const row = rowRef.current
    if (!viewport || !row) return

    let availableHeight = 0
    let rowHeight = 0
    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        if (entry.target === viewport)
          availableHeight = entry.contentRect.height
        if (entry.target === row) rowHeight = row.getBoundingClientRect().height
      }
      if (availableHeight > 0 && rowHeight > 0) {
        setPageSize(Math.max(1, Math.floor(availableHeight / rowHeight)))
      }
    })
    observer.observe(viewport)
    observer.observe(row)
    return () => observer.disconnect()
  }, [hasConversations, firstVisibleId])

  const visibleConversations = conversations.slice(
    page * pageSize,
    (page + 1) * pageSize,
  )

  return (
    <aside
      aria-label="대화방 목록"
      className="flex h-full min-h-0 flex-col overflow-hidden rounded-xl border border-slate-200 bg-white"
    >
      <div className="flex shrink-0 items-center justify-between border-b border-slate-200 p-3">
        <h2 className="text-sm font-semibold text-slate-900">대화</h2>
        <Button variant="secondary" onClick={onCreate} disabled={creating}>
          + 새 대화
        </Button>
      </div>
      {!hasConversations ? (
        <p className="p-3 text-sm text-slate-500">아직 대화가 없어요.</p>
      ) : (
        <div ref={viewportRef} className="min-h-0 flex-1 overflow-y-auto p-2">
          <ul>
            {visibleConversations.map((conversation, index) => {
              const selected = conversation.id === selectedId
              return (
                <li
                  key={conversation.id}
                  ref={index === 0 ? rowRef : undefined}
                  className="flex items-center gap-1"
                >
                  <button
                    type="button"
                    aria-pressed={selected}
                    onClick={() => onSelect(conversation.id)}
                    className={`min-w-0 flex-1 truncate rounded-lg px-3 py-2 text-left text-sm ${selected ? 'bg-slate-100 font-semibold text-slate-900' : 'text-slate-700 hover:bg-slate-50'}`}
                  >
                    {conversation.title ?? NEW_CONVERSATION_TITLE}
                  </button>
                  {/* 임시(개발 데이터 정리용, 나중에 제거): DB에서 바로 지운다. */}
                  <button
                    type="button"
                    disabled={deleting}
                    aria-label={`${conversation.title ?? NEW_CONVERSATION_TITLE} 삭제`}
                    onClick={() => {
                      if (window.confirm('이 대화를 DB에서 완전히 지울까요?')) {
                        onDelete(conversation.id)
                      }
                    }}
                    className="shrink-0 rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 disabled:opacity-50"
                  >
                    삭제
                  </button>
                </li>
              )
            })}
          </ul>
        </div>
      )}
      {hasConversations && (
        <div className="shrink-0 border-t border-slate-200 py-3">
          <Pagination
            page={page}
            size={pageSize}
            totalElements={conversations.length}
            onChange={(nextPage) =>
              setPagination({
                selectedId,
                pageSize,
                page: nextPage,
                followingSelection: false,
              })
            }
          />
        </div>
      )}
    </aside>
  )
}

export default ConversationList
