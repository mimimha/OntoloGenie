import { useMutation, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { ApiError } from '../../api/errors'
import Button from '../../components/Button'
import Dialog from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState } from '../../components/Feedback'
import {
  buildTransform,
  toTransformDraft,
  transformLabels,
} from '../review/ruleDraft'
import TransformEditor from '../review/TransformEditor'
import type { SourceColumn } from '../sources/types'
import {
  fetchTransformFunctions,
  mappingSaveMessage,
  savePropertyMapping,
  type Definition,
  type ObjectMapping,
  type PropertyMapping,
  type TransformStep,
} from './api'

export default function PropertyMappingDialog({
  parent,
  existing,
  columns,
  properties,
  onClose,
  onReload,
  onSaved,
}: {
  parent: ObjectMapping
  existing?: PropertyMapping
  columns: SourceColumn[]
  properties: Definition[]
  onClose: () => void
  onReload: () => void
  onSaved: (message: string) => void
}) {
  const [columnId, setColumnId] = useState(existing?.sourceColumnId ?? 0)
  const [propertyId, setPropertyId] = useState(
    existing?.propertyDefinitionId ?? 0,
  )
  const [steps, setSteps] = useState(
    () => existing?.transformSteps.map(toTransformDraft) ?? [],
  )
  const [validation, setValidation] = useState('')
  const catalog = useQuery({
    queryKey: ['source-mappings', 'transforms'],
    queryFn: fetchTransformFunctions,
  })
  const functions =
    catalog.data?.filter(({ op }) => Object.hasOwn(transformLabels, op)) ?? []
  const save = useMutation({
    mutationFn: (transformSteps: TransformStep[]) =>
      savePropertyMapping(
        {
          sourceMappingId: parent.id,
          sourceResourceId: parent.sourceResourceId,
          propertyDefinitionId: propertyId,
          sourceColumnId: columnId,
          transformSteps,
        },
        existing,
      ),
    onSuccess: (result) => onSaved(mappingSaveMessage(result)),
  })
  const stale =
    save.error instanceof ApiError && save.error.code === 'VERSION_CONFLICT'
  const hasPath = existing?.sourcePath != null
  const selectedColumnExists = columns.some((column) => column.id === columnId)
  const selectedPropertyExists = properties.some(
    (property) => property.id === propertyId,
  )

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (save.isPending || stale || hasPath || !catalog.isSuccess) return
    if (!selectedColumnExists || !selectedPropertyExists) {
      setValidation(
        '현재 사용할 수 있는 원천 컬럼과 대상 속성을 선택해 주세요.',
      )
      return
    }
    let transformSteps: TransformStep[]
    try {
      transformSteps = steps.map((step) => {
        if (!functions.some((fn) => fn.op === step.op))
          throw new Error('현재 사용할 수 있는 변환 함수를 선택해 주세요.')
        return buildTransform(step)
      })
    } catch (error) {
      if (error instanceof Error) setValidation(error.message)
      return
    }
    setValidation('')
    save.mutate(transformSteps)
  }

  return (
    <Dialog
      title={existing ? '속성 매핑 편집' : '속성 매핑 등록'}
      onClose={() => {
        if (!save.isPending) onClose()
      }}
    >
      <form noValidate onSubmit={submit} className="mt-4 space-y-4 text-sm">
        {hasPath && (
          <p role="alert">
            기존 문서 경로가 있는 매핑입니다. 문서 경로를 지원하는 화면에서
            편집해 주세요.
          </p>
        )}
        <fieldset
          disabled={save.isPending || stale || hasPath}
          className="space-y-4"
        >
          <label className="block">
            원천 컬럼
            <select
              required
              className="mt-1 w-full rounded border border-slate-300 p-2"
              value={columnId}
              onChange={(event) => setColumnId(Number(event.target.value))}
            >
              <option value={0}>원천 컬럼 선택</option>
              {columnId > 0 && !selectedColumnExists && (
                <option value={columnId} disabled>
                  기존 컬럼 #{columnId} (사용할 수 없음)
                </option>
              )}
              {columns.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.columnName}
                </option>
              ))}
            </select>
          </label>
          <label className="block">
            대상 속성
            <select
              required
              className="mt-1 w-full rounded border border-slate-300 p-2"
              value={propertyId}
              onChange={(event) => setPropertyId(Number(event.target.value))}
            >
              <option value={0}>대상 속성 선택</option>
              {propertyId > 0 && !selectedPropertyExists && (
                <option value={propertyId} disabled>
                  기존 속성 #{propertyId} (사용할 수 없음)
                </option>
              )}
              {properties.map((item) => (
                <option key={item.id} value={item.id}>
                  {item.name} · {item.code}
                </option>
              ))}
            </select>
          </label>
          {(!columns.length || !properties.length) && (
            <EmptyState message="매핑에 사용할 컬럼과 속성이 필요합니다." />
          )}
          {catalog.isPending && (
            <LoadingState message="변환 함수를 불러오는 중…" />
          )}
          {catalog.isError && (
            <ErrorState
              error={catalog.error}
              onRetry={() => void catalog.refetch()}
            />
          )}
          {catalog.isSuccess && (
            <TransformEditor
              label="변환 단계"
              steps={steps}
              onChange={setSteps}
              functions={functions}
              emptyMessage="변환 없이 원천 값을 사용합니다."
            />
          )}
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
        <div className="flex justify-end">
          <Button
            type="submit"
            pending={save.isPending}
            disabled={
              stale ||
              hasPath ||
              !catalog.isSuccess ||
              !columns.length ||
              !properties.length
            }
          >
            {save.isPending ? '저장 중…' : '저장'}
          </Button>
        </div>
      </form>
    </Dialog>
  )
}
