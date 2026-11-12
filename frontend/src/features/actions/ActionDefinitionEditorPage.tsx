import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { Link, useLocation, useParams } from 'react-router'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import PageHeader from '../../components/PageHeader'
import { ErrorState, LoadingState } from '../../components/Feedback'
import StatusBadge from '../../components/StatusBadge'
import { agentUsage, codeError } from './agentUsage'
import {
  actionPath,
  createActionDefinition,
  fetchActionDefinition,
  fetchActionEditorOptions,
  updateActionDefinition,
  type ActionEditorOptions,
} from './api'
import InputSchemaEditor from './InputSchemaEditor'
import { changesDataLabels, targetKindLabels } from './labels'
import {
  initialSchemaDraft,
  readSchemaDraft,
  sameJson,
  type SchemaDraft,
} from './schemaFields'
import type {
  ActionDefinition,
  ActionDefinitionChanges,
  ActionDefinitionInput,
  ActionKind,
  ActionTargetKind,
} from './types'

function UsagePanel({ definition }: { definition: ActionDefinition }) {
  const usage = agentUsage(definition)
  return (
    <section className="space-y-2 management-subpanel text-sm">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-mono text-sm text-slate-500">
          {definition.code}
        </span>
        <StatusBadge tone={usage.usable ? 'success' : 'warning'}>
          {usage.label}
        </StatusBadge>
      </div>
      {!usage.usable && <p>{usage.reason}</p>}
      <p className="text-sm text-slate-500">
        사용자별 권한은 따로 검사합니다. 필요 권한이 없는 사용자에게는 Agent가
        이 기능을 쓰지 않습니다.
      </p>
    </section>
  )
}

function RecordDefinitionView({
  definition,
  options,
}: {
  definition: ActionDefinition
  options: ActionEditorOptions
}) {
  const type = options.objectTypes.find(
    (item) => item.id === definition.targetObjectTypeId,
  )
  const rows: [string, string][] = [
    ['이름', definition.name],
    ['설명', definition.description],
    ['데이터 변경', changesDataLabels[definition.actionKind]],
    [
      '대상',
      `${targetKindLabels[definition.targetKind]}${type ? ` · ${type.name}` : ''}`,
    ],
    ['필요 권한', definition.requiredPermission],
    ['사용', definition.isActive ? '사용' : '사용 안 함'],
  ]
  return (
    <div className="management-form space-y-5">
      <UsagePanel definition={definition} />
      <p className="rounded-lg bg-amber-50 p-3 text-sm text-amber-900">
        관리 작업의 정의는 시스템이 관리해 화면에서 바꿀 수 없습니다.
      </p>
      <dl className="grid gap-3 text-sm sm:grid-cols-[10rem_1fr]">
        {rows.map(([label, value]) => (
          <div key={label} className="contents">
            <dt className="text-slate-500">{label}</dt>
            <dd>{value}</dd>
          </div>
        ))}
      </dl>
    </div>
  )
}

type Draft = {
  code: string
  name: string
  description: string
  changesData: '' | ActionKind
  targetKind: ActionTargetKind
  targetObjectTypeId: string
  handlerKey: string
  schema: SchemaDraft
  requiresConfirmation: boolean
  requiredPermission: string
  isActive: boolean
}

function initialDraft(original?: ActionDefinition): Draft {
  return {
    code: original?.code ?? '',
    name: original?.name ?? '',
    description: original?.description ?? '',
    changesData: original?.actionKind ?? '',
    targetKind: original?.targetKind ?? 'OBJECT',
    targetObjectTypeId: original?.targetObjectTypeId
      ? String(original.targetObjectTypeId)
      : '',
    handlerKey: original?.handlerKey ?? '',
    schema: initialSchemaDraft(original?.inputSchema),
    requiresConfirmation: original?.requiresConfirmation ?? false,
    requiredPermission: original?.requiredPermission ?? '',
    isActive: original?.isActive ?? true,
  }
}

