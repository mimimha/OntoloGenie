import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import { ApiError } from '../../api/errors'
import { ErrorState } from '../../components/Feedback'
import { createSource, updateSource } from './api'
import { parseEndpoint } from './endpoint'
import { purposeLabels } from './labels'
import type {
  AccountPurpose,
  CredentialInput,
  Source,
  SourceDetail,
  SourceKind,
} from './types'

const kindOptions: { value: SourceKind; label: string }[] = [
  { value: 'POSTGRES', label: 'PostgreSQL' },
  { value: 'CSV', label: 'CSV' },
  { value: 'DOCUMENT', label: '문서·기록' },
]

const purposes: AccountPurpose[] = ['SYNC', 'AI']

const inputClass = 'rounded-lg border border-slate-300 px-2 py-1'

function formatEndpoint(config: Source['connectionConfig']) {
  if (!config.host) return ''
  return `${config.host}${config.port ? `:${config.port}` : ''}/${config.database ?? ''}`
}

// 창을 연 때(또는 최신 내용을 다시 불러온 때)의 값. 사용자가 이 값에서 바꾼 칸만 보낸다.
function snapshot(source?: SourceDetail) {
  const username = (purpose: AccountPurpose) =>
    source?.credentials.find((item) => item.purpose === purpose)?.username ?? ''
  return {
    name: source?.name ?? '',
    endpoint: source ? formatEndpoint(source.connectionConfig) : '',
    usernames: { SYNC: username('SYNC'), AI: username('AI') },
  }
}

type Props = {
  // 없으면 등록, 있으면 수정
  source?: SourceDetail
  onClose: () => void
  // reconnectNeeded: 접속 대상이나 계정이 바뀌어 연결을 다시 확인해야 한다. endpointChanged: 접속 대상이 바뀌었다.
  onSaved: (
    source: Source,
    reconnectNeeded: boolean,
    endpointChanged: boolean,
  ) => void
}

