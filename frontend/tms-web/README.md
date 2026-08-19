# tms-web

React + TypeScript + Vite front end for **TMS by EBIM**.

## Rules that shape this app

- Business data is fetched from the Spring Boot API (`VITE_API_BASE_URL`), never straight
  from Supabase. In V1 the Supabase client is used for **authentication only**.
- Bootstrap is the visual base and SweetAlert2 handles confirmations and critical
  feedback. MUI is not used.
- Hiding a button is a UX hint, never authorization. The backend decides.

## Layout

    src/app/        router, providers, query client
    src/shared/     api client, typed env, reusable UI, alert helpers
    src/pages/      screens
    src/test/       test setup

## Commands

    npm install         install dependencies
    npm run dev         start the dev server on http://localhost:5173
    npm run typecheck   TypeScript project build (no emit)
    npm run lint        oxlint
    npm test            Vitest (jsdom + Testing Library)
    npm run coverage    Vitest with V8 coverage
    npm run build       typecheck + production bundle into dist/

## Configuration

Copy `.env.example` to `.env.local` and adjust. `.env.local` is git-ignored; the example
file contains placeholders only.
