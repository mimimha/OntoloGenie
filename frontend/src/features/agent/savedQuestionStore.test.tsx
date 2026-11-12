import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ReactNode } from 'react'
import { beforeEach, expect, test, vi } from 'vitest'
import { demoAccount } from '../../mocks/auth'
import { AuthContext } from '../auth/auth-context'
import type { Account } from '../auth/types'
import ChatComposer from './ChatComposer'
import {
  MAX_SAVED_QUESTIONS,
  readSavedQuestions,
  savedQuestionLabel,
  withQuestion,
} from './savedQuestionStore'

// 자주 쓰는 질문(257, 1단계): 이 브라우저에 계정별로 보관한다.
beforeEach(() => {
  window.localStorage.clear()
})

function signedIn(account: Account, children: ReactNode) {
  return (
    <AuthContext.Provider
      value={{
        account,
        pending: false,
        error: null,
        refresh: async () => ({ error: null }),
        signIn: async () => {},
        signOut: async () => {},
      }}
    >
      {children}
    </AuthContext.Provider>
  )
}

function renderComposer(account: Account = demoAccount) {
  const onSend = vi.fn(async () => {})
  const view = render(
    signedIn(account, <ChatComposer disabled={false} onSend={onSend} />),
  )
  return { onSend, view }
}

test('입력한 질문을 내 질문으로 저장하고, 누르면 입력란에 채운다', async () => {
  const user = userEvent.setup()
  renderComposer()
  const input = screen.getByLabelText('업무 질문')
  const saveButton = screen.getByRole('button', { name: '내 질문 저장' })

  // 입력한 게 없으면 저장할 수 없고, 저장한 질문이 없으면 내 질문 줄도 없다.
  expect(saveButton).toBeDisabled()
  expect(screen.queryByRole('list', { name: '내 질문' })).toBeNull()

  await user.type(input, '  P103에 연결된 비가동 기록 보여줘  ')
  await user.click(saveButton)

  // 저장하면 버튼이 막히므로 입력란으로 돌아가 이어서 쓸 수 있다. 같은 문장은 두 번 저장하지 않는다.
  expect(input).toHaveFocus()
  expect(screen.getByRole('button', { name: '저장됨' })).toBeDisabled()
  const saved = screen.getByRole('list', { name: '내 질문' })
  await user.clear(input)
  // 버튼에는 앞 16글자만 보이고, 화면 읽기 프로그램에는 문장 전체를 읽힌다. 누르면 문장 전체가 채워진다.
  expect(within(saved).getByText('P103에 연결된 비가동 기록…')).toBeVisible()
  await user.click(
    within(saved).getByRole('button', {
      name: 'P103에 연결된 비가동 기록 보여줘',
    }),
  )
  expect(input).toHaveValue('P103에 연결된 비가동 기록 보여줘')
  expect(input).toHaveFocus()
})

test('저장한 질문은 다시 열어도 남고, 지우면 사라진다', async () => {
  const user = userEvent.setup()
  const first = renderComposer()
  await user.type(screen.getByLabelText('업무 질문'), 'P101 상세 정보 알려줘')
  await user.click(screen.getByRole('button', { name: '내 질문 저장' }))
  first.view.unmount()

  // 화면을 새로 열어도(새로 고침과 같다) 이 브라우저에 남아 있다.
  renderComposer()
  const saved = screen.getByRole('list', { name: '내 질문' })
  expect(
    within(saved).getByRole('button', { name: 'P101 상세 정보 알려줘' }),
  ).toBeInTheDocument()

  await user.click(
    within(saved).getByRole('button', {
      name: "'P101 상세 정보 알려줘' 내 질문에서 지우기",
    }),
  )
  expect(screen.queryByRole('list', { name: '내 질문' })).toBeNull()
  expect(readSavedQuestions(demoAccount.id)).toEqual([])
})

test('계정마다 따로 보관해 같은 브라우저를 다른 계정이 써도 섞이지 않는다', async () => {
  const user = userEvent.setup()
  const first = renderComposer()
  await user.type(screen.getByLabelText('업무 질문'), 'P101 상세 정보 알려줘')
  await user.click(screen.getByRole('button', { name: '내 질문 저장' }))
  first.view.unmount()

  renderComposer({ ...demoAccount, id: demoAccount.id + 1 })

  expect(screen.queryByRole('list', { name: '내 질문' })).toBeNull()
})

