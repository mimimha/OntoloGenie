import PageHeader from './PageHeader'

type PagePlaceholderProps = {
  title: string
}

function PagePlaceholder({ title }: PagePlaceholderProps) {
  return (
    <section className="management-page management-panel">
      <PageHeader title={title} description="준비 중인 화면이에요." />
    </section>
  )
}

export default PagePlaceholder
