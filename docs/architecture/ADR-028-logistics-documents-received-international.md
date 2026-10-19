# ADR-028 - Logistics documents are received, never issued: an international catalogue

- **Status:** Accepted and implemented (V69, 2026-10-18)
- **Date:** 2026-10-18
- **Amends:** ADR-015 (decision 1, the Peru-only catalogue). ADR-015's other decisions stand: many to
  many with orders, no foreign key to the trip, ingestion read-only for TMS, nothing moves an order.
- **Relates to:** ADR-018 (document delivery tracking, carrier boundary), ADR-024 (Peruvian taxes on
  the *carrier's* invoice - a different document in the opposite direction), ADR-005 (tenant RLS).
- **Migration:** `V69__logistics_documents_international.sql`
- **Contract:** `docs/integrations/INBOUND_API_V1.md` §15

## Context

V55 records the documents a customer's ERP issued for its orders, with a catalogue written for Peru:
`INVOICE, DELIVERY_NOTE, GRE_REMITENTE, GRE_TRANSPORTISTA, OTHER`, and no field for the identifiers
other tax authorities use.

The first customer outside Peru is **PLAPASA (Ecuador)**. In Ecuador goods move under the SRI
*guía de remisión* (comprobante electrónico code **06**). What the operation needs to see is the
number (`001-001-000000123`: establecimiento-puntoEmisión-secuencial), the **clave de acceso** (the
49-digit authorization number), the issue date, the issuer's RUC (13 digits), the transport start and
end dates, the carrier's RUC and name, the vehicle plate, the departure address, and for each
*destinatario* its tax id, name, destination address, reason for the transfer, the sales document
that supports the movement (the factura number) and the lines.

**Product decision (business owner, not re-litigated here):** TMS does **not** generate, sign or
send electronic documents. Invoices and remission guides are issued by the customer's ERP or
e-invoicing provider; TMS only **receives** their data through integration and shows and links it.
No SUNAT/SRI emission, no XML signing, no submission, no status query to the authority.

## Decision

1. **A generic type, the Peruvian ones kept as aliases.** `REMISSION_GUIDE` joins the catalogue; the
   CHECK `ck_logistics_document_type` is recreated (drop + add, one copy). The country and the
   authority's code say which guide it is:

   | `documentType` | Equivalent | Authority code |
   |---|---|---|
   | `REMISSION_GUIDE` + `fiscalCountry=EC` | SRI guía de remisión | `06` |
   | `GRE_REMITENTE` | `REMISSION_GUIDE` + `PE` | SUNAT `09` |
   | `GRE_TRANSPORTISTA` | `REMISSION_GUIDE` + `PE` | SUNAT `31` |
   | `REMISSION_GUIDE` + `PE` | either GRE, by `fiscalDocumentCode` | `09` / `31` |

   The GRE types are not migrated, renamed or backfilled: an ERP that sends them keeps working, and a
   row received before V69 is untouched. A GRE labelled with another country is refused
   (`ck_logistics_document_gre_is_peru`).

2. **Nullable fiscal columns on `tms.logistics_document`.** `fiscal_country` (ISO 3166-1 alpha-2),
   `fiscal_document_code` (`06`, `09`, `31`, `01`... as sent), `authorization_number` (≤ 64: the SRI
   clave de acceso, a SUNAT reference), `transport_start_date`, `transport_end_date`,
   `carrier_tax_id`, `carrier_name`, `vehicle_plate`, `origin_address`, `transfer_reason`. All
   optional; nothing is derived from anything else.

3. **The destinatarios are a child table.** `tms.logistics_document_recipient`: one row per recipient
   in the order sent (`sequence`), with tax id, name, destination address, reason, establishment
   code, route, the supporting document (code, number, authorization, date) and the lines as a
   `jsonb` array (`code`, `additionalCode`, `description`, `quantity`, `unit`). Rewritten whole on a
   re-send, like `logistics_document_order`: grants `SELECT, INSERT, DELETE`, never `UPDATE`. Tenant
   policy (ADR-005) and a restrictive carrier policy that defers to the parent document (ADR-018).
   The lines are `jsonb` and not a third table because they are display-only: they are never
   reconciled with `transport_order_line` and never queried by field.

4. **Shape, never substance.** TMS validates what a value can be, not whether the authority
   accepted it. With `fiscalCountry = EC`:
   - `documentNumber` must be `\d{3}-\d{3}-\d{9}` when the document is an SRI comprobante
     (`REMISSION_GUIDE`, or any type carrying a `fiscalDocumentCode`); an internal delivery note
     without a code keeps its own numbering;
   - `authorizationNumber`, when present, must be exactly 49 digits;
   - `issuerTaxId`, when present, must be a 13-digit RUC;
   - an Ecuadorian `REMISSION_GUIDE` with a code other than `06` is refused, never corrected.

   For Peru nothing new is checked beyond the alias codes (`09`/`31`): its numbers stay "never
   parsed", as V55 decided. `externalStatus` (e.g. `AUTORIZADO`) is still stored and never
   interpreted. The mod-11 check digit of the clave de acceso is **not** verified (see open points).
   The rules live in `orders.domain.LogisticsDocumentShape` (pure) and are mirrored by V69's CHECKs.

5. **Same endpoint, same scope, same idempotency.** `POST /integration/v1/logistics-documents`,
   scope `integration.document:write`. The new JSON fields are optional and `NON_NULL` on the request
   record, so a body without them re-serialises exactly as before and its `PayloadHash` is unchanged:
   an `Idempotency-Key` replay across the upgrade still replays. Identity stays (sourceSystem,
   documentType, documentNumber); a re-send with other fiscal data or recipients is `UPDATED`.

6. **Linked to orders, shown on the trip through them.** No foreign key to the trip (ADR-015 §2
   stands). The guide's plate and carrier are what the ERP printed; they are shown beside the trip's
   own and **not** compared with them.

7. **Shown with a country label and a disclaimer.** Order detail and trip workspace show the
   document with its country label ("Guía de remisión (SRI 06)", "GRE remitente (SUNAT 09)") and an
   expandable detail: copyable clave de acceso, issuer, transport dates, carrier and plate, departure
   address, recipients and lines, always with the note *«Recibido del ERP · el TMS no emite ni valida
   ante el SRI»* (SUNAT for Peru). The carrier portal shows and copies the clave de acceso on the
   document card (`PortalDocumentView.fiscal`), for a roadside check.

## Consequences

- One migration, one table, ten nullable columns, three new CHECKs on shape; no row is rewritten.
- A second country needs no migration as long as its guide fits these fields: `fiscalCountry` and
  `fiscalDocumentCode` carry it, and the label falls back to "Guía de remisión (XX)".
- What a country-specific rule adds (another number format) is a branch in `LogisticsDocumentShape`
  plus a CHECK - the same friction as V69.
- TMS can never be the party that is wrong about a document's legal validity: it never claims one.

## Open points

1. **Mod-11 check digit** of the clave de acceso: pure shape and cheap, but a bug of ours would then
   hide a legal document from the operation. Left out until the customer asks.
2. **Per-recipient order link.** A guide with several destinatarios links to its orders at document
   level; which recipient matches which order is not modelled.
3. **Plate/carrier comparison** with the trip (an informational discrepancy like ADR-013's
   reconciliation) is deferred: it would be the first time TMS interprets the content of a document.
4. **Other Ecuadorian comprobantes** (factura `01`) are accepted with the same shape rules, but no
   screen distinguishes them beyond the label.
