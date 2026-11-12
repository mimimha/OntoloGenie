type ObjectCardProps = {
  code: string
  name: string
  typeLabel: string
  selected: boolean
  onSelect: () => void
}

function ObjectCard({
  code,
  name,
  typeLabel,
  selected,
  onSelect,
}: ObjectCardProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className="explorer-object-row"
    >
      <span className="explorer-object-meta">
        <span>{code}</span>
        <span>{typeLabel}</span>
      </span>
      <span className="explorer-object-name">{name}</span>
    </button>
  )
}

export default ObjectCard
