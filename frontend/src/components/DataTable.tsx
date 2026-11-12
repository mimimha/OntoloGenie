import type { ComponentProps } from 'react'

type DataTableProps = ComponentProps<'table'> & { label: string }

function DataTable({
  label,
  className = '',
  children,
  ...props
}: DataTableProps) {
  return (
    <div
      className="ui-table-container"
      tabIndex={0}
      role="region"
      aria-label={`${label} 스크롤 영역`}
    >
      <table {...props} aria-label={label} className={`ui-table ${className}`}>
        {children}
      </table>
    </div>
  )
}

export default DataTable
