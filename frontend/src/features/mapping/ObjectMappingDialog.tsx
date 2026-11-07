import { useMutation, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import { fetchColumns } from '../sources/api'
import type { SourceResource } from '../sources/types'
import {
  mappingSaveMessage,
  fetchAllPropertyMappings,
  saveObjectMapping,
  type Definition,
  type ObjectMapping,
} from './api'
import MappingPreview from './MappingPreview'
import {
  PreviewDraftError,
  type MappingPreviewAccess,
  type MappingPreviewDraft,
} from './previewModel'

export default function ObjectMappingDialog({
  sourceId,
  initialResourceId,
  resources,
  objectTypes,
  existing,
  onClose,
  onReload,
  onSaved,
  previewAccess,
}: {
  sourceId: number
  initialResourceId: number
  resources: SourceResource[]
  objectTypes: Definition[]
  existing?: ObjectMapping
  onClose: () => void
  onReload: () => void
  onSaved: (mapping: ObjectMapping, message: string) => void
  previewAccess?: MappingPreviewAccess
}) {
  const [resourceId, setResourceId] = useState(initialResourceId)
  const [typeId, setTypeId] = useState(existing?.objectTypeId ?? 0)
  const [keys, setKeys] = useState(existing?.keyColumns ?? [])
  const [active, setActive] = useState(existing?.isActive ?? true)
  const [validation, setValidation] = useState('')
  const columns = useQuery({
    queryKey: ['sources', sourceId, 'resources', resourceId, 'columns'],
    queryFn: () => fetchColumns(sourceId, resourceId),
  })
  const present = columns.data?.filter((column) => column.isPresent) ?? []
  const save = useMutation({
    mutationFn: () =>
      saveObjectMapping(
        {
          sourceResourceId: resourceId,
          objectTypeId: typeId,
          keyColumns: keys,
          isActive: active,
        },
        existing,
      ),
    onSuccess: (result) => onSaved(result, mappingSaveMessage(result)),
  })
  const stale =
    save.error instanceof ApiError && save.error.code === 'VERSION_CONFLICT'
  const hasRowFilter = !!existing && Object.keys(existing.rowFilter).length > 0

  function currentDraft() {
    if (!objectTypes.some((item) => item.id === typeId))
      throw new PreviewDraftError('객체 유형을 선택해 주세요.')
    if (
      !keys.length ||
      keys.some((key) => !present.some((column) => column.columnName === key))
    )
      throw new PreviewDraftError(
        '현재 존재하는 키 컬럼을 한 개 이상 선택해 주세요.',
      )
    return {
      sourceResourceId: resourceId,
      objectTypeId: typeId,
      keyColumns: keys,
      rowFilter: {},
      isActive: active,
    }
  }

  async function preparePreview(): Promise<MappingPreviewDraft> {
    const draft = currentDraft()
    const saved = existing ? await fetchAllPropertyMappings(existing.id) : []
    return {
      ...draft,
      sourceMappingId: existing?.id,
      rowVersion: existing?.rowVersion,
      properties: saved.map(
        ({
          propertyDefinitionId,
          sourceColumnId,
          sourcePath,
          transformSteps,
        }) => ({
          propertyDefinitionId,
          sourceColumnId,
          sourcePath,
          transformSteps,
        }),
      ),
    }
  }

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (save.isPending || stale || hasRowFilter || !columns.isSuccess) return
    try {
      currentDraft()
    } catch (error) {
      if (error instanceof Error) setValidation(error.message)
      return
    }
    setValidation('')
    save.mutate()
  }

  return (
    <Dialog
      title={existing ? '객체 매핑 편집' : '객체 매핑 등록'}
      size="wide"
      onClose={() => {
        if (!save.isPending) onClose()
      }}
    >
      <form noValidate onSubmit={submit} className="mt-4 space-y-4 text-sm">
        {hasRowFilter && (
          <p role="alert">
            기존 행 조건이 있는 매핑입니다. 행 조건을 지원하는 화면에서 편집해
            주세요.
          </p>
        )}
        {existing && (
          <p className="text-slate-600">
            기존 속성 연결을 유지하기 위해 이 화면에서는 등록된 원천 자원과 객체
            유형을 고정합니다.
          </p>
        )}
        <fieldset
          disabled={save.isPending || stale || hasRowFilter}
          className="space-y-4"
        >
          <label className="block">
            원천 자원
            <select
              className="mt-1 w-full rounded border border-slate-300 p-2"
              value={resourceId}
              disabled={!!existing}
              onChange={(event) => {
                setResourceId(Number(event.target.value))
                setKeys([])
                setValidation('')
              }}
            >
              {resources.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.resourceKey}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            객체 유형
            <select
              required
              className="mt-1 w-full rounded border border-slate-300 p-2"
              value={typeId}
              disabled={!!existing}
              onChange={(event) => setTypeId(Number(event.target.value))}
            >
              <option value={0}>객체 유형 선택</option>
              {objectTypes.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name} · {item.code}
                </option>
              ))}
            </select>
          </label>
          <fieldset className="space-y-2">
            <legend className="font-medium">키 컬럼</legend>
            <p className="text-slate-500">
              선택한 순서대로 객체 식별 키를 구성합니다.
            </p>
            {keys.length > 0 && <p>키 순서: {keys.join(' → ')}</p>}
            {columns.isPending && (
              <LoadingState message="원천 컬럼을 불러오는 중…" />
            )}
            {columns.isError && (
              <ErrorState
                error={columns.error}
                onRetry={() => void columns.refetch()}
              />
            )}
            {columns.isSuccess && present.length === 0 && (
              <EmptyState message="현재 사용할 수 있는 컬럼이 없습니다." />
            )}
            {columns.isSuccess &&
              keys
                .filter(
                  (key) => !present.some((column) => column.columnName === key),
                )
                .map((key) => (
                  <label key={key} className="flex gap-2">
                    <input
                      type="checkbox"
                      checked
                      onChange={() =>
                        setKeys(keys.filter((value) => value !== key))
                      }
                    />
                    {key} (현재 컬럼에 없음)
                  </label>
                ))}
            <div className="grid gap-2 sm:grid-cols-2">
              {present.map((column) => (
                <label
                  key={column.id}
                  className="flex min-w-0 items-start gap-2 break-all"
                >
                  <input
                    type="checkbox"
                    checked={keys.includes(column.columnName)}
                    onChange={(event) =>
                      setKeys(
                        event.target.checked
                          ? [...keys, column.columnName]
                          : keys.filter((key) => key !== column.columnName),
                      )
                    }
                  />
                  {column.columnName}
                </label>
              ))}
            </div>
          </fieldset>
          <label className="flex gap-2">
            <input
              type="checkbox"
              checked={active}
              onChange={(event) => setActive(event.target.checked)}
            />
            활성 매핑
          </label>
        </fieldset>
        {validation && (
          <p role="alert" className="text-red-800">
            {validation}
          </p>
        )}
        {save.isError && <ErrorState error={save.error} />}
        {stale && (
          <Button variant="secondary" onClick={onReload}>
            최신 매핑 다시 불러오기
          </Button>
        )}
        <MappingPreview
          key={JSON.stringify([
            resourceId,
            typeId,
            keys,
            active,
            existing,
            previewAccess?.policyDescription,
            save.isPending,
            stale,
          ])}
          access={previewAccess}
          prepareDraft={preparePreview}
          disabled={
            save.isPending ||
            stale ||
            hasRowFilter ||
            !columns.isSuccess ||
            !resources.some(
              (resource) => resource.id === resourceId && resource.isReadable,
            )
          }
        />
        <div className="flex justify-end">
          <Button
            type="submit"
            disabled={
              stale || hasRowFilter || !columns.isSuccess || !objectTypes.length
            }
            pending={save.isPending}
          >
            {save.isPending ? '저장 중…' : '저장'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
