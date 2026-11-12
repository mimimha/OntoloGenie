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
    <div className="agent-question-shortcuts">
      <span className="agent-shortcut-label">추천 질문</span>
      <ul aria-label="추천 질문">
        {questions.map((question) => (
          <li key={question.label}>
            <button
              type="button"
              title={question.text}
              disabled={disabled}
              onClick={() => onPick(question.text)}
              className="agent-shortcut-button"
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