test('화면을 연 채로 계정이 바뀌면 그 계정의 목록으로 바꾼다', async () => {
  const user = userEvent.setup()
  const other = { ...demoAccount, id: demoAccount.id + 1 }
  window.localStorage.setItem(
    `axcore.agent.savedQuestions.${other.id}`,
    JSON.stringify(['다른 계정 질문']),
  )
  const { view, onSend } = renderComposer()
  await user.type(screen.getByLabelText('업무 질문'), 'P101 상세 정보 알려줘')
  await user.click(screen.getByRole('button', { name: '내 질문 저장' }))

  view.rerender(
    signedIn(other, <ChatComposer disabled={false} onSend={onSend} />),
  )

  const saved = screen.getByRole('list', { name: '내 질문' })
  expect(
    within(saved).getByRole('button', { name: '다른 계정 질문' }),
  ).toBeInTheDocument()
  expect(
    within(saved).queryByRole('button', { name: 'P101 상세 정보 알려줘' }),
  ).toBeNull()
})

test('다른 탭에서 그사이 저장한 질문을 덮어쓰지 않는다', async () => {
  const user = userEvent.setup()
  renderComposer()
  // 이 화면을 연 뒤 다른 탭에서 저장했다.
  window.localStorage.setItem(
    `axcore.agent.savedQuestions.${demoAccount.id}`,
    JSON.stringify(['다른 탭 질문']),
  )

  await user.type(screen.getByLabelText('업무 질문'), 'P101 상세 정보 알려줘')
  await user.click(screen.getByRole('button', { name: '내 질문 저장' }))

  expect(readSavedQuestions(demoAccount.id)).toEqual([
    '다른 탭 질문',
    'P101 상세 정보 알려줘',
  ])
})

test('저장 공간에 쓸 수 없어도 화면은 깨지지 않고 이번 화면에서는 보인다', async () => {
  const user = userEvent.setup()
  const setItem = vi
    .spyOn(Storage.prototype, 'setItem')
    .mockImplementation(() => {
      throw new Error('저장 공간이 가득 참')
    })
  try {
    renderComposer()
    const input = screen.getByLabelText('업무 질문')
    // 저장소가 늘 비어 있어도 앞서 저장한 질문이 사라지지 않는다.
    for (const question of ['P101 상세 정보 알려줘', 'P103 상세 정보 알려줘']) {
      await user.clear(input)
      await user.type(input, question)
      await user.click(screen.getByRole('button', { name: '내 질문 저장' }))
    }
    expect(screen.getByRole('status')).toHaveTextContent('현재 화면에서만')
    expect(screen.getByRole('button', { name: '임시 보관 중' })).toBeDisabled()
    const saved = screen.getByRole('list', { name: '내 질문' })
    expect(
      within(saved)
        .getAllByRole('button')
        .filter(
          (button) => !button.getAttribute('aria-label')?.includes('지우기'),
        )
        .map((button) => button.getAttribute('aria-label')),
    ).toEqual(['P101 상세 정보 알려줘', 'P103 상세 정보 알려줘'])

    // 하나를 지우면 나머지는 남는다.
    await user.click(
      within(saved).getByRole('button', {
        name: "'P101 상세 정보 알려줘' 내 질문에서 지우기",
      }),
    )
    expect(
      within(saved).getByRole('button', { name: 'P103 상세 정보 알려줘' }),
    ).toBeInTheDocument()
    expect(
      within(saved).queryByRole('button', { name: 'P101 상세 정보 알려줘' }),
    ).toBeNull()
  } finally {
    setItem.mockRestore()
  }
})

test('저장이 복구되면 임시 추가·삭제와 다른 탭의 질문을 함께 보관한다', async () => {
  const user = userEvent.setup()
  const key = `axcore.agent.savedQuestions.${demoAccount.id}`
  window.localStorage.setItem(key, JSON.stringify(['기존 질문']))
  const first = renderComposer()
  const input = screen.getByLabelText('업무 질문')
  const setItem = vi
    .spyOn(Storage.prototype, 'setItem')
    .mockImplementation(() => {
      throw new Error('저장 공간이 가득 참')
    })
  try {
    for (const question of ['임시 질문 1', '임시 질문 2']) {
      await user.clear(input)
      await user.type(input, question)
      await user.click(screen.getByRole('button', { name: '내 질문 저장' }))
    }
    for (const question of ['기존 질문', '임시 질문 1']) {
      await user.click(
        screen.getByRole('button', {
          name: `'${question}' 내 질문에서 지우기`,
        }),
      )
    }
    expect(screen.getByRole('status')).toBeVisible()
  } finally {
    setItem.mockRestore()
  }

  window.localStorage.setItem(
    key,
    JSON.stringify(['기존 질문', '다른 탭 질문']),
  )
  await user.clear(input)
  await user.type(input, '새 질문')
  await user.click(screen.getByRole('button', { name: '내 질문 저장' }))

  expect(screen.queryByRole('status')).toBeNull()
  expect(screen.getByRole('button', { name: '저장됨' })).toBeDisabled()
  expect(readSavedQuestions(demoAccount.id)).toEqual([
    '다른 탭 질문',
    '임시 질문 2',
    '새 질문',
  ])
  first.view.unmount()
  renderComposer()
  expect(
    screen.getByRole('button', { name: '임시 질문 2' }),
  ).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '임시 질문 1' })).toBeNull()
})

