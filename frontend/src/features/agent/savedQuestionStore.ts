import { useCallback, useState } from 'react'

// 사용자가 자주 쓰는 질문(257, 1단계). 서버에 저장하지 않고 이 브라우저(localStorage)에 계정별로 보관한다.
// 다른 PC·브라우저에서는 보이지 않고, 브라우저 저장 공간을 지우면 사라진다. 저장 공간을 쓸 수 없으면(사생활 보호 모드 등)
// 저장하지 않고 빈 목록으로 둔다. 서버 저장과 질문 관리 정책은 필요해지면 따로 정한다.

// 한 계정이 저장할 수 있는 질문 수. 추천 질문과 한 화면에 보이도록 둔다.
export const MAX_SAVED_QUESTIONS = 10

// 질문 입력란 최대 길이와 같다(API 085 content 최대 2000자).
const MAX_QUESTION_LENGTH = 2000

// 버튼에 보일 이름의 최대 글자 수. 넘으면 줄여서 보여 주고, 문장 전체는 버튼 설명(title)과 입력란에 채운다.
const LABEL_LENGTH = 16

const KEY_PREFIX = 'axcore.agent.savedQuestions.'

function storageKey(accountId: number) {
  return `${KEY_PREFIX}${accountId}`
}

/** 저장된 질문을 저장한 순서대로 읽는다. 저장 공간을 쓸 수 없거나 값이 깨졌으면 빈 목록이다. */
export function readSavedQuestions(accountId: number): string[] {
  try {
    const raw = window.localStorage.getItem(storageKey(accountId))
    if (!raw) return []
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) return []
    // 손으로 고쳤거나 예전 값이 남아 있어도 같은 문장은 한 번만, 입력란 길이 안으로 읽는다.
    const questions = parsed
      .filter((value): value is string => typeof value === 'string')
      .map((value) => value.trim().slice(0, MAX_QUESTION_LENGTH))
      .filter((value) => value.length > 0)
    return [...new Set(questions)].slice(0, MAX_SAVED_QUESTIONS)
  } catch {
    return []
  }
}

/** 저장했으면 true. 저장 공간을 쓸 수 없으면(사생활 보호 모드, 가득 참) false다. */
function writeSavedQuestions(accountId: number, questions: string[]): boolean {
  try {
    window.localStorage.setItem(
      storageKey(accountId),
      JSON.stringify(questions),
    )
    return true
  } catch {
    return false
  }
}

/** 질문을 더한 목록. 같은 문장이 이미 있거나 비었으면 그대로이고, 가득 찼으면 더하지 않는다. */
export function withQuestion(questions: string[], question: string): string[] {
  const text = question.trim().slice(0, MAX_QUESTION_LENGTH)
  if (
    !text ||
    questions.includes(text) ||
    questions.length >= MAX_SAVED_QUESTIONS
  ) {
    return questions
  }
  return [...questions, text]
}

/** 버튼에 보일 짧은 이름. */
export function savedQuestionLabel(question: string): string {
  const characters = Array.from(question)
  return characters.length > LABEL_LENGTH
    ? `${characters.slice(0, LABEL_LENGTH).join('')}…`
    : question
}

/**
 * 로그인한 계정의 저장한 질문 목록과 저장·삭제. 계정이 없으면(로그인 전) 빈 목록이고 저장하지 않는다.
 * 같은 브라우저를 다른 계정이 써도 계정별 키로 나눠 섞이지 않는다.
 */
export function useSavedQuestions(accountId: number | null) {
  const [loaded, setLoaded] = useState<{
    accountId: number | null
    questions: string[]
  }>(() => ({
    accountId,
    questions: accountId === null ? [] : readSavedQuestions(accountId),
  }))
  // 계정이 바뀌면 그 계정의 목록을 다시 읽는다.
  let current = loaded
  if (loaded.accountId !== accountId) {
    current = {
      accountId,
      questions: accountId === null ? [] : readSavedQuestions(accountId),
    }
    setLoaded(current)
  }
  const questions = current.questions

  // 저장·삭제할 때마다 저장소를 새로 읽어 고친다. 다른 탭에서 그사이 저장한 질문을 이 화면이 처음 읽은 목록으로 덮지 않는다.
  // 저장소에 쓸 수 없으면 읽어도 늘 비어 있으므로, 그때는 화면의 목록을 기준으로 고쳐 이번 화면에서만 보인다.
  const change = useCallback(
    (edit: (questions: string[]) => string[]) => {
      if (accountId === null) return
      const stored = readSavedQuestions(accountId)
      const next = edit(stored)
      if (next === stored || writeSavedQuestions(accountId, next)) {
        setLoaded({ accountId, questions: next })
        return
      }
      setLoaded((previous) => ({
        accountId,
        questions: edit(previous.questions),
      }))
    },
    [accountId],
  )

  const save = useCallback(
    (question: string) =>
      change((questions) => withQuestion(questions, question)),
    [change],
  )

  const remove = useCallback(
    (question: string) =>
      change((questions) => questions.filter((saved) => saved !== question)),
    [change],
  )

  const isFull = questions.length >= MAX_SAVED_QUESTIONS
  // 로그인 전에는 저장할 계정이 없다.
  const canSave = accountId !== null
  return { questions, save, remove, isFull, canSave }
}
