export type ObjectTypeItem = {
  id: number
  code: string
  name: string
  displayPropertyId: number | null
  rowVersion: number
}

export type PropertyItem = {
  id: number
  code: string
  name: string
  sensitivity: 'NORMAL' | 'RESTRICTED'
}
