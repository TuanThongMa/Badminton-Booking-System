import { backendJson } from '@/lib/backend-api'

export type CourtPriceDayType = 'WEEKDAY' | 'WEEKEND' | 'SPECIAL_DAY'

export type CourtPrice = {
  id: number
  startTime: string
  endTime: string
  dayType: CourtPriceDayType
  priceVnd: number
}

export async function fetchCourtPrices() {
  return backendJson<CourtPrice[]>('/court-prices')
}
