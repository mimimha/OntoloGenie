// 목업의 분석 실행 번호 표기 (예: ANALYSIS-002)
export function analysisCode(id: number) {
  return `ANALYSIS-${String(id).padStart(3, '0')}`
}
