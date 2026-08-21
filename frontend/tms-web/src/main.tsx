import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { RouterProvider } from 'react-router-dom'
import 'bootstrap/dist/css/bootstrap.min.css'
import 'bootstrap/dist/js/bootstrap.bundle.min.js'
import 'bootstrap-icons/font/bootstrap-icons.css'
import './index.css'
import './shared/i18n'
import { AppProviders } from './app/AppProviders'
import { applyStoredTheme } from './shared/theme/ThemeProvider'
import { router } from './app/router'

// Before the first render, so the page paints in the stored theme instead of flashing the
// default one and correcting itself.
applyStoredTheme()

const container = document.getElementById('root')
if (!container) {
  throw new Error('Root container #root is missing from index.html')
}

createRoot(container).render(
  <StrictMode>
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  </StrictMode>,
)
