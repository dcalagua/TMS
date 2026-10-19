package com.ebim.tms.settlement.api;

import com.ebim.tms.settlement.application.AccrualAgingView;
import com.ebim.tms.settlement.application.AccrualJournal;
import com.ebim.tms.settlement.application.AccrualJournalService;
import com.ebim.tms.settlement.application.AccrualJournalView;
import com.ebim.tms.settlement.application.AccrualReverseRequest;
import com.ebim.tms.settlement.application.AccrualService;
import com.ebim.tms.settlement.application.AccrualSummaryView;
import com.ebim.tms.settlement.application.AccrualSweep;
import com.ebim.tms.settlement.application.AccrualSweepResult;
import com.ebim.tms.settlement.application.AccrualView;
import com.ebim.tms.settlement.application.UnaccruedTripsView;
import com.ebim.tms.settlement.domain.AccrualStatus;
import com.ebim.tms.shared.api.InvalidRequestException;
import com.ebim.tms.shared.api.PageQuery;
import com.ebim.tms.shared.api.PageResponse;
import com.ebim.tms.shared.audit.AuditActorProvider;
import com.ebim.tms.shared.security.CompanyScope;
import com.ebim.tms.shared.settings.CompanySettingsPort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Freight accruals (V65, ADR-023 section 1): the open obligation between the service and the
 * invoice, its aging, the trips that could not be accrued, and the journal for the ERP.
 *
 * <p>Read with {@code settlement.accrual:read}; the journal with {@code settlement.accrual:export};
 * reversing and "accrue now" with {@code settlement.accrual:manage}.
 */
@RestController
@RequestMapping("${tms.api.base-path}/settlement/accruals")
@Tag(name = "Freight accruals", description = "Accruals, aging, unaccrued trips and the accrual journal")
public class AccrualController {

    private final AccrualService accrualService;
    private final AccrualSweep accrualSweep;
    private final AccrualJournalService journalService;
    private final CompanySettingsPort companySettingsPort;
    private final AuditActorProvider auditActorProvider;

    public AccrualController(AccrualService accrualService, AccrualSweep accrualSweep,
            AccrualJournalService journalService, CompanySettingsPort companySettingsPort,
            AuditActorProvider auditActorProvider) {
        this.accrualService = accrualService;
        this.accrualSweep = accrualSweep;
        this.journalService = journalService;
        this.companySettingsPort = companySettingsPort;
        this.auditActorProvider = auditActorProvider;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('settlement.accrual:read')")
    @Operation(summary = "Accruals, filtered by service period (YYYY-MM), carrier, currency and status")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public PageResponse<AccrualSummaryView> list(CompanyScope scope,
            @RequestParam(required = false) String period,
            @RequestParam(required = false) UUID carrierId,
            @RequestParam(required = false) String currency,
            @RequestParam(required = false) AccrualStatus status,
            @ModelAttribute PageQuery pageQuery) {
        return accrualService.list(scope, period == null || period.isBlank() ? null : period(period), carrierId,
                currency, status, pageQuery);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('settlement.accrual:read')")
    @Operation(summary = "One accrual with its movements; the movements sum to its balance")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AccrualView get(CompanyScope scope, @PathVariable UUID id) {
        return accrualService.get(scope, id);
    }

    @GetMapping("/aging")
    @PreAuthorize("hasAuthority('settlement.accrual:read')")
    @Operation(summary = "Open balance at a date by carrier and currency, in 0-30/31-60/61-90/>90 day buckets")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AccrualAgingView aging(CompanyScope scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return accrualService.aging(scope, asOf);
    }

    @GetMapping("/unaccrued")
    @PreAuthorize("hasAuthority('settlement.accrual:read')")
    @Operation(summary = "Executed trips in a date range with no accrual, and why (own fleet is not listed)")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public UnaccruedTripsView unaccrued(CompanyScope scope,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return accrualService.unaccrued(scope, companySettingsPort.settingsOf(scope.companyId()).accrualTrigger(),
                from, to);
    }

    @GetMapping("/journal")
    @PreAuthorize("hasAuthority('settlement.accrual:export')")
    @Operation(summary = "The accrual journal of a period (YYYY-MM) with the mapped GL accounts, as JSON or CSV")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public ResponseEntity<?> journal(CompanyScope scope, @RequestParam String period,
            @RequestParam(defaultValue = "JSON") AccrualJournalService.Format format) {
        YearMonth month = period(period);
        AccrualJournalView journal = journalService.journal(scope, month, format);
        if (format == AccrualJournalService.Format.JSON) {
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(journal);
        }
        byte[] content = AccrualJournal.csv(journal).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("accrual-journal-" + scope.companyCode() + "-" + month + ".csv")
                        .build()
                        .toString())
                // The CSV carries no warning rows - it is for a machine; the count says to look.
                .header("X-Journal-Warnings", String.valueOf(journal.warnings().size()))
                .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
                .cacheControl(CacheControl.noStore())
                .body(content);
    }

    @PostMapping("/{id}/reverse")
    @PreAuthorize("hasAuthority('settlement.accrual:manage')")
    @Operation(summary = "Reverse an open accrual, or close the remainder of a partially settled one. Reason required")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AccrualView reverse(CompanyScope scope, @PathVariable UUID id,
            @Valid @RequestBody AccrualReverseRequest request) {
        return accrualService.reverse(scope, id, auditActorProvider.requireAppUserId(), request.reason());
    }

    @PostMapping("/sweep")
    @PreAuthorize("hasAuthority('settlement.accrual:manage')")
    @Operation(summary = "Accrue now: run the sweep for this company over a date range (at most one year)")
    @Parameter(name = "X-Company-Id", in = ParameterIn.HEADER, required = true,
            description = "Id of a company the caller is a member of")
    public AccrualSweepResult sweep(CompanyScope scope,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return accrualSweep.sweepRange(scope, from, to, auditActorProvider.requireAppUserId());
    }

    private static YearMonth period(String value) {
        try {
            return YearMonth.parse(value.trim());
        } catch (DateTimeParseException invalid) {
            throw new InvalidRequestException("period must be a month as YYYY-MM, for example 2026-10.");
        }
    }
}
