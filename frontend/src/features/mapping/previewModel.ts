import type { SampleCell } from '../sources/samplePreview'
import type { CheckStatus } from '../sources/types'
import type { ObjectMapping, PropertyMapping } from './api'

// 040·102 응답에서 제공하지 않는 정보는 null 또는 unavailable로 구분한다.
export type MappingPreviewDraft = Pick<
  ObjectMapping,
  'sourceResourceId' | 'objectTypeId' | 'keyColumns' | 'rowFilter' | 'isActive'
> & {
  sourceMappingId?: number
  rowVersion?: number
  properties: Pick<
    PropertyMapping,
    'propertyDefinitionId' | 'sourceColumnId' | 'sourcePath' | 'transformSteps'
  >[]
}

export type MappingPreviewReceipt = {
  previewId: number
  joined: boolean
  expiresAt: string
}
export type MappingPreviewResult = {
  status: CheckStatus
  rows: {
    rowNumber: number | null
    objectKey: SampleCell
    values: {
      source: string
      property: string
      before: SampleCell
      after: SampleCell
    }[]
  }[]
  rowErrors: {
    rowNumber: number
    source?: string
    transform?: string | null
    property: string | null
    step: number | null
    code: string | null
    // 어댑터에서 정책에 맞춰 정제한 근거만 전달한다. 원천 오류 원문이 아니다.
    reason: string
    before: SampleCell
  }[]
}

export type MappingPreviewAccess = {
  policyDescription: string
  request: (
    draft: MappingPreviewDraft,
    rowLimit: number,
  ) => Promise<MappingPreviewReceipt>
  read: (previewId: number) => Promise<MappingPreviewResult>
}

export class PreviewDraftError extends Error {}
