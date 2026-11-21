import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'
import type { CourtPrice } from './court-prices.ts'
import { TIME_SLOTS, getCourtPriceValue, getDayType } from './booking-pricing.ts'

// The DB bills every booking from court_prices (trigger fn_calculate_court_rate, V9),
// so the price quoted to the customer must match the seeded court_prices rows.
const seed = readFileSync(
  new URL('../backend/src/main/resources/db/migration/V5__init_court_prices.sql', import.meta.url),
  'utf8'
)
const courtPrices = [...seed.matchAll(/\('(\d\d:\d\d)', '(\d\d:\d\d)', '(\w+)', (\d+)\)/g)].map(
  ([, startTime, endTime, dayType, priceVnd], id) => ({
    id,
    startTime,
    endTime,
    dayType: dayType as CourtPrice['dayType'],
    priceVnd: Number(priceVnd),
  })
)

const WEDNESDAY = new Date(2026, 10, 25)
const SATURDAY = new Date(2026, 10, 28)

test('getDayType matches the billing trigger (ISO Saturday/Sunday are weekend)', () => {
  assert.equal(getDayType(WEDNESDAY), 'weekday')
  assert.equal(getDayType(SATURDAY), 'weekend')
  assert.equal(getDayType(new Date(2026, 10, 29)), 'weekend')
})

for (const date of [WEDNESDAY, SATURDAY]) {
  for (const slot of TIME_SLOTS) {
    const dayType = getDayType(date)
    test(`quotes the billed price for ${dayType} ${slot.label}`, () => {
      const billed = courtPrices.find(
        (price) =>
          price.startTime === `${String(slot.start).padStart(2, '0')}:00` &&
          price.dayType === dayType.toUpperCase()
      )
      assert.ok(billed, 'court_prices has a row for this slot')
      assert.equal(getCourtPriceValue(courtPrices, slot, dayType), billed.priceVnd)
    })
  }
}
