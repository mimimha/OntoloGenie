import { apiGet } from '../../api/client'
import type { ModelGraph } from './types'

// 042 객체 유형 관계도
export async function fetchModelGraph() {
  const body = await apiGet<ModelGraph>('/ontology/model-graph')
  return body.data
}
