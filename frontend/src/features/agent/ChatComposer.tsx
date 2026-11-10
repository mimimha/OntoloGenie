import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import Button from '../../components/Button'
import { useAuth } from '../auth/auth-context'
import SuggestedQuestions from './SuggestedQuestions'
import { suggestedQuestions } from './suggestions'
import type { ReferenceObject } from './types'

type ChatComposerProps = {
  disabled: boolean
  initialDraft?: string
  // 첫 질문 전까지의 참고 객체. 추천 질문을 그 객체에 맞춘다. 없으면 null(150).
  reference?: ReferenceObject | null
  onSend: (content: string) => Promise<void>
}

function ChatComposer({
  disabled,
  initialDraft = '',
  reference = null,
  onSend,
}: ChatComposerProps) {
  const { account } = useAuth()
  const [draft, setDraft] = useState(initialDraft)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const content = draft.trim()

  async function send() {
    if (!content || disabled) return
    try {
      await onSend(content)
      setDraft('')
    } catch {
      // 실패하면 쓴 질문을 그대로 두고, 오류는 대화 화면에서 보여 준다.
    }
  }

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void send()
  }

  // Enter는 보내기, Shift+Enter는 줄바꿈이다. 한글을 조합하는 중에 누른 Enter는 글자를 끝내는 키라 보내지 않는다.
  // (조합 중이면 isComposing이 참이고, 일부 브라우저는 keyCode 229로만 알려 준다.)
  function handleKeyDown(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (
      event.key !== 'Enter' ||
      event.shiftKey ||
      event.nativeEvent.isComposing ||
      event.keyCode === 229
    ) {
      return
    }
    event.preventDefault()
    void send()
  }

  // 추천 질문을 고르면 입력란에 채우고 바로 고쳐 쓸 수 있게 입력란으로 옮긴다.
  function handlePick(text: string) {
    setDraft(text)
    inputRef.current?.focus()
  }

  return (
    <form onSubmit={handleSubmit} className="border-t border-slate-200 p-3">
      <SuggestedQuestions
        questions={suggestedQuestions(reference, account?.roleCode ?? null)}
        disabled={disabled}
        onPick={handlePick}
      />
      {/* 화면에는 이름을 보이지 않고, 화면 읽기 프로그램용 이름(aria-label)만 둔다. 보내기 버튼은 입력 상자 안 오른쪽 아래에 둔다. */}
      <div className="flex items-end gap-2 rounded-lg border border-slate-300 p-2 focus-within:border-slate-500">
        <textarea
          id="agent-input"
          aria-label="업무 질문"
          ref={inputRef}
          value={draft}
          onChange={(event) => setDraft(event.target.value)}
          onKeyDown={handleKeyDown}
          disabled={disabled}
          maxLength={2000}
          rows={3}
          placeholder="궁금한 업무를 입력하세요. 필요하면 대상이나 조건을 다시 확인합니다. (Enter 보내기, Shift+Enter 줄바꿈)"
          className="min-w-0 flex-1 resize-none bg-transparent text-sm outline-none"
        />
        <Button type="submit" disabled={disabled || !content}>
          {disabled ? '처리 중…' : '보내기 ↑'}
        </Button>
      </div>
    </form>
  )
}

export default ChatComposer
