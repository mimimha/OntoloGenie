import type { ReactNode } from 'react'

function PageHeader({
  title,
  description,
  action,
  variant = 'page',
}: {
  title: string
  description: string
  action?: ReactNode
  variant?: 'page' | 'message'
}) {
  if (variant === 'page') {
    return (
      <>
        <h1 className="ui-page-title">{title}</h1>
        {action && <header className="ui-page-actions">{action}</header>}
      </>
    )
  }
  return (
    <header className="ui-page-header">
      <div>
        <h1>{title}</h1>
        <p>{description}</p>
      </div>
      {action}
    </header>
  )
}

export default PageHeader
