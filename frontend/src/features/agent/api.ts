import { ApiError, apiDelete, apiGet, apiPost } from '../../api/client'
import type { AgentConversation, AgentMessage, QuestionRequest } from './types'

export const CONVERSATION_PAGE_SIZE = 50
export const MESSAGE_PAGE_SIZE = 100

// 081 내 대화 목록
export async function fetchConversations() {
  const conversations: AgentConversation[] = []
  let page = 0
  let hasNext = true
  while (hasNext) {
    const body = await apiGet<AgentConversation[]>(
      `/agent/conversations?page=${page}&size=${CONVERSATION_PAGE_SIZE}`,
    )
    conversations.push(...body.data)
    hasNext = body.meta?.hasNext === true && body.data.length > 0
    page += 1
  }
  return conversations
}

// 082 대화 생성
export async function createConversation() {
  const body = await apiPost<AgentConversation>('/agent/conversations')
  if (!body) {
    throw new ApiError('대화를 만들지 못했어요.', { kind: 'invalid-response' })
  }
  return body.data
}

// 임시(개발 데이터 정리용, 나중에 제거): 대화를 DB에서 지운다. API 명세에 없다.
export async function deleteConversationTemporarily(conversationId: number) {
  await apiDelete(`/agent/conversations/${conversationId}`)
}

// 084 대화 메시지 목록 (시간순)
export async function fetchMessages(conversationId: number) {
  const body = await apiGet<AgentMessage[]>(
    `/agent/conversations/${conversationId}/messages?page=0&size=${MESSAGE_PAGE_SIZE}`,
  )
  return body.data
}

// 085 질문 보내기: 202로 접수되고, 답변은 084를 다시 읽어 확인한다.
export async function sendQuestion(
  conversationId: number,
  request: QuestionRequest,
) {
  const body = await apiPost<AgentMessage[]>(
    `/agent/conversations/${conversationId}/messages`,
    request,
  )
  if (!body) {
    throw new ApiError('질문을 보내지 못했어요.', { kind: 'invalid-response' })
  }
  return body.data
}
