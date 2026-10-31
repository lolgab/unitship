import { describe, expect, it } from 'vitest'
import { niceBytes, niceMax } from './components/Chart'

describe('the top of a scale', () => {
  it('is 1, 2, 5 or 10 times a power of ten', () => {
    expect(niceMax(0)).toBe(1)
    expect(niceMax(0.3)).toBe(0.5)
    expect(niceMax(1)).toBe(1)
    expect(niceMax(1.1)).toBe(2)
    expect(niceMax(430)).toBe(500)
    expect(niceMax(501)).toBe(1000)
  })
  it('is round in the unit of bytes that is shown', () => {
    expect(niceBytes(0)).toBe(1)
    expect(niceBytes(61 * 1048576)).toBe(100 * 1048576)
    expect(niceBytes(3.4 * 1073741824)).toBe(5 * 1073741824)
    expect(niceBytes(700)).toBe(1000)
    expect(niceBytes(2000)).toBe(2 * 1024)
  })
})
