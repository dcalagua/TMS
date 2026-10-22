package com.ebim.tms.shared.security;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The authorization vocabulary of TMS, mirroring {@code tms.permission} one for one.
 *
 * <p>A permission is an atomic capability written {@code resource:action}. The database is the
 * catalogue of record (migrations V3, V5 and V14); this enum is the compile-time view of it, so a
 * typo in a {@code @PreAuthorize} expression is a failing test rather than a silently open
 * endpoint. {@code PermissionCatalogueIntegrationTest} asserts the two are identical.
 *
 * <p>Granularity is per resource, not per module: {@code masterdata.origin:read} rather than
 * {@code MASTER_DATA_VIEW}. Coarse module-level names remain available to the UI through
 * {@link Capability}, which groups these; see {@code docs/security/AUTHORIZATION_MODEL.md}
 * for the mapping between the two.
 */
public enum Permission {

    IAM_ORGANIZATION_READ("iam.organization:read"),
    IAM_ORGANIZATION_MANAGE("iam.organization:manage"),
    IAM_COMPANY_READ("iam.company:read"),
    IAM_COMPANY_MANAGE("iam.company:manage"),
    IAM_USER_READ("iam.user:read"),
    IAM_USER_MANAGE("iam.user:manage"),
    IAM_MEMBERSHIP_READ("iam.membership:read"),
    IAM_MEMBERSHIP_MANAGE("iam.membership:manage"),

    MASTERDATA_LOCATION_READ("masterdata.location:read"),
    MASTERDATA_LOCATION_MANAGE("masterdata.location:manage"),
    MASTERDATA_ORIGIN_READ("masterdata.origin:read"),
    MASTERDATA_ORIGIN_MANAGE("masterdata.origin:manage"),
    MASTERDATA_ZONE_READ("masterdata.zone:read"),
    MASTERDATA_ZONE_MANAGE("masterdata.zone:manage"),
    MASTERDATA_DESTINATION_READ("masterdata.destination:read"),
    MASTERDATA_DESTINATION_MANAGE("masterdata.destination:manage"),
    MASTERDATA_FREQUENCY_READ("masterdata.frequency:read"),
    MASTERDATA_FREQUENCY_MANAGE("masterdata.frequency:manage"),
    MASTERDATA_ROUTE_READ("masterdata.route:read"),
    MASTERDATA_ROUTE_MANAGE("masterdata.route:manage"),

    /**
     * The warehouse-owned physical profile of a material (migration V59, ADR-022). Read only: the
     * one write path is the integration API, so there is no manage permission to grant.
     */
    MASTERDATA_MATERIAL_PROFILE_READ("masterdata.material_profile:read"),

    FLEET_CARRIER_READ("fleet.carrier:read"),
    FLEET_CARRIER_MANAGE("fleet.carrier:manage"),
    FLEET_VEHICLE_TYPE_READ("fleet.vehicle_type:read"),
    FLEET_VEHICLE_TYPE_MANAGE("fleet.vehicle_type:manage"),
    FLEET_VEHICLE_READ("fleet.vehicle:read"),
    FLEET_VEHICLE_MANAGE("fleet.vehicle:manage"),
    /**
     * The driver master (migration V26). A resource of its own and not part of
     * {@link #FLEET_VEHICLE_MANAGE}: a driver record holds personal data - name, identity
     * document, phone - that a vehicle record does not.
     */
    FLEET_DRIVER_READ("fleet.driver:read"),
    FLEET_DRIVER_MANAGE("fleet.driver:manage"),

    ORDERS_ORDER_READ("orders.order:read"),
    ORDERS_ORDER_MANAGE("orders.order:manage"),
    /**
     * Place and lift holds on an order (migration V54, ADR-014). Its own authority: stopping an
     * order for credit or stock is a commercial decision that the person keying orders may not hold.
     */
    ORDERS_HOLD_MANAGE("orders.hold:manage"),
    /**
     * Generate, list and revoke an order's customer tracking links (migration V74, ADR-029). Its own
     * authority: handing an anonymous party a view of an order is a customer-facing decision that a
     * VIEWER, who may read every order, must not be able to make.
     */
    ORDERS_TRACKING_LINK_MANAGE("orders.tracking_link:manage"),

