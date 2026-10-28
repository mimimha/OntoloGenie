export type PageMeta = {
  page: number
  size: number
  totalElements: number
  hasNext: boolean
}

export type ApiResponse<T> = {
  data: T
  meta?: PageMeta
}

export type ApiErrorBody = {
  error: {
    code: string
    message: string
    details: Record<string, unknown>
    requestId: string
  }
}
