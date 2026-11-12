export function createRipplePath(phase: number, echo = false): string {
  const points = Array.from({ length: 128 }, (_, index) => {
    const angle = (index / 128) * Math.PI * 2
    const radius =
      (echo ? 102 : 100) +
      1.7 * Math.sin(angle * 3 + phase) +
      1.0 * Math.sin(angle * 7 - phase * 0.7) +
      0.6 * Math.cos(angle * 11 + phase * 0.4)
    return `${(Math.cos(angle) * radius).toFixed(3)} ${(Math.sin(angle) * radius).toFixed(3)}`
  })
  return `M ${points.join(' L ')} Z`
}

export function createRippleShapeFrames(echo = false): Keyframe[] {
  return [0, 0.8, 1.7, 2.6].map((phase, index) => ({
    d: `path("${createRipplePath(phase + (echo ? 1.9 : 0), echo)}")`,
    offset: index / 3,
  }))
}
