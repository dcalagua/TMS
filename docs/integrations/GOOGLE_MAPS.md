# TMS by EBIM - Google Maps integration (location picker)

One line: **the browser loads the Google Maps JavaScript API directly with a restricted,
publicly-visible key to let an operator place a Location's coordinates on a map; Spring Boot
never calls Google, and the feature degrades to the existing manual latitude/longitude fields
when no key is configured.**

## 1. Scope

This covers the Location/Store create-and-edit drawer (`LocationFormDrawer`) and the shared
components it is built from. It does not cover shipment/trip stop maps or route
optimization - `StopsMap` is a read-only foundation for that later work
(`tms-overnight-v3/prompts/10_shipment_maps_and_stop_sequence.md`), not a finished feature.
OR-Tools/route optimization and GPS/telematics remain out of scope per the repository's
deferred-decisions list.

## 2. Why the browser calls Google directly

The Maps JavaScript API (rendering a map, placing a marker, geocoding an address) is a
browser-side rendering concern, not a business rule. Routing it through Spring Boot would add a
network hop with no security benefit: a Maps JavaScript API key is designed to be public and
restricted by *where it can be called from* (HTTP referrer), not by who holds it server-side.
Coordinates chosen on the map still reach the backend through the existing
`LocationRequest`/`LocationView` contract (`POST`/`PUT /masterdata/locations`) - Spring Boot
validates and persists them exactly as it did before this feature existed; nothing about the
API surface changed.

## 3. Configuration

### 3.1 Environment variable

`frontend/tms-web/.env.example` documents `VITE_GOOGLE_MAPS_API_KEY`. Copy it into
`.env.local` (git-ignored) and set a real key for local development:

```
VITE_GOOGLE_MAPS_API_KEY=your-restricted-browser-key
```

Leaving it blank (or omitting `.env.local` entirely) is a supported state: `appEnv.googleMapsApiKey`
resolves to `null`, `isGoogleMapsConfigured()` returns `false`, and every map component renders
its non-blocking "unavailable" notice instead of a blank or broken map. No screen requires a
Google Maps key to function - it is only required to see the map instead of typing coordinates.

### 3.2 Google Cloud Console setup

1. Create (or reuse) a Google Cloud project and enable exactly two APIs:
   - **Maps JavaScript API** - renders the map and marker.
   - **Geocoding API** - powers the address search box (`google.maps.Geocoder`).
   Do not enable Places or any other Maps product; this feature does not use them, and an
   unused enabled API is an unused attack/billing surface.
2. Create an API key under **APIs & Services -> Credentials**.
3. **Application restrictions -> HTTP referrers**: add the exact origins that serve the
   frontend, for example:
   - `http://localhost:5173/*` (local dev)
   - `https://<staging-domain>/*`
   - `https://<production-domain>/*`
   A browser key with no referrer restriction is usable by anyone who copies it out of the
   page source; this is the control that makes a public key safe to ship.
4. **API restrictions -> Restrict key**: select only "Maps JavaScript API" and "Geocoding API".
5. Put the key in `.env.local` for local development, and in the frontend build/deploy
   environment's `VITE_GOOGLE_MAPS_API_KEY` variable for staging/production. It is never a
   server secret and is never read by Spring Boot - do not put it in the backend's
   configuration or in Supabase secrets.

## 4. Architecture

```
frontend/tms-web/src/shared/maps/
  googleMapsLoader.ts    one Loader/importLibrary instance shared by every map component,
                          so opening two maps on one page never injects the bootstrap
                          script twice. Exposes isGoogleMapsConfigured() and one
                          loadXLibrary() function per Maps JS "library" actually used
                          (core, maps, marker, geocoding).
  types.ts                LatLngValue, MapStop, MapLoadStatus - shared across components.
  LocationPickerMap.tsx    address search + click-to-place + draggable marker; reports
                          plain {lat, lng} numbers back to the caller via onChange.
  StopsMap.tsx             read-only multi-marker map that fits its view to a list of
                          stops; the foundation Job 10 (shipment stop maps) builds on.
```

`LocationPickerMap` does not own any form state: `LocationFormDrawer` passes it the current
`latitude`/`longitude` (parsed from the form's existing text fields) and receives numbers back
through `onChange`, which it writes into those same React Hook Form fields with
`shouldDirty`/`shouldValidate`. This is why manually typed coordinates and map-picked
coordinates stay in sync in both directions, and why the existing `-90..90`/`-180..180`
range validation and pair-completeness rule (`ck_location_coordinates_pair`,
mirrored client-side as `latLongPair`) apply unchanged regardless of how a value was entered.

In the drawer, the raw latitude/longitude inputs live inside an open-by-default
`<details class="tms-details-compact">` labelled "Coordenadas exactas" / "Exact coordinates",
under the map rather than beside it - the map is the primary way to set a location's position;
the exact numbers are there for anyone who needs to read or paste them, not as the main UX.

## 5. Graceful degradation

Both `LocationPickerMap` and `StopsMap` render one of three states, driven by
`MapLoadStatus`:

| Status | Trigger | What renders |
|---|---|---|
| `unavailable` | no API key configured, or the script/library failed to load | a `role="status"` notice; no map canvas, no network calls |
| `loading` | key configured, libraries loading | the map canvas plus a small loading line |
| `ready` | libraries resolved | the interactive map |

A missing key never throws and never blocks the surrounding form: `LocationFormDrawer` and any
future consumer of `StopsMap` keep working with manual data entry alone.

## 6. Testing

- **Unit/component (Vitest)**: `googleMapsLoader.test.ts`, `LocationPickerMap.test.tsx` and
  `StopsMap.test.tsx` mock `./googleMapsLoader` entirely with fake `Map`/`Marker`/`Geocoder`
  classes, so the suite never loads the real Google script and never needs a key. They cover
  the unavailable state, a load failure, map clicks, marker drag, and address search
  (including the "no results" case).
- **Playwright (`e2e/maps.spec.ts`)**: runs against this repository's dev environment, which
  has no key configured, and asserts the Location drawer's graceful no-key state plus that the
  "Coordenadas exactas" section is an open-by-default disclosure. This is the mandatory,
  CI-safe coverage.
- **Optional live coverage**: a suite tagged `@google-maps-live` that drives the real picker
  (actual map render, actual click-to-place, actual geocoding) against a provisioned key is
  intentionally not included in this change, because no key is available in this environment
  to write and verify it against. To add one later: guard it with
  `test.skip(!process.env.VITE_GOOGLE_MAPS_API_KEY, 'no Google Maps key provisioned')` and keep
  it out of the default CI project so a missing key never fails a build, only skips the extra
  coverage.

## 7. Non-goals (see deferred-decisions list)

Route optimization, live GPS/telematics tracking, and shipment stop sequencing UI are not part
of this change. `StopsMap` intentionally has no click-to-add, drag-to-reorder, or routing line -
those decisions belong to the shipment/stop-sequence work and should be made there, against
real requirements, rather than guessed at here.
