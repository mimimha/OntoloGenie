import type { ActionKind, ActionTargetKind } from './types'

export const changesDataLabels: Record<ActionKind, string> = {
  WRITE: '데이터를 바꿈',
  READ: '데이터를 바꾸지 않음',
}

export const targetKindLabels: Record<ActionTargetKind, string> = {
  OBJECT: '객체 대상',
  SYSTEM: '시스템 전체',
}

export const activeLabels = { true: '사용', false: '사용 안 함' } as const
