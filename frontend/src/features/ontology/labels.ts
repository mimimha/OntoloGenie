import type { Cardinality } from './types'

export const cardinalityLabels: Record<Cardinality, string> = {
  ONE_TO_ONE: '1:1',
  ONE_TO_MANY: '1:N',
  MANY_TO_ONE: 'N:1',
  MANY_TO_MANY: 'N:M',
}
