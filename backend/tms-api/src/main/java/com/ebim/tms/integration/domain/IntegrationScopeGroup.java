package com.ebim.tms.integration.domain;

/**
 * The kind of partner an {@link IntegrationScope} is meant for, so the credentials form can show
 * twelve checkboxes as four short groups instead of one long list. Presentation only - a group
 * grants nothing, and a credential may hold scopes of several groups.
 */
public enum IntegrationScopeGroup {

    /** The ERP or order source: locations, orders and the documents it issued. */
    ERP,

    /** A warehouse system (EWM by EBIM or another WMS): plan in, dispatch, milestones and order events out. */
    WAREHOUSE,

    /** A carrier or its telematics: tenders to answer and vehicle positions to report. */
    CARRIER,

    /** Finance: what the ERP paid against an exported carrier invoice. */
    FINANCE
}
