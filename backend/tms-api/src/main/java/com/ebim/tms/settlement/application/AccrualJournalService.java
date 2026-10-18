package com.ebim.tms.settlement.application;

import com.ebim.tms.settlement.domain.CarrierInvoice;
import com.ebim.tms.settlement.domain.FreightAccrual;
import com.ebim.tms.settlement.domain.FreightAccrualEntry;
import com.ebim.tms.settlement.infrastructure.CarrierInvoiceRepository;
import com.ebim.tms.settlement.infrastructure.FreightAccrualEntryRepository;
import com.ebim.tms.settlement.infrastructure.FreightAccrualRepository;
import com.ebim.tms.settlement.infrastructure.GlAccountMappingRepository;
import com.ebim.tms.shared.audit.AuditAction;
import com.ebim.tms.shared.audit.AuditAggregateType;
import com.ebim.tms.shared.audit.AuditRecorder;
import com.ebim.tms.shared.reference.AccruableTripLookupPort;
import com.ebim.tms.shared.reference.AccruableTripLookupPort.AccruableTrip;
import com.ebim.tms.shared.reference.CarrierLookupPort;
import com.ebim.tms.shared.reference.MasterReference;
import com.ebim.tms.shared.reference.RouteTemplate;
import com.ebim.tms.shared.reference.RouteTemplateLookupPort;
import com.ebim.tms.shared.security.CompanyScope;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The accrual journal of a period, with the mapped accounts (V65, ADR-023). Every generation is
 * audited ({@code ACCRUAL_JOURNAL_EXPORTED}): a file handed to accounting is a disclosure.
 */
@Service
public class AccrualJournalService {

    public enum Format { JSON, CSV }

    private final FreightAccrualEntryRepository entryRepository;
    private final FreightAccrualRepository accrualRepository;
    private final CarrierInvoiceRepository invoiceRepository;
    private final GlAccountMappingRepository mappingRepository;
    private final AccruableTripLookupPort tripLookupPort;
    private final RouteTemplateLookupPort routeLookupPort;
    private final CarrierLookupPort carrierLookupPort;
    private final AuditRecorder auditRecorder;

    public AccrualJournalService(FreightAccrualEntryRepository entryRepository,
            FreightAccrualRepository accrualRepository, CarrierInvoiceRepository invoiceRepository,
            GlAccountMappingRepository mappingRepository, AccruableTripLookupPort tripLookupPort,
            RouteTemplateLookupPort routeLookupPort, CarrierLookupPort carrierLookupPort,
            AuditRecorder auditRecorder) {
        this.entryRepository = entryRepository;
        this.accrualRepository = accrualRepository;
        this.invoiceRepository = invoiceRepository;
        this.mappingRepository = mappingRepository;
        this.tripLookupPort = tripLookupPort;
        this.routeLookupPort = routeLookupPort;
        this.carrierLookupPort = carrierLookupPort;
        this.auditRecorder = auditRecorder;
    }

    @Transactional
    public AccrualJournalView journal(CompanyScope scope, YearMonth period, Format format) {
        UUID companyId = scope.companyId();
        List<FreightAccrualEntry> entries =
                entryRepository.findByCompanyIdAndPeriodOrderByCreatedAtAscIdAsc(companyId, period.atDay(1));

        Map<UUID, FreightAccrual> accruals = accrualRepository.findByCompanyIdAndIdIn(companyId,
                entries.stream().map(FreightAccrualEntry::accrualId).collect(Collectors.toSet())).stream()
                .collect(Collectors.toMap(FreightAccrual::id, Function.identity()));
        Map<UUID, AccruableTrip> trips = tripLookupPort.findByIds(accruals.values().stream()
                .map(FreightAccrual::tripId).collect(Collectors.toSet()), companyId);
        Set<UUID> routeIds = trips.values().stream().map(AccruableTrip::routeId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, RouteTemplate> routes = routeIds.isEmpty() ? Map.of()
                : routeLookupPort.findAllInCompany(routeIds, companyId);
        Set<UUID> carrierIds = accruals.values().stream().map(FreightAccrual::carrierId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, MasterReference> carriers = carrierIds.isEmpty() ? Map.of()
                : carrierLookupPort.findAllInCompany(carrierIds, companyId);
        Set<UUID> invoiceIds = entries.stream().map(FreightAccrualEntry::carrierInvoiceId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> invoices = invoiceIds.isEmpty() ? Map.of()
                : invoiceRepository.findByCompanyIdAndIdIn(companyId, invoiceIds).stream()
                        .collect(Collectors.toMap(CarrierInvoice::id, CarrierInvoice::invoiceNumber));

        List<AccrualJournal.Movement> movements = new ArrayList<>();
        for (FreightAccrualEntry entry : entries) {
            FreightAccrual accrual = accruals.get(entry.accrualId());
            AccruableTrip trip = trips.get(accrual.tripId());
            UUID routeId = trip == null ? null : trip.routeId();
            RouteTemplate route = routeId == null ? null : routes.get(routeId);
            MasterReference carrier = accrual.carrierId() == null ? null : carriers.get(accrual.carrierId());
            movements.add(new AccrualJournal.Movement(entry.id(),
                    entry.createdAt().atZoneSameInstant(scope.zoneId()).toLocalDate(), entry.entryType(),
                    entry.sourceType(), entry.sourceKey(), entry.amount(), entry.reason(), accrual.tripId(),
                    trip == null ? null : trip.shipmentNumber(), routeId, route == null ? null : route.originId(),
                    carrier == null ? null : carrier.code(), carrier == null ? null : carrier.name(),
                    entry.carrierInvoiceId() == null ? null : invoices.get(entry.carrierInvoiceId()),
                    accrual.currency()));
        }
        AccrualJournalView journal = AccrualJournal.build(period, movements,
                mappingRepository.findByCompanyIdAndActiveTrue(companyId));
        auditRecorder.record(scope, AuditAggregateType.FREIGHT_ACCRUAL, companyId,
                AuditAction.ACCRUAL_JOURNAL_EXPORTED,
                Map.of("period", period.toString(), "format", format.name(),
                        "rows", journal.rows().size(), "warnings", journal.warnings().size()));
        return journal;
    }
}
