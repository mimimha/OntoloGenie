export function formatPropertyValue(value: unknown) {
  if (value === null || value === undefined) {
    return '-'
  }
  return String(value)
}

export function formatSourceKey(
  sourceKey: Record<string, unknown> | null | undefined,
) {
  if (!sourceKey) {
    return '-'
  }
  return Object.entries(sourceKey)
    .map(([column, value]) => `${column}: ${String(value)}`)
    .join(', ')
}
