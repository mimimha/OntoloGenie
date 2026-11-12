import { expect, test } from 'vitest'
import {
  emptyRow,
  readSchemaDraft,
  rowsToSchema,
  sameJson,
  schemaToRows,
  tableRows,
} from './schemaFields'

test('한 단계짜리 형식은 표로 읽고 같은 형식으로 다시 만든다', () => {
  const schema = {
    type: 'object',
    required: ['dueAt'],
    properties: {
      dueAt: { type: 'string', format: 'date-time', description: '납기' },
      quantity: { type: 'number', minimum: 0.5, maximum: 10 },
      note: { type: 'string', maxLength: 200 },
      urgent: { type: 'boolean' },
      codes: { type: 'array', items: { type: 'integer' }, minItems: 1 },
    },
  }
  const rows = schemaToRows(schema)
  expect(rows?.map((row) => [row.name, row.type, row.required])).toEqual([
    ['dueAt', 'date-time', true],
    ['quantity', 'number', false],
    ['note', 'string', false],
    ['urgent', 'boolean', false],
    ['codes', 'array', false],
  ])
  const rebuilt = rowsToSchema(rows!)
  expect(rebuilt.schema).toEqual(schema)
  expect(sameJson(rebuilt.schema, schema)).toBe(true)
})

test.each([
  ['객체가 아님', []],
  ['최상위 type이 object가 아님', { type: 'array' }],
  ['최상위에 다른 키워드', { type: 'object', title: '제목' }],
  ['객체 안의 객체', { type: 'object', properties: { a: { type: 'object' } } }],
  [
    '객체 목록',
    {
      type: 'object',
      properties: { a: { type: 'array', items: { type: 'object' } } },
    },
  ],
  [
    '여러 자료형',
    { type: 'object', properties: { a: { type: ['string', 'null'] } } },
  ],
  [
    '자료형에 맞지 않는 규칙',
    { type: 'object', properties: { a: { type: 'string', minimum: 1 } } },
  ],
  [
    '없는 항목을 필수로 지정',
    { type: 'object', required: ['b'], properties: { a: { type: 'string' } } },
  ],
])('%s이면 표로 나타내지 않는다', (_case, schema) => {
  expect(schemaToRows(schema)).toBeNull()
})

test('비어 있는 표는 항목 없는 객체 형식이다', () => {
  expect(rowsToSchema([]).schema).toEqual({ type: 'object', properties: {} })
  expect(schemaToRows({ type: 'object', properties: {} })).toEqual([])
})

test.each([
  [{ name: ' ' }, '1번째 입력 항목의 이름을 입력해 주세요.'],
  [{ name: 'a', type: 'string' as const, min: '-1' }, '0 이상의 정수'],
  [{ name: 'a', type: 'string' as const, min: '1.5' }, '0 이상의 정수'],
  [{ name: 'a', type: 'integer' as const, max: '2.5' }, '정수로 입력'],
  [{ name: 'a', type: 'number' as const, min: 'abc' }, '숫자로 입력'],
  [
    { name: 'a', type: 'integer' as const, min: '5', max: '1' },
    '최소가 최대보다 클 수 없습니다.',
  ],
])('잘못된 표 값은 형식으로 바꾸지 않는다: %o', (patch, message) => {
  const result = rowsToSchema([{ ...emptyRow(), ...patch }])
  expect(result.error).toContain(message)
})

test('정수 범위는 음수도 받는다', () => {
  const result = rowsToSchema([
    { ...emptyRow(), name: 'offset', type: 'integer', min: '-3' },
  ])
  expect(result.schema).toEqual({
    type: 'object',
    properties: { offset: { type: 'integer', minimum: -3 } },
  })
})

test('JSON 편집 값은 객체일 때만 형식으로 쓴다', () => {
  expect(readSchemaDraft({ mode: 'json', text: '{"type":"object"}' })).toEqual({
    schema: { type: 'object' },
  })
  expect(readSchemaDraft({ mode: 'json', text: '[1]' }).error).toBe(
    '입력 형식은 JSON 객체로 입력해 주세요.',
  )
})

