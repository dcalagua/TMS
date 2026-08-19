import '@testing-library/jest-dom/vitest'
import '../shared/i18n'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'

// `globals: false` means Testing Library cannot auto-register its cleanup hook.
afterEach(() => {
  cleanup()
})
