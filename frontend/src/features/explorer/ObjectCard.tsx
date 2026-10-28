type ObjectCardProps = {
  name: string
  typeLabel: string
  selected: boolean
  onSelect: () => void
}

function ObjectCard({ name, typeLabel, selected, onSelect }: ObjectCardProps) {
  return (
    <button
      type="button"
      onClick={onSelect}
      aria-pressed={selected}
      className={`flex w-full items-center gap-3 rounded-lg border px-4 py-3 text-left ${
        selected
          ? 'border-slate-900 bg-slate-100'
          : 'border-slate-200 bg-white hover:bg-slate-50'
      }`}
    >
      <span className="flex-1 font-medium text-slate-900">{name}</span>
      <span className="rounded-full bg-slate-100 px-2 py-0.5 text-xs text-slate-600">
        {typeLabel}
      </span>
    </button>
  )
}

export default ObjectCard
