import { expect, test } from 'vitest'
import { matchesProposal } from './catalog'
import type { Proposal } from './types'

const proposal = {
  id: 12,
  itemKind: 'LINK_RULE',
  payload: { name: '작업지시 → 품목' },
  evidence: {
    candidateId: 'LR-work_order_item_cd_to_item',
    fingerprint: 'a1b2c3d4e5f6a7b8',
    items: [{ values: [4021, 'P-101'] }],
  },
} as unknown as Proposal

test('화면에 보이는 숫자와 후보 번호로 찾고, 내부 대조용 값으로는 찾지 않는다', () => {
  expect(matchesProposal(proposal, '4021')).toBe(true)
  expect(matchesProposal(proposal, '#12')).toBe(true)
  expect(matchesProposal(proposal, 'P-101')).toBe(true)
  expect(matchesProposal(proposal, 'a1b2c3')).toBe(false)
  expect(matchesProposal(proposal, 'LR-work_order')).toBe(false)
})
