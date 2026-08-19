import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  ApiError,
  apiRequest,
  onApiResponseError,
  resetAuthRefreshState,
  setAuthRefreshHandler,
  setAuthTokenProvider,
} from './httpClient'

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
  setAuthRefreshHandler(() => Promise.resolve(null))
  resetAuthRefreshState()
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

describe('authentication recovery', () => {
  it('replays a 401 once with the refreshed token and returns the retried result', async () => {
    setAuthTokenProvider(() => 'stale-token')
    setAuthRefreshHandler(() => Promise.resolve('renewed-token'))
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      return headers.Authorization === 'Bearer renewed-token'
        ? jsonResponse({ recovered: true })
        : jsonResponse({ code: 'unauthenticated' }, 401)
    })

    await expect(apiRequest('/orders')).resolves.toEqual({ recovered: true })
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('gives up after one retry so a permanently rejecting backend cannot cause a loop', async () => {
    setAuthTokenProvider(() => 'stale-token')
    setAuthRefreshHandler(() => Promise.resolve('renewed-token'))
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async () => jsonResponse({ code: 'invalid-token' }, 401))
    const handler = vi.fn()
    const unsubscribe = onApiResponseError(handler)

    await expect(apiRequest('/orders')).rejects.toMatchObject({ code: 'invalid-token' })

    expect(fetchMock).toHaveBeenCalledTimes(2)
    expect(handler).toHaveBeenCalledTimes(1)
    unsubscribe()
  })

  it('reports nothing to error handlers when the retry succeeds', async () => {
    setAuthTokenProvider(() => 'stale-token')
    setAuthRefreshHandler(() => Promise.resolve('renewed-token'))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      return headers.Authorization === 'Bearer renewed-token' ? jsonResponse({}) : jsonResponse({ code: 'unauthenticated' }, 401)
    })
    const handler = vi.fn()
    const unsubscribe = onApiResponseError(handler)

    await apiRequest('/orders')

    expect(handler).not.toHaveBeenCalled()
    unsubscribe()
  })

  it('refreshes once for many requests failing together, not once per request', async () => {
    setAuthTokenProvider(() => 'stale-token')
    const refresh = vi.fn(() => Promise.resolve('renewed-token'))
    setAuthRefreshHandler(refresh)
    vi.spyOn(globalThis, 'fetch').mockImplementation(async (_input, init) => {
      const headers = (init?.headers ?? {}) as Record<string, string>
      return headers.Authorization === 'Bearer renewed-token' ? jsonResponse({}) : jsonResponse({ code: 'unauthenticated' }, 401)
    })

    await Promise.all([apiRequest('/a'), apiRequest('/b'), apiRequest('/c')])

    expect(refresh).toHaveBeenCalledTimes(1)
  })

  it('does not retry when the refresh cannot produce a different token', async () => {
    setAuthTokenProvider(() => 'stale-token')
    setAuthRefreshHandler(() => Promise.resolve('stale-token'))
    const fetchMock = vi
      .spyOn(globalThis, 'fetch')
      .mockImplementation(async () => jsonResponse({ code: 'unauthenticated' }, 401))

    await expect(apiRequest('/orders')).rejects.toBeInstanceOf(ApiError)

    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it('never retries a failure that is not an authentication failure', async () => {
    setAuthTokenProvider(() => 'token')
    const refresh = vi.fn(() => Promise.resolve('renewed-token'))
    setAuthRefreshHandler(refresh)
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation(async () => problemResponse({}, 403))

    await expect(apiRequest('/orders')).rejects.toMatchObject({ status: 403 })

    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(refresh).not.toHaveBeenCalled()
  })

  it('surfaces a refresh that throws as an unrecoverable failure instead of propagating it', async () => {
    setAuthTokenProvider(() => 'stale-token')
    setAuthRefreshHandler(() => Promise.reject(new Error('network down')))
    vi.spyOn(globalThis, 'fetch').mockImplementation(async () => jsonResponse({ code: 'unauthenticated' }, 401))

    await expect(apiRequest('/orders')).rejects.toMatchObject({ code: 'unauthenticated' })
  })
})