test.each([
  [
    '최소가 최대보다 큼',
    {
      type: 'object',
      properties: { a: { type: 'integer', minimum: 10, maximum: 1 } },
    },
  ],
  [
    '정수 범위에 소수',
    { type: 'object', properties: { a: { type: 'integer', minimum: 0.5 } } },
  ],
  [
    '음수 길이',
    { type: 'object', properties: { a: { type: 'string', minLength: -1 } } },
  ],
  [
    '소수 개수',
    {
      type: 'object',
      properties: {
        a: { type: 'array', items: { type: 'string' }, minItems: 1.5 },
      },
    },
  ],
  ['항목 목록이 없음', { type: 'object' }],
  [
    '빈 설명',
    { type: 'object', properties: { a: { type: 'string', description: '' } } },
  ],
  [
    '앞뒤 공백이 있는 이름',
    { type: 'object', properties: { ' a': { type: 'string' } } },
  ],
])(
  '서버는 받지만 표로 다시 만들면 달라지는 형식(%s)은 JSON으로 연다',
  (_case, schema) => {
    expect(schemaToRows(schema)).toBeNull()
  },
)

test("항목 이름이 '__proto__'여도 사라지지 않는다", () => {
  const schema = JSON.parse(
    '{"type":"object","properties":{"__proto__":{"type":"string"}}}',
  ) as Record<string, unknown>
  const rows = schemaToRows(schema)
  expect(rows?.map((row) => row.name)).toEqual(['__proto__'])
  const rebuilt = rowsToSchema(rows!).schema!
  expect(Object.keys(rebuilt.properties as object)).toEqual(['__proto__'])
  expect(sameJson(rebuilt, schema)).toBe(true)
})

test('저장소가 항목 순서를 바꿔 돌려줘도 표로 열고 같은 형식으로 본다', () => {
  // 표에서 workOrderId, qty 순서로 넣어 저장하면 jsonb가 짧은 키를 앞에 두어 돌려준다.
  const saved = {
    type: 'object',
    required: ['workOrderId', 'qty'],
    properties: { qty: { type: 'integer' }, workOrderId: { type: 'integer' } },
  }
  const rows = schemaToRows(saved)
  expect(rows?.map((row) => [row.name, row.required])).toEqual([
    ['qty', true],
    ['workOrderId', true],
  ])
  expect(sameJson(rowsToSchema(rows!).schema, saved)).toBe(true)
})

test('필수 이름의 차이는 순서가 아니라 구성으로 판단한다', () => {
  const base = { type: 'object', properties: { a: {}, b: {} } }
  expect(
    sameJson(
      { ...base, required: ['a', 'b'] },
      { ...base, required: ['b', 'a'] },
    ),
  ).toBe(true)
  expect(
    sameJson({ ...base, required: ['a'] }, { ...base, required: ['a', 'b'] }),
  ).toBe(false)
  expect(
    sameJson({ type: ['string', 'null'] }, { type: ['null', 'string'] }),
  ).toBe(false)
})

test('대소문자나 밑줄만 다른 이름도 서로 다른 항목으로 보고 순서와 상관없이 비교한다', () => {
  const names = ['plan_id', 'planId', 'PlanId', 'a']
  const properties = (order: string[]) =>
    Object.fromEntries(order.map((name) => [name, { type: 'string' }]))
  const left = {
    type: 'object',
    required: names,
    properties: properties(names),
  }
  const reversed = [...names].reverse()
  const right = {
    type: 'object',
    required: reversed,
    properties: properties(reversed),
  }
  expect(sameJson(left, right)).toBe(true)
  expect(
    sameJson(left, { ...right, required: ['plan_id', 'planId', 'a'] }),
  ).toBe(false)
})

test.each([
  [
    { type: 'object', properties: { a: { type: 'object' } } },
    '표로 나타낼 수 없는 형식',
  ],
  [
    {
      type: 'object',
      properties: { a: { type: 'integer', minimum: 10, maximum: 1 } },
    },
    '최소가 최대보다 클 수 없습니다.',
  ],
  [
    { type: 'object', properties: { a: { type: 'string', description: '' } } },
    '표로 바꾸면 형식이 달라집니다',
  ],
])('표로 열 수 없으면 이유를 알려 준다: %o', (schema, reason) => {
  expect(tableRows(schema).reason).toContain(reason)
})
