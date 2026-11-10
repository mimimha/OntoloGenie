import { useRef, useState, type FormEvent } from 'react'
import Button from '../../components/Button'
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
  const [draft, setDraft] = useState(initialDraft)
  const inputRef = useRef<HTMLTextAreaElement>(null)
  const content = draft.trim()

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!content || disabled) return
    try {
      await onSend(content)
      setDraft('')
    } catch {
      // 실패하면 쓴 질문을 그대로 두고, 오류는 대화 화면에서 보여 준다.
    }
  }

  // 추천 질문을 고르면 입력란에 채우고 바로 고쳐 쓸 수 있게 입력란으로 옮긴다.
  function handlePick(text: string) {
    setDraft(text)
    inputRef.current?.focus()
  }

  return (
    <form onSubmit={handleSubmit} className="border-t border-slate-200 p-3">
      <SuggestedQuestions
        questions={suggestedQuestions(reference)}
        disabled={disabled}
        onPick={handlePick}
      />
      <label
        htmlFor="agent-input"
        className="text-sm font-medium text-slate-700"
      >
        업무 질문
      </label>
      <textarea
        id="agent-input"
        ref={inputRef}
        value={draft}
        onChange={(event) => setDraft(event.target.value)}
        disabled={disabled}
        maxLength={2000}
        rows={3}
        placeholder="궁금한 업무를 입력하세요. 필요하면 대상이나 조건을 다시 확인합니다."
        className="mt-1 w-full rounded-lg border border-slate-300 p-2 text-sm disabled:bg-slate-50"
      />
      <div className="mt-2 flex justify-end">
        <Button type="submit" disabled={disabled || !content}>
          {disabled ? '처리 중…' : '보내기 ↑'}
        </Button>
      </div>
    </form>
  )
}

export default ChatComposer