test('다른 탭이 목록을 채워도 삭제로 공간을 확보하면 임시 질문까지 저장한다', async () => {
  const user = userEvent.setup()
  const key = `axcore.agent.savedQuestions.${demoAccount.id}`
  const original = Array.from({ length: 9 }, (_, i) => `기존 질문 ${i + 1}`)
  window.localStorage.setItem(key, JSON.stringify(original))
  renderComposer()
  const setItem = vi
    .spyOn(Storage.prototype, 'setItem')
    .mockImplementation(() => {
      throw new Error('저장 공간이 가득 참')
    })
  try {
    await user.type(screen.getByLabelText('업무 질문'), '임시 질문')
    await user.click(screen.getByRole('button', { name: '내 질문 저장' }))
  } finally {
    setItem.mockRestore()
  }
  window.localStorage.setItem(
    key,
    JSON.stringify([...original, '다른 탭 질문']),
  )
  await user.click(
    screen.getByRole('button', { name: "'기존 질문 1' 내 질문에서 지우기" }),
  )

  expect(readSavedQuestions(demoAccount.id)).toEqual([
    ...original.slice(1),
    '다른 탭 질문',
    '임시 질문',
  ])
  expect(screen.getByRole('button', { name: '임시 질문' })).toBeInTheDocument()
  expect(screen.queryByRole('status')).toBeNull()
})

test('합친 목록이 한도를 넘으면 임시 질문을 유지하고 공간을 더 확보한 뒤 저장한다', async () => {
  const user = userEvent.setup()
  const key = `axcore.agent.savedQuestions.${demoAccount.id}`
  const original = Array.from({ length: 8 }, (_, i) => `기존 질문 ${i + 1}`)
  window.localStorage.setItem(key, JSON.stringify(original))
  const { view } = renderComposer()
  const input = screen.getByLabelText('업무 질문')
  const setItem = vi
    .spyOn(Storage.prototype, 'setItem')
    .mockImplementation(() => {
      throw new Error('저장 공간이 가득 참')
    })
  try {
    for (const question of ['임시 질문 1', '임시 질문 2']) {
      await user.clear(input)
      await user.type(input, question)
      await user.click(screen.getByRole('button', { name: '내 질문 저장' }))
    }
  } finally {
    setItem.mockRestore()
  }
  const otherTab = [...original, '다른 탭 질문 1', '다른 탭 질문 2']
  window.localStorage.setItem(key, JSON.stringify(otherTab))
  await user.click(
    screen.getByRole('button', { name: "'기존 질문 1' 내 질문에서 지우기" }),
  )

  expect(screen.getByRole('status')).toHaveTextContent('10개를 넘습니다')
  expect(
    screen.getByRole('button', { name: '임시 질문 1' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '임시 질문 2' }),
  ).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '임시 보관 중' })).toBeDisabled()
  expect(readSavedQuestions(demoAccount.id)).toEqual(otherTab)

  await user.click(
    screen.getByRole('button', { name: "'기존 질문 2' 내 질문에서 지우기" }),
  )
  expect(screen.queryByRole('status')).toBeNull()
  expect(readSavedQuestions(demoAccount.id)).toEqual([
    ...original.slice(2),
    '다른 탭 질문 1',
    '다른 탭 질문 2',
    '임시 질문 1',
    '임시 질문 2',
  ])
  view.unmount()
  renderComposer()
  expect(
    screen.getByRole('button', { name: '임시 질문 1' }),
  ).toBeInTheDocument()
  expect(
    screen.getByRole('button', { name: '임시 질문 2' }),
  ).toBeInTheDocument()
})

