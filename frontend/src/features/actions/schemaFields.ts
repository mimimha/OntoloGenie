// 입력 형식(JSON Schema)을 관리자가 표로 편집할 수 있게 한 단계짜리 항목 목록으로 바꾼다.
// 표로 나타낼 수 없는 형식(객체 안의 객체, 객체 목록, 여러 자료형 등)은 JSON으로만 편집한다.

export type FieldType =
  'string' | 'integer' | 'number' | 'boolean' | 'date-time' | 'array'
export type ItemType = 'string' | 'integer' | 'number' | 'boolean'

export type FieldRow = {
  key: number
  name: string
  type: FieldType
  itemType: ItemType
  required: boolean
  description: string
  min: string
  max: string
}

export const fieldTypeLabels: Record<FieldType, string> = {
  string: '문자',
  integer: '정수',
  number: '숫자',
  boolean: '예/아니요',
  'date-time': '날짜·시각',
  array: '목록',
}

export const itemTypeLabels: Record<ItemType, string> = {
  string: '문자',
  integer: '정수',
  number: '숫자',
  boolean: '예/아니요',
}

const ITEM_TYPES = Object.keys(itemTypeLabels)

// 자료형마다 최소·최대에 쓰는 키워드. 예/아니요·날짜는 범위를 두지 않는다.
const RANGE_KEYWORDS: Partial<Record<FieldType, [string, string, string]>> = {
  string: ['minLength', 'maxLength', '길이'],
  integer: ['minimum', 'maximum', '값'],
  number: ['minimum', 'maximum', '값'],
  array: ['minItems', 'maxItems', '개수'],
}

export function rangeLabel(type: FieldType): string | null {
  return RANGE_KEYWORDS[type]?.[2] ?? null
}

