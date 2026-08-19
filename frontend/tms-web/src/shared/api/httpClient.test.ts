import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError, apiRequest, onApiResponseError, setAuthTokenProvider } from './httpClient'

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  })
}

function problemResponse(overrides: Record<string, unknown> = {}, status = 403): Response {
  return jsonResponse(
    {
      type: 'urn:tms:problem:company-scope-forbidden',
      title: 'Company scope is not allowed',
      status,
      detail: 'The selected company is not available to this account.',
      code: 'company-scope-forbidden',
      timestamp: '2026-08-19T08:41:07.123Z',
      correlationId: 'server-generated-id',
      ...overrides,
    },
    status,
  )
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

  it('sends a unique X-Correlation-Id on every request', async () => {
    // A Response body can only be read once, so build a fresh one per call.
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse({}))

    await apiRequest('/orders')
    await apiRequest('/orders')

    const firstHeaders = fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>
    const secondHeaders = fetchMock.mock.calls[1]?.[1]?.headers as Record<string, string>
    expect(firstHeaders['X-Correlation-Id']).toBeTruthy()
    expect(firstHeaders['X-Correlation-Id']).not.toBe(secondHeaders['X-Correlation-Id'])
  })

  it('sends X-Company-Id only when a companyId is given', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(jsonResponse({}))

    await apiRequest('/orders', { companyId: 'company-123' })

    const headers = fetchMock.mock.calls[0]?.[1]?.headers as Record<string, string>
    expect(headers['X-Company-Id']).toBe('company-123')
  })

  it('parses Problem Details into a stable ApiError.code, not detail', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(problemResponse())

    const error = (await apiRequest('/companies/current').catch((caught) => caught)) as ApiError

    expect(error).toBeInstanceOf(ApiError)
    expect(error.status).toBe(403)
    expect(error.code).toBe('company-scope-forbidden')
    expect(error.correlationId).toBe('server-generated-id')
  })

  it('notifies registered response-error handlers exactly once per failure, without retrying', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(problemResponse({ code: 'unauthenticated' }, 401))
    const handler = vi.fn()
    const unsubscribe = onApiResponseError(handler)

    await expect(apiRequest('/me')).rejects.toBeInstanceOf(ApiError)

    expect(handler).toHaveBeenCalledTimes(1)
    expect(handler.mock.calls[0]?.[0]).toMatchObject({ code: 'unauthenticated' })
    unsubscribe()
  })
})
