import { expect, test } from 'vitest'
import {
  arrowLine,
  labelStyle,
  placeGroups,
  toLinkGroups,
  type LinkGroup,
} from './graphLayout'
import type { LinkDirection, ObjectLinkGroup } from './types'

function linkGroup(
  linkTypeId: number,
  direction: LinkDirection,
  linkCount: number,
): ObjectLinkGroup {
  return {
    linkTypeId,
    linkTypeName: `관계 ${linkTypeId}`,
    direction,
    relatedObjectTypeId: linkTypeId,
    relatedObjectTypeName: `유형 ${linkTypeId}`,
    linkCount,
  }
}

function group(key: string, outgoing = true) {
  return { key, outgoing } as unknown as LinkGroup
}

test('연결이 없으면 묶음도 카드 자리도 없다', () => {
  expect(toLinkGroups([])).toEqual([])
  expect(placeGroups([])).toEqual([])
})

test('관계 유형·방향별 건수를 카드 하나의 묶음으로 바꾼다', () => {
  expect(toLinkGroups([linkGroup(7, 'IN', 230)])).toEqual([
    {
      key: '7:in',
      linkTypeId: 7,
      linkTypeName: '관계 7',
      relatedTypeName: '유형 7',
      direction: 'IN',
      outgoing: false,
      linkCount: 230,
    },
  ])
})

test('관계 유형이 같아도 방향이 다르면 다른 묶음이다', () => {
  const groups = toLinkGroups([linkGroup(7, 'OUT', 1), linkGroup(7, 'IN', 1)])

  expect(groups.map(({ key }) => key)).toEqual(['7:out', '7:in'])
})

test('연결이 많은 묶음부터 놓고, 건수가 같으면 받은 순서를 지킨다', () => {
  const groups = toLinkGroups([
    linkGroup(5, 'OUT', 1),
    linkGroup(6, 'OUT', 1),
    linkGroup(7, 'OUT', 2),
  ])

  expect(groups.map(({ key }) => key)).toEqual(['7:out', '5:out', '6:out'])
})

test('묶음 카드는 위쪽 가운데부터 시계 방향으로 같은 간격에 놓는다', () => {
  const nodes = placeGroups([group('a'), group('b'), group('c'), group('d')])

  expect(nodes.map(({ x, y }) => [x, y])).toEqual([
    [50, 14],
    [86, 50],
    [50, 86],
    [14, 50],
  ])
})

test('묶음이 많아도 카드 자리가 서로 겹치지 않는다', () => {
  const nodes = placeGroups(
    Array.from({ length: 8 }, (_, index) => group(String(index))),
  )

  for (const [index, node] of nodes.entries()) {
    for (const other of nodes.slice(index + 1)) {
      expect(Math.hypot(node.x - other.x, node.y - other.y)).toBeGreaterThan(20)
    }
  }
})

test('화살촉 선은 출발 쪽에서 선 가운데까지 그린다', () => {
  const outgoing = { group: group('a', true), x: 86, y: 50 }
  const incoming = { group: group('b', false), x: 86, y: 50 }

  expect(arrowLine(outgoing)).toEqual({ x1: 50, y1: 50, x2: 68, y2: 50 })
  expect(arrowLine(incoming)).toEqual({ x1: 86, y1: 50, x2: 68, y2: 50 })
})

test('관계 이름은 선 가운데에서 선과 직각 방향으로 비켜 놓는다', () => {
  const right = { group: group('a'), x: 88, y: 50 }
  const top = { group: group('b'), x: 50, y: 12 }

  expect(labelStyle(right)).toEqual({
    left: '69%',
    top: '50%',
    transform: 'translate(calc(-50% + 0px), calc(-100% + -10px))',
  })
  expect(labelStyle(top)).toEqual({
    left: '50%',
    top: '31%',
    transform: 'translate(calc(0% + 10px), calc(-50% + 0px))',
  })
})
