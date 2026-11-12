import { useId } from 'react'
import { createRipplePath } from './voice-powered-orb-ripple-shape'

export function RippleTrace({ echo = false }: { echo?: boolean }) {
  const gradientId = `orb-ripple-${useId().replace(/:/g, '')}`
  return (
    <svg
      className="voice-powered-orb-ripple-trace"
      viewBox="-104 -104 208 208"
      aria-hidden="true"
      focusable="false"
    >
      <defs>
        <linearGradient id={gradientId} x1="0%" y1="0%" x2="100%" y2="100%">
          <stop offset="0%" stopColor="#9c43fe" stopOpacity="0.95" />
          <stop offset="27%" stopColor="#5c6bff" stopOpacity="0.35" />
          <stop offset="55%" stopColor="#4cc2e9" stopOpacity="0.9" />
          <stop offset="78%" stopColor="#6450e8" stopOpacity="0.2" />
          <stop offset="100%" stopColor="#9c43fe" stopOpacity="0.7" />
        </linearGradient>
      </defs>
      <path
        className="voice-powered-orb-ripple-line"
        d={createRipplePath(echo ? 1.9 : 0, echo)}
        fill="none"
        stroke={`url(#${gradientId})`}
        strokeWidth={echo ? 1.1 : 0.8}
        strokeLinejoin="round"
        strokeLinecap="round"
      />
    </svg>
  )
}
