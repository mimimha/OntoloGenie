export type Cardinality =
  'ONE_TO_ONE' | 'ONE_TO_MANY' | 'MANY_TO_ONE' | 'MANY_TO_MANY'

// 042 객체 유형 관계도의 노드 하나 (객체 유형)
export type ModelNode = {
  id: number
  code: string
  name: string
  // 유형별 객체 수. 업무 데이터 화면에서 쓰려고 백엔드에 추가를 요청할 값이라 아직 없을 수 있다.
  objectCount?: number
}

// 042 객체 유형 관계도의 선 하나 (관계 유형)
export type ModelEdge = {
  id: number
  code: string
  name: string
  sourceTypeId: number
  targetTypeId: number
  cardinality: Cardinality
  relationCount: number
  activeLinkCount: number
}

// 042 객체 유형 관계도 응답
export type ModelGraph = {
  nodes: ModelNode[]
  edges: ModelEdge[]
}
