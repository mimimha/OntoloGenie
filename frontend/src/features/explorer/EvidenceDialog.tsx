import { formatSourceKey } from './format'
import ObjectLinks from './ObjectLinks'
import type { ObjectInstanceDetail } from './types'

type EvidenceDialogProps = {
  object: ObjectInstanceDetail
  onClose: () => void
}

function EvidenceDialog({ object, onClose }: EvidenceDialogProps) {
  return (
    <div className="fixed inset-0 z-10 flex items-center justify-center bg-slate-900/40 p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-labelledby="evidence-dialog-title"
        className="max-h-[85vh] w-full max-w-2xl overflow-y-auto rounded-xl bg-white p-6"
      >
        <div className="flex items-start justify-between gap-4">
          <h2
            id="evidence-dialog-title"
            className="text-lg font-semibold text-slate-900"
          >
            원본·연결 근거 · {object.displayName}
          </h2>
          <button
            type="button"
            onClick={onClose}
            className="rounded-lg border border-slate-300 px-3 py-1 text-sm text-slate-700"
          >
            닫기
          </button>
        </div>

        <section className="mt-4 text-sm">
          <h3 className="font-semibold text-slate-900">원천 위치</h3>
          {object.sourceSystemName ? (
            <dl className="mt-2 grid grid-cols-[7rem_1fr] gap-y-1">
              <dt className="text-slate-500">원천 시스템</dt>
              <dd className="text-slate-900">{object.sourceSystemName}</dd>
              <dt className="text-slate-500">원천 테이블</dt>
              <dd className="text-slate-900">{object.sourceResourceKey}</dd>
              <dt className="text-slate-500">원천 행</dt>
              <dd className="text-slate-900">
                {formatSourceKey(object.sourceKey)}
              </dd>
            </dl>
          ) : (
            <p className="mt-2 text-slate-500">
              플랫폼에서 만든 객체라 원천 위치가 없습니다.
            </p>
          )}
        </section>

        <section className="mt-6 text-sm">
          <h3 className="font-semibold text-slate-900">연결 근거</h3>
          <div className="mt-2">
            <ObjectLinks objectId={object.id} />
          </div>
        </section>
      </div>
    </div>
  )
}

export default EvidenceDialog
