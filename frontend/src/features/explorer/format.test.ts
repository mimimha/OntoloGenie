import { expect, test } from 'vitest'
import { formatCount, formatPropertyValue, withAndParticle } from './format'

test('건수는 천 단위 쉼표로 표시한다', () => {
  expect(formatCount(0)).toBe('0')
  expect(formatCount(1284)).toBe('1,284')
  expect(formatCount(5064)).toBe('5,064')
})

test('받침이 있으면 과를 붙인다', () => {
  expect(withAndParticle('프레스 작업')).toBe('프레스 작업과')
})

test('받침이 없으면 와를 붙인다', () => {
  expect(withAndParticle('프레스 3호기')).toBe('프레스 3호기와')
})

test('한글로 끝나지 않으면 와(과)를 붙인다', () => {
  expect(withAndParticle('PR-03')).toBe('PR-03와(과)')
})

test('객체와 배열 값은 JSON 문자열로 보여 준다', () => {
  expect(formatPropertyValue({ basis: 'skill', score: 3 })).toBe(
    '{"basis":"skill","score":3}',
  )
  expect(formatPropertyValue([1, 'a'])).toBe('[1,"a"]')
})

test('값이 없으면 -, 단순 값은 그대로 보여 준다', () => {
  expect(formatPropertyValue(null)).toBe('-')
  expect(formatPropertyValue(undefined)).toBe('-')
  expect(formatPropertyValue(12.5)).toBe('12.5')
  expect(formatPropertyValue(false)).toBe('false')
})