// 원천 등록(010)·정보 변경(012). 비밀번호는 요청에만 담고 화면에 다시 보여 주지 않는다.
function SourceFormDialog({ source, onClose, onSaved }: Props) {
  const queryClient = useQueryClient()
  const editing = source !== undefined
  const [opened, setOpened] = useState(() => snapshot(source))
  const [name, setName] = useState(opened.name)
  const [code, setCode] = useState(source?.code ?? '')
  const [kind, setKind] = useState<SourceKind>(source?.sourceKind ?? 'POSTGRES')
  const [endpoint, setEndpoint] = useState(opened.endpoint)
  const [accounts, setAccounts] = useState<
    Record<AccountPurpose, { username: string; password: string }>
  >(() => ({
    SYNC: { username: opened.usernames.SYNC, password: '' },
    AI: { username: opened.usernames.AI, password: '' },
  }))
  const [formError, setFormError] = useState('')
  // 다른 곳에서 먼저 바뀌어(409) 최신 내용을 다시 불러왔다
  const [reloaded, setReloaded] = useState(false)
  const usesDatabase = kind === 'POSTGRES'

  const save = useMutation({
    mutationFn: async () => {
      const config = usesDatabase ? parseEndpoint(endpoint) : {}
      const credentials: CredentialInput[] = []
      for (const purpose of usesDatabase ? purposes : []) {
        const account = {
          username: accounts[purpose].username.trim(),
          password: accounts[purpose].password,
        }
        const before = opened.usernames[purpose]
        // 수정에서는 비밀번호를 새로 넣은 계정만 바꾼다.
        if (!editing || account.password) {
          credentials.push({ purpose, ...account })
        } else if (account.username !== before) {
          throw new Error(
            `${purposeLabels[purpose]} 이름을 바꾸려면 비밀번호도 입력해 주세요.`,
          )
        }
      }
      if (!editing) {
        return {
          saved: await createSource({
            code: code.trim(),
            name: name.trim(),
            sourceKind: kind,
            connectionConfig: config ?? {},
            credentials,
          }),
          reconnectNeeded: false,
          endpointChanged: false,
        }
      }
      const configChanged =
        usesDatabase && formatEndpoint(config ?? {}) !== opened.endpoint
      return {
        saved: await updateSource(source.id, {
          rowVersion: source.rowVersion,
          ...(name.trim() !== opened.name ? { name: name.trim() } : {}),
          ...(configChanged ? { connectionConfig: config ?? {} } : {}),
          ...(credentials.length ? { credentials } : {}),
        }),
        reconnectNeeded: configChanged || credentials.length > 0,
        endpointChanged: configChanged,
      }
    },
    onMutate: () => setReloaded(false),
    onSuccess: ({ saved, reconnectNeeded, endpointChanged }) => {
      void queryClient.invalidateQueries({ queryKey: ['sources'] })
      onSaved(saved, reconnectNeeded, endpointChanged)
    },
    // 다른 곳에서 먼저 저장했으면(rowVersion 충돌) 최신 내용을 받아 와야 같은 창에서 다시 저장할 수 있다.
    onError: async (error) => {
      if (!editing || !(error instanceof ApiError) || error.status !== 409)
        return
      const key = ['sources', source.id, 'detail']
      await queryClient.invalidateQueries({ queryKey: key })
      const latest = queryClient.getQueryData<SourceDetail>(key)
      if (latest) {
        // 손대지 않은 칸은 최신 값으로 바꿔, 다시 저장할 때 다른 곳에서 바꾼 값을 옛 값으로 되돌리지 않는다
        const next = snapshot(latest)
        if (name === opened.name) setName(next.name)
        if (endpoint === opened.endpoint) setEndpoint(next.endpoint)
        setAccounts((current) => ({
          SYNC:
            current.SYNC.username === opened.usernames.SYNC
              ? { ...current.SYNC, username: next.usernames.SYNC }
              : current.SYNC,
          AI:
            current.AI.username === opened.usernames.AI
              ? { ...current.AI, username: next.usernames.AI }
              : current.AI,
        }))
        setOpened(next)
      }
      setReloaded(true)
    },
  })

  function submit(event: FormEvent) {
    event.preventDefault()
    if (!name.trim() || (!editing && !code.trim())) {
      setFormError('원천 이름과 코드를 입력해 주세요.')
      return
    }
    if (usesDatabase) {
      if (!parseEndpoint(endpoint)) {
        setFormError(
          '접속 대상을 "주소:포트/데이터베이스" 형식으로 입력해 주세요.',
        )
        return
      }
      const missing = purposes.find(
        (purpose) =>
          !accounts[purpose].username.trim() ||
          (!editing && !accounts[purpose].password),
      )
      if (missing) {
        setFormError(
          `${purposeLabels[missing]}의 이름${editing ? '' : '과 비밀번호'}를 입력해 주세요.`,
        )
        return
      }
    }
    setFormError('')
    save.mutate()
  }

  function setAccount(
    purpose: AccountPurpose,
    field: 'username' | 'password',
    value: string,
  ) {
    setAccounts((previous) => ({
      ...previous,
      [purpose]: { ...previous[purpose], [field]: value },
    }))
  }

  return (
    <Dialog title={editing ? '원천 정보 수정' : '원천 등록'} onClose={onClose}>
      <form
        className="sources-form mt-4 space-y-4 text-sm"
        onSubmit={submit}
        noValidate
      >
        <p className="text-sm text-slate-600">
          조회 전용 계정만 등록하세요. 비밀번호는 저장할 때만 보내고 화면에 다시
          보여 주지 않습니다.
        </p>
        <label className="flex flex-col gap-1">
          원천 이름
          <input
            value={name}
            onChange={(event) => setName(event.target.value)}
            placeholder="예: ERP 운영 DB"
            className={inputClass}
          />
        </label>
        <label className="flex flex-col gap-1">
          원천 코드
          <input
            value={code}
            onChange={(event) => setCode(event.target.value.toUpperCase())}
            placeholder="예: ERP_MAIN"
            disabled={editing}
            className={`${inputClass} font-mono disabled:bg-slate-50`}
          />
        </label>
        <label className="flex flex-col gap-1">
          유형
          <select
            value={kind}
            onChange={(event) => setKind(event.target.value as SourceKind)}
            disabled={editing}
            className={`${inputClass} disabled:bg-slate-50`}
          >
            {kindOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        {usesDatabase && (
          <>
            <label className="flex flex-col gap-1">
              접속 대상
              <input
                value={endpoint}
                onChange={(event) => setEndpoint(event.target.value)}
                placeholder="예: erp-db.internal:5432/erp"
                className={`${inputClass} font-mono`}
              />
            </label>
            {purposes.map((purpose) => (
              <fieldset key={purpose} className="space-y-2">
                <legend className="font-medium text-slate-900">
                  {purposeLabels[purpose]}
                </legend>
                <div className="grid gap-2 sm:grid-cols-2">
                  <label className="flex flex-col gap-1">
                    {`${purposeLabels[purpose]} 이름`}
                    <input
                      value={accounts[purpose].username}
                      onChange={(event) =>
                        setAccount(purpose, 'username', event.target.value)
                      }
                      autoComplete="off"
                      className={`${inputClass} font-mono`}
                    />
                  </label>
                  <label className="flex flex-col gap-1">
                    {`${purposeLabels[purpose]} 비밀번호`}
                    <input
                      type="password"
                      value={accounts[purpose].password}
                      onChange={(event) =>
                        setAccount(purpose, 'password', event.target.value)
                      }
                      autoComplete="new-password"
                      placeholder={editing ? '바꿀 때만 입력' : ''}
                      className={inputClass}
                    />
                  </label>
                </div>
              </fieldset>
            ))}
          </>
        )}
        {formError && (
          <p role="alert" className="text-red-600">
            {formError}
          </p>
        )}
        {save.isError && <ErrorState error={save.error} />}
        {save.isError && reloaded && (
          <p role="status" className="text-slate-700">
            다른 곳에서 바뀐 내용을 불러왔습니다. 입력한 내용을 확인하고 다시
            저장해 주세요.
          </p>
        )}
        <div className="flex justify-end gap-2">
          <Button variant="secondary" onClick={onClose}>
            취소
          </Button>
          <Button type="submit" pending={save.isPending}>
            {editing ? '저장' : '등록'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}

export default SourceFormDialog
