import { apiGet, apiPost } from '../../api/client'
import { ApiError, isRecord } from '../../api/errors'
import { transformLabels } from '../review/ruleDraft'
import type { SampleCell } from '../sources/samplePreview'
import {
  PreviewDraftError,
  type MappingPreviewAccess,
  type MappingPreviewResult,
} from './previewModel'

const positiveId = (value: unknown): value is number =>
  typeof value === 'number' && Number.isSafeInteger(value) && value > 0
const timestamp = (value: unknown): value is string =>
  typeof value === 'string' && Number.isFinite(Date.parse(value))

function invalidResponse(): never {
  throw new ApiError('변환 미리보기 응답을 확인할 수 없습니다.', {
    kind: 'invalid-response',
  })
}

function cell(value: unknown): SampleCell {
  if (
    value === null ||
    typeof value === 'string' ||
    typeof value === 'boolean' ||
    (typeof value === 'number' && Number.isFinite(value))
  )
    return { kind: 'visible', value }
  if (Array.isArray(value) || isRecord(value))
    return { kind: 'visible', value: JSON.stringify(value) }
  return invalidResponse()
}

function objectRow(value: unknown): MappingPreviewResult['rows'][number] {
  if (
    !isRecord(value) ||
    !isRecord(value.sourceKey) ||
    !isRecord(value.properties)
  )
    return invalidResponse()
  return {
    // objects에는 원천 행 번호가 없다. 키 오류로 빠진 행을 배열 순서로 복원하지 않는다.
    rowNumber: null,
    objectKey: cell(value.sourceKey),
    values: Object.entries(value.properties).map(([property, after]) => ({
      source: '미제공',
      property,
      before: { kind: 'unavailable' },
      after: cell(after),
    })),
  }
}

function rowError(value: unknown): MappingPreviewResult['rowErrors'][number] {
  if (
    !isRecord(value) ||
    !Number.isSafeInteger(value.rowIndex) ||
    (value.rowIndex as number) < 0 ||
    (value.rowIndex as number) >= 20 ||
    typeof value.column !== 'string' ||
    !(value.transform === null || typeof value.transform === 'string') ||
    typeof value.message !== 'string'
  )
    return invalidResponse()
  const transform =
    typeof value.transform === 'string' &&
    Object.hasOwn(transformLabels, value.transform)
      ? value.transform
      : null
  // 원천 값이 섞일 수 있는 message는 화면 모델에도 전달하지 않는다.
  let reason = '키 값·대상 속성의 자료형·변환 인자를 확인해 주세요.'
  if (value.message === '키 값이 비어 있어 객체를 만들 수 없습니다.')
    reason = value.message
  else if (transform)
    reason = `${transformLabels[transform]} 처리에 실패했습니다. 원천 값과 변환 인자를 확인해 주세요.`
  else if (
    /^값을 속성 자료형 (STRING|INTEGER|DECIMAL|DATE|TIME|DATETIME|BOOLEAN|JSON)\(으\)로 바꿀 수 없습니다\.$/.test(
      value.message,
    )
  )
    reason = value.message
  return {
    rowNumber: (value.rowIndex as number) + 1,
    source: value.column,
    property: null,
    step: null,
    transform,
    code: null,
    reason,
    before: { kind: 'unavailable' },
  }
}

export const mappingPreviewApi: MappingPreviewAccess = {
  policyDescription:
    '관리자에게 허용된 원천 값과 변환 결과를 표시합니다. 변환 전 값과 객체의 원천 행 번호는 제공되지 않으며, 원천 값이 포함될 수 있는 오류 원문은 표시하지 않습니다.',
  async request(draft, rowLimit) {
    if (
      !positiveId(draft.sourceResourceId) ||
      !Number.isInteger(rowLimit) ||
      rowLimit < 1 ||
      rowLimit > 20
    )
      throw new PreviewDraftError(
        '원천 자원과 미리보기 행 수(1~20)를 확인해 주세요.',
      )
    const response = await apiPost<unknown>(
      `/ontology/source-resources/${draft.sourceResourceId}/mapping-previews`,
      {
        mapping: {
          objectTypeId: draft.objectTypeId,
          keyColumns: draft.keyColumns,
          rowFilter: draft.rowFilter,
          propertyMappings: draft.properties,
        },
        rowLimit,
      },
    )
    const data = response?.data
    if (
      !isRecord(data) ||
      !positiveId(data.previewId) ||
      !['QUEUED', 'RUNNING'].includes(String(data.status)) ||
      typeof data.joined !== 'boolean' ||
      !timestamp(data.requestedAt) ||
      !timestamp(data.expiresAt) ||
      Date.parse(data.expiresAt) <= Date.parse(data.requestedAt)
    )
      return invalidResponse()
    return {
      previewId: data.previewId,
      joined: data.joined,
      expiresAt: data.expiresAt,
    }
  },
  async read(previewId) {
    const { data } = await apiGet<unknown>(
      `/ontology/source-previews/${previewId}`,
    )
    if (
      !isRecord(data) ||
      data.id !== previewId ||
      data.previewKind !== 'MAPPING_PREVIEW' ||
      !positiveId(data.sourceResourceId) ||
      !['QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'].includes(
        String(data.status),
      ) ||
      !timestamp(data.expiresAt)
    )
      return invalidResponse()
    if (Date.parse(data.expiresAt) <= Date.now())
      throw new ApiError('미리보기가 만료되었습니다.', {
        kind: 'not-found',
        status: 404,
      })
    const status = data.status as MappingPreviewResult['status']
    if (status !== 'SUCCEEDED') return { status, rows: [], rowErrors: [] }
    if (
      !isRecord(data.result) ||
      !Array.isArray(data.result.objects) ||
      data.result.objects.length > 20 ||
      !Array.isArray(data.result.rowErrors)
    )
      return invalidResponse()
    return {
      status,
      rows: data.result.objects.map(objectRow),
      rowErrors: data.result.rowErrors.map(rowError),
    }
  },
}
