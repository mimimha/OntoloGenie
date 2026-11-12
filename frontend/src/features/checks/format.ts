export const runTime = (value: string | null) =>
  value ? new Date(value).toLocaleString('ko-KR') : '—'
