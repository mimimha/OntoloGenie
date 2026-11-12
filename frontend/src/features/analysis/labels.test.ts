import { expect, test } from 'vitest'
import { analysisStatusLabel } from './labels'

test('알려진 분석 상태는 한국어 라벨로, 모르는 상태는 받은 값 그대로 보여 준다', () => {
  expect(analysisStatusLabel('SUCCEEDED')).toEqual({
    label: '완료',
    tone: 'success',
  })
  expect(analysisStatusLabel('CANCELLED')).toEqual({
    label: 'CANCELLED',
    tone: 'neutral',
  })
})
