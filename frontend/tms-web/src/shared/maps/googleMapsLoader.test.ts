import { afterEach, describe, expect, it, vi } from 'vitest'

const jsApiLoaderMocks = vi.hoisted(() => ({
  setOptions: vi.fn(),
  importLibrary: vi.fn(),
}))
vi.mock('@googlemaps/js-api-loader', () => jsApiLoaderMocks)

const envMocks = vi.hoisted(() => ({ appEnv: { googleMapsApiKey: null as string | null } }))
vi.mock('../config/env', () => ({ appEnv: envMocks.appEnv }))

afterEach(() => {
  vi.clearAllMocks()
  vi.resetModules()
  envMocks.appEnv.googleMapsApiKey = null
})

describe('googleMapsLoader', () => {
  it('reports unconfigured when no API key is set', async () => {
    const { isGoogleMapsConfigured } = await import('./googleMapsLoader')
    expect(isGoogleMapsConfigured()).toBe(false)
  })

  it('rejects loadMapsLibrary without ever calling the underlying loader when no key is set', async () => {
    const { loadMapsLibrary } = await import('./googleMapsLoader')
    await expect(loadMapsLibrary()).rejects.toThrow(/not configured/i)
    expect(jsApiLoaderMocks.setOptions).not.toHaveBeenCalled()
    expect(jsApiLoaderMocks.importLibrary).not.toHaveBeenCalled()
  })

  it('sets the API key once and reuses one promise per library across repeated calls', async () => {
    envMocks.appEnv.googleMapsApiKey = 'test-key'
    const fakeMapsLibrary = { Map: vi.fn() }
    jsApiLoaderMocks.importLibrary.mockResolvedValue(fakeMapsLibrary)

    const { loadMapsLibrary } = await import('./googleMapsLoader')
    const first = await loadMapsLibrary()
    const second = await loadMapsLibrary()

    expect(first).toBe(fakeMapsLibrary)
    expect(second).toBe(fakeMapsLibrary)
    expect(jsApiLoaderMocks.importLibrary).toHaveBeenCalledTimes(1)
    expect(jsApiLoaderMocks.importLibrary).toHaveBeenCalledWith('maps')
    expect(jsApiLoaderMocks.setOptions).toHaveBeenCalledTimes(1)
    expect(jsApiLoaderMocks.setOptions).toHaveBeenCalledWith({ key: 'test-key', v: 'weekly' })
  })

  it('does not cache a failed load, so a later retry can succeed', async () => {
    envMocks.appEnv.googleMapsApiKey = 'test-key'
    jsApiLoaderMocks.importLibrary.mockRejectedValueOnce(new Error('network down'))
    const fakeMapsLibrary = { Map: vi.fn() }
    jsApiLoaderMocks.importLibrary.mockResolvedValueOnce(fakeMapsLibrary)

    const { loadMapsLibrary } = await import('./googleMapsLoader')
    await expect(loadMapsLibrary()).rejects.toThrow('network down')
    await expect(loadMapsLibrary()).resolves.toBe(fakeMapsLibrary)
    expect(jsApiLoaderMocks.importLibrary).toHaveBeenCalledTimes(2)
  })
})
