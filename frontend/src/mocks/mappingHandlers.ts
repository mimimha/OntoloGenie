import { http, HttpResponse } from 'msw'
import type {
  Definition,
  ObjectMapping,
  PropertyMapping,
  TransformFunction,
} from '../features/mapping/api'

const types: Definition[] = [
  { id: 501, code: 'ITEM', name: '품목' },
  { id: 502, code: 'VENDOR', name: '거래처' },
]
const properties: Record<number, Definition[]> = {
  501: [
    { id: 601, code: 'ITEM_NAME', name: '품목명' },
    { id: 602, code: 'SPEC', name: '규격' },
  ],
  502: [{ id: 603, code: 'VENDOR_NAME', name: '거래처명' }],
}
const transforms: TransformFunction[] = [
  {
    op: 'TRIM',
    label: '앞뒤 공백 제거',
    description: '앞뒤 공백을 뺍니다.',
    argument: null,
  },
  {
    op: 'ADD_PREFIX',
    label: '접두어 붙이기',
    description: '값 앞에 접두어를 붙입니다.',
    argument: { type: 'string', label: '붙일 접두어' },
  },
  {
    op: 'PAD_ZEROS',
    label: '앞에 0 채우기',
    description: '지정한 자릿수까지 0을 채웁니다.',
    argument: { type: 'integer', label: '자릿수' },
  },
  {
    op: 'MAP_VALUE',
    label: '대응표로 바꾸기',
    description: '대응하는 값으로 바꿉니다.',
    argument: { type: 'object', label: '대응표(원래 값 → 바꿀 값)' },
  },
]
const initialObject: ObjectMapping = {
  id: 701,
  sourceResourceId: 11,
  objectTypeId: 501,
  keyColumns: ['item_code'],
  rowFilter: {},
  rowVersion: 2,
  origin: 'SEED',
  proposalItemId: null,
  isActive: true,
}
const initialProperty: PropertyMapping = {
  id: 801,
  sourceMappingId: 701,
  sourceResourceId: 11,
  propertyDefinitionId: 601,
  sourceColumnId: 1002,
  sourcePath: null,
  transformSteps: [{ op: 'TRIM', arg: null }],
  rowVersion: 3,
}
const objectPath = '/api/v1/ontology/object-mappings'
const propertyPath = '/api/v1/ontology/property-mappings'
let objectMappings: ObjectMapping[]
let propertyMappings: PropertyMapping[]

export function resetMappingMocks() {
  objectMappings = [initialObject]
  propertyMappings = [initialProperty]
}
resetMappingMocks()

function paged<T>(data: T[], search: URLSearchParams) {
  const page = Number(search.get('page') ?? 0)
  const size = Number(search.get('size') ?? 20)
  return HttpResponse.json({
    data: data.slice(page * size, (page + 1) * size),
    meta: {
      page,
      size,
      totalElements: data.length,
      hasNext: (page + 1) * size < data.length,
    },
  })
}

function failure(status: number, code: string, message: string) {
  return HttpResponse.json(
    { error: { code, message, details: {}, requestId: 'mock-mapping' } },
    { status },
  )
}

type ObjectWrite = Pick<
  ObjectMapping,
  'sourceResourceId' | 'objectTypeId' | 'keyColumns' | 'rowFilter' | 'isActive'
> & { rowVersion?: number }
type PropertyWrite = Pick<
  PropertyMapping,
  | 'sourceMappingId'
  | 'sourceResourceId'
  | 'propertyDefinitionId'
  | 'sourceColumnId'
  | 'transformSteps'
> & { rowVersion?: number }

