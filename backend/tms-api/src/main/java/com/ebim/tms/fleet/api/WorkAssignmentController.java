package com.ebim.tms.fleet.api;

import com.ebim.tms.fleet.application.WorkAssignmentRequest;
import com.ebim.tms.fleet.application.WorkAssignmentService;
import com.ebim.tms.fleet.application.WorkAssignmentView;
import com.ebim.tms.shared.security.CompanyScope;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * A driver and a vehicle's day (migration V47).
 *
 * <p><b>There is one write endpoint for add, remove and reorder</b>, and that is deliberate. The
 * three differ only in what the caller sends, and every one of them revalidates the entire sequence
 * - moving a shipment breaks the leg into it and the leg out of it. Three endpoints would be three
 * ways to reach one revalidation, and three places to forget it.
 *
 * <p><b>Nothing here grants permission to depart.</b> A shipment in somebody's day is still refused
 * at the gate by every guard that refuses it now. {@code /confirm} states that the day was checked;
 * it does not authorise anything.
 */
@RestController
@RequestMapping("${tms.api.base-path}/fleet/work-assignments")
@Tag(name = "Work assignments", description = "Sequencing a day's shipments onto one driver and vehicle")
public class WorkAssignmentController {

    private final WorkAssignmentService workAssignmentService;

    public WorkAssignmentController(WorkAssignmentService workAssignmentService) {
        this.workAssignmentService = workAssignmentService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('fleet.work_assignment:read')")
    @Operation(summary = "Every day's work planned for one operational date, with its conflicts")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public List<WorkAssignmentView> listForDay(CompanyScope scope,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return workAssignmentService.listForDay(scope, date);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.work_assignment:read')")
    @Operation(summary = "One day's work, revalidated on read so a stale sequence shows as stale")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WorkAssignmentView get(CompanyScope scope, @PathVariable UUID id) {
        return workAssignmentService.get(scope, id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('fleet.work_assignment:manage')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Open a day for one driver and vehicle, optionally with its shipments in order")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WorkAssignmentView create(CompanyScope scope, @Valid @RequestBody WorkAssignmentRequest request) {
        return workAssignmentService.create(scope, request);
    }

    /**
     * Replaces the day - its resources and its whole sequence.
     *
     * <p>A PUT carrying the entire order rather than a patch: an omitted shipment means it is no
     * longer in the day, which is the only way one can be removed, and the sequence is the list's
     * own order. Every call revalidates all of it.
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('fleet.work_assignment:manage')")
    @Operation(summary = "Add, remove or reorder shipments, or swap the driver or vehicle. Revalidates the whole day")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WorkAssignmentView update(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody WorkAssignmentRequest request) {
        return workAssignmentService.update(scope, id, request);
    }

    /**
     * Commits to the day, and refuses one that does not work.
     *
     * <p>The only place feasibility is enforced rather than reported: a planner may build an
     * impossible day and look at it, which is how a problem gets diagnosed. Committing to one is a
     * different act, and the refusal names every conflict so it says what to fix.
     */
    @PostMapping("/{id}/confirm")
    @PreAuthorize("hasAuthority('fleet.work_assignment:manage')")
    @Operation(summary = "Confirm the day. Refused while any conflict remains, naming each one")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WorkAssignmentView confirm(CompanyScope scope, @PathVariable UUID id) {
        return workAssignmentService.confirm(scope, id);
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('fleet.work_assignment:manage')")
    @Operation(summary = "Cancel the day, releasing its vehicle and driver for another")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public WorkAssignmentView cancel(CompanyScope scope, @PathVariable UUID id) {
        return workAssignmentService.cancel(scope, id);
    }
}
