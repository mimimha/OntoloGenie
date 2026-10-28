type PagePlaceholderProps = {
  title: string
}

function PagePlaceholder({ title }: PagePlaceholderProps) {
  return (
    <section>
      <h1 className="text-xl font-semibold text-slate-900">{title}</h1>
      <p className="mt-2 text-slate-600">준비 중인 화면이에요.</p>
    </section>
  )
}

export default PagePlaceholder
