# ADR-015 - Logistics documents beside the order

**Status:** Accepted for the model and read-only ingestion (V55). Document **delivery tracking**
(the paper trail after the truck) is proposed and not implemented - see section 5.
**Date:** 2026-09-27
**Constrained by:** ADR-003 (company scope), ADR-005 (tenant RLS), ADR-006 (evidence storage),
ADR-009 (order execution lifecycle), `docs/domain/SPLIT_ORDER_EXECUTION.md`

## Context

An order travels with paperwork: the customer's invoice, a delivery note, and in Peru the electronic
remission guides (GRE remitente, issued by the owner of the goods; GRE transportista, issued by the
carrier). The operation wants to see, per order and per trip, which documents go with it, and later
to follow those documents to the customer and back (a signed copy returned).

An audit of the code on 2026-09-27 found nothing reusable without distorting it:

| Existing | Why not |
|---|---|
| `order_delivery` / `order_delivery_line` (V28, V45) | Physical fulfilment: what the customer received. A document is not a delivery, and two documents for one delivery - or one document for two orders - do not fit a per-order row |
| `delivery_evidence` (V28, ADR-006) | Proof-of-delivery artefacts (photo, signature, scanned note) attached to a delivery. An artefact *of* a document at best, not the document |
| `carrier_invoice` (V46) | The carrier's invoice to the shipper, for freight settlement. The opposite direction and a different party |
| `transport_order.external_reference` | One ERP key per order. An order routinely has two or three documents |

## Decision

1. **A generic document, not an invoice table.** `tms.logistics_document`: company, `document_type`
   (`INVOICE`, `DELIVERY_NOTE`, `GRE_REMITENTE`, `GRE_TRANSPORTISTA`, `OTHER`), the ERP namespace it
   comes from (`source_system`, same rules as `external_source`), its number exactly as issued
   (`document_number`, never parsed - series formats are the ERP's and the tax authority's), issue
   date, optional issuer/recipient tax ids and an optional informational amount and currency.
   Unique per (company, source_system, document_type, document_number).
2. **Many to many with orders.** `tms.logistics_document_order (document, order)`. An invoice may
   cover several orders; an order may carry several documents. No foreign key to the trip: which
   trip carries a document is derived through the orders on it, so a split order's documents show
   on each trip that carries part of it.
3. **`order_delivery` is not replaced.** Physical fulfilment stays derived from `order_delivery`
   (ADR-009, V45). Nothing here moves an order, a trip or a delivery.
4. **Ingestion is read-only for TMS** (V55): the ERP upserts documents over the integration API
   (`POST /integration/v1/logistics-documents`, scope `integration.document:write`), keyed by
   (sourceSystem, documentType, documentNumber) and linked to orders by (externalSource,
   externalReference). Operators read them per order and per trip.

## 5. Proposed, not implemented: document delivery tracking

A document's own journey - handed to the driver, delivered, refused, signed copy returned to the
office - is a lifecycle of its own and **not** the order's. It is not implemented because three
decisions cannot be inferred from the code and carry legal or fiscal weight:

1. **The unit that is tracked.** Per document, per document *copy*, or per stop visit? A GRE travels
   with the goods and one copy stays with the customer; an invoice may be delivered separately.
2. **What "delivered" means legally** for each type in each country (SUNAT for the GRE, the
   customer's own acceptance rules for an invoice), and whether TMS must keep the signed copy as
   evidence (it would reuse `delivery_evidence` and ADR-006's store).
3. **Who issues a GRE transportista** when the carrier is a third party: TMS on the carrier's behalf,
   the carrier, or nobody - and therefore whether TMS may ever *create* such a document or only
   record one.

Until these are decided, TMS records the documents and their links, shows them, and changes nothing
because of them.

## Consequences

- Two tables with tenant RLS; one integration scope; two read endpoints.
- A split order's documents are visible on every trip that carries part of it.
- The document tracking lifecycle can be added later as its own table of events without touching
  `order_delivery`.