    PLANNING_PLAN_READ("planning.plan:read"),
    PLANNING_PLAN_MANAGE("planning.plan:manage"),
    PLANNING_TRIP_READ("planning.trip:read"),
    PLANNING_TRIP_MANAGE("planning.trip:manage"),
    /**
     * Operating a trip through its day - ready, dispatch, complete (migration V25). Deliberately
     * not implied by {@link #PLANNING_TRIP_MANAGE}: building a plan and running it are different
     * jobs, and a role may hold either without the other.
     */
    PLANNING_TRIP_EXECUTE("planning.trip:execute"),
    /**
     * Dispatching a trip by hand, with a reason, in a company whose dispatch mode requires the
     * warehouse to confirm it (ADR-013 section 4, migration V52). Granted to the two administrator
     * roles and deliberately not to PLANNER; an integration credential never holds a permission.
     */
    PLANNING_TRIP_DISPATCH_OVERRIDE("planning.trip:dispatch-override"),
    /**
     * Placing a shipment with its carrier (migration V31). A resource of its own for the reason
     * {@link #PLANNING_TRIP_EXECUTE} is one: offering a load at a price is a commercial act,
     * building the plan is not, and an installation may well want the two in different hands.
     */
    PLANNING_TENDER_READ("planning.tender:read"),
    PLANNING_TENDER_MANAGE("planning.tender:manage"),
    /** Dock scheduling (migration V41). VIEWER holds the read: a booking carries no price. */
    APPOINTMENTS_APPOINTMENT_READ("appointments.appointment:read"),
    APPOINTMENTS_APPOINTMENT_MANAGE("appointments.appointment:manage"),
    /** Configuring doors is an administrator's job: adding one changes what the site can promise. */
    APPOINTMENTS_RESOURCE_MANAGE("appointments.resource:manage"),

    /**
     * The commercial agreement a carrier is paid under (migration V30). Separate from
     * {@link #RATES_TRIP_COST_READ} because they are different disclosures: a dispatcher may
     * legitimately need to see what one shipment cost without being shown the tariff behind it.
     */
    RATES_RATE_CARD_READ("rates.rate_card:read"),
    RATES_RATE_CARD_MANAGE("rates.rate_card:manage"),
    RATES_TRIP_COST_READ("rates.trip_cost:read"),
    RATES_TRIP_COST_MANAGE("rates.trip_cost:manage"),

    /**
     * Freight audit (migration V46). Six, and the split is the point.
     *
     * <p>Recording an invoice, deciding it is payable and handing it to accounting are three
     * different authorities, and an installation will want them in different hands: a clerk keys
     * what arrives, a controller approves the expenditure, and only somebody trusted with the
     * accounting boundary exports it. Collapsing them into one {@code settlement:manage} would let
     * whoever types an invoice approve their own.
     */
    SETTLEMENT_INVOICE_READ("settlement.invoice:read"),
    SETTLEMENT_INVOICE_MANAGE("settlement.invoice:manage"),
    SETTLEMENT_INVOICE_MATCH("settlement.invoice:match"),
    /** Authorising an expenditure. The one that must never be held by a machine - see debt D4. */
    SETTLEMENT_INVOICE_APPROVE("settlement.invoice:approve"),
    SETTLEMENT_INVOICE_EXPORT("settlement.invoice:export"),
    SETTLEMENT_TOLERANCE_MANAGE("settlement.tolerance:manage"),
    /**
     * Freight accruals (V65, ADR-023). Reading the open obligation is for everyone who reads invoices;
     * exporting it to accounting and reversing it are administrators' authority.
     */
    SETTLEMENT_ACCRUAL_READ("settlement.accrual:read"),
    SETTLEMENT_ACCRUAL_EXPORT("settlement.accrual:export"),
    SETTLEMENT_ACCRUAL_MANAGE("settlement.accrual:manage"),

    /**
     * Payment status after the export (migration V67, ADR-023 section 2). TMS records what was paid
     * and pays nothing (D-01). Two permissions because a manual payment is four-eyes: whoever keys
     * it is not whoever confirms it - and, as with approval, the rule is enforced on the people, not
     * only on the permissions.
     */
    SETTLEMENT_PAYMENT_RECORD("settlement.payment:record"),
    SETTLEMENT_PAYMENT_CONFIRM("settlement.payment:confirm"),

    /**
     * Cost to serve (migration V68, ADR-026): what each order, customer, destination and route cost.
     * Reading is for everyone who reads accruals; recalculating a trip's allocation is an administrator's.
     */
    SETTLEMENT_COST_ALLOCATION_READ("settlement.cost_allocation:read"),
    SETTLEMENT_COST_ALLOCATION_MANAGE("settlement.cost_allocation:manage"),

    /**
     * The carbon footprint (migration V73): what each trip emitted, split between its orders on the
     * cost-allocation basis, and reported per customer, destination and route. Read-only - nothing
     * about it is ever stored.
     */
    SETTLEMENT_EMISSION_READ("settlement.emission:read"),

    /**
     * Editing the company's fiscal regime (migration V66, ADR-027): its fiscal country, VAT rates and
     * withholdings. Reading the resolved regime is {@link #SETTLEMENT_INVOICE_READ}, so every invoice
     * form can preview with it; changing what is withheld from every carrier is an administrator's.
     */
    SETTLEMENT_TAX_MANAGE("settlement.tax:manage"),

    /**
     * Freight claims (migration V71): damage, shortage, loss and delay claimed from a carrier, and
     * the incident centre that lists them beside the open trip exceptions. Three permissions because
     * opening a claim and deciding what the carrier owes are different authorities: the planner who
     * knows the chairs arrived broken opens it; an administrator - never the same person - accepts it.
     */
    SETTLEMENT_CLAIM_READ("settlement.claim:read"),
    SETTLEMENT_CLAIM_MANAGE("settlement.claim:manage"),
    SETTLEMENT_CLAIM_DECIDE("settlement.claim:decide"),

