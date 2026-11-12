export type ActionTargetKind = 'OBJECT' | 'SYSTEM'
export type ActionMode = 'EXECUTE' | 'RECORD'
export type ActionKind = 'READ' | 'WRITE'

// API 087~090의 ActionDefinition. handlerReady는 서버에 Handler가 배포됐는지 계산한 값이다.
export type ActionDefinition = {
  id: number
  code: string
  name: string
  description: string
  targetKind: ActionTargetKind
  targetObjectTypeId: number | null
  actionMode: ActionMode
  handlerKey: string | null
  inputSchema: Record<string, unknown>
  actionKind: ActionKind
  requiresConfirmation: boolean
  requiredPermission: string
  isActive: boolean
  handlerReady: boolean
  rowVersion: number
}

export type ActionDefinitionInput = {
  code: string
  name: string
  description: string
  targetKind: ActionTargetKind
  targetObjectTypeId: number | null
  handlerKey: string
  inputSchema: Record<string, unknown>
  actionKind: ActionKind
  requiresConfirmation: boolean
  requiredPermission: string
  isActive: boolean
}

export type ActionDefinitionChanges = Partial<
  Omit<ActionDefinitionInput, 'code'>
> & { rowVersion: number }
