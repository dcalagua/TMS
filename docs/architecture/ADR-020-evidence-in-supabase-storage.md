# ADR-020 - Delivery and document evidence in a private Supabase Storage bucket

**Status:** Accepted (product owner, 2026-10-12). Implemented as `SupabaseEvidenceStorage`.
**Date:** 2026-10-12
**Amends:** CLAUDE.md "deferred by decision: Storage" - lifted for evidence only.
**Constrained by:** ADR-002 (Flyway owns application DDL), ADR-006 (evidence behind
`EvidenceStoragePort`, private, no URL), ADR-018/019 (carrier photos are mandatory for a delivered
document)

## Context

ADR-006 put proof-of-delivery bytes behind `EvidenceStoragePort` and shipped a local-volume store,
naming Supabase Storage as "one more implementation with no change to any caller". QAS runs on Render
with an ephemeral disk: a volume store loses every photo on redeploy, and without a store the
carrier portal cannot report a document as delivered (the photo is mandatory, ADR-018).

## Decision

1. **Mode `SUPABASE`** of `tms.storage.evidence.mode`, implemented by `SupabaseEvidenceStorage`
   against the Storage REST API with the JDK HTTP client. No new dependency.
2. **A private bucket per project, `tms-evidence`**: `public = false`, 25 MB limit (the schema's own
   ceiling), images and PDF only. `storage.objects` carries **no policy**, so no browser key - anon,
   publishable or a user's JWT - can list or read it.
3. **Only the backend talks to the bucket**, with the project's `service_role` key held as a secret
   in the deployment (`TMS_EVIDENCE_SUPABASE_SERVICE_KEY`) and never logged (`toString` redacts it).
   Observed on 2026-10-12: Storage requires a JWT in `Authorization`; the newer `sb_secret_` keys
   are refused, so the legacy `service_role` JWT is the key to configure until Supabase changes that.
4. **Bytes still reach people only through the API**, after company, carrier and permission checks.
   No signed URL, no public URL (ADR-006 unchanged).
5. **Same keys as the volume store** (`<company>/<yyyy>/<MM>/<uuid>.<ext>`, `EvidenceKeys`), so a
   later move between stores is a copy and no row changes. A key of another company is refused
   before any request is sent.
6. **Size and checksum are computed by the backend** from the bytes read (bounded in memory by the
   configured ceiling) before the upload, so an oversized file is never sent and the stored checksum
   is the one of the stored bytes. Upload never overwrites (`x-upsert: false`).
7. **The bucket is platform configuration, not application schema.** It is created once per project
   (Storage API or dashboard), not by Flyway and not by a Supabase migration - ADR-002 is untouched.

## Consequences

- Evidence survives redeploys and works with more than one backend instance.
- The `service_role` key bypasses RLS on the whole project: it must only ever be in the backend's
  secret settings. Rotating it is a deployment change.
- `SupabaseEvidenceStorageLiveTest` verifies a real bucket on demand (skipped without its variables).
- Local development keeps `LOCAL` (the local Supabase stack runs with `storage.enabled = false`).