function readInput(
  draft: Draft,
  editing: boolean,
): ActionDefinitionInput | string {
  const code = draft.code.trim()
  const violation = editing ? '' : codeError(code)
  if (violation) return violation
  const name = draft.name.trim()
  if (!name || name.length > 200)
    return '이름은 1자 이상 200자 이하로 입력해 주세요.'
  if (!draft.description.trim()) return '설명을 입력해 주세요.'
  if (!draft.changesData)
    return '이 기능이 데이터를 만들거나 바꾸는지 선택해 주세요.'
  const handlerKey = draft.handlerKey.trim()
  if (!handlerKey || handlerKey.length > 200)
    return 'Handler 이름은 1자 이상 200자 이하로 입력해 주세요.'
  if (!draft.requiredPermission) return '필요 권한을 선택해 주세요.'
  const schema = readSchemaDraft(draft.schema)
  if (schema.error !== undefined) return schema.error
  return {
    code,
    name,
    description: draft.description.trim(),
    targetKind: draft.targetKind,
    targetObjectTypeId:
      draft.targetKind === 'OBJECT' && draft.targetObjectTypeId
        ? Number(draft.targetObjectTypeId)
        : null,
    handlerKey,
    inputSchema: schema.schema,
    actionKind: draft.changesData,
    requiresConfirmation: draft.requiresConfirmation,
    requiredPermission: draft.requiredPermission,
    isActive: draft.isActive,
  }
}

function changesFrom(
  original: ActionDefinition,
  input: ActionDefinitionInput,
): ActionDefinitionChanges {
  const changes: ActionDefinitionChanges = { rowVersion: original.rowVersion }
  if (input.name !== original.name) changes.name = input.name
  if (input.description !== original.description)
    changes.description = input.description
  if (input.targetKind !== original.targetKind)
    changes.targetKind = input.targetKind
  if (input.targetObjectTypeId !== original.targetObjectTypeId)
    changes.targetObjectTypeId = input.targetObjectTypeId
  if (input.handlerKey !== original.handlerKey)
    changes.handlerKey = input.handlerKey
  if (!sameJson(input.inputSchema, original.inputSchema))
    changes.inputSchema = input.inputSchema
  if (input.actionKind !== original.actionKind)
    changes.actionKind = input.actionKind
  if (input.requiresConfirmation !== original.requiresConfirmation)
    changes.requiresConfirmation = input.requiresConfirmation
  if (input.requiredPermission !== original.requiredPermission)
    changes.requiredPermission = input.requiredPermission
  if (input.isActive !== original.isActive) changes.isActive = input.isActive
  return changes
}