let nextKey = 1
export function emptyRow(): FieldRow {
  return {
    key: nextKey++,
    name: '',
    type: 'string',
    itemType: 'string',
    required: false,
    description: '',
    min: '',
    max: '',
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function readRow(
  name: string,
  value: unknown,
  required: boolean,
): FieldRow | null {
  if (!isRecord(value) || typeof value.type !== 'string') return null
  const row = { ...emptyRow(), name, required }
  const allowed = new Set(['type', 'description'])
  if (value.type === 'string' && value.format === 'date-time') {
    row.type = 'date-time'
    allowed.add('format')
  } else if (
    ['string', 'integer', 'number', 'boolean', 'array'].includes(value.type)
  ) {
    row.type = value.type as FieldType
  } else return null
  if (row.type === 'array') {
    const items = value.items
    if (
      !isRecord(items) ||
      Object.keys(items).some((key) => key !== 'type') ||
      typeof items.type !== 'string' ||
      !ITEM_TYPES.includes(items.type)
    )
      return null
    row.itemType = items.type as ItemType
    allowed.add('items')
  }
  if (value.description !== undefined) {
    if (typeof value.description !== 'string') return null
    row.description = value.description
  }
  const range = RANGE_KEYWORDS[row.type]
  if (range) {
    const [minKey, maxKey] = range
    for (const [key, target] of [
      [minKey, 'min'],
      [maxKey, 'max'],
    ] as const) {
      if (value[key] === undefined) continue
      if (typeof value[key] !== 'number') return null
      row[target] = String(value[key])
      allowed.add(key)
    }
  }
  return Object.keys(value).every((key) => allowed.has(key)) ? row : null
}

function readRows(schema: unknown): FieldRow[] | null {
  if (!isRecord(schema) || schema.type !== 'object') return null
  if (
    Object.keys(schema).some(
      (key) => !['type', 'properties', 'required'].includes(key),
    )
  )
    return null
  const properties = schema.properties ?? {}
  if (!isRecord(properties)) return null
  const required = schema.required ?? []
  if (
    !Array.isArray(required) ||
    required.some(
      (name) => typeof name !== 'string' || !Object.hasOwn(properties, name),
    )
  )
    return null
  const rows: FieldRow[] = []
  for (const [name, value] of Object.entries(properties)) {
    const row = readRow(name, value, required.includes(name))
    if (!row) return null
    rows.push(row)
  }
  return rows
}

export type TableRowsResult =
  | { rows: FieldRow[]; reason?: undefined }
  | { reason: string; rows?: undefined }

/**
 * 표로 읽어 다시 만든 형식이 원본과 같을 때만 표로 연다. 서버는 받지만 표 검사에 걸리는 값(최소 > 최대, 소수 길이 등)이나
 * 다시 만들면 달라지는 형식을 표로 열면 저장이 막히거나 바꾸지 않은 형식이 바뀌어 전송된다.
 */
export function tableRows(schema: unknown): TableRowsResult {
  const rows = readRows(schema)
  if (!rows)
    return {
      reason:
        '객체 안의 객체나 객체 목록처럼 표로 나타낼 수 없는 형식입니다. JSON으로 편집해 주세요.',
    }
  const rebuilt = rowsToSchema(rows)
  if (rebuilt.error !== undefined)
    return { reason: `표로 바꿀 수 없습니다. ${rebuilt.error}` }
  if (!sameJson(rebuilt.schema, schema))
    return {
      reason:
        '표로 바꾸면 형식이 달라집니다(빈 설명, 이름 앞뒤 공백 등). JSON으로 편집해 주세요.',
    }
  return { rows }
}

/** 표로 나타낼 수 없으면 null이다. 그때는 JSON으로 편집한다. */
export function schemaToRows(schema: unknown): FieldRow[] | null {
  return tableRows(schema).rows ?? null
}

type BoundKind = 'count' | 'integer' | 'number'

function readBound(text: string, kind: BoundKind): number | null | string {
  const trimmed = text.trim()
  if (!trimmed) return null
  const value = Number(trimmed)
  if (!Number.isFinite(value)) return '숫자로 입력해 주세요.'
  if (kind === 'count' && (!Number.isInteger(value) || value < 0))
    return '0 이상의 정수로 입력해 주세요.'
  if (kind === 'integer' && !Number.isInteger(value))
    return '정수로 입력해 주세요.'
  return value
}

export type SchemaResult =
  | { schema: Record<string, unknown>; error?: undefined }
  | { error: string; schema?: undefined }

export function rowsToSchema(rows: FieldRow[]): SchemaResult {
  // 일반 객체에 '__proto__'를 넣으면 속성이 아니라 프로토타입이 바뀌어 항목이 사라진다.
  const properties = new Map<string, unknown>()
  const required: string[] = []
  for (const [index, row] of rows.entries()) {
    const label = `${index + 1}번째 입력 항목`
    const name = row.name.trim()
    if (!name) return { error: `${label}의 이름을 입력해 주세요.` }
    if (properties.has(name))
      return { error: `입력 항목 이름 '${name}'이 겹칩니다.` }
    const field: Record<string, unknown> =
      row.type === 'date-time'
        ? { type: 'string', format: 'date-time' }
        : { type: row.type }
    if (row.type === 'array') field.items = { type: row.itemType }
    const range = RANGE_KEYWORDS[row.type]
    if (range) {
      const kind: BoundKind =
        row.type === 'integer' || row.type === 'number' ? row.type : 'count'
      const min = readBound(row.min, kind)
      const max = readBound(row.max, kind)
      for (const bound of [min, max])
        if (typeof bound === 'string') return { error: `${label}: ${bound}` }
      if (typeof min === 'number' && typeof max === 'number' && min > max)
        return { error: `${label}: 최소가 최대보다 클 수 없습니다.` }
      if (typeof min === 'number') field[range[0]] = min
      if (typeof max === 'number') field[range[1]] = max
    }
    const description = row.description.trim()
    if (description) field.description = description
    properties.set(name, field)
    if (row.required) required.push(name)
  }
  const fields = Object.fromEntries(properties)
  return {
    schema: required.length
      ? { type: 'object', required, properties: fields }
      : { type: 'object', properties: fields },
  }
}

/**
 * 키 순서와 상관없이 같은 JSON인지 본다. 표로 다시 만든 형식이 원래와 같으면 변경으로 보내지 않는다.
 * required는 순서 없는 이름 모음이라 정렬해서 비교한다. 저장소(jsonb)가 properties 키 순서를 바꿔 돌려주므로
 * 항목 순서로 만든 required와 순서가 어긋날 수 있다.
 */
export function sameJson(left: unknown, right: unknown): boolean {
  const canonical = (value: unknown, key?: string): unknown => {
    if (Array.isArray(value)) {
      const items = value.map((item) => canonical(item))
      return key === 'required' &&
        items.every((item) => typeof item === 'string')
        ? [...items].sort()
        : items
    }
    return isRecord(value)
      ? Object.fromEntries(
          Object.keys(value)
            .sort()
            .map((name) => [name, canonical(value[name], name)]),
        )
      : value
  }
  return JSON.stringify(canonical(left)) === JSON.stringify(canonical(right))
}

export type SchemaDraft =
  { mode: 'table'; rows: FieldRow[] } | { mode: 'json'; text: string }

const EMPTY_SCHEMA = { type: 'object', properties: {} }

export function parseSchema(text: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(text)
    return typeof value === 'object' && value !== null && !Array.isArray(value)
      ? (value as Record<string, unknown>)
      : null
  } catch {
    return null
  }
}

export function initialSchemaDraft(
  schema: Record<string, unknown> = EMPTY_SCHEMA,
): SchemaDraft {
  const rows = schemaToRows(schema)
  return rows
    ? { mode: 'table', rows }
    : { mode: 'json', text: JSON.stringify(schema, null, 2) }
}

export function readSchemaDraft(draft: SchemaDraft): SchemaResult {
  if (draft.mode === 'table') return rowsToSchema(draft.rows)
  const schema = parseSchema(draft.text)
  return schema
    ? { schema }
    : { error: '입력 형식은 JSON 객체로 입력해 주세요.' }
}
