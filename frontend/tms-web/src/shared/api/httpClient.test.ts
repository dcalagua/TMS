import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiRequest, setAuthTokenProvider } from './httpClient'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

afterEach(() => {
  vi.restoreAllMocks()
  setAuthTokenProvider(() => null)
})

describe('apiRequest', () => {
  it('calls the configured backend base path and returns parsed JSON', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({ status: 'UP' }))

    const result = await apiRequest<{ status: string }>('/system/info')

    expect(result).toEqual({ status: 'UP' })
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(String(fetchMock.mock.calls[0]?.[0])).toBe('http://localhost:8080/api/v1/system/info')
  })

  it('omits empty query parameters and attaches the bearer token when available', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse([]))
    setAuthTokenProvider(() => 'test-token')

    await apiRequest('/orders', { query: { page: 0, status: undefined } })

    const [url, init] = fetchMock.mock.calls[0] ?? []
    const headers = (init?.headers ?? {}) as Record<string, string>
    expect(String(url)).toBe('http://localhost:8080/api/v1/orders?page=0')
    expect(headers.Authorization).toBe('Bearer test-token')
  })

  it('raises ApiError carrying the HTTP status so 401 is distinguishable from a failure', async () => {
    // A Response body can only be read once, so build a fresh one per call.
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse({ message: 'nope' }, 401))

    await expect(apiRequest('/orders')).rejects.toBeInstanceOf(ApiError)
    await expect(apiRequest('/orders')).rejects.toMatchObject({ status: 401 })
  })
})