    /**
     * A driver-and-vehicle pairing's day (migration V47).
     *
     * <p>Under {@code fleet} rather than {@code planning} because the thing being scheduled is a
     * resource, not a shipment - and because the people who build a day are the same people who
     * maintain the trucks and the drivers. Read and manage, split as everywhere else.
     */
    FLEET_WORK_ASSIGNMENT_READ("fleet.work_assignment:read"),
    FLEET_WORK_ASSIGNMENT_MANAGE("fleet.work_assignment:manage"),

    /**
     * Fleet compliance documents (migration V72): the papers of a vehicle or a driver beyond the
     * licence - registration, technical inspection, insurance, operating permit, medical certificate.
     * Keeping them and deciding which of them block a trip are separate authorities: the policy is
     * an administrator's, because it is what refuses a planner's assignment.
     */
    FLEET_DOCUMENT_READ("fleet.document:read"),
    FLEET_DOCUMENT_MANAGE("fleet.document:manage"),
    FLEET_DOCUMENT_POLICY_MANAGE("fleet.document_policy:manage"),

    /**
     * What running our own trucks is modelled to cost (migration V48).
     *
     * <p>Its own resource rather than folded into {@code rates.rate_card}: a tariff is a commercial
     * agreement with a carrier and this is a finance model of our own operation - driver hourly
     * cost, depreciation, what we believe fuel runs at - and an installation will want the two in
     * different hands. Deliberately NOT given to VIEWER, which is the line {@code rates} draws too:
     * reading these rates discloses our cost structure, not our operation.
     */
    COSTING_OWN_FLEET_READ("costing.own_fleet:read"),
    COSTING_OWN_FLEET_WRITE("costing.own_fleet:write"),

    INTEGRATION_CLIENT_READ("integration.client:read"),
    INTEGRATION_CLIENT_MANAGE("integration.client:manage"),

    /**
     * Where this company's published events are pushed (migration V35). Its own resource rather
     * than part of {@link #INTEGRATION_CLIENT_MANAGE} because the two fail in opposite directions:
     * a credential is a way <em>in</em> - mismanaging one lets somebody write orders into this
     * company - and a subscription is a way <em>out</em> - mismanaging one sends this company's
     * shipment numbers to an address of the administrator's choosing. Both go to the same two
     * roles today; a deployment that later wants an integrations operator who may configure
     * endpoints and may not mint credentials can say so without a migration.
     */
    INTEGRATION_WEBHOOK_READ("integration.webhook:read"),
    INTEGRATION_WEBHOOK_MANAGE("integration.webhook:manage"),

    MONITORING_TRANSPORT_READ("monitoring.transport:read"),

    /**
     * The document delivery monitor and the office's return confirmation (migration V57, ADR-018).
     * Split like everything else: reading what the carriers reported is not the authority to say
     * the signed copy is back on the shelf.
     */
    PLANNING_DOCUMENT_DELIVERY_READ("planning.document_delivery:read"),
    PLANNING_DOCUMENT_DELIVERY_RETURN("planning.document_delivery:return"),

    /**
     * The carrier portal (migration V57, ADR-018). Held only through a CARRIER role on a carrier
     * membership, which the identity query guarantees: a portal permission never reaches an office
     * user, and an office permission never reaches a carrier user.
     */
    PORTAL_TRIP_READ("portal.trip:read"),
    PORTAL_DOCUMENT_REPORT("portal.document:report"),

    /**
     * Stop execution and proof of delivery from the carrier portal (migration V70, ADR-018
     * addendum): arrival, service, completion or failure of a stop and an incident on it; and the
     * delivery result of an order with its evidence. Split like the office's own execution is not
     * - the office holds both under {@code planning.trip:execute} - because a carrier company may
     * want the driver to report the stop and the dispatcher to sign off the result.
     */
    PORTAL_STOP_EXECUTE("portal.stop:execute"),
    PORTAL_DELIVERY_REPORT("portal.delivery:report"),

    /**
     * My carrier's invoices, their due dates and what was paid (migration V67, ADR-023 section 2).
     * Held by {@code CARRIER_OPERATOR} only, like the other portal permissions.
     */
    PORTAL_INVOICE_READ("portal.invoice:read"),

    AUDIT_LOG_READ("audit.log:read");

    private static final Map<String, Permission> BY_CODE = Arrays.stream(values())
            .collect(Collectors.toUnmodifiableMap(Permission::code, Function.identity()));

    private final String code;

    Permission(String code) {
        this.code = code;
    }

    /** The {@code resource:action} string, identical to {@code tms.permission.code}. */
    public String code() {
        return code;
    }

    public String resource() {
        return code.substring(0, code.indexOf(':'));
    }

    public String action() {
        return code.substring(code.indexOf(':') + 1);
    }

    /**
     * Resolves a code coming from the database. Empty when the database holds a permission
     * this build does not know about, which happens legitimately during a rolling deploy: the
     * caller ignores it rather than failing the request, and never grants it.
     */
    public static Optional<Permission> fromCode(String code) {
        return Optional.ofNullable(BY_CODE.get(code));
    }
}
