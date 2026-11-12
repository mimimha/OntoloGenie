import { apiGet, apiPatch, apiPost, ApiError } from '../../api/client'
import { fetchRoles } from '../accounts/api'
import { fetchObjectTypes } from '../mapping/api'
import type {
  ActionDefinition,
  ActionDefinitionChanges,
  ActionDefinitionInput,
  ActionKind,
} from './types'

export const ACTION_PAGE_SIZE = 20

export type ActionFilters = {
  isActive?: 'true' | 'false'
  actionKind?: ActionKind
}

export function actionPath(code: string) {
  return `/admin/actions/${encodeURIComponent(code)}`
}

export function fetchActionDefinitions(
  filters: ActionFilters,
  page: number,
  signal?: AbortSignal,
) {
  const params = new URLSearchParams({
    page: String(page),
    size: String(ACTION_PAGE_SIZE),
  })
  for (const [key, value] of Object.entries(filters))
    if (value) params.set(key, value)
  return apiGet<ActionDefinition[]>(`/actions?${params}`, { signal })
}

export async function fetchActionDefinition(
  code: string,
  signal?: AbortSignal,
) {
  return (
    await apiGet<ActionDefinition>(`/actions/${encodeURIComponent(code)}`, {
      signal,
    })
  ).data
}

export type ActionEditorOptions = {
  objectTypes: { id: number; name: string; code: string }[]
  permissions: string[]
}

// 권한 코드 목록 API가 없어 역할별 권한을 모아 고를 수 있게 한다. 등록 여부는 서버가 다시 검사한다.
export async function fetchActionEditorOptions(): Promise<ActionEditorOptions> {
  const [objectTypes, roles] = await Promise.all([
    fetchObjectTypes(),
    fetchRoles(),
  ])
  const permissions = [
    ...new Set(roles.flatMap((role) => role.permissions)),
  ].sort()
  return { objectTypes, permissions }
}

export async function createActionDefinition(input: ActionDefinitionInput) {
  const response = await apiPost<ActionDefinition>('/actions', {
    ...input,
    actionMode: 'EXECUTE',
  })
  if (!response?.data)
    throw new ApiError(
      '등록 응답을 확인할 수 없습니다. 목록에서 등록 여부를 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  return response.data
}

export async function updateActionDefinition(
  code: string,
  changes: ActionDefinitionChanges,
) {
  const response = await apiPatch<ActionDefinition>(
    `/actions/${encodeURIComponent(code)}`,
    changes,
  )
  if (!response?.data)
    throw new ApiError(
      '수정 응답을 확인할 수 없습니다. 최신 정의를 다시 확인해 주세요.',
      { kind: 'invalid-response' },
    )
  return response.data
}
