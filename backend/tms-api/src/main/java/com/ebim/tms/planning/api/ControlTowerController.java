package com.ebim.tms.planning.api;

import com.ebim.tms.planning.application.ControlTowerFilter;
import com.ebim.tms.planning.application.ControlTowerService;
import com.ebim.tms.planning.application.ControlTowerTripView;
import com.ebim.tms.planning.application.ControlTowerView;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The control tower ({@code docs/domain/CONTROL_TOWER_V1.md}): one operating day of transport, read
 * and never written.
 *
 * <p><b>Two endpoints, because they refresh differently.</b> The overview is the KPI strip and the
 * three panels - small, whole-day, and what a wallboard re-reads every minute. The board is the
 * operational table, paginated and filtered, and it is re-read when somebody changes a filter or
 * turns a page. Serving both from one response would make every filter change re-count the day and
 * every timer tick re-fetch the table.
 *
 * <p><b>{@code monitoring.transport:read} and no new permission.</b> That permission has been in
 * the catalogue since migration V3 and granted to the seeded roles since V5, and V5's own header
 * describes what it is for in as many words: "a read-only transport monitor: the operational
 * overview screen that dispatch and customer service use". Until now nothing but tracking stood
 * behind it. Minting {@code monitoring.control_tower:read} beside it would give a company two
 * permissions for one idea and force every existing role to be re-granted to keep a screen they
 * were already entitled to - the same reasoning {@code TrackingController} gives.
 *
 * <p>It also has, deliberately, no {@code manage} counterpart. Acting on what the tower shows -
 * dispatching a shipment, resolving an exception, planning the backlog - happens on the trip and
 * planning endpoints with the permissions those require. A supervisor may be entitled to see the
 * whole day and to change none of it, which is exactly what customer service is.
 *
 * <p><b>Where authority is finer than the endpoint.</b> One figure on the overview - the count of
 * still-unplanned orders - is about orders rather than about transport, and is answered with null
 * for a caller who does not also hold {@code orders.order:read}. That check lives in the service,
 * on the field it protects, rather than as a second {@code hasAuthority} here that would take the
 * whole screen away over one number.
 *
 * <p><b>What it discloses.</b> The table rows are {@code TripView}s, so a caller holding only
 * {@code monitoring.transport:read} sees the shipment header - including the driver's name and
 * phone - without holding {@code planning.trip:read}. That is the disclosure this permission was
 * always for: a monitor whose next action is "call the driver of SH-00000142" and that could not
 * show who to call would send the operator to a screen they are not entitled to open. It stops at
 * the header: nothing here returns an order, a line, a price or an evidence artefact, and the
 * one order-shaped number on it is behind {@code orders.order:read} as described above.
 *
 * <p>There is no write verb on this controller and there will not be one. A monitor that could
 * change what it monitors would be a second way to move a lifecycle, and the lifecycle has exactly
 * one ({@code TripController}).
 */
@RestController
@RequestMapping("${tms.api.base-path}/monitoring/control-tower")
@Tag(name = "Control Tower", description = "Read-only operational overview of one transport day")
public class ControlTowerController {

    private final ControlTowerService controlTowerService;

    public ControlTowerController(ControlTowerService controlTowerService) {
        this.controlTowerService = controlTowerService;
    }

    /**
     * The day's KPI strip plus the fullest shipments, the open problems and the stops running late.
     *
     * <p>{@code date} defaults to today <em>in the company's zone</em>, so a browser in another
     * time zone cannot shift which day the screen is about. The origin and carrier filters narrow
     * the table below, not these numbers - see {@code ControlTowerService}.
     */
    @GetMapping
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "The operating day at a glance: counters, workload, open exceptions and late stops")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ControlTowerView overview(CompanyScope scope, @ModelAttribute ControlTowerFilter filter) {
        return controlTowerService.overview(scope, filter);
    }

    /**
     * The operational table: the day's shipments with their departure verdict, stop progress and
     * open-problem count.
     *
     * <p>Paginated and sorted through the same contract as {@code GET /planning/trips}, whose sort
     * allow-list it reuses - a control tower for a 300-trip day is a list somebody scrolls, not a
     * payload somebody downloads.
     */
    @GetMapping("/trips")
    @PreAuthorize("hasAuthority('monitoring.transport:read')")
    @Operation(summary = "The day's shipments with delay, stop progress and open exceptions per row")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<ControlTowerTripView> trips(CompanyScope scope,
            @ModelAttribute ControlTowerFilter filter, @ModelAttribute PageQuery pageQuery) {
        return controlTowerService.board(scope, filter, pageQuery);
    }
}