function ActionDefinitionForm({
  original,
  options,
  onReload,
}: {
  original?: ActionDefinition
  options: ActionEditorOptions
  onReload: () => void
}) {
  const client = useQueryClient()
  const [draft, setDraft] = useState(() => initialDraft(original))
  const [pending, setPending] = useState(false)
  const [error, setError] = useState<Error | null>(null)
  const [result, setResult] = useState<ActionDefinition | null>(null)
  const submitting = useRef(false)
  const editing = original !== undefined
  const blocked =
    editing &&
    error instanceof ApiError &&
    [401, 403, 404, 409, 422].includes(error.status ?? 0)
  const permissions = options.permissions.includes(draft.requiredPermission)
    ? options.permissions
    : [draft.requiredPermission, ...options.permissions].filter(Boolean)
  const codeViolation = editing || !draft.code ? '' : codeError(draft.code)

  function update<K extends keyof Draft>(key: K, value: Draft[K]) {
    setDraft((current) => ({ ...current, [key]: value }))
  }

  async function submit() {
    if (submitting.current || blocked || result) return
    const input = readInput(draft, editing)
    if (typeof input === 'string') {
      setError(new Error(input))
      return
    }
    let changes: ActionDefinitionChanges | undefined
    if (original) {
      changes = changesFrom(original, input)
      if (Object.keys(changes).length === 1) {
        setError(new Error('변경할 내용을 입력해 주세요.'))
        return
      }
    }
    submitting.current = true
    setPending(true)
    setError(null)
    try {
      const saved = original
        ? await updateActionDefinition(original.code, changes!)
        : await createActionDefinition(input)
      setResult(saved)
      await client.invalidateQueries({
        queryKey: ['actions'],
        refetchType: 'none',
      })
    } catch (cause) {
      setError(
        cause instanceof Error
          ? cause
          : new Error('Action 정의 요청을 처리하지 못했습니다.'),
      )
    } finally {
      setPending(false)
      submitting.current = false
    }
  }

  if (result)
    return (
      <section
        role="status"
        className="space-y-3 rounded-xl border border-emerald-200 bg-emerald-50 p-5"
      >
        <h2 className="font-semibold">
          {editing ? 'Action 정의를 변경했습니다.' : 'Action을 등록했습니다.'}
        </h2>
        <p>
          {result.name} · <span className="font-mono">{result.code}</span>
        </p>
        {!result.handlerReady && (
          <p>
            아직 구현되지 않은 기능입니다. 구현되기 전에는 실행되지 않습니다.
            {(!original || original.handlerKey !== result.handlerKey) &&
              ' 서버가 개발자에게 구현 요청 알림을 보냅니다.'}
          </p>
        )}
        <Link
          className="text-violet-700 underline"
          to={actionPath(result.code)}
          onClick={() => {
            if (editing) onReload()
          }}
        >
          정의 상세 확인
        </Link>
      </section>
    )

  return (
    <form
      className="management-form space-y-5"
      onSubmit={(event) => {
        event.preventDefault()
        void submit()
      }}
    >
      {original && <UsagePanel definition={original} />}
      <fieldset disabled={pending || Boolean(blocked)} className="space-y-5">
        <label className="flex flex-col gap-2 text-sm">
          코드
          <input
            className="rounded-lg border p-2 font-mono"
            value={draft.code}
            onChange={(event) =>
              update('code', event.target.value.toUpperCase())
            }
            disabled={editing}
            maxLength={64}
            autoComplete="off"
            aria-invalid={Boolean(codeViolation) || undefined}
            aria-describedby="action-code-help"
          />
          <span
            id="action-code-help"
            className={codeViolation ? 'text-red-700' : 'text-slate-500'}
          >
            {codeViolation ||
              (editing
                ? '코드는 등록한 뒤 바꿀 수 없습니다.'
                : '영문 대문자로 시작하고 대문자·숫자·밑줄(_)만, 64자 이하. 예: ESTIMATE_COMPLETION')}
          </span>
        </label>
        <label className="flex flex-col gap-2 text-sm">
          이름
          <input
            className="rounded-lg border p-2"
            value={draft.name}
            onChange={(event) => update('name', event.target.value)}
            maxLength={200}
          />
        </label>
        <label className="flex flex-col gap-2 text-sm">
          설명
          <textarea
            className="min-h-20 rounded-lg border p-2"
            value={draft.description}
            onChange={(event) => update('description', event.target.value)}
          />
          <span className="text-slate-500">
            Agent가 이 설명을 보고 언제 쓸지 판단합니다. 무엇을 하는지
            구체적으로 적어 주세요.
          </span>
        </label>
        <fieldset className="space-y-2 rounded-lg border p-4 text-sm">
          <legend className="px-1 font-medium">
            이 기능이 데이터를 만들거나 바꾸나요?
          </legend>
          <label className="flex items-start gap-2">
            <input
              type="radio"
              name="changesData"
              checked={draft.changesData === 'WRITE'}
              onChange={() => update('changesData', 'WRITE')}
            />
            <span>
              예
              <span className="block text-slate-500">
                계획·요청처럼 데이터를 만들거나 바꿉니다. Agent는 사용자가 해
                달라고 요청할 때만 실행하고 실행 이력에 남깁니다.
              </span>
            </span>
          </label>
          <label className="flex items-start gap-2">
            <input
              type="radio"
              name="changesData"
              checked={draft.changesData === 'READ'}
              onChange={() => update('changesData', 'READ')}
            />
            <span>
              아니요
              <span className="block text-slate-500">
                계산하거나 찾아서 알려 주기만 합니다. 예: 예상 완료 시각 계산,
                대체 설비 찾기.
              </span>
            </span>
          </label>
        </fieldset>
        <div className="grid gap-5 sm:grid-cols-2">
          <label className="flex flex-col gap-2 text-sm">
            대상
            <select
              className="rounded-lg border p-2"
              value={draft.targetKind}
              onChange={(event) => {
                const kind = event.target.value as ActionTargetKind
                setDraft((current) => ({
                  ...current,
                  targetKind: kind,
                  targetObjectTypeId:
                    kind === 'SYSTEM' ? '' : current.targetObjectTypeId,
                }))
              }}
            >
              {Object.entries(targetKindLabels).map(([value, label]) => (
                <option key={value} value={value}>
                  {label}
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-2 text-sm">
            대상 객체 유형
            <select
              className="rounded-lg border p-2"
              value={draft.targetObjectTypeId}
              disabled={draft.targetKind === 'SYSTEM'}
              onChange={(event) =>
                update('targetObjectTypeId', event.target.value)
              }
            >
              <option value="">지정 안 함</option>
              {options.objectTypes.map((type) => (
                <option key={type.id} value={type.id}>
                  {type.name} ({type.code})
                </option>
              ))}
            </select>
          </label>
          <label className="flex flex-col gap-2 text-sm">
            Handler 이름
            <input
              className="rounded-lg border p-2 font-mono"
              value={draft.handlerKey}
              onChange={(event) => update('handlerKey', event.target.value)}
              maxLength={200}
              autoComplete="off"
            />
            <span className="text-slate-500">
              개발자가 구현할 기능 이름입니다. 보통 코드와 같게 둡니다.
            </span>
          </label>
          <label className="flex flex-col gap-2 text-sm">
            필요 권한
            <select
              className="rounded-lg border p-2"
              value={draft.requiredPermission}
              onChange={(event) =>
                update('requiredPermission', event.target.value)
              }
            >
              <option value="">권한 선택</option>
              {permissions.map((code) => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </label>
        </div>
        <InputSchemaEditor
          value={draft.schema}
          onChange={(value) => update('schema', value)}
        />
        <label className="flex gap-2 text-sm">
          <input
            type="checkbox"
            checked={draft.requiresConfirmation}
            onChange={(event) =>
              update('requiresConfirmation', event.target.checked)
            }
          />
          실행 전에 사용자 확인 받기
        </label>
        <label className="flex gap-2 text-sm">
          <input
            type="checkbox"
            checked={draft.isActive}
            onChange={(event) => update('isActive', event.target.checked)}
          />
          사용
        </label>
        {original && (
          <p className="text-sm text-slate-500">
            수정 기준 버전 {original.rowVersion}
          </p>
        )}
      </fieldset>
      {error && <ErrorState error={error} />}
      {Boolean(blocked) && (
        <>
          <p className="text-sm">
            입력은 현재 화면에 보존됩니다. 최신 정의를 다시 조회하면 입력을
            초기화합니다.
          </p>
          <Button variant="secondary" onClick={onReload}>
            최신 정의 다시 조회·입력 초기화
          </Button>
        </>
      )}
      <p className="text-sm text-slate-500">
        통신 오류가 발생하면 자동 재전송하지 않습니다. 목록에서 처리 여부를
        확인한 뒤 다시 시도하세요.
      </p>
      <Button type="submit" pending={pending} disabled={Boolean(blocked)}>
        {editing ? '변경 저장' : 'Action 등록'}
      </Button>
    </form>
  )
}

export default function ActionDefinitionEditorPage() {
  const { code } = useParams()
  const location = useLocation()
  const editing = code !== undefined
  const [reload, setReload] = useState(0)
  const query = useQuery({
    queryKey: ['actions', 'editor', code ?? 'new', reload],
    queryFn: async ({ signal }) => {
      const [definition, options] = await Promise.all([
        code === undefined ? undefined : fetchActionDefinition(code, signal),
        fetchActionEditorOptions(),
      ])
      return { definition, options }
    },
    retry: false,
    refetchOnMount: 'always',
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  })
  let content
  if (query.isPending || query.isFetching || query.isPaused)
    content = <LoadingState message="최신 Action 정의를 불러오는 중…" />
  else if (query.isError)
    content = (
      <ErrorState
        error={query.error}
        onRetry={() => {
          void query.refetch()
        }}
      />
    )
  else if (query.data.definition?.actionMode === 'RECORD')
    content = (
      <RecordDefinitionView
        definition={query.data.definition}
        options={query.data.options}
      />
    )
  else
    content = (
      <ActionDefinitionForm
        key={`${location.pathname}:${reload}:${query.data.definition?.rowVersion ?? 0}`}
        original={query.data.definition}
        options={query.data.options}
        onReload={() => setReload((value) => value + 1)}
      />
    )
  return (
    <div className="management-page space-y-5">
      <Link
        className="text-sm text-violet-700 underline"
        to={`/admin/actions${location.search}`}
      >
        Action 정의 목록으로 돌아가기
      </Link>
      <PageHeader
        title={editing ? 'Action 정의 상세·변경' : 'Action 등록'}
        description="실행 조건과 입력 형식을 확인하고 Action 정의를 설정합니다."
      />
      {content}
    </div>
  )
}
