import { useRef, useState, type FormEvent, type KeyboardEvent } from 'react'
import Button from '../../components/Button'
import { useAuth } from '../auth/auth-context'
import SavedQuestions from './SavedQuestions'
import { MAX_SAVED_QUESTIONS, useSavedQuestions } from './savedQuestionStore'
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
  // 자주 쓰는 질문(257). 이 브라우저에 계정별로 보관한다.
  const saved = useSavedQuestions(account?.id ?? null)
  const alreadySaved = saved.questions.includes(content)

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
      <SavedQuestions
        questions={saved.questions}
        disabled={disabled}
        onPick={handlePick}
        onRemove={saved.remove}
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
        {/* 지금 입력한 문장을 내 질문으로 저장한다. 로그인 전이거나, 비었거나, 이미 저장했거나, 가득 찼으면 누를 수 없다.
            저장하면 버튼이 막히므로 다시 입력란으로 돌아가 이어서 쓸 수 있게 한다. 별 기호는 화면 읽기 프로그램이 읽지 않는다. */}
        <button
          type="button"
          onClick={() => {
            saved.save(content)
            inputRef.current?.focus()
          }}
          disabled={!saved.canSave || !content || alreadySaved || saved.isFull}
          title={
            saved.isFull
              ? `내 질문은 ${MAX_SAVED_QUESTIONS}개까지 저장할 수 있습니다. 하나를 지운 뒤 저장하세요.`
              : '지금 입력한 질문을 내 질문으로 저장합니다.'
          }
          className="shrink-0 rounded-md border border-slate-300 px-2 py-1.5 text-xs text-slate-700 hover:border-slate-900 disabled:opacity-40"
        >
          <span aria-hidden="true">{alreadySaved ? '★ ' : '☆ '}</span>
          {alreadySaved ? '저장됨' : '내 질문 저장'}
        </button>
        <Button type="submit" disabled={disabled || !content}>
          {disabled ? '처리 중…' : '보내기 ↑'}
        </Button>
      </div>
    </form>
  )
}

export default ChatComposer
