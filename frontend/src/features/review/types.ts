export type Proposal = {
  id: number
  analysisRunId: number | null
  origin: string
  itemKind: string
  revision: number
  payload: Record<string, unknown>
  evidence: Record<string, unknown>
  aiConfidence: number | null
  reviewStatus: string
  applyStatus: string
  applyError: string | null
  appliedAt: string | null
  // 후보 검사(CANDIDATE) 대상인가. 연결 규칙만 true이고, false면 승인에 검사 결과가 필요 없고 latestCheck는 null이다.
  checkRequired?: boolean
  latestCheck: {
    checkRunId: number
    status: string
    matchedCount: number | null
    totalCount: number | null
    matchRate: number | null
    unsupported?: boolean
  } | null
}

export type ProposalFilters = {
  origin?: string
  itemKind?: string
  reviewStatus?: string
  applyStatus?: string
}

export type ProposalOption = {
  id: number
  optionNo: number
  revision: number
  payload: Record<string, unknown>
  evidence: Record<string, unknown>
  aiConfidence: number | null
}

export type ProposalWriteResult = Omit<Proposal, 'latestCheck'> & {
  checkRunId: number
}
