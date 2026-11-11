import type { LinkDirection, ObjectLinkGroup } from './types'

// 같은 관계 유형·같은 방향의 연결 묶음. 관계도에는 묶음 하나를 카드 하나로 그린다.
export type LinkGroup = {
  key: string
  linkTypeId: number
  linkTypeName: string
  relatedTypeName: string
  direction: LinkDirection
  outgoing: boolean
  linkCount: number
}

export type GroupNode = {
  group: LinkGroup
  x: number
  y: number
}

// 연결이 많은 묶음부터 놓고, 건수가 같으면 받은 순서를 지킨다.
export function toLinkGroups(groups: ObjectLinkGroup[]): LinkGroup[] {
  return groups
    .map((group) => ({
      key: `${group.linkTypeId}:${group.direction.toLowerCase()}`,
      linkTypeId: group.linkTypeId,
      linkTypeName: group.linkTypeName,
      relatedTypeName: group.relatedObjectTypeName,
      direction: group.direction,
      outgoing: group.direction === 'OUT',
      linkCount: group.linkCount,
    }))
    .sort((a, b) => b.linkCount - a.linkCount)
}

// 관계도에서 묶음 카드의 자리(%)를 정한다. 가운데(50, 50)는 선택한 객체 자리다.
// 위쪽 가운데부터 시계 방향으로 타원 위에 같은 간격으로 놓아 카드끼리 겹치지 않게 한다.
export function placeGroups(groups: LinkGroup[]): GroupNode[] {
  return groups.map((group, index) => {
    const angle = -Math.PI / 2 + (2 * Math.PI * index) / groups.length
    return {
      group,
      x: round(50 + 36 * Math.cos(angle)),
      y: round(50 + 36 * Math.sin(angle)),
    }
  })
}

function round(value: number) {
  return Math.round(value * 10) / 10
}

// 화살촉을 붙일 선. 출발 쪽에서 선의 한가운데까지 그려서, 화살촉이 카드에 가려지지 않고
// 선 가운데에서 관계 방향(출발 → 도착)을 가리키게 한다.
export function arrowLine(node: GroupNode) {
  const [fromX, fromY, toX, toY] = node.group.outgoing
    ? [50, 50, node.x, node.y]
    : [node.x, node.y, 50, 50]
  return {
    x1: fromX,
    y1: fromY,
    x2: (fromX + toX) / 2,
    y2: (fromY + toY) / 2,
  }
}

// 관계 이름을 놓을 자리. 선의 가운데에 두되, 글자 상자를 선과 직각 방향으로 밀어내
// 선에 걸치지 않게 한다. 가로 선은 위쪽, 세로 선은 오른쪽으로 비킨다.
export function labelStyle(node: GroupNode) {
  const dx = node.x - 50
  const dy = node.y - 50
  const length = Math.hypot(dx, dy)
  const side = dx > 0 ? -1 : 1
  const normalX = (side * -dy) / length
  const normalY = (side * dx) / length
  return {
    left: `${(50 + node.x) / 2}%`,
    top: `${(50 + node.y) / 2}%`,
    transform: `translate(calc(${(normalX - 1) * 50}% + ${normalX * 10}px), calc(${(normalY - 1) * 50}% + ${normalY * 10}px))`,
  }
}