test('계정을 바꾸면 이전 계정의 임시 질문과 저장 실패 안내를 가져오지 않는다', async () => {
  const user = userEvent.setup()
  const { view, onSend } = renderComposer()
  const setItem = vi
    .spyOn(Storage.prototype, 'setItem')
    .mockImplementation(() => {
      throw new Error('저장 공간이 가득 참')
    })
  try {
    await user.type(screen.getByLabelText('업무 질문'), '첫 계정 임시 질문')
    await user.click(screen.getByRole('button', { name: '내 질문 저장' }))
    expect(screen.getByRole('status')).toBeVisible()
  } finally {
    setItem.mockRestore()
  }

  const other = { ...demoAccount, id: demoAccount.id + 1 }
  view.rerender(
    signedIn(other, <ChatComposer disabled={false} onSend={onSend} />),
  )
  expect(screen.queryByRole('status')).toBeNull()
  expect(screen.queryByRole('list', { name: '내 질문' })).toBeNull()
  await user.clear(screen.getByLabelText('업무 질문'))
  await user.type(screen.getByLabelText('업무 질문'), '두 번째 계정 질문')
  await user.click(screen.getByRole('button', { name: '내 질문 저장' }))
  expect(readSavedQuestions(other.id)).toEqual(['두 번째 계정 질문'])
  expect(readSavedQuestions(demoAccount.id)).toEqual([])
})

test('로그인 전에는 내 질문을 저장할 수 없다', async () => {
  const user = userEvent.setup()
  render(
    <AuthContext.Provider
      value={{
        account: null,
        pending: false,
        error: null,
        refresh: async () => ({ error: null }),
        signIn: async () => {},
        signOut: async () => {},
      }}
    >
      <ChatComposer disabled={false} onSend={vi.fn(async () => {})} />
    </AuthContext.Provider>,
  )

  await user.type(screen.getByLabelText('업무 질문'), 'P101 상세 정보 알려줘')

  expect(screen.getByRole('button', { name: '내 질문 저장' })).toBeDisabled()
})

test('가득 차면 더 저장할 수 없다', async () => {
  const user = userEvent.setup()
  window.localStorage.setItem(
    `axcore.agent.savedQuestions.${demoAccount.id}`,
    JSON.stringify(
      Array.from({ length: MAX_SAVED_QUESTIONS }, (_, i) => `질문 ${i + 1}`),
    ),
  )
  renderComposer()

  await user.type(screen.getByLabelText('업무 질문'), '새 질문')

  expect(screen.getByRole('button', { name: '내 질문 저장' })).toBeDisabled()
})

test('저장 값이 깨졌거나 저장 공간을 쓸 수 없으면 빈 목록으로 둔다', () => {
  window.localStorage.setItem(
    `axcore.agent.savedQuestions.${demoAccount.id}`,
    '{깨진 값',
  )
  expect(readSavedQuestions(demoAccount.id)).toEqual([])

  window.localStorage.setItem(
    `axcore.agent.savedQuestions.${demoAccount.id}`,
    JSON.stringify(['질문', 3, '  ', null, ' 질문 ']),
  )
  // 문자열이 아닌 값·빈 문장은 빼고, 같은 문장은 한 번만 읽는다.
  expect(readSavedQuestions(demoAccount.id)).toEqual(['질문'])

  const getItem = vi
    .spyOn(Storage.prototype, 'getItem')
    .mockImplementation(() => {
      throw new Error('저장 공간을 쓸 수 없음')
    })
  try {
    expect(readSavedQuestions(demoAccount.id)).toEqual([])
  } finally {
    getItem.mockRestore()
  }
})

test('목록에 더할 때 앞뒤 공백을 지우고, 빈 문장·같은 문장·가득 찬 목록은 그대로 둔다', () => {
  expect(withQuestion([], '  질문  ')).toEqual(['질문'])
  expect(withQuestion(['질문'], '질문')).toEqual(['질문'])
  expect(withQuestion(['질문'], '   ')).toEqual(['질문'])
  const full = Array.from({ length: MAX_SAVED_QUESTIONS }, (_, i) => `${i}`)
  expect(withQuestion(full, '하나 더')).toBe(full)
})

test('긴 질문은 버튼에 줄여서 보인다', () => {
  expect(savedQuestionLabel('짧은 질문')).toBe('짧은 질문')
  expect(savedQuestionLabel('가'.repeat(20))).toBe(`${'가'.repeat(16)}…`)
})
