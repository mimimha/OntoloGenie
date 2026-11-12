import type { ButtonHTMLAttributes } from 'react'

type ButtonProps = ButtonHTMLAttributes<HTMLButtonElement> & {
  variant?: 'primary' | 'secondary' | 'danger' | 'text'
  pending?: boolean
}

function Button({
  variant = 'primary',
  pending = false,
  disabled,
  children,
  className = '',
  type = 'button',
  ...props
}: ButtonProps) {
  return (
    <button
      {...props}
      type={type}
      disabled={disabled || pending}
      aria-busy={pending || undefined}
      className={`ui-button ui-button--${variant} ${className}`}
    >
      {pending && <span className="ui-spinner" aria-hidden="true" />}
      {children}
    </button>
  )
}

export default Button
