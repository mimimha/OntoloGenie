import { SUGGESTION_HINT } from './labels'
import type { SuggestedQuestion } from './suggestions'

type SuggestedQuestionsProps = {
  questions: SuggestedQuestion[]
  disabled: boolean
  onPick: (text: string) => void
}

// 입력란 위 추천 질문 버튼(150, Figma 목업). 누르면 문장이 입력란에 채워지고, 보내기는 사용자가 한다.
function SuggestedQuestions({
  questions,
  disabled,
  onPick,
}: SuggestedQuestionsProps) {
  return (
    <div className="mb-2">
      <p className="text-xs text-slate-500">{SUGGESTION_HINT}</p>
      <ul aria-label="추천 질문" className="mt-1 flex flex-wrap gap-2">
        {questions.map((question) => (
          <li key={question.label}>
            <button
              type="button"
              title={question.text}
              disabled={disabled}
              onClick={() => onPick(question.text)}
              className="rounded-full border border-slate-300 px-3 py-1 text-xs text-slate-700 hover:border-slate-900 disabled:opacity-50"
            >
              {question.label}
            </button>
          </li>
        ))}
      </ul>
    </div>
  )
}

export default SuggestedQuestions