export const mappingHandlers = [
  http.get('/api/v1/ontology/object-types', ({ request }) =>
    paged(types, new URL(request.url).searchParams),
  ),
  http.get('/api/v1/ontology/object-types/:id/properties', ({ params }) =>
    HttpResponse.json({ data: properties[Number(params.id)] ?? [] }),
  ),
  http.get('/api/v1/ontology/transforms', () =>
    HttpResponse.json({ data: transforms }),
  ),
  http.get(objectPath, ({ request }) => {
    const search = new URL(request.url).searchParams
    const resourceId = search.get('sourceResourceId')
    return paged(
      objectMappings.filter(
        (mapping) =>
          resourceId === null ||
          mapping.sourceResourceId === Number(resourceId),
      ),
      search,
    )
  }),
  http.post(objectPath, async ({ request }) => {
    const input = (await request.json()) as ObjectWrite
    if (
      input.isActive &&
      objectMappings.some(
        (mapping) =>
          mapping.isActive &&
          mapping.sourceResourceId === input.sourceResourceId &&
          mapping.objectTypeId === input.objectTypeId,
      )
    )
      return failure(
        409,
        'CONFLICT',
        '같은 원천 자원과 객체 유형의 활성 매핑이 이미 있습니다.',
      )
    const data: ObjectMapping = {
      ...input,
      id: 701 + objectMappings.length,
      rowVersion: 0,
      origin: 'MANUAL',
      proposalItemId: null,
    }
    objectMappings.push(data)
    return HttpResponse.json({ data }, { status: 201 })
  }),
  http.patch(`${objectPath}/:id`, async ({ params, request }) => {
    const input = (await request.json()) as ObjectWrite
    const existing = objectMappings.find(({ id }) => id === Number(params.id))
    if (!existing)
      return failure(404, 'RESOURCE_NOT_FOUND', '객체 매핑을 찾을 수 없습니다.')
    if (input.rowVersion !== existing.rowVersion)
      return failure(
        409,
        'VERSION_CONFLICT',
        '다른 사용자가 매핑을 변경했습니다.',
      )
    const reactivated = !existing.isActive && input.isActive
    const identityChanged =
      input.sourceResourceId !== existing.sourceResourceId ||
      input.objectTypeId !== existing.objectTypeId ||
      JSON.stringify(input.keyColumns) !== JSON.stringify(existing.keyColumns)
    const reloadReason =
      input.isActive && identityChanged
        ? 'IDENTITY_CHANGED'
        : reactivated
          ? 'REACTIVATED'
          : null
    const data = { ...existing, ...input, rowVersion: existing.rowVersion + 1 }
    objectMappings = objectMappings.map((item) =>
      item.id === data.id ? data : item,
    )
    return HttpResponse.json({
      data: { ...data, reloadRequired: reloadReason !== null, reloadReason },
    })
  }),
  http.get(propertyPath, ({ request }) => {
    const search = new URL(request.url).searchParams
    const mappingId = search.get('sourceMappingId')
    return paged(
      propertyMappings.filter(
        (mapping) =>
          mappingId === null || mapping.sourceMappingId === Number(mappingId),
      ),
      search,
    )
  }),
  http.post(propertyPath, async ({ request }) => {
    const input = (await request.json()) as PropertyWrite
    if (
      propertyMappings.some(
        (mapping) =>
          mapping.sourceMappingId === input.sourceMappingId &&
          mapping.propertyDefinitionId === input.propertyDefinitionId,
      )
    )
      return failure(409, 'CONFLICT', '이 속성은 이미 매핑돼 있습니다.')
    const data: PropertyMapping = {
      ...input,
      id: 801 + propertyMappings.length,
      rowVersion: 0,
      sourcePath: null,
    }
    propertyMappings.push(data)
    const reloadRequired = objectMappings.some(
      ({ id, isActive }) => id === data.sourceMappingId && isActive,
    )
    return HttpResponse.json(
      {
        data: {
          ...data,
          reloadRequired,
          reloadReason: reloadRequired ? 'PROPERTY_MAPPING_ADDED' : null,
        },
      },
      { status: 201 },
    )
  }),
  http.patch(`${propertyPath}/:id`, async ({ params, request }) => {
    const input = (await request.json()) as PropertyWrite
    const existing = propertyMappings.find(({ id }) => id === Number(params.id))
    if (!existing)
      return failure(404, 'RESOURCE_NOT_FOUND', '속성 매핑을 찾을 수 없습니다.')
    if (input.rowVersion !== existing.rowVersion)
      return failure(
        409,
        'VERSION_CONFLICT',
        '다른 사용자가 매핑을 변경했습니다.',
      )
    const data = { ...existing, ...input, rowVersion: existing.rowVersion + 1 }
    propertyMappings = propertyMappings.map((item) =>
      item.id === data.id ? data : item,
    )
    const reloadRequired = objectMappings.some(
      ({ id, isActive }) => id === data.sourceMappingId && isActive,
    )
    return HttpResponse.json({
      data: {
        ...data,
        reloadRequired,
        reloadReason: reloadRequired ? 'PROPERTY_MAPPING_CHANGED' : null,
      },
    })
  }),
]
